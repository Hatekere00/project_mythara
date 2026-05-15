package com.mythara.wear.complications

import android.app.PendingIntent
import android.content.Intent
import android.text.format.DateUtils
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.mythara.wear.ClusterDataStore
import com.mythara.wear.MainActivity

/**
 * Watch-face complication that returns ONLY the live countdown
 * ("in 14 min") for the next upcoming reminder. Paired with
 * [ReminderTitleComplicationService] (which returns the title) so
 * the Tactical face can render the two pieces in different visual
 * weights — the countdown stays static + bold + always-visible while
 * the title beneath it marquees.
 *
 * Reads the same cached [ClusterDataStore.reminder] state the
 * combined [ReminderComplicationService] does, so no new
 * phone-side wiring is required.
 */
class ReminderCountdownComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationFor(type, "in 12 min")

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val rem = ClusterDataStore.reminder(this)
        val text = if (rem != null) {
            DateUtils.getRelativeTimeSpanString(
                rem.atMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        } else {
            ""
        }
        listener.onComplicationData(complicationFor(request.complicationType, text))
    }

    private fun complicationFor(type: ComplicationType, text: String): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(20)).build(),
                contentDescription = PlainComplicationText.Builder("Time until next reminder: $text").build(),
            ).setTapAction(tap).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(40)).build(),
                contentDescription = PlainComplicationText.Builder("Time until next reminder: $text").build(),
            ).setTapAction(tap).build()

            else -> null
        }
    }
}
