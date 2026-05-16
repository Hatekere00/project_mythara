package com.mythara.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the quick-reply text the user types into the
 * notification's inline RemoteInput field (works from the
 * lockscreen, the shade, and Wear OS watch faces) and feeds it
 * straight into the agent loop via [AgentRunner.submit].
 *
 * Why a BroadcastReceiver rather than launching the activity:
 * the user is INTENTIONALLY staying out of the app — they want
 * to ping Mythara without unlocking + opening chat. Receiver +
 * AgentRunner.scope (process-wide, not tied to the activity)
 * is the only path that delivers that.
 *
 * The reply lands as a normal user-typed turn in chat history;
 * Mythara's response goes back via the existing ReplyNotification
 * path so the round-trip is fully off-screen if the user wants.
 */
@AndroidEntryPoint
class NotificationReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var runner: AgentRunner

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFICATION_REPLY) return
        val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = bundle.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Log.d(TAG, "empty reply text — ignoring")
            return
        }
        Log.d(TAG, "received quick reply: '${text.take(60)}…'")
        // Submit as typed (fromVoice=false). The reply notification
        // is text-input so a user typed it; voice replies come via
        // the assist intent path instead.
        runner.submit(text = text, fromVoice = false)
    }

    companion object {
        private const val TAG = "Mythara/ReplyRcv"

        /** Custom action so the OS routes the RemoteInput bundle here. */
        const val ACTION_NOTIFICATION_REPLY = "com.mythara.action.NOTIFICATION_REPLY"

        /** Key the RemoteInput Builder uses to store the typed text. */
        const val KEY_REPLY_TEXT = "mythara_reply_text"
    }
}
