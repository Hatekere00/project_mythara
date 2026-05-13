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
import com.mythara.data.EnterpriseAutopilotStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EnterpriseAutopilotViewModel @Inject constructor(
    private val store: EnterpriseAutopilotStore,
) : ViewModel() {
    val enabled: StateFlow<Boolean> =
        store.enabledFlow().stateIn(viewModelScope, SharingStarted.Eagerly, EnterpriseAutopilotStore.DEFAULT_ENABLED)

    fun set(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }
}

/**
 * Separate enterprise autopilot toggle. When ON, Mythara will
 * auto-respond on enterprise apps (Teams, Outlook, Slack, etc.) for
 * favorites whose app-allowlist covers those packages. When OFF, work
 * apps stay strictly read-only — calendar invites still surface,
 * meeting notifications still get logged, but no auto-replies and no
 * auto-actions on work channels.
 *
 * Defaults OFF because the consequences of an auto-reply on a work
 * thread are heavier than on personal chat. The user has to flip this
 * deliberately.
 */
@Composable
fun EnterpriseAutopilotPanel(vm: EnterpriseAutopilotViewModel = hiltViewModel()) {
    val on by vm.enabled.collectAsState()

    val borderColor = if (on) MytharaColors.Charple else MytharaColors.SurfaceHigh
    val titleColor = if (on) MytharaColors.Charple else MytharaColors.FgMute

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(if (on) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (on) Glyph.DiamondFilled else Glyph.DiamondOutline} enterprise autopilot",
                style = MaterialTheme.typography.labelLarge.copy(color = titleColor),
            )
            Text(
                text = if (on) "ON" else "OFF",
                color = if (on) MytharaColors.Charple else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.set(!on) }) {
                Text(
                    text = if (on) Glyph.CircleFilled else Glyph.CircleOutline,
                    color = if (on) MytharaColors.Charple else MytharaColors.FgMute,
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
            text = "${Glyph.AccentBar} controls auto-actions on Teams, Outlook, Slack, Webex, Zoom, Google Chat. When ON, Lumi can auto-reply on these apps for the favorites you've configured + creates calendar events on your work calendar. When OFF, these apps are strictly read — meeting invites still surface, calendar reads still work, but nothing gets sent back without an explicit tap. Reading from enterprise apps (Outlook calendar events, Teams meeting times) is always allowed regardless of this toggle.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
    }
}
