package com.mythara.ui.system

import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/**
 * Resolves the actual ON-SCREEN bounding rect of the device's
 * display cutout (camera hole / pinhole), in dp, relative to the
 * top-left of the window.
 *
 * Why this instead of the [androidx.compose.foundation.layout.WindowInsets.displayCutout]
 * inset:
 *   - The Compose `WindowInsets.displayCutout` API returns only an
 *     edge inset (how much padding is needed at top/bottom/sides
 *     to clear the cutout). It can't tell you WHERE on that edge
 *     the cutout actually sits — a centred Pixel pinhole and a
 *     left-corner Galaxy pinhole both report the same top inset.
 *   - For the Mythara Dynamic Island, we want the pill to BEND
 *     AROUND the cutout (iPhone-style bouncing dock that wraps the
 *     hole) rather than just sit below it. That requires the real
 *     centre + width.
 *
 * On Android 9+ ([android.view.DisplayCutout]) we read
 * `getBoundingRects()`, take the FIRST rect (devices with multiple
 * cutouts are vanishingly rare and the centred pinhole is what we
 * care about for Pixel + most Samsung), and convert px → dp.
 *
 * On older devices the API doesn't exist; we return null and the
 * caller falls back to a centre-of-strip layout.
 */
data class CutoutRect(
    /** Left edge of the cutout, in dp from the window left. */
    val leftDp: Float,
    /** Top edge of the cutout, in dp from the window top. */
    val topDp: Float,
    val widthDp: Float,
    val heightDp: Float,
) {
    val centerXDp: Float get() = leftDp + widthDp / 2f
    val centerYDp: Float get() = topDp + heightDp / 2f
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
}

/**
 * Live cutout bounds. Re-computed on every WindowInsets dispatch
 * so a fold/unfold (which changes the active display) refreshes
 * without a recomposition trigger.
 */
@Composable
fun rememberCutoutRect(): CutoutRect? {
    val view = LocalView.current
    val density = LocalDensity.current
    var rect by remember { mutableStateOf(readCutoutRect(view, density.density)) }
    DisposableEffect(view) {
        val listener = View.OnApplyWindowInsetsListener { v, insets ->
            rect = readCutoutRectFromInsets(insets, density.density)
            insets
        }
        view.setOnApplyWindowInsetsListener(listener)
        // Force an initial pass so we don't wait for the next inset
        // dispatch.
        view.requestApplyInsets()
        onDispose { view.setOnApplyWindowInsetsListener(null) }
    }
    return rect
}

private fun readCutoutRect(view: View, density: Float): CutoutRect? {
    val insets = view.rootWindowInsets ?: return null
    return readCutoutRectFromInsets(insets, density)
}

private fun readCutoutRectFromInsets(insets: WindowInsets, density: Float): CutoutRect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    val cutout = insets.displayCutout ?: return null
    val rects = cutout.boundingRects
    if (rects.isEmpty()) return null
    // Prefer the topmost cutout (the camera hole on every centred-
    // pinhole Pixel + Galaxy ships top-edge). If there's a notch
    // shape with multiple rects, take the union — keeps the dock
    // wrap consistent.
    val top = rects.minByOrNull { it.top } ?: return null
    return CutoutRect(
        leftDp = top.left / density,
        topDp = top.top / density,
        widthDp = (top.right - top.left) / density,
        heightDp = (top.bottom - top.top) / density,
    )
}
