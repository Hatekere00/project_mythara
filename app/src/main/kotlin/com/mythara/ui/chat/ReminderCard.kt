package com.mythara.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.reminders.ReminderAlarmReceiver
import com.mythara.reminders.ReminderAlarmScheduler
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-chat reminder card. Shows when a scheduled task is "fired" (alarm
 * has fired AND status is RUNNING / PENDING but past-due). Three quick
 * actions surface as Crush-styled chips: Done, +15m, +1h. Tapping
 * fires the same broadcast as the notification's actions, so the
 * state machine has exactly one entry point (ReminderAlarmReceiver).
 *
 * Visual treatment: Citron-accented border so a live reminder pops
 * against the surrounding chat. Pending-future reminders (the alarm
 * hasn't fired yet) render in a muted Malibu so the user sees them
 * inline-time but doesn't conflate them with "do this now".
 */
@Composable
fun ReminderCard(item: ChatViewModel.ChatItem.ReminderCard) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val isLive = item.scheduledForMs <= now && !item.terminal
    val borderColor = when {
        item.terminal -> MytharaColors.SurfaceHigh
        isLive -> MytharaColors.Citron
        else -> MytharaColors.Malibu
    }
    val titlePrefix = when {
        item.terminal -> if (item.status == "DONE") "${Glyph.Check} done" else "${Glyph.Cross} ${item.status.lowercase()}"
        isLive -> "${Glyph.Dot} reminder"
        else -> "${Glyph.CircleOutline} scheduled"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = titlePrefix,
                color = borderColor,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = formatScheduledFor(item.scheduledForMs),
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (item.body.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.body,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (item.terminal) {
            // No actions on terminal cards — the user already resolved
            // this. Result text (if any) shows below the title.
            if (item.resultText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${Glyph.AccentBar} ${item.resultText}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Column
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionChip("${Glyph.Check} done", MytharaColors.Julep) { fireAction(ctx, item.id, "done") }
            ActionChip("+15m", MytharaColors.Mustard) { fireAction(ctx, item.id, "snooze_15m") }
            ActionChip("+1h", MytharaColors.Mustard) { fireAction(ctx, item.id, "snooze_1h") }
            ActionChip("+3h", MytharaColors.Mustard) { fireAction(ctx, item.id, "snooze_3h") }
        }
    }
}

@Composable
private fun ActionChip(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun fireAction(ctx: Context, taskId: String, kind: String) {
    val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
        action = ReminderAlarmScheduler.ACTION_ACTION
        putExtra(ReminderAlarmScheduler.EXTRA_TASK_ID, taskId)
        putExtra(ReminderAlarmScheduler.EXTRA_ACTION_KIND, kind)
    }
    ctx.sendBroadcast(intent)
}

private fun formatScheduledFor(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = ms - now
    val abs = Math.abs(diff)
    val fmt = when {
        abs < 24L * 3600 * 1000 -> SimpleDateFormat("h:mm a", Locale.getDefault())
        abs < 7L * 24 * 3600 * 1000 -> SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    }
    val whenStr = fmt.format(Date(ms))
    return when {
        diff > 60_000 -> "in ${humanDelta(diff)} · $whenStr"
        diff > -60_000 -> "now · $whenStr"
        else -> "$whenStr (${humanDelta(-diff)} ago)"
    }
}

private fun humanDelta(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    val d = h / 24
    return when {
        d > 0 -> "${d}d ${h % 24}h"
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}
