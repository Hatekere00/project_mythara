package com.mythara.ui.util

import android.content.Context

/**
 * True when the running device is a TABLET (as distinct from a phone
 * or unfolded foldable). Used by [com.mythara.ui.MytharaRoot] to pick
 * between the single-pane chat layout, the foldable two-pane layout,
 * and the tablet command-center dashboard.
 *
 * The signal is `smallestScreenWidthDp >= 720`:
 *
 *   - Pixel 8 / 10 Pro phone      ≈ 411 dp  (Compact path)
 *   - Pixel Fold unfolded         ≈ 673 dp  (Two-pane fold path)
 *   - Pixel Tablet                ≈ 800 dp  (Tablet dashboard path)
 *   - Galaxy Tab S9               ≈ 853 dp  (Tablet dashboard path)
 *
 * smallestScreenWidthDp is the width in dp of the device's smallest
 * edge, so rotation doesn't flip the classification — the same
 * physical device always reports the same value regardless of
 * orientation. A 720 dp cutoff cleanly puts the Pixel Tablet and
 * larger on the dashboard side while leaving unfolded foldables on
 * the simpler two-pane layout.
 *
 * Preferred over `PackageManager.FEATURE_FOLDABLE` (which fires only
 * on foldables, not the Pixel Tablet) and over the Jetpack Window
 * library (which adds a dependency just for this one check). One
 * Configuration read, no SDK surface.
 */
fun Context.isTabletDisplay(): Boolean =
    resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SW_DP

private const val TABLET_MIN_SW_DP = 720
