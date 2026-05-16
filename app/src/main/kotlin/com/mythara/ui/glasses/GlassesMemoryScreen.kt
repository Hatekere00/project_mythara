package com.mythara.ui.glasses

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.analytics.interactions.ContactInteractionRow
import com.mythara.lifeline.LifelineEntity
import com.mythara.lifeline.LifelineRepository
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Phase 11 — in-app screen surfacing what Mythara has captured from
 * the Meta Display Glasses:
 *
 *   1. **Recent meetings** — physical-meet interactions aggregated
 *      into short visits (same contact within 30 min + 50 m collapse
 *      into one row). Renders contact name + count + place + when.
 *
 *   2. **Glasses photo grid** — every photo tagged
 *      `source_device_type = "glasses"`, newest first. Tap to open
 *      in chat scrollback at that photo's position (uses the existing
 *      LifelineCard's tap-to-open intent).
 *
 *   3. (Reserved for future) Detected-but-unlinked face clusters — a
 *      simple list with "assign to existing contact" or "create new"
 *      actions. Not in v3.0; needs the unknown-face clustering pass.
 */
@HiltViewModel
class GlassesMemoryViewModel @Inject constructor(
    private val lifeline: LifelineRepository,
    private val interactionRepo: ContactInteractionRepository,
    private val contactRepo: ContactProfileRepository,
) : ViewModel() {

    data class Ui(
        val loading: Boolean = true,
        val meetings: List<Meeting> = emptyList(),
        val photos: List<LifelineEntity> = emptyList(),
    )

    data class Meeting(
        val nameKey: String,
        val displayName: String,
        val firstTsMs: Long,
        val lastTsMs: Long,
        val captureCount: Int,
        val placeLabel: String?,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = withContext(Dispatchers.IO) { build() }
        }
    }

    private suspend fun build(): Ui {
        val meets = runCatching {
            interactionRepo.dao.listPhysicalMeets(limit = 200)
        }.getOrDefault(emptyList())
        val photos = runCatching {
            lifeline.dao.listBySourceDeviceType("glasses", limit = 200)
        }.getOrDefault(emptyList())

        // Group meetings: same nameKey, within MEET_WINDOW_MS, and
        // within MEET_GEO_DEG of each other.
        val groups = mutableListOf<MutableList<ContactInteractionRow>>()
        // listPhysicalMeets returns newest-first; reverse so we walk
        // forward in time + collapse adjacent rows into one Meeting.
        for (row in meets.sortedBy { it.tsMs }) {
            val existing = groups.lastOrNull { g ->
                g.last().nameKey == row.nameKey &&
                    (row.tsMs - g.last().tsMs) <= MEET_WINDOW_MS &&
                    sameishLocation(g.last(), row)
            }
            if (existing != null) existing += row else groups += mutableListOf(row)
        }
        val meetings = groups.map { g ->
            val nameKey = g.first().nameKey
            val display = runCatching { contactRepo.dao.byKey(nameKey)?.displayName }
                .getOrNull() ?: nameKey
            Meeting(
                nameKey = nameKey,
                displayName = display,
                firstTsMs = g.minOf { it.tsMs },
                lastTsMs = g.maxOf { it.tsMs },
                captureCount = g.size,
                placeLabel = g.firstOrNull { !it.placeLabel.isNullOrBlank() }?.placeLabel,
            )
        }.sortedByDescending { it.lastTsMs }
        return Ui(loading = false, meetings = meetings, photos = photos)
    }

    private fun sameishLocation(a: ContactInteractionRow, b: ContactInteractionRow): Boolean {
        if (a.lat == null || a.lng == null || b.lat == null || b.lng == null) return true
        return abs(a.lat - b.lat) <= MEET_GEO_DEG && abs(a.lng - b.lng) <= MEET_GEO_DEG
    }

    companion object {
        // 30 minutes — beyond this, even the same person at the same
        // place reads as a second visit ("you saw Sarah at lunch, then
        // again at dinner" not "you saw Sarah for 5 hours").
        private const val MEET_WINDOW_MS = 30L * 60_000

        // ~50 m at typical mid-latitudes — 1 degree ≈ 111 km, so
        // 0.0005° ≈ 55 m. Locations within this radius read as "same
        // place" for the meeting-aggregation purpose.
        private const val MEET_GEO_DEG = 0.0005
    }
}

@Composable
fun GlassesMemoryScreen(
    onBack: () -> Unit,
    onOpenContact: (nameKey: String) -> Unit = {},
    vm: GlassesMemoryViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "${Glyph.DiamondFilled} glasses",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (ui.loading) {
            Text("loading…", color = MytharaColors.FgDim)
            return@Column
        }

        // ── recent meetings ──
        SectionHeader("recent meetings (via glasses)")
        if (ui.meetings.isEmpty()) {
            Text(
                "${Glyph.CircleOutline} no glasses-detected meetings yet — face-tag a glasses photo " +
                    "(or wait for the worker to recognise someone you've met) and they appear here.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            ui.meetings.forEach { m ->
                MeetingRow(m, onClick = { onOpenContact(m.nameKey) })
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(20.dp))

        // ── glasses photo grid ──
        SectionHeader("glasses photo memory (${ui.photos.size})")
        if (ui.photos.isEmpty()) {
            Text(
                "${Glyph.CircleOutline} no glasses photos yet — capture from the band to start " +
                    "building this archive.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(420.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(ui.photos, key = { it.id }) { row ->
                    PhotoTile(row)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = "${Glyph.DiamondOutline} $label",
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelLarge,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MeetingRow(m: GlassesMemoryViewModel.Meeting, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondFilled} ${m.displayName}",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatTs(m.lastTsMs),
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val duration = m.lastTsMs - m.firstTsMs
        val durLabel = when {
            duration < 60_000 -> "just now"
            duration < 3_600_000 -> "${duration / 60_000} min"
            else -> "${duration / 3_600_000}h ${(duration % 3_600_000) / 60_000}m"
        }
        Text(
            text = "${Glyph.AccentBar} ${m.captureCount} capture${if (m.captureCount == 1) "" else "s"} · " +
                "$durLabel${m.placeLabel?.let { " · $it" } ?: ""}",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PhotoTile(row: LifelineEntity) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val thumbnail = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = row.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(row.uri)
                val stream = when (uri.scheme) {
                    "file" -> java.io.FileInputStream(uri.path!!)
                    else -> ctx.contentResolver.openInputStream(uri)
                } ?: return@runCatching null
                stream.use {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = 4 // ~quarter-resolution for grid tiles
                    }
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MytharaColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumbnail.value
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = row.captionText,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(
                "${Glyph.DiamondOutline}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val TS_FORMAT = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
private fun formatTs(ms: Long): String = TS_FORMAT.format(Date(ms))
