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
import com.mythara.agent.SemanticRecall
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tiny VM that exposes the recall on/off toggle. Used by RecallPanel
 * only — no point pulling this through SettingsViewModel since the
 * dependency would leak the recall implementation detail upward.
 */
@HiltViewModel
class RecallPanelViewModel @Inject constructor(
    private val recall: SemanticRecall,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = recall.enabledFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { recall.setEnabled(value) }
    }
}

/**
 * "Memory in chat" toggle in main Settings. When ON (default), every
 * chat turn prepends a short system message to MiniMax describing the
 * top relevant durable facts + the user's recent mood trend. When OFF,
 * chat is "pure" — only the messages the user typed/spoke leave the
 * device.
 *
 * This is the explicit user-control over the privacy boundary that
 * shifted when M8.3 part 1 introduced semantic recall. Observe stores
 * locally with the user's existing consent; this toggle gates whether
 * that local data rides along to the cloud LLM in chat context.
 */
@Composable
fun RecallPanel(vm: RecallPanelViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} memory in chat",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.setEnabled(!enabled) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (enabled) Glyph.CircleFilled else Glyph.CircleOutline,
                color = if (enabled) MytharaColors.Charple else MytharaColors.FgMute,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "include durable memory + mood trend in chat context",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} when on, every chat turn sends top-6 relevant Observe facts and your recent mood trend to MiniMax as system-prompt context. Mythara can reference things you said weeks ago and adapt her tone. When off, only the message you typed/spoke leaves the device — chat is 'pure' MiniMax with no local-memory augmentation.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
