package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `place_call` — open the system dialer pre-filled with the chosen
 * number. The user taps Call themselves. Uses `ACTION_DIAL`, which
 * requires NO runtime permission (CALL_PHONE is only needed for
 * `ACTION_CALL` direct-dial, which lands with M6's ConfirmationGate).
 *
 * Same safety stance as `send_sms`: in v1 the user is always the one
 * who hits the trigger. Mythara just opens the right surface with the
 * right context.
 */
@Singleton
class PlaceCallTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "place_call"
    override val description: String =
        "Open the phone dialer pre-filled with a number. " +
            "The user taps the call button themselves — Mythara never dials silently in v1. " +
            "Use when the user asks 'call John' or 'phone the office' — after resolving the name to a number via read_contact."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "number",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Phone number to dial (E.164 preferred — e.g. +14155551234).")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("number"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val number = (args["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (number.isEmpty()) return ToolResult(false, """{"error":"missing_number"}""")
        val uri = Uri.parse("tel:$number")
        val intent = Intent(Intent.ACTION_DIAL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = runCatching { ctx.startActivity(intent) }
        return if (result.isSuccess) {
            ToolResult(
                ok = true,
                output = """{"ok":true,"opened":"dialer","number":${JsonPrimitive(number)}}""",
            )
        } else {
            ToolResult(
                ok = false,
                output = """{"error":"launch_failed","detail":${JsonPrimitive(result.exceptionOrNull()?.message ?: "no dialer")}}""",
            )
        }
    }
}
