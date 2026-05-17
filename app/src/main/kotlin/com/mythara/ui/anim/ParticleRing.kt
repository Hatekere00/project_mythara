package com.mythara.ui.anim

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Reusable orbital particle ring rendered around a [center] offset.
 *
 * The particles drift on lightweight Lissajous orbits at a stable
 * [baseRadius] — the math is ported from [wear/RoseGlow.ParticleGlow]
 * so the phone-side ring shares its visual DNA with the watch app's
 * "around the rose" particle field.
 *
 * Usage:
 *   - [PreAmuletRing] uses it as the bloom-anticipation ring under a
 *     long-press finger.
 *   - The thinking-state mini-rose badge + idle bottom-edge hint
 *     (Phase E) reuse the same primitive with a smaller [count] +
 *     smaller [radius].
 *
 * Inputs:
 *   - [center] — where to draw, in pixels relative to the Canvas.
 *   - [radius] — orbit base radius in dp. Particles drift ±[driftAmp]
 *     from this.
 *   - [count] — particle count (24 = phone bloom default, 18 = watch
 *     idle, 36 = watch active).
 *   - [palette] — core + halo color pair. Brand defaults are exposed
 *     as [ParticlePalettes].
 *   - [progress] — 0..1; particles fade in with progress (alpha scaled
 *     by progress), the ring radius is also scaled by progress so the
 *     visual EXPANDS as progress climbs from 0 to 1.
 *   - [alphaMul] — extra overall alpha multiplier (for fade-out on
 *     dismiss without animating progress backwards).
 *
 * The orbit math integrates real frame time via [withFrameNanos] so
 * the motion is smooth across device frame rates and doesn't get
 * yanked when a Compose recomposition fires.
 */
@Composable
fun ParticleRing(
    center: Offset,
    radius: Dp,
    modifier: Modifier = Modifier,
    count: Int = 24,
    palette: ParticlePalette = ParticlePalettes.BloomDefault,
    progress: Float = 1f,
    alphaMul: Float = 1f,
    seed: Long = SEED_DEFAULT,
) {
    val particles = remember(count, seed) { generateParticles(count, seed) }

    // Integrated time — drives all Lissajous phase advancements.
    var tSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nowNs ->
                if (lastNanos != 0L) {
                    val deltaSec = (nowNs - lastNanos) / 1_000_000_000f
                    tSec += deltaSec
                }
                lastNanos = nowNs
            }
        }
    }

    Canvas(modifier = modifier) {
        if (count <= 0 || alphaMul <= 0f) return@Canvas
        val cx = center.x
        val cy = center.y
        val targetR = radius.toPx() * progress.coerceIn(0f, 1f)
        for (p in particles) {
            val drift = p.driftAmp * progress
            val orbitX = cx + targetR * cos(p.baseAngleRad)
            val orbitY = cy + targetR * sin(p.baseAngleRad)
            val dx = drift * cos(tSec * (2f * PI.toFloat() / p.periodX) + p.phaseX)
            val dy = drift * sin(tSec * (2f * PI.toFloat() / p.periodY) + p.phaseY)
            val x = orbitX + dx
            val y = orbitY + dy
            val a = (progress * alphaMul).coerceIn(0f, 1f)
            // Halo first so the brighter core sits on top.
            drawCircle(
                color = palette.halo.copy(alpha = HALO_ALPHA * a),
                radius = p.coreRadius * 3.5f,
                center = Offset(x, y),
            )
            drawCircle(
                color = palette.core.copy(alpha = CORE_ALPHA * a),
                radius = p.coreRadius,
                center = Offset(x, y),
            )
        }
    }
}

/** A core/halo color pair for a [ParticleRing]. Halo is drawn under
 *  the core at lower alpha to create a soft glow without per-particle
 *  blur (which would be a perf cliff for the bloom path). */
data class ParticlePalette(val core: Color, val halo: Color)

/** Brand-coherent palettes. Use [BloomDefault] for the pre-amulet
 *  ring; [Thinking] for the mini-rose thinking badge in Phase E. */
object ParticlePalettes {
    /** Charple/Lavender — the bloom anticipation ring colour. */
    val BloomDefault = ParticlePalette(
        core = Color(0xFF6B50FF), // Charple
        halo = Color(0xFF9B86FF), // Lavender
    )

    /** Cyan/Bok-leaning — the "I just committed" flash + the future
     *  thinking-state palette. */
    val Bok = ParticlePalette(
        core = Color(0xFF68FFD6),
        halo = Color(0xFF7DFF9F),
    )
}

private data class Particle(
    val baseAngleRad: Float,
    val driftAmp: Float,
    val periodX: Float,
    val periodY: Float,
    val phaseX: Float,
    val phaseY: Float,
    val coreRadius: Float,
)

private fun generateParticles(count: Int, seed: Long): List<Particle> {
    val rng = Random(seed)
    return List(count) {
        Particle(
            baseAngleRad = rng.nextFloat() * 2f * PI.toFloat(),
            driftAmp = 4f + rng.nextFloat() * 8f,
            periodX = 6f + rng.nextFloat() * 12f,
            periodY = 6f + rng.nextFloat() * 12f,
            phaseX = rng.nextFloat() * 2f * PI.toFloat(),
            phaseY = rng.nextFloat() * 2f * PI.toFloat(),
            coreRadius = 2f + rng.nextFloat() * 2.5f,
        )
    }
}

private const val HALO_ALPHA = 0.30f
private const val CORE_ALPHA = 0.85f
/** Deterministic default — same `Rose` seed the wear-side glow uses
 *  so the visual rhyme between watch and phone is intentional. */
private const val SEED_DEFAULT: Long = 0x526F7365L
