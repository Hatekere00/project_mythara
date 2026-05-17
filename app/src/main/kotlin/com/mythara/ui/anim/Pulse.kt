package com.mythara.ui.anim

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale

/**
 * Subtle breathing pulse on a composable, used by the new chrome to
 * signal "alive" / "in flight" without screaming for attention.
 *
 * Two flavours animate together:
 *   • alpha drifts between [minAlpha] and 1.0
 *   • scale drifts between 1.0 and [maxScale]
 *
 * Frequency is in Hz — the default 0.8 Hz matches the watch face's
 * heart-rate ECG pulse + the wear app's "active PTT" rhythm so any
 * pulsing surface across the Mythara ecosystem reads as one
 * heartbeat.
 *
 * When [active] is false the modifier is a no-op (no animation
 * driver runs, no recompositions are scheduled).
 */
fun Modifier.pulse(
    active: Boolean,
    hz: Float = 0.8f,
    minAlpha: Float = 0.6f,
    maxScale: Float = 1.04f,
): Modifier = composed {
    if (!active) return@composed this
    val periodMs = (1000f / hz.coerceAtLeast(0.05f)).toInt()
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-phase",
    )
    val a = minAlpha + (1f - minAlpha) * phase
    val s = 1f + (maxScale - 1f) * phase
    this.alpha(a).scale(s)
}

/**
 * Variant that only pulses ALPHA — useful for text/glyphs where a
 * scale wobble would look glitchy. Same 0.8 Hz default as [pulse].
 */
@Composable
fun rememberAlphaPulse(active: Boolean, hz: Float = 0.8f, minAlpha: Float = 0.55f): Float {
    if (!active) return 1f
    val periodMs = (1000f / hz.coerceAtLeast(0.05f)).toInt()
    val transition = rememberInfiniteTransition(label = "alpha-pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha-pulse-phase",
    )
    return minAlpha + (1f - minAlpha) * phase
}
