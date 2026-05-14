package com.mythara.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import com.mythara.audit.AuditRepository
import com.mythara.audit.DistinctDeviceRow
import com.mythara.memory.DeviceIdStore
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick cluster roster. Pulls from the local audit DB's distinct
 * device-id query so we don't depend on a network round-trip just
 * to fill the tile — the registry lookup happens when the user
 * actually navigates to the People / cluster screen.
 */
@HiltViewModel
class DevicesTileViewModel @Inject constructor(
    private val auditRepo: AuditRepository,
    private val deviceIdStore: DeviceIdStore,
) : ViewModel() {

    data class DeviceSummary(val id: String, val isSelf: Boolean, val lastSeenMs: Long, val entries: Int)

    private val _devices = MutableStateFlow<List<DeviceSummary>>(emptyList())
    val devices: StateFlow<List<DeviceSummary>> = _devices.asStateFlow()

    init {
        viewModelScope.launch {
            val myId = runCatching { deviceIdStore.id() }.getOrElse { "unknown" }
            val rows: List<DistinctDeviceRow> = runCatching {
                auditRepo.dao.listDistinctDevices()
            }.getOrDefault(emptyList())
            // Self always present + sorted: self first, then by recency.
            val seen = rows.associate { it.deviceId to it }
            val merged = (seen.keys + myId).toSet().map { id ->
                val row = seen[id]
                DeviceSummary(
                    id = id,
                    isSelf = id == myId,
                    lastSeenMs = row?.lastSeenMs ?: System.currentTimeMillis(),
                    entries = row?.entries ?: 0,
                )
            }.sortedWith(compareByDescending<DeviceSummary> { it.isSelf }.thenByDescending { it.lastSeenMs })
            _devices.value = merged
        }
    }
}

@Composable
fun DevicesTile(onExpand: () -> Unit) {
    val vm: DevicesTileViewModel = hiltViewModel()
    val devices by vm.devices.collectAsState()
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} devices",
        accent = MytharaColors.Charple,
        badge = "${devices.size}",
        onTap = onExpand,
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (d in devices.take(4)) {
                Row {
                    Text(
                        text = if (d.isSelf) Glyph.Dot else Glyph.CircleOutline,
                        color = if (d.isSelf) MytharaColors.Julep else MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        text = "  ${d.id.takeLast(8)}${if (d.isSelf) " · self" else ""}",
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (devices.size > 4) {
                Text(
                    text = "+ ${devices.size - 4} more",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
