package com.mythara.ui.fold

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * Three-way fold posture for the host activity.
 *
 *  - [Folded]      : the device is currently folded shut, OR isn't a
 *                    foldable at all (no [FoldingFeature] visible in
 *                    the window layout). The amulet renders, but no
 *                    bloom transition has happened yet.
 *  - [HalfOpened]  : tabletop / book half-open posture
 *                    (FoldingFeature.state == HALF_OPENED). Triggers
 *                    the rose-bloom on the upgrade from [Folded].
 *  - [Flat]        : unfolded flat (FoldingFeature.state == FLAT).
 *                    Same bloom trigger as [HalfOpened]; bloom
 *                    settles into the wide two-pane layout.
 */
enum class FoldPosture { Folded, HalfOpened, Flat }

/**
 * Composable that subscribes to the activity's
 * [WindowInfoTracker.windowLayoutInfo] flow and exposes the current
 * fold posture as an observable Compose [State].
 *
 * The flow is collected only while the host activity is at least
 * STARTED — `repeatOnLifecycle` cancels the subscription when the
 * activity moves to STOPPED so we don't pin background callbacks on
 * the screen-off path.
 *
 * Falls back to [FoldPosture.Folded] when the underlying tracker
 * hasn't emitted yet (e.g. the very first frame after process
 * start). Once the first emission arrives the state stays in sync
 * with the system's reports.
 */
@Composable
fun rememberFoldPosture(): State<FoldPosture> {
    val context = LocalContext.current
    val activity = context as? Activity
    val state = remember { mutableStateOf(FoldPosture.Folded) }
    val lifecycleOwner = LocalLifecycleOwner.current

    if (activity != null) {
        LaunchedEffect(activity) {
            val tracker = WindowInfoTracker.getOrCreate(activity)
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                tracker.windowLayoutInfo(activity).collect { info ->
                    state.value = info.toPosture()
                }
            }
        }
    }
    return state
}

/**
 * Map a single [androidx.window.layout.WindowLayoutInfo] emission
 * to a [FoldPosture]. The library reports the *currently visible*
 * folding features — when there are none, the device is either a
 * non-foldable or a foldable currently folded shut.
 *
 * If multiple features are reported (extremely unusual on consumer
 * hardware), the first one wins — a fold that's in two different
 * states simultaneously isn't physically meaningful and reporting
 * either is fine for our trigger logic.
 */
private fun androidx.window.layout.WindowLayoutInfo.toPosture(): FoldPosture {
    val fold = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        ?: return FoldPosture.Folded
    return when (fold.state) {
        FoldingFeature.State.HALF_OPENED -> FoldPosture.HalfOpened
        FoldingFeature.State.FLAT -> FoldPosture.Flat
        else -> FoldPosture.Folded
    }
}
