package com.mythara.ui.amulet

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Modifier extension that fires [onLongPress] when the user holds a
 * single finger on the wrapped surface for [thresholdMs] milliseconds
 * without releasing or moving outside [moveTolerancePx].
 *
 * Designed to coexist with child gesture detectors (TextField cursor
 * placement, button presses, scrollback drag, etc.) — uses the
 * Final pointer-event pass so it only sees down events that no child
 * has consumed. A long-press on a TextField won't fire the amulet
 * (the TextField wins the gesture for cursor placement); a long-press
 * on bare chat scrollback / wallpaper / scaffold WILL.
 *
 * Movement check: any movement greater than [moveTolerancePx] from
 * the initial down position cancels the long-press detection so a
 * scroll / drag never accidentally summons the amulet.
 *
 * Optional bloom-anticipation callback:
 *   - [onPreBloom] fires at the HALFWAY point ([preBloomMs], default
 *     300 ms) while the finger is still down + still within tolerance.
 *     Used by [com.mythara.ui.amulet.PreAmuletRing] to start a
 *     particle ring under the finger that grows into the full rose
 *     when [onLongPress] commits at [thresholdMs].
 *   - [onPreBloomCancelled] fires if the user releases or moves out of
 *     tolerance AFTER `onPreBloom` already fired but BEFORE the full
 *     long-press commits. Lets the caller fade the ring out cleanly
 *     instead of leaving a stranded ring on screen.
 *   - Both callbacks default to no-op so existing callers behave
 *     identically.
 *
 * The fold + commit-haptic semantics are unchanged: this detector
 * still fires `onLongPress` exactly once per full long-press
 * gesture, on the same conditions as before.
 */
fun Modifier.detectGlobalLongPress(
    thresholdMs: Long = 600L,
    moveTolerancePx: Float = 24f,
    preBloomMs: Long = 300L,
    onPreBloom: (Offset) -> Unit = {},
    onPreBloomCancelled: () -> Unit = {},
    onLongPress: (Offset) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        // Final pass — only fire if no descendant consumed the down.
        // requireUnconsumed=true is the default behaviour for Final.
        val down = awaitFirstDown(
            requireUnconsumed = true,
            pass = PointerEventPass.Final,
        )
        val downPos = down.position
        var moved = false
        var preBloomFired = false

        // Loop awaiting events until either:
        //   - the user lifts the finger (release before threshold = no fire)
        //   - the user moves past tolerance (drag = no fire)
        //   - preBloomMs passes with finger still down + still inside
        //     tolerance → fire onPreBloom (continue waiting for the
        //     full threshold)
        //   - thresholdMs passes with finger still down within tolerance
        //     → fire onLongPress
        val fired = withTimeoutOrNull(thresholdMs) {
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // Released before threshold — not a long press.
                    return@withTimeoutOrNull false
                }
                val total = change.position - downPos
                if (kotlin.math.abs(total.x) > moveTolerancePx ||
                    kotlin.math.abs(total.y) > moveTolerancePx) {
                    moved = true
                    return@withTimeoutOrNull false
                }
                // Pre-bloom fires once, at the halfway mark, while
                // still inside tolerance. Uses absolute elapsed time
                // since the first down so it stays accurate across
                // event delivery jitter.
                if (!preBloomFired) {
                    val elapsed = change.uptimeMillis - down.uptimeMillis
                    if (elapsed >= preBloomMs) {
                        preBloomFired = true
                        onPreBloom(downPos)
                    }
                }
            }
            false
        }

        // If pre-bloom fired but we ended up cancelling (released
        // early or moved out), let the caller tear the ring down.
        if (preBloomFired && (fired == false || moved)) {
            onPreBloomCancelled()
        }

        // withTimeoutOrNull returns null on timeout — that's the
        // "still pressed, still inside tolerance" case = LONG PRESS.
        if (fired == null && !moved) {
            onLongPress(downPos)
        }
    }
}
