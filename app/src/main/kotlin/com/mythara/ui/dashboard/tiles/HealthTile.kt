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
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@HiltViewModel
class HealthTileViewModel @Inject constructor(
    private val vault: LearningVault,
) : ViewModel() {

    data class Summary(
        val steps: Long?,
        val sleepMin: Long?,
        val avgHr: Double?,
        val kcal: Double?,
        val tsMs: Long?,
    )

    private val _summary = MutableStateFlow(Summary(null, null, null, null, null))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching {
                    vault.listByTier(Tier.Semantic, limit = 200)
                        .filter { vault.decodeFacets(it).any { f -> f == "topic:health" } }
                        .maxByOrNull { it.tsMillis }
                }.getOrNull()
            } ?: return@launch
            val obj: JsonObject = runCatching { json.parseToJsonElement(latest.content).jsonObject }
                .getOrNull() ?: return@launch
            _summary.value = Summary(
                steps = obj["steps_24h"]?.jsonPrimitive?.content?.toLongOrNull(),
                sleepMin = obj["sleep_24h_minutes"]?.jsonPrimitive?.content?.toLongOrNull(),
                avgHr = obj["hr_24h_avg"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                kcal = obj["kcal_24h"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                tsMs = obj["ts_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
    }
}

@Composable
fun HealthTile(onExpand: () -> Unit) {
    val vm: HealthTileViewModel = hiltViewModel()
    val s by vm.summary.collectAsState()
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} health",
        accent = MytharaColors.Julep,
        badge = if (s.tsMs == null) null else "24h",
        onTap = onExpand,
    ) {
        if (s.steps == null && s.sleepMin == null && s.avgHr == null) {
            Text(
                text = "${Glyph.CircleOutline} no health data yet (grant Health Connect)",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            return@DashboardTileFrame
        }
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            metricRow("steps", s.steps?.toString() ?: "—")
            metricRow("sleep", s.sleepMin?.let { "${it / 60}h ${it % 60}m" } ?: "—")
            metricRow("avg hr", s.avgHr?.let { "${it.toInt()} bpm" } ?: "—")
            metricRow("kcal", s.kcal?.let { it.toInt().toString() } ?: "—")
        }
    }
}

@Composable
private fun metricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
