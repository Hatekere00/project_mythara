package com.mythara.wear.complications

import android.app.PendingIntent
import android.content.Intent
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
 * Watch-face complication that returns ONLY the title of the next
 * upcoming reminder ("Gibraltar dry run"). Paired with
 * [ReminderCountdownComplicationService] (which returns "in 14 min")
 * so the Tactical face can render the two pieces in different
 * visual treatments — bold static countdown on top, marquee-
 * scrolling title beneath.
 */
class ReminderTitleComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationFor(type, "Stretch")

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val rem = ClusterDataStore.reminder(this)
        val text = rem?.title.orEmpty()
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
                contentDescription = PlainComplicationText.Builder(text).build(),
            ).setTapAction(tap).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(120)).build(),
                contentDescription = PlainComplicationText.Builder(text).build(),
            ).setTapAction(tap).build()

            else -> null
        }
    }
}
