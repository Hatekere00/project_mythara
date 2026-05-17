package com.mythara.ui.anim

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Specification for an [EdgeGlow] overlay — color gradient + edge
 * placement + thickness + breathing rhythm.
 *
 * Defaults match the Mythara Minimal brand: a 2-dp Charple→Bok
 * gradient on the bottom edge, breathing at 0.25 Hz (a relaxed
 * 4-second cycle).
 */
data class EdgeGlowSpec(
    val edge: Edge = Edge.Bottom,
    val thicknessDp: Dp = 2.dp,
    val startColor: Color = Color(0xFF6B50FF), // Charple
    val endColor: Color = Color(0xFF68FFD6),   // Bok
    val breathingHz: Float = 0.25f,
    val minAlpha: Float = 0.18f,
    val maxAlpha: Float = 0.42f,
) {
    enum class Edge { Top, Bottom, Left, Right }
}

/**
 * Edge-glow overlay rendered by [MytharaScaffold] (and any other
 * surface that wants the brand "I'm alive" line on a screen edge).
 *
 * Draws a thin gradient bar along [EdgeGlowSpec.edge] that breathes
 * between [EdgeGlowSpec.minAlpha] and [EdgeGlowSpec.maxAlpha] at
 * [EdgeGlowSpec.breathingHz].
 *
 * Place inside a parent Box so it can fillMaxSize() and align to
 * the edge — typically the same Box that wraps the scaffold body.
 */
@Composable
fun EdgeGlow(spec: EdgeGlowSpec, modifier: Modifier = Modifier) {
    val periodMs = (1000f / spec.breathingHz.coerceAtLeast(0.05f)).toInt()
    val transition = rememberInfiniteTransition(label = "edge-glow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "edge-glow-phase",
    )
    val a = spec.minAlpha + (spec.maxAlpha - spec.minAlpha) * phase
    val alignment = when (spec.edge) {
        EdgeGlowSpec.Edge.Top -> Alignment.TopCenter
        EdgeGlowSpec.Edge.Bottom -> Alignment.BottomCenter
        EdgeGlowSpec.Edge.Left -> Alignment.CenterStart
        EdgeGlowSpec.Edge.Right -> Alignment.CenterEnd
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = alignment) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thickness = spec.thicknessDp.toPx()
            val brush: Brush
            val topLeft: Offset
            val sizePx: Size
            when (spec.edge) {
                EdgeGlowSpec.Edge.Top -> {
                    brush = Brush.horizontalGradient(
                        0f to spec.startColor.copy(alpha = a),
                        1f to spec.endColor.copy(alpha = a),
                    )
                    topLeft = Offset(0f, 0f)
                    sizePx = Size(size.width, thickness)
                }
                EdgeGlowSpec.Edge.Bottom -> {
                    brush = Brush.horizontalGradient(
                        0f to spec.startColor.copy(alpha = a),
                        1f to spec.endColor.copy(alpha = a),
                    )
                    topLeft = Offset(0f, size.height - thickness)
                    sizePx = Size(size.width, thickness)
                }
                EdgeGlowSpec.Edge.Left -> {
                    brush = Brush.verticalGradient(
                        0f to spec.startColor.copy(alpha = a),
                        1f to spec.endColor.copy(alpha = a),
                    )
                    topLeft = Offset(0f, 0f)
                    sizePx = Size(thickness, size.height)
                }
                EdgeGlowSpec.Edge.Right -> {
                    brush = Brush.verticalGradient(
                        0f to spec.startColor.copy(alpha = a),
                        1f to spec.endColor.copy(alpha = a),
                    )
                    topLeft = Offset(size.width - thickness, 0f)
                    sizePx = Size(thickness, size.height)
                }
            }
            drawRect(brush = brush, topLeft = topLeft, size = sizePx)
        }
    }
}
