package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.ConfirmationSettings
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConfirmationPanelViewModel @Inject constructor(
    private val store: ConfirmationSettings,
) : ViewModel() {
    val alwaysConfirm: StateFlow<Boolean> = store.alwaysConfirmFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun setAlwaysConfirm(value: Boolean) {
        viewModelScope.launch { store.setAlwaysConfirm(value) }
    }
}

/**
 * "Confirm before destructive actions" toggle.
 *
 * Default OFF. The user explicitly said "when I say send a message
 * it must actually send", so out-of-the-box send/call/tap fire
 * silently. Flip on to revert to the v1 gated behaviour (per-call
 * dialog before SMS/dial/tap/swipe/type/skill-run).
 *
 * Independent of the per-key always-allow allowlist below it — the
 * allowlist still grants individual skips when "always allow this"
 * was ticked on a past prompt.
 */
@Composable
fun ConfirmationPanel(vm: ConfirmationPanelViewModel = hiltViewModel()) {
    val alwaysConfirm by vm.alwaysConfirm.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} confirm before destructive actions",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.setAlwaysConfirm(!alwaysConfirm) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (alwaysConfirm) Glyph.CircleFilled else Glyph.CircleOutline,
                color = if (alwaysConfirm) MytharaColors.Charple else MytharaColors.FgMute,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "ask before send / call / tap / swipe / type",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(6.dp))
        val explainer = if (alwaysConfirm) {
            "${Glyph.AccentBar} paranoid mode — every destructive tool call pops a confirmation dialog. Tap 'always allow this' in the dialog to skip future prompts for the SAME action+target."
        } else {
            "${Glyph.AccentBar} direct mode — when you say 'text mom <message>' Mythara sends immediately, no popup. Tap / swipe / type fire silently too. Trust + speed; flip on if you want every action gated."
        }
        Text(
            text = explainer,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
