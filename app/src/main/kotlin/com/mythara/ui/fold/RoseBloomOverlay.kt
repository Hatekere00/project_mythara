package com.mythara.ui.fold

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import com.mythara.R
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.MytharaColors

/**
 * Full-screen overlay that plays the rose-bloom transition every
 * time [com.mythara.ui.fold.FoldPosture] flips out of [FoldPosture.Folded].
 * Lifts the brand mark from a tiny seed at canvas-centre into a
 * fully-bloomed rose, with the warrior silhouette fading in behind
 * as a guardian. Tap-to-skip jumps the animation to its end frame.
 *
 * Z-order inside the overlay (bottom-up):
 *   1. Solid charmtone background — masks the layout settling under-
 *      neath so the bloom reads cleanly.
 *   2. Warrior silhouette PNG (bundled bloom_silhouette.png) faded
 *      in over the first 0.4 s.
 *   3. 10-petal rose drawn on Canvas, scaled by `progress`, rotating
 *      slightly on the way in.
 *   4. Cyan hex nucleus fading in over the last 0.4 s.
 *
 * Total runtime BLOOM_DURATION_MS = 1200 ms by default. The host
 * provides an [onComplete] callback which fires once the animation
 * reaches its end frame — caller hides the overlay then.
 *
 * The overlay assumes a flat / half-open canvas — for true tabletop
 * the host can pass [biasUp] to lift the rose's centre into the top
 * half so the mark doesn't land on the hinge.
 */
@Composable
fun RoseBloomOverlay(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    biasUp: Boolean = false,
) {
    val ctx = LocalContext.current
    val silhouette = androidx.compose.ui.graphics.ImageBitmap.imageResource(
        ctx.resources, R.drawable.bloom_silhouette,
    )

    // Master animation progress 0..1 over BLOOM_DURATION_MS.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = BLOOM_DURATION_MS),
        )
        onComplete()
    }

    val petalPath = remember { Path() }
    val hexPath = remember { Path() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // Tap-to-skip — snap progress to 1 and fire
                    // the completion callback immediately.
                    onComplete()
                },
            ),
    ) {
        // Warrior silhouette — fades in over the first 40% of the
        // bloom. The bundled bitmap is already pre-tinted to the
        // amulet palette so we just blit it with an animated alpha.
        val silAlpha = (progress.value / SILHOUETTE_FADE_FRAC).coerceIn(0f, 1f)
        androidx.compose.foundation.Image(
            painter = BitmapPainter(silhouette),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(silAlpha),
        )

        // Animated rose — scale lerps from 0 to 1 over the petal
        // bloom window. The bloom rotates the rose slightly so the
        // settling motion has a "click-into-place" feel rather than
        // a hard landing.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = if (biasUp) h * 0.32f else h / 2f
            // Petal scale comes in over ROSE_BLOOM_FRAC; clamped
            // [0..1] outside the bloom window so the rose sits at
            // its final size for the rest of the animation.
            val bloomT = ((progress.value - ROSE_BLOOM_START_FRAC) /
                (ROSE_BLOOM_END_FRAC - ROSE_BLOOM_START_FRAC)).coerceIn(0f, 1f)
            // Smooth-step easing for the bloom — bloomT itself is
            // linear from the master animation.
            val eased = bloomT * bloomT * (3f - 2f * bloomT)
            val canvasShortSide = minOf(w, h)
            val finalScale = (canvasShortSide * 0.20f) /
                RoseGeometry.OuterRadiusSourceUnits
            val petalScale = eased * finalScale

            // Slight settle-rotation: lift in with 18° pre-roll, end
            // at 0° at progress=1.0 so the rose sits at canonical
            // orientation by the time the layout takes over.
            val settleDeg = (1f - eased) * 18f

            rotate(degrees = settleDeg, pivot = Offset(cx, cy)) {
                // Big purple petals.
                for (deg in RoseGeometry.BigPetalAngles) {
                    RoseGeometry.petalPath(
                        diamond = RoseGeometry.BigPetal,
                        angleDegrees = deg.toFloat(),
                        cx = cx, cy = cy, scale = petalScale,
                        out = petalPath,
                    )
                    drawPath(petalPath, color = RoseGeometry.Purple)
                }
                // Small lavender petals.
                for (deg in RoseGeometry.SmallPetalAngles) {
                    RoseGeometry.petalPath(
                        diamond = RoseGeometry.SmallPetal,
                        angleDegrees = deg.toFloat(),
                        cx = cx, cy = cy, scale = petalScale,
                        out = petalPath,
                    )
                    drawPath(petalPath, color = RoseGeometry.Lavender)
                }
                // Cyan hex nucleus — fades in across the last 40%
                // of the master animation. Always at full scale so
                // the centre doesn't pop into existence.
                val hexT = ((progress.value - HEX_FADE_START_FRAC) /
                    (1f - HEX_FADE_START_FRAC)).coerceIn(0f, 1f)
                if (hexT > 0f) {
                    RoseGeometry.hexPath(cx, cy, finalScale, hexPath)
                    drawPath(hexPath, color = RoseGeometry.Cyan.copy(alpha = hexT))
                }
            }
        }

        // Last-frame fade-out — the overlay's own alpha drops to 0
        // over the final 8% of the animation so the layout
        // underneath bleeds through gracefully instead of popping.
        val fadeOutT = ((progress.value - 0.92f) / 0.08f).coerceIn(0f, 1f)
        if (fadeOutT > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .alpha(1f - fadeOutT),
            )
        }
    }
}

/** Total bloom duration. Tunable in Phase 6 polish. */
private const val BLOOM_DURATION_MS = 1200

/** Silhouette fades in over the first SILHOUETTE_FADE_FRAC of the
 *  master animation. */
private const val SILHOUETTE_FADE_FRAC = 0.40f

/** The petal bloom window — petals start expanding at this fraction
 *  of the master animation and reach full scale at ROSE_BLOOM_END_FRAC. */
private const val ROSE_BLOOM_START_FRAC = 0.20f
private const val ROSE_BLOOM_END_FRAC = 0.80f

/** Hex nucleus opacity ramps in over the last (1 - HEX_FADE_START_FRAC)
 *  of the animation, settling at full alpha by progress=1.0. */
private const val HEX_FADE_START_FRAC = 0.60f
