package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.mythara.agent.ConfirmationGate
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.PhoneControlAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `send_whatsapp_direct` — send a WhatsApp message without leaving
 * Mythara in the user's hands. The agent drives WhatsApp through
 * Accessibility automation: opens the wa.me deep-link (which lands
 * directly in a chat with the message pre-filled), waits for the
 * UI to settle, taps the send button via Accessibility, then
 * brings Mythara back to the foreground.
 *
 * The user sees a brief WhatsApp flash (~2s) then is back in
 * Mythara with Lumi confirming. Compare to `send_whatsapp` (the
 * composer variant) which dumps the user inside WhatsApp with a
 * pre-filled draft and they have to tap Send + navigate back
 * themselves.
 *
 * Requirements:
 *  - PhoneControlAccessibilityService granted (Settings →
 *    Accessibility → Mythara). Without it the tool fails fast
 *    and the model can fall back to send_whatsapp.
 *  - WhatsApp installed (com.whatsapp).
 *  - Phone number in E.164 with country code (wa.me strips '+').
 *
 * No public WhatsApp API exists for true zero-UI sending — this is
 * the closest you can get without write-overlay-permission tricks.
 *
 * Gated by ConfirmationGate per the per-call rules, but when the
 * global "always confirm" toggle is off (default), this fires
 * immediately like every other direct-send.
 */
@Singleton
class SendWhatsAppDirectTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "send_whatsapp_direct"
    override val description: String =
        "Send a WhatsApp message WITHOUT leaving the user staring at WhatsApp. " +
            "Mythara opens WhatsApp briefly (~2 seconds), auto-fills the message, " +
            "auto-taps Send via the Accessibility service, then returns Mythara " +
            "to the foreground. " +
            "Prefer this over `send_whatsapp` whenever the user explicitly said 'send'/'message'/'whatsapp X Y' " +
            "(active intent) rather than 'compose'/'draft' (passive). " +
            "Requires the user to have granted Mythara's Accessibility service. " +
            "Falls back to send_whatsapp (composer) on accessibility-not-granted."

    /**
     * Driving another app's UI counts as destructive — same gate
     * shape as other direct-send tools. Allowlist key is per-number
     * so granting "always allow" to mom doesn't grant it for
     * everyone.
     */
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
                        put(
                            "description",
                            "Recipient phone number in E.164 (e.g. +14155551234). Country code REQUIRED — wa.me can't resolve local-format numbers.",
                        )
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Message body. The user won't see it before send.")
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
            title = "WhatsApp $to?",
            body = if (body.length <= PREVIEW_CHARS) body else "${body.take(PREVIEW_CHARS)}…",
            allowlistKey = if (to.isNotEmpty()) "${name}:$to" else null,
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        if (to.isEmpty()) return ToolResult(false, """{"error":"missing_to"}""")
        if (body.isEmpty()) return ToolResult(false, """{"error":"missing_body"}""")

        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(
                ok = false,
                output = """{"error":"accessibility_not_granted","detail":"Enable Mythara in Settings → Accessibility. The composer-variant send_whatsapp works without it."}""",
            )

        // 1. Launch WhatsApp with the message pre-filled via wa.me.
        //    Strip the '+' from the phone — wa.me URLs want bare digits.
        val phoneDigits = to.filter { it.isDigit() }
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("whatsapp://send?phone=$phoneDigits&text=$encodedBody")
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.d(TAG, "launching WhatsApp for $phoneDigits (body ${body.length} chars)")
        val launched = runCatching { ctx.startActivity(intent) }.isSuccess
        if (!launched) {
            Log.w(TAG, "whatsapp:// scheme failed, retrying via wa.me HTTPS")
            // Fallback to wa.me HTTPS URL (still resolves to WhatsApp
            // via Android's intent picker when installed).
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$phoneDigits?text=$encodedBody")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!runCatching { ctx.startActivity(webIntent) }.isSuccess) {
                Log.w(TAG, "wa.me launch also failed — WhatsApp not installed?")
                return ToolResult(false, """{"error":"whatsapp_unavailable","detail":"Couldn't open WhatsApp. Is it installed?"}""")
            }
        }

        // 2. Wait for the chat surface to render + the send button
        //    to be tap-ready. Initial wait is short; we poll for
        //    the send button rather than rely on a single fixed
        //    delay — cold WhatsApp can take 2+ seconds on slow
        //    devices, warm WhatsApp resolves in ~400ms.
        delay(INITIAL_WAIT_MS)

        // 3. Poll for the send button. WhatsApp's send-button labels
        //    drift across releases — id "send" has been stable for
        //    years, content-descriptions have rotated through "Send",
        //    "send", "Send message", "Send button", "Send messages".
        //    Newer builds also start labelling with the actual recipient
        //    name (e.g. "Send to Mom"). We try a wide net AND log every
        //    attempt so user-side failures can be diagnosed from logcat.
        val sendSelectors = listOf<Pair<String, suspend () -> Boolean>>(
            "id:send" to { service.tapNodeWithId("send") },
            "id:send_button" to { service.tapNodeWithId("send_button") },
            "desc:Send" to { service.tapNodeWithDesc("Send") },
            "desc:send" to { service.tapNodeWithDesc("send") },
            "desc:Send button" to { service.tapNodeWithDesc("Send button") },
            "desc:Send message" to { service.tapNodeWithDesc("Send message") },
            "desc:Send messages" to { service.tapNodeWithDesc("Send messages") },
            // Localised variants seen in the wild.
            "desc:Enviar" to { service.tapNodeWithDesc("Enviar") },
            "desc:Envoyer" to { service.tapNodeWithDesc("Envoyer") },
            // Newer builds tag the send arrow with the recipient name.
            "desc:Send to" to { service.tapNodeWithDesc("Send to") },
        )
        var sent = false
        var hitSelector: String? = null
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        var attempts = 0
        outer@ while (System.currentTimeMillis() < deadline) {
            attempts++
            for ((label, selector) in sendSelectors) {
                if (runCatching { selector() }.getOrDefault(false)) {
                    sent = true
                    hitSelector = label
                    Log.d(TAG, "send button hit on '$label' after $attempts attempts")
                    break@outer
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        if (!sent) {
            Log.w(TAG, "send button NOT found after $attempts attempts over ${MAX_WAIT_MS}ms — UI must have changed")
            return ToolResult(
                ok = false,
                output = """{"error":"send_button_not_found","detail":"Opened WhatsApp with the message pre-filled, but couldn't auto-tap Send after $attempts attempts. WhatsApp's UI may have changed — tap Send manually this time."}""",
            )
        }

        // 4. Give WhatsApp a beat to register the send, then bring
        //    Mythara back to the foreground.
        delay(RETURN_DELAY_MS)
        service.bringMytharaToFront()

        return ToolResult(
            ok = true,
            output = """{"ok":true,"sent_to":${JsonPrimitive(to)},"body_len":${body.length},"returned_to_mythara":true}""",
        )
    }

    companion object {
        private const val TAG = "Mythara/WA"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val PREVIEW_CHARS = 160
        /** Short wait before we start polling — gives the activity a moment to draw. */
        private const val INITIAL_WAIT_MS = 400L
        /** Total poll budget. Cold-start WhatsApp on slow phones needs ~2s. */
        private const val MAX_WAIT_MS = 3_500L
        /** Interval between successive attempts during polling. */
        private const val POLL_INTERVAL_MS = 250L
        /** Time between tapping Send and bringing Mythara back. */
        private const val RETURN_DELAY_MS = 600L
    }
}
