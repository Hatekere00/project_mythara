package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ui.canvas.CanvasController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `update_canvas` — push a JavaScript snippet that updates the
 * currently-rendered Canvas in place.
 *
 * Use this for incremental changes — e.g. after the agent's move in
 * a tic-tac-toe game, update the relevant cell without re-rendering
 * the whole board. Cheaper than [RenderCanvasTool] and preserves
 * any in-page state the user has accumulated.
 *
 * The snippet is `eval`d in the page's main world via
 * `webView.evaluateJavascript()`. Don't post sensitive data through
 * this path — it shows up in webView console.
 */
@Singleton
class UpdateCanvasTool @Inject constructor(
    private val controller: CanvasController,
) : Tool {
    override val name = "update_canvas"
    override val description =
        "Run a JS snippet against the current Canvas render to update it in place (no re-load)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("js", buildJsonObject {
                put("type", "string")
                put("description", "JavaScript snippet to evaluate against the current Canvas WebView.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("js"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val js = args["js"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (js.isBlank()) return ToolResult.fail("js must be a non-empty JavaScript snippet")
        controller.updateJs(js)
        return ToolResult.ok("queued ${js.length}-char js snippet")
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
}
