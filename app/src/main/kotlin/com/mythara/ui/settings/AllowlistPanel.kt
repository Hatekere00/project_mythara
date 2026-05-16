package com.mythara.ui.settings

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
import com.mythara.data.AllowlistStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings panel listing every "always allow this" decision the user
 * has previously checked. Tapping × on a row revokes the auto-allow —
 * the next call with that key prompts again.
 *
 * Format displayed: prefix → category, suffix → param (e.g.
 * "send_sms_direct → +1415…", "place_call_direct → +1212…"). Keys
 * without a colon are tool-wide grants (rare; only if a tool ever
 * skips per-arg keys).
 */
@HiltViewModel
class AllowlistPanelViewModel @Inject constructor(
    private val store: AllowlistStore,
) : ViewModel() {
    val entries: StateFlow<Set<String>> = store.allowedFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    fun revoke(key: String) {
        viewModelScope.launch { store.revoke(key) }
    }

    fun clearAll() {
        viewModelScope.launch { store.clear() }
    }
}

@Composable
fun AllowlistPanel(vm: AllowlistPanelViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsState()

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
                text = "${Glyph.DiamondOutline} always-allow list",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { vm.clearAll() }) {
                    Text("${Glyph.Cross} clear all", color = MytharaColors.Sriracha)
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        if (entries.isEmpty()) {
            Text(
                text = "${Glyph.AccentBar} nothing here. when Mythara asks 'send SMS to mom?' you can tick 'always allow this' to skip the prompt for future identical calls. listed here, revocable per row.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        Text(
            text = "${Glyph.AccentBar} these destructive tool calls fire without a confirmation prompt. tap × to revoke any.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        // Use the sorted form so user sees a stable order across recomposes.
        entries.sorted().forEach { key ->
            AllowlistRow(key = key, onRevoke = { vm.revoke(key) })
        }
    }
}

@Composable
private fun AllowlistRow(key: String, onRevoke: () -> Unit) {
    val (tool, target) = splitKey(key)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRevoke() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (target.isNotBlank()) {
                Text(
                    text = target,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            text = "${Glyph.Cross} revoke",
            color = MytharaColors.Sriracha,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun splitKey(key: String): Pair<String, String> {
    val idx = key.indexOf(':')
    return if (idx > 0) key.substring(0, idx) to key.substring(idx + 1)
    else key to ""
}
