package com.mythara.reminders

import android.util.Log
import com.mythara.mic.Tts
import com.mythara.tasks.TaskEntity
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personalized voice announcement of a fired reminder.
 *
 * v1 keeps it simple — a curated template with the task's title, the
 * wall-clock time, and a small phrasing variation pulled from a rota
 * so two-back-to-back reminders don't sound robotic. v2 (next pass)
 * can route through the LLM with the user's persona context for a
 * fully bespoke greeting; but v1 cost = $0/reminder, fires instantly
 * (no network round-trip on alarm-wake), and reads naturally.
 *
 * The voice route is whatever the user already configured for
 * Mythara's regular TTS path — Android-native by default, ElevenLabs
 * when the key is set + the toggle's on. Same `Tts.speak` entry
 * point chat replies use, so announcements inherit the user's voice
 * choice transparently.
 */
@Singleton
class ReminderAnnouncer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    /**
     * dagger.Lazy because Tts → Speak / locale pipeline can transitively
     * reach AgentRunner; lazy here defers resolution past Hilt's
     * compile-time cycle check.
     */
    private val tts: Lazy<Tts>,
) {

    suspend fun announce(task: TaskEntity) {
        val text = buildAnnouncement(task)
        runCatching {
            // Lazy.get() materialises the Tts singleton; .speak is fire-
            // and-forget — returns immediately, audio plays on its own.
            tts.get().speak(text)
        }.onFailure { Log.w(TAG, "tts announce failed: ${it.message}") }
        Log.d(TAG, "announced ${task.id}: \"$text\"")
    }

    /**
     * Compose the spoken text. Pulls a rotating opener so consecutive
     * announcements vary. Adds wall-clock time when it'd help orient
     * the user (long-snoozed reminders especially).
     */
    private fun buildAnnouncement(task: TaskEntity): String {
        val opener = OPENERS[(System.currentTimeMillis() / 1000L % OPENERS.size).toInt()]
        val title = task.title.ifBlank { "your reminder" }
        val body = task.body.takeIf { it.isNotBlank() }
        val clock = task.scheduledForMs?.let { CLOCK_FMT.format(Date(it)) }
        return buildString {
            append(opener).append(' ').append(title).append('.')
            if (body != null) {
                append(' ').append(body)
                if (!body.endsWith('.')) append('.')
            }
            if (clock != null) {
                append(" Scheduled for ").append(clock).append('.')
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/Announcer"
        private val CLOCK_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())

        /**
         * Conversational openers — rotates per fire so consecutive
         * reminders sound less like a robot. Kept short on purpose;
         * the title carries the meaning.
         */
        private val OPENERS = listOf(
            "Hey — reminder:",
            "Just a heads up:",
            "Quick reminder:",
            "Reminder time:",
            "Don't forget:",
            "Time for:",
            "Heads up:",
        )
    }
}
