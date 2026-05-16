package com.mythara.agent.tools

import com.mythara.agent.ConfirmationGate
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.PhoneControlAccessibilityService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M6 automation tools. Each one drives the device via
 * [PhoneControlAccessibilityService]'s gesture dispatch + node
 * actions. All three require user confirmation through
 * [ConfirmationGate] — they perform actions on the user's behalf
 * inside other apps, which is exactly the surface that needs an
 * explicit "yes" before each call.
 *
 * Coordinates are absolute screen pixels. The model gets dimensions
 * from `read_screen` if it needs to pick a target visually. We don't
 * try to translate "tap the Send button" into coords here — that's
 * the model's job once it sees the screen snapshot.
 */

@Singleton
class TapTool @Inject constructor() : Tool {

    override val name: String = "tap"
    override val description: String =
        "Tap a single point on the screen at (x,y) screen pixels. " +
            "Use after read_screen to interact with a UI element the model identified. " +
            "Requires the user to grant Accessibility access and confirm each tap."

    override val requiresConfirmation: Boolean = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("x", buildJsonObject { put("type", "integer"); put("description", "Horizontal screen pixel.") })
                put("y", buildJsonObject { put("type", "integer"); put("description", "Vertical screen pixel.") })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))))
    }

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest {
        val x = (args["x"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val y = (args["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        // No allowlist key — each tap is a unique location, blanket
        // grants would defeat the point. Users who want hands-off
        // automation can flip a future "automation mode" master toggle.
        return ConfirmationGate.ConfirmRequest(
            id = "", toolName = name,
            title = "Tap screen at ($x, $y)?",
            body = "Mythara wants to dispatch a tap gesture at this point on whatever's currently on screen.",
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(false, """{"error":"accessibility_not_granted","detail":"Enable Mythara in Settings → Accessibility."}""")
        val x = (args["x"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, """{"error":"missing_x"}""")
        val y = (args["y"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, """{"error":"missing_y"}""")
        val ok = service.tap(x.toFloat(), y.toFloat())
        return if (ok) ToolResult(true, """{"ok":true,"x":$x,"y":$y}""")
        else ToolResult(false, """{"error":"gesture_failed","detail":"Accessibility service rejected or canceled the tap. Coordinates may be off-screen."}""")
    }
}

@Singleton
class SwipeTool @Inject constructor() : Tool {

    override val name: String = "swipe"
    override val description: String =
        "Swipe from (x1,y1) to (x2,y2) over an optional duration. " +
            "Use to scroll, drag, or fling. Defaults to 300ms which feels like a natural scroll."

    override val requiresConfirmation: Boolean = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("x1", buildJsonObject { put("type", "integer") })
                put("y1", buildJsonObject { put("type", "integer") })
                put("x2", buildJsonObject { put("type", "integer") })
                put("y2", buildJsonObject { put("type", "integer") })
                put(
                    "duration_ms",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional, default 300, range 50-2000.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf("x1", "y1", "x2", "y2").map { JsonPrimitive(it) }))
    }

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest {
        val x1 = (args["x1"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val y1 = (args["y1"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val x2 = (args["x2"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val y2 = (args["y2"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return ConfirmationGate.ConfirmRequest(
            id = "", toolName = name,
            title = "Swipe from ($x1, $y1) to ($x2, $y2)?",
            body = "Mythara wants to drag/fling across the screen on the foreground app.",
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(false, """{"error":"accessibility_not_granted"}""")
        val x1 = (args["x1"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, """{"error":"missing_x1"}""")
        val y1 = (args["y1"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, """{"error":"missing_y1"}""")
        val x2 = (args["x2"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, """{"error":"missing_x2"}""")
        val y2 = (args["y2"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ToolResult(false, """{"error":"missing_y2"}""")
        val duration = ((args["duration_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 300L)
            .coerceIn(50L, 2_000L)
        val ok = service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration)
        return if (ok) ToolResult(true, """{"ok":true,"x1":$x1,"y1":$y1,"x2":$x2,"y2":$y2,"duration_ms":$duration}""")
        else ToolResult(false, """{"error":"gesture_failed"}""")
    }
}

@Singleton
class TypeTextTool @Inject constructor() : Tool {

    override val name: String = "type_text"
    override val description: String =
        "Type text into the currently focused editable field. " +
            "Use after read_screen confirms a text field is focused. " +
            "If nothing is focused, the tool tries the first editable node it finds."

    override val requiresConfirmation: Boolean = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("text"))))
    }

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest {
        val text = (args["text"] as? JsonPrimitive)?.content.orEmpty()
        return ConfirmationGate.ConfirmRequest(
            id = "", toolName = name,
            title = "Type into focused field?",
            body = if (text.length <= 120) text else "${text.take(120)}…",
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(false, """{"error":"accessibility_not_granted"}""")
        val text = (args["text"] as? JsonPrimitive)?.content
            ?: return ToolResult(false, """{"error":"missing_text"}""")
        val ok = service.typeText(text)
        return if (ok) ToolResult(true, """{"ok":true,"text_len":${text.length}}""")
        else ToolResult(false, """{"error":"no_editable_focus","detail":"Couldn't find a focused/editable field. Run read_screen and tap the target field first."}""")
    }
}
