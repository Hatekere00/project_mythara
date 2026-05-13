package com.mythara.agent

import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.minimax.ErrorMapper
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.StreamingChat
import com.mythara.minimax.models.ChatMessage
import com.mythara.minimax.models.ChatRequest
import com.mythara.minimax.models.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mythara's agentic runtime — same shape as Crush's main loop.
 *
 * Per user turn:
 *   1. Persist the user message to history.
 *   2. Snapshot the entire conversation, hand it to MiniMax with the
 *      tools-array attached.
 *   3. Stream the model's response. If it ends in `finish_reason=stop`,
 *      we're done — emit Finished.
 *   4. If it ends in `finish_reason=tool_calls`, persist the assistant
 *      message (text + tool_calls), execute each tool, persist a
 *      `role:tool` message per result, then loop back to step 2 with
 *      the enlarged history. The model sees its tool results and either
 *      generates text or calls more tools.
 *   5. MAX_ITERATIONS caps the loop so a buggy model can't burn the
 *      user's quota forever; the cap shows up as Turn.Finished with a
 *      sentinel suffix.
 *
 * The emitted [Turn] flow lets the UI render tool-call cards in real
 * time — Crush-style "● Reading file…" → "✓ Reading file (0.4s)".
 */
@Singleton
class AgentLoop @Inject constructor(
    private val settings: SettingsStore,
    private val history: HistoryRepository,
    private val registry: ToolRegistry,
    private val recall: SemanticRecall,
) {

    sealed interface Turn {
        /** Streamed text fragment to append to the active assistant bubble. */
        data class Delta(val text: String) : Turn

        /** A tool call is about to run. Render the bubble in "● running" state. */
        data class ToolStart(val callId: String, val name: String, val args: String) : Turn

        /** Tool finished. Render "✓ done (durationMs)" or "× failed". */
        data class ToolEnd(
            val callId: String,
            val name: String,
            val ok: Boolean,
            val output: String,
            val durationMs: Long,
        ) : Turn

        /**
         * End of the entire turn (post-loop). Carries the dominant
         * mood trend the agent observed (if any) so the chat layer
         * can pass it through to TTS for prosody modulation.
         */
        data class Finished(
            val finalText: String,
            val iterations: Int,
            val userMoodTrend: String? = null,
        ) : Turn

        /** Stream-level failure (HTTP / SSE / mapped MiniMax code). */
        data class Error(val message: String, val retryable: Boolean) : Turn

        /** No API key configured yet — UI surfaces a "Settings" prompt. */
        data object MissingApiKey : Turn
    }

    fun submit(userText: String): Flow<Turn> = flow {
        val snap = settings.snapshot()
        val apiKey = snap.apiKey
        if (apiKey.isNullOrBlank()) {
            emit(Turn.MissingApiKey); return@flow
        }

        history.dao.insert(
            MessageRow(tsMillis = System.currentTimeMillis(), role = "user", content = userText),
        )

        // One-shot semantic recall over the user's latest message. The
        // result lasts for the duration of this turn — never persisted
        // to history, never re-computed per tool-use iteration.
        val recalledFacts = recall.recall(userText)
        val recallSystem: ChatMessage? = recall.render(recalledFacts)?.let { rendered ->
            android.util.Log.d(TAG, "injecting ${recalledFacts.size} recalled facts")
            ChatMessage(role = "system", content = rendered)
        }

        // Recent emotional trend across the last 6 hours of semantic
        // records. Drives a separate system message so MiniMax can
        // shape its tone (warm + supportive when the user's anxious,
        // upbeat when excited, default otherwise). Only fires when
        // Gemma extraction is enabled and ≥50% of recent records
        // agree on a single mood — too mixed and we say nothing,
        // which avoids whiplash advice.
        val moodTrend = recall.recentMoodTrend()
        val moodSystem: ChatMessage? = recall.renderMoodSystemMessage(moodTrend)?.let { rendered ->
            android.util.Log.d(TAG, "injecting mood context: $moodTrend")
            ChatMessage(role = "system", content = rendered)
        }

        // Auto-process notifications mode. When ChatViewModel forwards a
        // status-bar notification into the agent loop, it prefixes the
        // user text with `[notif]`. We inject a one-shot system message
        // that tells the model how to handle it: terse spoken summary
        // for actionable stuff, single token NOSURFACE for noise.
        val notifSystem: ChatMessage? = if (userText.startsWith(NOTIF_PREFIX)) {
            ChatMessage(
                role = "system",
                content =
                    "A phone notification just arrived and you're auto-surfacing it. " +
                        "If it's actionable or worth the user knowing right now (a real message, a calendar reminder, a delivery update, an alert), " +
                        "give them a ≤15-word natural spoken summary — they'll hear this read aloud. " +
                        "If it's just system noise (sync indicators, foreground-service pings, OS updates, generic ads, content the user has already seen), " +
                        "reply with the single token NOSURFACE and nothing else. " +
                        "Do not call tools for this turn unless the notification is unclear and a quick read_screen would resolve it.",
            )
        } else {
            null
        }

        val client = MiniMaxClient(apiKey = apiKey, region = snap.region)
        val streaming = StreamingChat(client)

        var iter = 0
        var lastAssistantText = ""

        loop@ while (iter < MAX_ITERATIONS) {
            iter++

            val historyMessages: List<ChatMessage> = history.dao.listAll().map { row ->
                ChatMessage(
                    role = row.role,
                    content = row.content,
                    toolCalls = row.toolCallsJson?.let { decodeToolCalls(it) },
                    toolCallId = row.toolCallId,
                    name = row.name,
                )
            }
            // Prepend system messages (notif triage hint first if any,
            // then mood context, then recalled facts) so MiniMax sees
            // every framing layer before persisted chat history.
            val prior: List<ChatMessage> = buildList {
                if (notifSystem != null) add(notifSystem)
                if (moodSystem != null) add(moodSystem)
                if (recallSystem != null) add(recallSystem)
                addAll(historyMessages)
            }

            val req = ChatRequest(
                model = snap.model,
                messages = prior,
                tools = registry.apiSchema().takeIf { it.isNotEmpty() },
                toolChoice = if (registry.apiSchema().isNotEmpty()) "auto" else null,
                stream = true,
                // MiniMax M2.7 is a reasoning model; without reasoning_split=false
                // it emits thinking tokens through a side channel
                // (delta.reasoning_details) that the SSE parser doesn't surface
                // and the tool-use loop can't round-trip. Keep reasoning baked
                // into `content` so history replay works verbatim.
                reasoningSplit = false,
            )

            val streamedText = StringBuilder()
            var toolCalls: List<ToolCall> = emptyList()
            var finishReason: String? = null
            var failure: ErrorMapper.Mapped? = null

            streaming.stream(snap.region, req).collect { ev ->
                when (ev) {
                    is StreamingChat.StreamEvent.Text -> {
                        streamedText.append(ev.delta)
                        emit(Turn.Delta(ev.delta))
                    }
                    is StreamingChat.StreamEvent.ToolCallsReady -> toolCalls = ev.calls
                    is StreamingChat.StreamEvent.Done -> finishReason = ev.finishReason
                    is StreamingChat.StreamEvent.Failure -> failure = ev.mapped
                }
            }

            if (failure != null) {
                val f = failure!!
                emit(Turn.Error(f.message, retryable = f.isRetryable))
                return@flow
            }

            lastAssistantText = streamedText.toString()

            // Persist the assistant turn (with tool_calls if present) so the
            // next iteration includes it verbatim — MiniMax requires the
            // assistant `tool_calls` message in history before each
            // `role:tool` reply, or the next call 400s.
            history.dao.insert(
                MessageRow(
                    tsMillis = System.currentTimeMillis(),
                    role = "assistant",
                    content = lastAssistantText.takeIf { it.isNotEmpty() },
                    toolCallsJson = if (toolCalls.isNotEmpty()) encodeToolCalls(toolCalls) else null,
                ),
            )

            // MiniMax (per function-call docs) reports `tool_use`; OpenAI-compat
            // implementations also return `tool_calls`. Treat both as the signal
            // that the next iteration should execute tools + resume.
            val toolFinish = finishReason == "tool_calls" || finishReason == "tool_use"
            if (toolCalls.isEmpty() || !toolFinish) {
                emit(Turn.Finished(lastAssistantText, iterations = iter, userMoodTrend = moodTrend))
                return@flow
            }

            // Execute every requested tool sequentially; emit start/end so
            // the UI can render Crush-style ● / ✓ glyphs in real time.
            for (call in toolCalls) {
                emit(Turn.ToolStart(call.id, call.function.name, call.function.arguments))
                val t0 = System.nanoTime()
                val result = registry.execute(call.function.name, call.function.arguments)
                val dt = (System.nanoTime() - t0) / 1_000_000
                history.dao.insert(
                    MessageRow(
                        tsMillis = System.currentTimeMillis(),
                        role = "tool",
                        content = result.output,
                        toolCallId = call.id,
                        name = call.function.name,
                    ),
                )
                emit(Turn.ToolEnd(call.id, call.function.name, result.ok, result.output, dt))
            }
            // Continue the outer loop — the next iteration re-streams with
            // the enlarged context (including all tool results).
        }

        // Hit the iteration cap. Surface a soft-stop so the user sees a
        // bounded conversation instead of an infinite-loop bill.
        emit(Turn.Finished(lastAssistantText + " [hit max iterations]", iterations = iter, userMoodTrend = moodTrend))
    }

    private fun encodeToolCalls(calls: List<ToolCall>): String =
        MiniMaxClient.json.encodeToString(ListSerializer(ToolCall.serializer()), calls)

    private fun decodeToolCalls(s: String): List<ToolCall>? =
        runCatching { MiniMaxClient.json.decodeFromString(ListSerializer(ToolCall.serializer()), s) }
            .getOrNull()

    companion object {
        private const val TAG = "Mythara/Agent"

        /**
         * Safety cap on tool-use iterations per user turn. 8 is generous
         * for genuine multi-step tasks (most stop after 1–3) but stops a
         * broken model from spinning forever on a malformed function call.
         */
        const val MAX_ITERATIONS = 8

        /**
         * Wire-format marker on the leading user-text line that flips the
         * agent into "notification triage" mode for this turn. Kept short
         * because it's part of the persisted chat history.
         */
        const val NOTIF_PREFIX = "[notif]"

        /** Sentinel the model returns when a notification isn't worth surfacing. */
        const val NOSURFACE_TOKEN = "NOSURFACE"
    }
}
