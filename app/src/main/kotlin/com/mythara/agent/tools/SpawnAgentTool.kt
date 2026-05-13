package com.mythara.agent.tools

import com.mythara.agent.AgentDepth
import com.mythara.agent.AgentLoop
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.Lazy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `spawn_agent` — delegate a focused subtask to a child agent.
 *
 * The main agent uses this when a task is self-contained and would
 * bloat the main conversation if done inline. Examples:
 *
 *   "Research X and email me a summary" →
 *     spawn_agent({task:"web-search X across 3 sources and synthesise",
 *                  system_prompt:"You are a research agent…"})
 *
 *   "What's on my calendar today and is there traffic to the first?" →
 *     spawn_agent({task:"list today's calendar events"}) +
 *     spawn_agent({task:"check traffic from current location to <addr>"})
 *
 * Subagent details:
 *   - Starts with FRESH context — no main-chat history, just the task
 *     and the optional system prompt.
 *   - Uses the SAME model and tool registry as the main agent.
 *   - Caps at [AgentLoop.SUBAGENT_MAX_ITERATIONS] inner tool-loop steps.
 *   - Recursion-bounded: the subagent CAN'T spawn its own subagents
 *     past [AgentLoop.SUBAGENT_MAX_DEPTH]. The depth is tracked via a
 *     coroutine-context element ([AgentDepth]).
 *   - Returns the final assistant text as the tool result. No streaming
 *     surfaces; the main agent sees the result as a normal tool reply
 *     and incorporates it into its next turn.
 *
 * [Lazy] injection breaks the Hilt cycle: AgentLoop → ToolRegistry →
 * SpawnAgentTool → AgentLoop. We only need the loop at execute time,
 * not at construction.
 */
@Singleton
class SpawnAgentTool @Inject constructor(
    private val agentLoopLazy: Lazy<AgentLoop>,
) : Tool {

    @Serializable
    data class Response(
        val ok: Boolean,
        val result: String,
        val iterations: Int = 0,
        val toolCalls: Int = 0,
        val depth: Int = 0,
    )

    override val name: String = "spawn_agent"
    override val description: String =
        "Delegate a focused subtask to a child agent that runs its own short loop " +
            "with a fresh context and the same tools. " +
            "Use when the task is self-contained (research, multi-step lookup, parallel work) " +
            "and would clutter the main chat if done inline. " +
            "Returns the child agent's final answer."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "task",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "What the subagent should do. Phrase as a clear, self-contained instruction (e.g. 'fetch the weather for ankur's current location and return a one-line summary').",
                        )
                    },
                )
                put(
                    "system_prompt",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional system framing for the subagent (e.g. 'You are a research agent. Cite each source.'). Falls back to a generic 'focused worker' prompt when omitted.",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("task"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val task = (args["task"] as? JsonPrimitive)?.content?.trim()
        if (task.isNullOrBlank()) {
            return ToolResult(false, """{"error":"missing_task","detail":"Pass a 'task' string."}""")
        }
        val systemPrompt = (args["system_prompt"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        // Read the current nesting depth from the coroutine context.
        // depth = 0 means we're inside the main agent's loop; > 0 means
        // we're already inside a subagent and the model is trying to
        // recurse.
        val currentDepth = currentCoroutineContext()[AgentDepth.Key]?.depth ?: 0
        if (currentDepth >= AgentLoop.SUBAGENT_MAX_DEPTH) {
            return ToolResult(
                ok = false,
                output = """{"error":"max_depth_reached","detail":"Subagents can't spawn further subagents (depth $currentDepth, cap ${AgentLoop.SUBAGENT_MAX_DEPTH})."}""",
            )
        }

        val nextDepth = currentDepth + 1
        val subResult = withContext(AgentDepth(nextDepth)) {
            agentLoopLazy.get().runSubagent(
                task = task,
                systemPrompt = systemPrompt,
            )
        }
        val response = Response(
            ok = subResult.ok,
            result = subResult.text,
            iterations = subResult.iterations,
            toolCalls = subResult.toolCalls,
            depth = nextDepth,
        )
        return ToolResult(
            ok = subResult.ok,
            output = JSON.encodeToString(Response.serializer(), response),
        )
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
