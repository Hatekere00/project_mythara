package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ui.canvas.CanvasController
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_canvas_input` — wait (up to timeout) for the user to post
 * input from the currently-rendered Canvas page via
 * `window.mythara.sendInput(jsonString)`.
 *
 * Use this in two-phase flows:
 *   1. Call [RenderCanvasTool] with a page that contains buttons /
 *      a form / a game UI.
 *   2. Immediately call this tool with a sensible timeout.
 *   3. Process the returned JSON in your next turn.
 *
 * Returns `{status:"input", json:"..."}` on user input, or
 * `{status:"timeout"}` if no input arrives in `timeout_ms`.
 */
@Singleton
class ReadCanvasInputTool @Inject constructor(
    private val controller: CanvasController,
) : Tool {
    override val name = "read_canvas_input"
    override val description =
        "Suspend until the user posts input from the Canvas (via window.mythara.sendInput), or until timeout."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Milliseconds to wait. Default 30000 (30 s). Max 120000.")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val timeout = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 30_000L)
            .coerceIn(500L, 120_000L)
        return try {
            val json = withTimeout(timeout) { controller.inputChannel.receive() }
            ToolResult.ok("""{"status":"input","json":${jsonEscape(json)}}""")
        } catch (_: TimeoutCancellationException) {
            ToolResult.ok("""{"status":"timeout"}""")
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

    /** Embed an arbitrary JSON-or-string body into a JSON string slot.
     *  If the body is already valid JSON, embed as-is; otherwise wrap
     *  in quotes. Cheap heuristic: starts with `{` or `[`. */
    private fun jsonEscape(s: String): String {
        val trimmed = s.trim()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            trimmed
        } else {
            "\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
