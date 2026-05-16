package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.calendar.CalendarPreAnnouncer
import com.mythara.data.CalendarPreAnnounceStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalendarPreAnnounceViewModel @Inject constructor(
    private val store: CalendarPreAnnounceStore,
    private val announcer: CalendarPreAnnouncer,
) : ViewModel() {
    val enabled: StateFlow<Boolean> =
        store.enabledFlow().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun set(value: Boolean) {
        viewModelScope.launch {
            store.setEnabled(value)
            // When the user flips ON, immediately scan for events in
            // the upcoming window so they don't have to wait 15 min
            // for the worker tick to register the first batch of
            // alarms.
            if (value) runCatching { announcer.scan() }
        }
    }
}

/**
 * Toggle: should Mythara announce a "<title> in 3 minutes — get ready"
 * line 3 minutes before every calendar event?
 *
 * Default OFF — opt-in. When ON, a 15-min periodic scanner walks the
 * upcoming hour of events (CalendarContract) and registers
 * AlarmManager exact alarms 3 min before each. Alarm fires →
 * announcement plays via TTS, OR via Music-Mode tones if the user
 * has Music Mode on (so the secret-language path stays consistent).
 */
@Composable
fun CalendarPreAnnouncePanel(vm: CalendarPreAnnounceViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} pre-announce calendar events",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = if (on) "ON" else "OFF",
                color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.set(!on) }) {
                Text(
                    text = if (on) Glyph.CircleFilled else Glyph.CircleOutline,
                    color = if (on) MytharaColors.Bok else MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "  tap to turn ${if (on) "off" else "on"}",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} when ON, Mythara reads upcoming events from your calendars and announces \"<title> in 3 minutes — get ready\" exactly 3 minutes before each one. Speaks via TTS by default; if Music Mode is on, plays the announcement as the secret-language tone phrase instead. Skips all-day events. Reads CalendarContract — needs the Calendar permission, which you grant via Settings → Apps → Mythara → Permissions.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
    }
}
