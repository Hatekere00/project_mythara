package com.mythara.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the list of LAUNCHER-ELIGIBLE apps installed on the
 * device — i.e. anything that exposes a `MAIN/LAUNCHER`
 * intent-filter. Used by:
 *
 *   1. The popup amulet's "apps" pages (paginated wheel of installed
 *      apps reached by cycling past the menu + PTT pages).
 *   2. The Spotlight drawer (existing) which already does its own
 *      lookup but should be migrated to share this provider.
 *
 * Sort order: alphabetical by display label, case-insensitive.
 * Excludes Mythara itself (the user can already see Mythara — it's
 * the surface running the amulet).
 *
 * Cache: the list is computed once per call, no in-memory cache —
 * Android's PackageManager already caches efficiently and the call
 * is fast (< 50ms typical for ~150 apps). If a hot-path use case
 * appears we can add a debounced refresh.
 */
@Singleton
class InstalledAppsProvider @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** A single launcher app entry. Carries enough to RENDER a
     *  chip and to LAUNCH the app on tap. */
    data class App(
        val packageName: String,
        val label: String,
        val launchComponentClass: String,
        /** Initial letter of the label, for the chip glyph
         *  fallback when [iconBitmap] isn't loaded yet. */
        val initial: String,
    )

    /**
     * Pull every launcher-eligible app, sorted A→Z by label.
     * Excludes our own package so the amulet doesn't list
     * Mythara as one of its destinations (the user is already
     * IN Mythara when they see the wheel).
     */
    suspend fun list(): List<App> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val self = ctx.packageName
        resolved.asSequence()
            .filter { it.activityInfo?.packageName != null }
            .filter { it.activityInfo.packageName != self }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = runCatching { ri.loadLabel(pm)?.toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: pkg
                App(
                    packageName = pkg,
                    label = label,
                    launchComponentClass = ri.activityInfo.name,
                    initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Decode the launcher icon for a single app to a bitmap.
     * Suspends because PackageManager.getApplicationIcon can hit
     * disk; called on demand by the amulet's app-page composables.
     *
     * Adaptive icons (Android 8+) are flattened to a square bitmap
     * because the amulet chip is a perfect circle and we want the
     * whole icon to fit inside the clip mask.
     */
    suspend fun iconBitmap(packageName: String, sizePx: Int = 96): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val drawable: Drawable = runCatching { pm.getApplicationIcon(packageName) }
                .getOrNull() ?: return@withContext null
            drawableToImageBitmap(drawable, sizePx)
        }

    private fun drawableToImageBitmap(drawable: Drawable, sizePx: Int): ImageBitmap? {
        val w = sizePx.coerceAtLeast(1)
        val h = sizePx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        when (drawable) {
            is AdaptiveIconDrawable -> {
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
            }
            else -> {
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
            }
        }
        return runCatching { bmp.asImageBitmap() }.getOrNull()
    }

    /**
     * Build a launch intent for an app. Returns null when the
     * package no longer has a launcher entry (uninstall race —
     * caller should refresh [list] in that case).
     */
    fun launchIntent(packageName: String): Intent? =
        runCatching { ctx.packageManager.getLaunchIntentForPackage(packageName) }
            .getOrNull()
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
}
