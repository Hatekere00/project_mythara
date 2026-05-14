package com.mythara.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Most recent HR-spike-with-attribution rows the HrCorrelationWorker
 * has written. Renders a "[contact] → +Δbpm · time" mini-list so
 * the user sees which pings are stressing them at a glance.
 */
@HiltViewModel
class HrCorrelationTileViewModel @Inject constructor(
    private val vault: LearningVault,
) : ViewModel() {

    data class SpikeRow(val tsMs: Long, val deltaBpm: Long, val contact: String)

    private val _rows = MutableStateFlow<List<SpikeRow>>(emptyList())
    val rows: StateFlow<List<SpikeRow>> = _rows.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    vault.listByTier(Tier.Semantic, limit = 300)
                        .filter { vault.decodeFacets(it).any { f -> f == "topic:hr-correlation" } }
                        .sortedByDescending { it.tsMillis }
                        .take(5)
                        .mapNotNull { entity ->
                            runCatching {
                                val obj: JsonObject = json.parseToJsonElement(entity.content).jsonObject
                                val ts = obj["ts_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: entity.tsMillis
                                val spike = obj["spike_bpm"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                val baseline = obj["baseline_bpm"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                                val delta = (spike - baseline.toLong()).coerceAtLeast(0L)
                                val contact = obj["candidates"]?.jsonArray?.firstOrNull()
                                    ?.jsonObject?.get("contact")?.jsonPrimitive?.content
                                    ?: "(unknown)"
                                SpikeRow(ts, delta, contact)
                            }.getOrNull()
                        }
                }.getOrDefault(emptyList())
            }
            _rows.value = parsed
        }
    }
}

@Composable
fun HrCorrelationTile(onExpand: () -> Unit) {
    val vm: HrCorrelationTileViewModel = hiltViewModel()
    val rows by vm.rows.collectAsState()
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} HR spikes",
        accent = MytharaColors.Sriracha,
        badge = if (rows.isEmpty()) null else "${rows.size}",
        onTap = onExpand,
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (rows.isEmpty()) {
                Text(
                    text = "${Glyph.CircleOutline} no spikes recorded",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                for (r in rows.take(4)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "+${r.deltaBpm}bpm",
                            color = MytharaColors.Sriracha,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "  ${r.contact}",
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatTime(r.tsMs),
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ms))
}

