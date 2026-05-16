package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ui.canvas.CanvasController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `render_canvas` — push an HTML render to the Canvas surface.
 *
 * The agent's visual channel. When text alone underserves the user —
 * an image you generated, an explainer card, a mini-game, a breath
 * pacer — render to the Canvas instead.
 *
 * Two modes:
 *   - `mode=inline`: the agent passes the HTML body directly. Up to
 *     ~32 KB; the JS bridge `window.mythara.sendInput(...)` is
 *     attached so the page can post structured input back.
 *   - `mode=file`: the agent passes a longer HTML body that gets
 *     written to filesDir/canvas/<uuid>.html first then loaded via
 *     `file://…`. Use this for renders > 32 KB or when you want to
 *     reference local image assets.
 *
 * Set `retain=true` to keep the render after the user navigates away
 * (default false → clears back to ambient on screen exit).
 *
 * Set `auto_navigate=true` to also pivot the UI to the Canvas screen
 * automatically (default true — your render is useless if the user
 * isn't looking at it).
 */
@Singleton
class RenderCanvasTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: CanvasController,
) : Tool {
    override val name = "render_canvas"
    override val description =
        "Render HTML to Mythara's Canvas surface (the agent's visual channel). " +
            "Use this when text underserves the user — images, cards, mini-games, breath pacers."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("html", buildJsonObject {
                put("type", "string")
                put("description", "Full HTML body. JS bridge `window.mythara.sendInput(json)` is available for user input.")
            })
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "'inline' (<= 32 KB) or 'file' (longer). Default 'inline'.")
            })
            put("retain", buildJsonObject {
                put("type", "boolean")
                put("description", "Keep render across navigation. Default false.")
            })
            put("auto_navigate", buildJsonObject {
                put("type", "boolean")
                put("description", "Auto-pivot the UI to Canvas. Default true.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("html"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val html = args["html"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (html.isBlank()) return ToolResult.fail("html must be a non-empty HTML body")
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "inline"
        val retain = args["retain"]?.jsonPrimitive?.booleanOrNull() ?: false
        val autoNavigate = args["auto_navigate"]?.jsonPrimitive?.booleanOrNull() ?: true

        return when (mode) {
            "inline" -> {
                controller.render(
                    CanvasController.Render(
                        mode = CanvasController.RenderMode.Inline,
                        payload = html,
                        retain = retain,
                    ),
                    autoNavigate = autoNavigate,
                )
                ToolResult.ok("rendered ${html.length} chars inline (retain=$retain, nav=$autoNavigate)")
            }
            "file" -> {
                runCatching {
                    val dir = File(context.filesDir, "canvas").apply { mkdirs() }
                    val file = File(dir, "${UUID.randomUUID()}.html")
                    file.writeText(html)
                    controller.render(
                        CanvasController.Render(
                            mode = CanvasController.RenderMode.File,
                            payload = file.absolutePath,
                            retain = retain,
                        ),
                        autoNavigate = autoNavigate,
                    )
                    ToolResult.ok("wrote ${file.name} (${html.length} chars) and rendered")
                }.getOrElse { ToolResult.fail("file write failed: ${it.message}") }
            }
            else -> ToolResult.fail("mode must be 'inline' or 'file'")
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun JsonPrimitive.booleanOrNull(): Boolean? = runCatching { content.toBoolean() }.getOrNull()
}
