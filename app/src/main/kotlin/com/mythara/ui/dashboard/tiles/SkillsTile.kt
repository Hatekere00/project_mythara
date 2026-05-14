package com.mythara.ui.dashboard.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.skills.Skill
import com.mythara.skills.SkillStore
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
 * Full-width strip below the tile grid. Horizontal-scrolling list of
 * the 8 most-recently-used skills as small chips.
 */
@HiltViewModel
class SkillsTileViewModel @Inject constructor(
    private val store: SkillStore,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _skills.value = runCatching { store.list() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.lastRunMs ?: it.createdMs }
                .take(8)
        }
    }
}

@Composable
fun SkillsTile(onExpand: () -> Unit) {
    val vm: SkillsTileViewModel = hiltViewModel()
    val skills by vm.skills.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} skills",
        accent = MytharaColors.Mustard,
        badge = "${skills.size}",
        onTap = onExpand,
    ) {
        if (skills.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no skills yet",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            return@DashboardTileFrame
        }
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items = skills, key = { it.name }) { skill ->
                SkillChip(skill)
            }
        }
    }
}

@Composable
private fun SkillChip(skill: Skill) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = "${Glyph.Dot} ${skill.name}",
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "  v${skill.version}",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
