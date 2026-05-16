package com.mythara.agent.todo

import android.util.Log
import com.mythara.ai.ModelRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a (user-message, agent-reply) pair after a turn completes
 * and extracts any IMPLIED follow-up actions the agent didn't
 * fully complete this turn. Writes them into [AgentTodoStore]
 * with [AgentTodoStore.Source.UserIntent].
 *
 * This is the "WRITER" half of the auto-continue substrate. The
 * substrate (store + controller) is what RUNS the items; this
 * extractor is what PUTS them there based on what the user
 * actually asked for.
 *
 * Examples of what the extractor catches:
 *   - User: "set up a meeting with Anurag and remind me 10 min before"
 *     Agent: "Scheduled the meeting for 3pm tomorrow."
 *     → Extracts ONE item: "Create a 10-min reminder before the
 *       Anurag meeting tomorrow at 3pm." (agent already did the
 *       schedule_meeting half, but the reminder is still pending.)
 *   - User: "draft an email to mom and check the grocery list"
 *     Agent: "Drafted the email — sending it shortly."
 *     → Extracts ONE item: "Check the grocery list."
 *
 * What it explicitly does NOT do:
 *   - Generate items the user didn't actually ask for. The prompt
 *     forbids "you should also do X" expansion — only literal
 *     follow-ups present in the user's request count.
 *   - Re-extract items the agent fully completed. The prompt
 *     instructs the model to read the agent's reply and skip
 *     anything already done.
 *   - Run on auto-continue / auto-reply / auto-triage / notif
 *     turns. Those aren't user intent — they'd cascade.
 *
 * Routing: light path (local Gemma) — fast, cheap, fully offline.
 * The extraction is short (single JSON list) and runs after EVERY
 * user turn, so cost discipline matters more than nuance here.
 *
 * Failure mode: graceful degradation. If the model returns
 * non-JSON, returns null, or times out, no items are added and
 * we log + move on. The user can always ask again, and the next
 * turn gets a fresh extraction pass.
 */
@Singleton
class TodoIntentExtractor @Inject constructor(
    private val router: ModelRouter,
    private val store: AgentTodoStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Extract follow-up items from a single (user, agent) exchange.
     * Returns the count of items written (0 when nothing extracted
     * or model unavailable). Suspending — caller decides whether
     * to await; in [com.mythara.agent.AgentRunner] we fire-and-
     * forget after [com.mythara.agent.AgentLoop.Turn.Finished].
     */
    suspend fun extract(userText: String, agentReply: String): Int = withContext(Dispatchers.IO) {
        if (userText.isBlank() || agentReply.isBlank()) return@withContext 0
        val prompt = buildPrompt(userText, agentReply)
        val raw = runCatching { router.runRaw(prompt, maxLen = 800, heavy = false) }
            .getOrNull()
            ?.trim()
        if (raw.isNullOrBlank()) {
            Log.d(TAG, "model returned null/blank — no items extracted")
            return@withContext 0
        }
        val items = parseItems(raw)
        if (items.isEmpty()) {
            Log.d(TAG, "model returned ${raw.take(80)} — parsed to 0 items")
            return@withContext 0
        }

        // Persist each extracted item. Idempotent via deterministic
        // id (sha8 of source-key + text) so a re-run on the same
        // exchange doesn't double-add.
        val now = System.currentTimeMillis()
        var written = 0
        for (text in items) {
            val id = stableId("intent", userText.take(120), text)
            runCatching {
                store.add(
                    AgentTodoStore.Item(
                        id = id,
                        text = text,
                        source = AgentTodoStore.Source.UserIntent,
                        createdAtMs = now,
                    ),
                )
                written++
            }.onFailure { Log.w(TAG, "store.add failed: ${it.message}") }
        }
        Log.i(TAG, "extracted $written follow-up items from user intent")
        written
    }

    private fun buildPrompt(userText: String, agentReply: String): String = buildString {
        append("You are extracting IMPLIED FOLLOW-UP ACTIONS from a personal-AI conversation.\n")
        append("The user just said something to their assistant, and the assistant replied. ")
        append("Your job: identify ONLY actions the user EXPLICITLY asked for that the assistant ")
        append("has NOT yet completed in its reply.\n\n")
        append("Rules — STRICT:\n")
        append("  1. Output ONLY a JSON array of strings, no preamble, no markdown fences. ")
        append("Empty array [] is a valid answer when there are no pending follow-ups.\n")
        append("  2. Each string is a single concrete action (max 100 chars), phrased as the ")
        append("assistant's next step (\"Schedule a 10-min reminder for the meeting\", not ")
        append("\"User wants a reminder\").\n")
        append("  3. NEVER invent actions the user didn't ask for. NEVER \"you should also do X\".\n")
        append("  4. If the assistant ALREADY completed an action in its reply, SKIP it.\n")
        append("  5. If the user only asked one thing and the assistant fully handled it, return [].\n")
        append("  6. Cap at 4 items maximum. Compound requests get collapsed if necessary.\n\n")
        append("--- USER MESSAGE ---\n")
        append(userText.take(800)).append("\n\n")
        append("--- ASSISTANT REPLY ---\n")
        append(agentReply.take(800)).append("\n\n")
        append("--- OUTPUT (JSON array of strings only) ---\n")
    }

    /**
     * Defensive JSON-array parse. Models sometimes wrap the answer
     * in ```json fences or trail commentary; we strip both before
     * parsing. Returns the deduplicated, trimmed, non-empty strings.
     */
    private fun parseItems(raw: String): List<String> {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringAfter("```", raw)
            .substringBefore("```")
            .trim()
            // Some models emit text before/after the array. Find the
            // outermost [...] and parse just that.
            .let { extractFirstArray(it) ?: it }

        val arr: JsonArray = runCatching {
            json.parseToJsonElement(cleaned).jsonArray
        }.getOrElse {
            Log.d(TAG, "parse failed (${it.message}) on: ${cleaned.take(120)}")
            return emptyList()
        }

        return arr.mapNotNull { el ->
            val s = runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                ?: runCatching { el.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            s?.trim()?.takeIf { it.isNotBlank() && it.length <= 240 }
        }.distinct().take(4)
    }

    private fun extractFirstArray(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun stableId(scope: String, src: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$scope|$src|$text".toByteArray())
        return "todo:" + md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Quick gate exposed for callers — returns false when no model
     *  is available so we don't waste a coroutine + log noise. */
    suspend fun ready(): Boolean = router.canInfer()

    companion object {
        private const val TAG = "Mythara/TodoIntentExtractor"
    }
}
