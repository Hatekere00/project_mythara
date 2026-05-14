package com.mythara.ui.dashboard.tiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Dashboard summary of the cross-device task queue. Mirrors the
 * pending-count + top-3-titles surface so the user sees what's
 * coming up without leaving the chat.
 */
@HiltViewModel
class TasksTileViewModel @Inject constructor(
    private val repo: TaskRepository,
) : ViewModel() {

    data class Summary(
        val pending: Int,
        val done: Int,
        val topTitles: List<String>,
    )

    val summary: StateFlow<Summary> = combine(
        repo.dao.pendingCountFlow(),
        repo.dao.observeRecent(limit = 30),
    ) { pendingCount, rows ->
        val live = rows.filter { it.status in LIVE }
        val terminal = rows.filter { it.status !in LIVE }
        Summary(
            pending = pendingCount,
            done = terminal.size,
            topTitles = live.take(3).map { it.title.ifBlank { "(untitled)" } },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        Summary(0, 0, emptyList()),
    )

    companion object {
        private val LIVE = setOf(
            TaskStatus.PENDING.name,
            TaskStatus.CLAIMED.name,
            TaskStatus.RUNNING.name,
        )
    }
}

@Composable
fun TasksTile(onExpand: () -> Unit) {
    val vm: TasksTileViewModel = hiltViewModel()
    val summary by vm.summary.collectAsState()
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} tasks",
        accent = MytharaColors.Malibu,
        badge = "${summary.pending} live",
        onTap = onExpand,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (summary.topTitles.isEmpty()) {
                Text(
                    text = "${Glyph.CircleOutline} nothing queued",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                summary.topTitles.forEachIndexed { idx, title ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${idx + 1}.",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = title,
                            color = MytharaColors.Fg,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (idx < summary.topTitles.size - 1) Spacer(Modifier.height(3.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${summary.done} done",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

