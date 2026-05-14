package com.mythara.ui.dashboard.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.lifeline.LifelineEntity
import com.mythara.lifeline.LifelineRepository
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PhotosTileViewModel @Inject constructor(
    repo: LifelineRepository,
) : ViewModel() {
    val recent: StateFlow<List<LifelineEntity>> = repo.dao.observeRecent(limit = 500)
        .map { rows -> rows.sortedByDescending { it.takenMs }.take(9) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())
}

@Composable
fun PhotosTile(onExpand: () -> Unit) {
    val vm: PhotosTileViewModel = hiltViewModel()
    val photos by vm.recent.collectAsState()
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} memory",
        accent = MytharaColors.Bok,
        badge = if (photos.isEmpty()) null else "${photos.size}",
        onTap = onExpand,
    ) {
        if (photos.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no photos yet",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(items = photos, key = { "${it.deviceId}:${it.mediaStoreId}" }) { p ->
                    PhotoThumb(p)
                }
            }
        }
    }
}

@Composable
private fun PhotoThumb(entry: LifelineEntity) {
    val ctx = LocalContext.current
    val isLocal = !entry.isRemote && entry.uri.isNotBlank()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MytharaColors.SurfaceMid),
        contentAlignment = Alignment.Center,
    ) {
        if (isLocal) {
            var bmp by remember(entry.uri) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(entry.uri) {
                bmp = withContext(Dispatchers.IO) { decode(ctx, Uri.parse(entry.uri)) }?.asImageBitmap()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            Text(
                text = "📷",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun decode(ctx: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }.onFailure { return null }
    val srcW = bounds.outWidth.takeIf { it > 0 } ?: return null
    var sample = 1
    while (srcW / sample > 192) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()
}

