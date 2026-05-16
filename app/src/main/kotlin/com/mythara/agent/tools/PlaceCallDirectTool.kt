package com.mythara.agent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.mythara.agent.ConfirmationGate
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
 * `place_call_direct` — initiate a call silently via ACTION_CALL.
 * Counterpart to [PlaceCallTool] (which opens the dialer pre-filled
 * for the user to tap Call). Always gated by [ConfirmationGate];
 * an accidental "yes" still means the user explicitly confirmed.
 *
 * Permission: CALL_PHONE. Granted at runtime — we surface a
 * `permission_denied` ToolResult when missing, with instructions
 * for the user.
 *
 * Confirmation key: `place_call_direct:<number>`. Always-allow per
 * number, not blanket — granting a recurring call to your partner
 * shouldn't grant calling random unknown numbers.
 */
@Singleton
class PlaceCallDirectTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "place_call_direct"
    override val description: String =
        "Place a phone call silently (no dialer UI in between). " +
            "Each call requires user confirmation via a prompt. " +
            "Prefer `place_call` (the dialer-opening variant) when the user might want " +
            "to reconsider before connecting. Use this only when the user has explicitly " +
            "said 'call them' or 'make the call'."

    override val requiresConfirmation: Boolean = true

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

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest? {
        val number = (args["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
        return ConfirmationGate.ConfirmRequest(
            id = "", toolName = name,
            title = "Call $number?",
            body = "Mythara will initiate the call immediately — no dialer in between.",
            allowlistKey = if (number.isNotEmpty()) "${name}:$number" else null,
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"CALL_PHONE isn't granted. Open Settings → Apps → Mythara → Permissions → Phone."}""",
            )
        }
        val number = (args["number"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (number.isEmpty()) return ToolResult(false, """{"error":"missing_number"}""")

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(intent)
            ToolResult(true, """{"ok":true,"dialed":${JsonPrimitive(number)}}""")
        }.getOrElse {
            ToolResult(false, """{"error":"call_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""")
        }
    }
}
