package com.mythara.reminders

import android.content.Context
import android.util.Log
import com.mythara.data.UserNameStore
import com.mythara.mic.Tts
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.tasks.TaskEntity
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personalized voice announcement of a fired reminder.
 *
 * Composes a short greeting using:
 *  - The user's NAME (from [UserNameStore]) when known.
 *  - TIME OF DAY ("Good morning", "This afternoon", "Tonight" etc.).
 *  - A rotating opener so consecutive announcements vary.
 *  - Optional thin context from [LearningVault] — recent topic-tagged
 *    facts about the user — so the line feels "Lumi knows me"
 *    rather than template-shaped. v1 just picks a single relevant
 *    snippet when available; v2 can route through Gemma for fully
 *    bespoke phrasing.
 *
 * Speaks via the FORCED ElevenLabs path — reminders always use the
 * premium voice even when the user has the global EL toggle off,
 * because reminders are infrequent + high-priority and the warmer
 * voice meaningfully improves perceived quality. When no EL key is
 * configured, falls back to Android TTS cleanly.
 */
@Singleton
class ReminderAnnouncer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    /** dagger.Lazy — Tts transitively touches AgentRunner; lazy
     *  defers resolution past Hilt's compile-time cycle check. */
    private val tts: Lazy<Tts>,
    private val userNames: UserNameStore,
    private val vault: LearningVault,
) {

    suspend fun announce(task: TaskEntity) {
        val text = buildAnnouncement(task)
        runCatching {
            // Forced EL route — reminders always premium.
            tts.get().speakForcedElevenLabs(text)
        }.onFailure { Log.w(TAG, "tts announce failed: ${it.message}") }
        Log.d(TAG, "announced ${task.id}: \"$text\"")
    }

    private suspend fun buildAnnouncement(task: TaskEntity): String {
        val name = runCatching { userNames.name() }.getOrDefault("")
        val now = Calendar.getInstance()
        val tod = timeOfDay(now)
        val opener = opener(tod)
        val title = task.title.ifBlank { "your reminder" }
        val body = task.body.takeIf { it.isNotBlank() }
        val clock = task.scheduledForMs?.let { CLOCK_FMT.format(Date(it)) }
        // Personal flair — pull one short topic-tagged snippet if
        // we know something colourful about the user. Cheap query;
        // empty when nothing matches.
        val flair = runCatching { pickFlair(task) }.getOrNull()

        return buildString {
            append(opener)
            if (name.isNotBlank()) {
                append(' ').append(name.trim())
            }
            append(" — ")
            append(title)
            if (!title.endsWith('.') && !title.endsWith('!') && !title.endsWith('?')) {
                append('.')
            }
            if (body != null) {
                append(' ').append(body)
                if (!body.endsWith('.') && !body.endsWith('!') && !body.endsWith('?')) {
                    append('.')
                }
            }
            if (clock != null) {
                append(" Scheduled for ").append(clock).append('.')
            }
            if (!flair.isNullOrBlank()) {
                append(' ').append(flair)
            }
        }
    }

    /** Map hour-of-day to a friendly slot label. */
    private fun timeOfDay(cal: Calendar): TimeOfDay {
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> TimeOfDay.MORNING
            in 11..13 -> TimeOfDay.MIDDAY
            in 14..16 -> TimeOfDay.AFTERNOON
            in 17..19 -> TimeOfDay.EVENING
            in 20..23 -> TimeOfDay.NIGHT
            else -> TimeOfDay.LATE_NIGHT
        }
    }

    /**
     * Choose a time-of-day-appropriate opener. Rotates within the slot
     * via the timestamp so consecutive same-slot fires don't repeat.
     */
    private fun opener(tod: TimeOfDay): String {
        val pool = OPENERS[tod] ?: OPENERS[TimeOfDay.AFTERNOON]!!
        val idx = ((System.currentTimeMillis() / 60_000L) % pool.size).toInt()
        return pool[idx]
    }

    /**
     * Optional warm tail-line. Currently a tiny vault peek — if the
     * task title hints at a topic (food / work / family), grab one
     * recent fact tagged with that topic and append. v2 can route
     * through Gemma for fully natural phrasing. v1 keeps it cheap
     * and offline.
     */
    private suspend fun pickFlair(task: TaskEntity): String? {
        // Heuristic — pick a topic from the task title. Conservative
        // so we don't shoehorn irrelevant context into the line.
        val hints = mapOf(
            "lunch" to "food", "dinner" to "food", "eat" to "food",
            "gym" to "fitness", "run" to "fitness", "yoga" to "fitness",
            "work" to "work", "meeting" to "work", "deadline" to "work",
            "mom" to "family", "dad" to "family", "kid" to "family",
        )
        val title = task.title.lowercase()
        val topic = hints.entries.firstOrNull { (kw, _) -> title.contains(kw) }?.value
            ?: return null
        val rows = runCatching {
            vault.listByTier(com.mythara.memory.Tier.Semantic, limit = 200)
                .filter { vault.decodeFacets(it).any { f -> f == "topic:$topic" } }
                .sortedByDescending { it.tsMillis }
                .take(1)
        }.getOrDefault(emptyList())
        val row = rows.firstOrNull() ?: return null
        // Trim to a sentence-ish length so we don't ramble. Take the
        // first sentence or first 80 chars, whichever comes first.
        val raw = row.content.trim()
        val sentence = raw.substringBefore('.').take(80)
        return sentence.takeIf { it.isNotBlank() }
    }

    private enum class TimeOfDay { MORNING, MIDDAY, AFTERNOON, EVENING, NIGHT, LATE_NIGHT }

    companion object {
        private const val TAG = "Mythara/Announcer"
        private val CLOCK_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())

        /**
         * Time-of-day-aware openers. Each slot has 4-5 variants —
         * rotation across same-slot fires keeps consecutive
         * reminders from sounding identical.
         */
        private val OPENERS: Map<TimeOfDay, List<String>> = mapOf(
            TimeOfDay.MORNING to listOf(
                "Hey, good morning",
                "Morning",
                "Heads up",
                "Quick one this morning",
                "Just a nudge",
            ),
            TimeOfDay.MIDDAY to listOf(
                "Hey",
                "Quick reminder",
                "Just popping in",
                "Heads up",
            ),
            TimeOfDay.AFTERNOON to listOf(
                "Hey",
                "Afternoon nudge",
                "Quick one",
                "Just a reminder",
                "Heads up",
            ),
            TimeOfDay.EVENING to listOf(
                "Hey, good evening",
                "Evening reminder",
                "Just popping in",
                "Quick nudge",
            ),
            TimeOfDay.NIGHT to listOf(
                "Hey",
                "Quick reminder before you wind down",
                "Just a nudge",
                "One thing",
            ),
            TimeOfDay.LATE_NIGHT to listOf(
                "Hey",
                "Quiet nudge",
                "Just so you know",
            ),
        )
    }
}
