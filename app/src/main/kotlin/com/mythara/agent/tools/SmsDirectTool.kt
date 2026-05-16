package com.mythara.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
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
 * `send_sms_direct` — silently send an SMS without opening the
 * composer. The user-supervised counterpart to [SmsComposerTool],
 * gated by [ConfirmationGate]: every call pops a "Send X to Y?"
 * prompt before [SmsManager.sendTextMessage] fires. Once accepted,
 * the message goes out instantly — no system UI in between.
 *
 * Why both this AND the composer-based tool: voice flows are way
 * smoother with direct-send ("hey mythara, tell mom I'm running late"
 * → confirm → done). But many cases want the user to review/edit
 * (long messages, sensitive recipients), so the composer path stays
 * for the model to pick when appropriate.
 *
 * Confirmation key: `send_sms_direct:<number>`. If the user ticks
 * "Always allow this", future direct-sends to that *specific* number
 * skip the prompt — but a fresh number always prompts. Tighter than
 * a per-tool grant; safer.
 */
@Singleton
class SmsDirectTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "send_sms_direct"
    override val description: String =
        "Send an SMS silently via SmsManager (no composer UI). " +
            "Each call requires the user to tap Allow on a confirmation prompt — " +
            "destructive action, not a free chat. Prefer `send_sms` (the composer variant) " +
            "when the user might want to review/edit before sending. Use this only when " +
            "the user has explicitly said 'send it' or via voice ('tell mom I'm late')."

    override val requiresConfirmation: Boolean = true

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Recipient phone number (E.164 preferred — e.g. +14155551234).")
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Message body. Keep it short; the user can't edit before send.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("to"), JsonPrimitive("body"))))
    }

    override fun confirmationFor(args: JsonObject): ConfirmationGate.ConfirmRequest? {
        val to = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        return ConfirmationGate.ConfirmRequest(
            id = "",
            toolName = name,
            title = "Send SMS to $to?",
            body = if (body.length <= MAX_PREVIEW_CHARS) body else "${body.take(MAX_PREVIEW_CHARS)}…",
            allowlistKey = if (to.isNotEmpty()) "${name}:$to" else null,
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"SEND_SMS isn't granted. Open Settings → Apps → Mythara → Permissions → SMS."}""",
            )
        }
        val to = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        if (to.isEmpty()) return ToolResult(false, """{"error":"missing_to"}""")
        if (body.isEmpty()) return ToolResult(false, """{"error":"missing_body"}""")

        return runCatching {
            // SmsManager.getDefault is the legacy API (still works on
            // API 26+); SubscriptionManager.from(ctx)... would let us
            // pick a specific SIM on multi-SIM phones — out of scope.
            @Suppress("DEPRECATION")
            val mgr = SmsManager.getDefault()
            // Multi-part split — long SMS exceed the 160 GSM-7 / 70
            // UCS-2 single-part cap and SmsManager will silently
            // truncate without this.
            val parts = mgr.divideMessage(body)
            if (parts.size == 1) {
                mgr.sendTextMessage(to, null, body, null, null)
            } else {
                mgr.sendMultipartTextMessage(to, null, parts, null, null)
            }
            ToolResult(
                ok = true,
                output = """{"ok":true,"sent_to":${JsonPrimitive(to)},"parts":${parts.size},"body_len":${body.length}}""",
            )
        }.getOrElse { e ->
            ToolResult(
                ok = false,
                output = """{"error":"send_failed","detail":${JsonPrimitive(e.message ?: e.javaClass.simpleName)}}""",
            )
        }
    }

    companion object {
        private const val MAX_PREVIEW_CHARS = 160
    }
}
