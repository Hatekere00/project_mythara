package com.mythara.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One installed app, surfaced in the picker. Icon is lazily decoded
 * from the package's launcher drawable on a background dispatcher so
 * the picker doesn't stutter while scrolling.
 */
data class PickableApp(
    val pkg: String,
    val label: String,
    val iconPainter: Painter,
)

/**
 * Modal bottom sheet that lets the user pick an installed app by name
 * rather than typing its package id. Used for both the blocked and
 * critical lists in [RestrictedAppsPanel].
 *
 * Why ModalBottomSheet over a full Dialog: sheets feel native on
 * Android, dismiss with a downswipe, and keep the Settings screen
 * visible behind the scrim so the user remembers what they were
 * configuring. Picking is a one-tap action so there's no need for
 * primary/secondary buttons.
 *
 * Apps are loaded once when the sheet opens; rotating/reopening
 * reloads. The list excludes system apps that have no launcher
 * activity (they wouldn't be reachable by open_app anyway) but
 * keeps system apps that DO launch (Maps, Calendar, Drive). The
 * `excludePackages` set is used to dim already-added entries so
 * the user can't add the same app twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    title: String,
    excludePackages: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apps by remember { mutableStateOf<List<PickableApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            loadInstalledApps(ctx.packageManager)
        }
        apps = loaded
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MytharaColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "${Glyph.DiamondOutline} $title",
                style = MaterialTheme.typography.titleSmall.copy(color = MytharaColors.Fg),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("search…", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            when {
                loading -> {
                    Text(
                        text = "${Glyph.Ellipsis} loading installed apps…",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                filtered.isEmpty() -> {
                    Text(
                        text = "${Glyph.CircleOutline} no apps match.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            val already = app.pkg in excludePackages
                            AppRow(
                                app = app,
                                disabled = already,
                                trailing = if (already) "added" else null,
                                onClick = {
                                    if (!already) {
                                        onPick(app.pkg)
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: PickableApp,
    disabled: Boolean,
    trailing: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .clickable(enabled = !disabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(32.dp)) {
            androidx.compose.foundation.Image(
                painter = app.iconPainter,
                contentDescription = app.label,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = if (disabled) MytharaColors.FgDim else MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = app.pkg,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Enumerate installed apps that have a launcher activity. Excludes
 * packages with no launch intent (services, sync adapters, etc.) since
 * those can't be opened by [com.mythara.agent.tools.OpenAppTool]
 * anyway, so listing them just clutters the picker.
 *
 * Icons are drawn from the package's `getApplicationIcon` and
 * rasterised to a 96x96 bitmap immediately. Adaptive icons go through
 * the default Drawable→Canvas path so they render correctly without
 * Coil or any dependency.
 */
private fun loadInstalledApps(pm: PackageManager): List<PickableApp> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(
        android.content.Intent.CATEGORY_LAUNCHER,
    )
    val infos = pm.queryIntentActivities(intent, 0)
    val seen = mutableSetOf<String>()
    val out = mutableListOf<PickableApp>()
    for (info in infos) {
        val pkg = info.activityInfo?.applicationInfo?.packageName ?: continue
        if (!seen.add(pkg)) continue
        val appInfo: ApplicationInfo = info.activityInfo.applicationInfo
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
        val painter: Painter = runCatching {
            val d = pm.getApplicationIcon(appInfo)
            val bmp = d.toIcon()
            BitmapPainter(bmp.asImageBitmap())
        }.getOrElse { ColorPainter(Color(0xFF6B50FF)) }
        out.add(PickableApp(pkg = pkg, label = label, iconPainter = painter))
    }
    out.sortBy { it.label.lowercase() }
    return out
}

private fun android.graphics.drawable.Drawable.toIcon(): Bitmap {
    val w = if (intrinsicWidth > 0) intrinsicWidth else 96
    val h = if (intrinsicHeight > 0) intrinsicHeight else 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}
