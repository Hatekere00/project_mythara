package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.PhoneControlAccessibilityService
import com.mythara.services.ScreenReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_screen` — returns a compact JSON snapshot of the currently
 * foregrounded app's UI. Powered by [PhoneControlAccessibilityService]
 * and [ScreenReader].
 *
 * Failure modes the model needs to understand:
 *   - service not granted (user never enabled it in
 *     Settings → Accessibility) → `error: accessibility_not_granted`
 *   - no active window (rare; usually transient between activities)
 *     → `error: no_active_window`
 *
 * Read-only — never confirmed before execution. Confirmation gating
 * is reserved for the M6 automation tools (tap / swipe / type) and
 * the M7 communication tools (SMS / call) that actually do things on
 * the user's behalf.
 */
@Singleton
class ReadScreenTool @Inject constructor(
    private val screenReader: ScreenReader,
) : Tool {

    override val name: String = "read_screen"

    override val description: String =
        "Read the user's current phone screen and return a structured JSON snapshot of what's visible (text, buttons, fields, scroll containers). Use when the user asks 'what's on my screen' or you need to understand the foreground app's UI to answer."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(
                ok = false,
                output = """{"error":"accessibility_not_granted","detail":"Mythara's Accessibility Service isn't enabled. Open Settings → Accessibility → Mythara to grant it."}""",
            )
        val root = service.currentRootNode()
        if (root == null) {
            return ToolResult(
                ok = false,
                output = """{"error":"no_active_window","detail":"Nothing in the foreground or the system briefly blocked us. Try again."}""",
            )
        }
        val snapshot = screenReader.snapshot(root)
            ?: return ToolResult(
                ok = false,
                output = """{"error":"snapshot_failed"}""",
            )
        runCatching { root.recycle() }
        return ToolResult(ok = true, output = screenReader.render(snapshot))
    }
}
