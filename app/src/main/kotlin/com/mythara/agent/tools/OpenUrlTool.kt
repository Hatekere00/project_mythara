package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `open_url` — open a URL either in Mythara's in-app Canvas WebView
 * or in the system Chrome browser.
 *
 * Use `mode=inline` (the default) for ordinary content the agent
 * wants to surface inside Mythara. Use `mode=chrome` for sites that
 * are likely to bot-flag the embedded WebView — Google search
 * results, Cloudflare-protected pages, anything requiring the user's
 * existing Chrome session/cookies/extensions.
 *
 * Inline mode renders via [CanvasController] in External mode, which
 * means the JS bridge `window.mythara` is REMOVED before loading
 * (we don't want third-party JS calling our app methods).
 *
 * Chrome mode dispatches `ACTION_VIEW` with the `com.android.chrome`
 * package set explicitly, falling back to any handler if Chrome is
 * not installed.
 */
@Singleton
class OpenUrlTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: CanvasController,
) : Tool {
    override val name = "open_url"
    override val description =
        "Open a URL in Mythara's Canvas WebView (mode=inline) or hand off to system Chrome (mode=chrome)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "Absolute http(s) URL.")
            })
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "'inline' (in-app WebView, no JS bridge) or 'chrome' (system Chrome). Default 'inline'.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("url"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return ToolResult.fail("url must be absolute http(s)")
        }
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "inline"

        return when (mode) {
            "inline" -> {
                controller.render(
                    CanvasController.Render(
                        mode = CanvasController.RenderMode.External,
                        payload = url,
                        retain = false,
                    ),
                    autoNavigate = true,
                )
                ToolResult.ok("opening $url in canvas")
            }
            "chrome" -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        // Prefer Chrome explicitly; if missing, retry
                        // without the package constraint.
                        setPackage("com.android.chrome")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        // Chrome not installed — fall back to any handler.
                        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(fallback)
                    }
                    ToolResult.ok("opened $url in chrome (or default handler)")
                }.getOrElse { ToolResult.fail("dispatch failed: ${it.message}") }
            }
            else -> ToolResult.fail("mode must be 'inline' or 'chrome'")
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
}
