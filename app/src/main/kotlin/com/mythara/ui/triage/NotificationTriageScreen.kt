package com.mythara.ui.triage

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.services.NotificationActionStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Surfaces what the auto-triage pipeline has been silently dismissing
 * on the user's behalf, and lets them mark a package as
 * "important — never auto-dismiss" so future notifications from it
 * always reach the system shade.
 *
 * Two sections:
 *   1. **Recent auto-dismissals** — the chronological log written by
 *      [NotificationActionStore.bumpAutoDismissed] every time the
 *      decision engine cancels a notification. Most recent first.
 *      Each entry has a "mark important" button that adds the source
 *      package to the exempt set.
 *   2. **Marked important** — the packages currently in the exempt
 *      set. Tapping a chip removes it from the set so the learned-
 *      pattern decision rules apply again.
 */
@HiltViewModel
class NotificationTriageViewModel @Inject constructor(
    private val store: NotificationActionStore,
) : ViewModel() {
    data class Ui(
        val dismissed: List<NotificationActionStore.DismissedEntry> = emptyList(),
        val exempt: Set<String> = emptySet(),
        val masterEnabled: Boolean = false,
    )

    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    val ui: StateFlow<Ui> =
        kotlinx.coroutines.flow.combine(
            store.exemptFlow(),
            store.enabledFlow(),
            _refreshTick,
        ) { exempt, master, _ ->
            Ui(
                dismissed = store.recentDismissals(),
                exempt = exempt,
                masterEnabled = master,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Ui())

    fun markImportant(pkg: String) {
        viewModelScope.launch {
            store.markImportant(pkg)
            _refreshTick.value = _refreshTick.value + 1
        }
    }

    fun unmarkImportant(pkg: String) {
        viewModelScope.launch {
            store.unmarkImportant(pkg)
            _refreshTick.value = _refreshTick.value + 1
        }
    }
}

@Composable
fun NotificationTriageScreen(
    onBack: () -> Unit,
    vm: NotificationTriageViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "TRIAGE",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = MytharaColors.Fg, letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Glyph.AccentBar} notifications Mythara silently dismissed for you " +
                    "based on what you've ignored before. Mark anything important so it stays " +
                    "in your shade in future. The master auto-triage toggle lives in Settings.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
            )
            Spacer(Modifier.height(8.dp))
            val masterStatus = if (ui.masterEnabled) "ON" else "OFF"
            val masterColor = if (ui.masterEnabled) MytharaColors.Bok else MytharaColors.FgMute
            Text(
                text = "auto-triage: $masterStatus",
                color = masterColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Currently marked-important packages — show as a stacked
            // chip row so the user can quickly see what's exempt and
            // un-exempt one with a tap.
            if (ui.exempt.isNotEmpty()) {
                item {
                    SectionHeader("marked important — never auto-dismiss")
                }
                items(ui.exempt.sorted()) { pkg ->
                    ImportantChip(
                        pkg = pkg,
                        appLabel = context.appLabel(pkg),
                        onUnmark = { vm.unmarkImportant(pkg) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            item {
                SectionHeader(
                    if (ui.dismissed.isEmpty()) "no recent auto-dismissals"
                    else "recent auto-dismissals (${ui.dismissed.size})",
                )
            }

            if (ui.dismissed.isEmpty()) {
                item {
                    Text(
                        text = "${Glyph.AccentBar} when Mythara dismisses a notification on " +
                            "your behalf, it'll show up here. Until you've used the app a " +
                            "while there's nothing for the engine to learn from.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                items(ui.dismissed, key = { "${it.pkg}-${it.tsMillis}" }) { entry ->
                    DismissedRow(
                        entry = entry,
                        appLabel = context.appLabel(entry.pkg),
                        isExempt = entry.pkg in ui.exempt,
                        onMarkImportant = { vm.markImportant(entry.pkg) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun DismissedRow(
    entry: NotificationActionStore.DismissedEntry,
    appLabel: String,
    isExempt: Boolean,
    onMarkImportant: () -> Unit,
) {
    val border = if (isExempt) MytharaColors.Bok else MytharaColors.SurfaceHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(if (isExempt) 1.5.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = appLabel,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = entry.pkg,
                    color = MytharaColors.FgDim,
                    fontSize = 10.sp,
                )
            }
            Text(
                text = formatTime(entry.tsMillis),
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!entry.title.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.title,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!entry.text.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.text,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (isExempt) {
            Text(
                text = "${Glyph.DiamondFilled} marked important",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            TextButton(onClick = onMarkImportant) {
                Text(
                    text = "${Glyph.DiamondOutline} mark important",
                    color = MytharaColors.Charple,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ImportantChip(
    pkg: String,
    appLabel: String,
    onUnmark: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.5.dp, MytharaColors.Bok, RoundedCornerShape(10.dp))
            .clickable(onClick = onUnmark)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "${Glyph.DiamondFilled} $appLabel",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = pkg, color = MytharaColors.FgDim, fontSize = 10.sp)
        }
        Text(
            text = "tap to unmark",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────

/** Lookup the user-friendly app label for a package name. Falls back
 *  to the package name itself when the app isn't installed locally
 *  (rare — would require the dismissal log to outlive an
 *  uninstall). */
private fun Context.appLabel(pkg: String): String {
    return runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)
}

/** Format a wall-clock time in the user's local timezone. */
private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = (now - ts) < 24 * 60 * 60 * 1000L &&
        DateFormat.getDateInstance().format(Date(ts)) ==
        DateFormat.getDateInstance().format(Date(now))
    return if (sameDay) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
    }
}
