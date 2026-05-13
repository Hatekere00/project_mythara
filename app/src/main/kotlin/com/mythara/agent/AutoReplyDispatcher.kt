package com.mythara.agent

import android.content.Context
import android.util.Log
import com.mythara.audit.AuditLogger
import com.mythara.data.AutopilotStore
import com.mythara.data.EnterpriseAutopilotStore
import com.mythara.data.FavoritesStore
import com.mythara.services.NotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the global [NotificationListener.newNotifications] stream,
 * matches incoming messages against the user's [FavoritesStore], and
 * fires a contact-scoped agent turn that composes + sends a reply via
 * the appropriate direct-send tool.
 *
 * Process-scoped — lives in [com.mythara.MytharaApp] and starts at
 * cold-boot. This is the key difference from the ChatViewModel-hosted
 * auto-process path: that one only runs while the chat surface is
 * subscribed (which doesn't happen on a locked phone with the UI
 * detached). The dispatcher uses [AgentRunner] directly so it works
 * regardless of UI state.
 *
 * Gating chain (any one of these returning false drops the auto-reply):
 *   1. Master AutopilotStore.isEnabled() must be true
 *   2. EnterpriseAutopilotStore.isEnabled() must be true for enterprise pkgs
 *   3. Notification must carry a sender (title) we can match to a Favorite
 *   4. The Favorite's per-contact enabled must be true
 *   5. The notification's pkg must be in the Favorite's apps allowlist
 *   6. The body must be non-empty (auto-reply to an emoji-only ping is noise)
 *
 * When all checks pass we hand a custom-shaped turn to the agent:
 *   [auto-reply to <NAME> on <APP>, tone=<TONE>] message: "<BODY>"
 *
 * The agent loop's system prompt recognises [AUTO_REPLY_PREFIX] and
 * injects:
 *   - the tone guidance
 *   - the "never mention other contacts" hard rule
 *   - the directive to call send_whatsapp_direct / send_sms_direct
 *     immediately with the composed reply
 *
 * Conversation isolation: the contact name is also passed as a
 * scope facet so [SemanticRecall] (when it eventually grows
 * contact-scoping) only surfaces vault entries tagged with that
 * contact. For now, the prompt-level guardrail is the primary
 * defense; vault-level filtering is a follow-up.
 */
@Singleton
class AutoReplyDispatcher @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val favorites: FavoritesStore,
    private val autopilot: AutopilotStore,
    private val entAutopilot: EnterpriseAutopilotStore,
    private val runner: AgentRunner,
    private val audit: AuditLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            NotificationListener.newNotifications.collect { r ->
                handle(r)
            }
        }
        Log.d(TAG, "AutoReplyDispatcher started")
    }

    private suspend fun handle(r: NotificationListener.Recent) {
        // Cheap drops first — bail fast on the common case (a normal
        // notification that doesn't match any favorite).
        if (r.ongoing) return
        if (r.packageName == ctx.packageName) return
        val title = r.title?.trim().orEmpty()
        val body = r.text?.trim().orEmpty()
        if (title.isEmpty() || body.isEmpty()) return

        val fav = favorites.matchByName(title) ?: return
        if (!fav.enabled) return
        if (fav.apps.isNotEmpty() && r.packageName !in fav.apps) {
            Log.d(TAG, "favorite ${fav.name} matched but pkg=${r.packageName} not in app allowlist")
            return
        }

        // Master gates.
        if (!autopilot.isEnabled()) {
            Log.d(TAG, "autopilot off — skipping auto-reply to ${fav.name}")
            return
        }
        if (EnterpriseAutopilotStore.isEnterprise(r.packageName) && !entAutopilot.isEnabled()) {
            Log.d(TAG, "enterprise autopilot off — skipping ${r.packageName} reply to ${fav.name}")
            return
        }

        // Don't pile auto-replies on top of an in-progress agent turn —
        // we'd thrash the streaming buffer and the user would hear
        // overlapping TTS.
        if (runner.busy.value) {
            Log.d(TAG, "agent busy — deferring auto-reply to ${fav.name}")
            return
        }

        Log.d(TAG, "auto-reply firing: ${fav.name} via ${r.packageName} (tone=${fav.tone.label})")
        audit.logSystem("auto-reply trigger: ${fav.name} on ${r.packageName} tone=${fav.tone.label}")

        // Format. The prefix flips the agent into auto-reply mode; the
        // structured trailing payload is parsed by AgentLoop into the
        // tone-conditioned system message.
        val turnText = buildString {
            append(AUTO_REPLY_PREFIX).append(' ')
            append("contact=").append(escape(fav.name)).append(' ')
            append("phone=").append(escape(fav.digits)).append(' ')
            append("app=").append(escape(r.packageName)).append(' ')
            append("tone=").append(fav.tone.label).append('\n')
            append("incoming: ").append(body)
        }
        runner.submit(text = turnText, fromVoice = false)
    }

    private fun escape(s: String): String = s.replace(' ', '_').replace('=', '_')

    companion object {
        private const val TAG = "Mythara/AutoReply"

        /**
         * Marker on the leading user-text line that flips the agent
         * into "compose a reply for me" mode for this turn. Kept short
         * because it's part of the persisted chat history.
         */
        const val AUTO_REPLY_PREFIX = "[auto-reply]"
    }
}
