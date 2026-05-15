package com.mythara.ui.amulet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared geometry of the Mythara 10-petal rose, extracted from
 * `splash_icon.xml` so both the live wallpaper
 * ([com.mythara.branding.WallpaperRenderer]) and the in-app rose
 * amulet / bloom overlay render the same shapes pixel-for-pixel.
 *
 * Source viewport is 108×108 with the rose centred at (54, 54). Each
 * petal is a diamond polygon — coordinates here are the four corners
 * of that diamond *before* rotation, expressed as offsets from the
 * centre in source units. Multiply by your render scale to land them
 * on a real canvas.
 *
 * The five "big" petals sit at 0/72/144/216/288°; the five "small"
 * petals at 36/108/180/252/324° (interleaved). The cyan hexagon at
 * the centre is the nucleus that pulses with the user's heart rate.
 *
 * Palette colours match the wallpaper renderer exactly — keep them
 * in lock-step or the in-app amulet will visibly drift from the
 * wallpaper underneath it.
 */
object RoseGeometry {

    // ─── Palette (matches branding/WallpaperRenderer.kt) ─────────────
    val Purple = Color(0xFF6B50FF)        // big petals
    val Lavender = Color(0xFF9B86FF)      // small petals + amulet ring
    val Cyan = Color(0xFF68FFD6)          // hexagon nucleus, version stamp

    /** Big petal diamond corners (pre-rotation, source units centred
     *  on origin): close-corner, left-shoulder, tip, right-shoulder. */
    val BigPetal: List<Offset> = listOf(
        Offset(0f, 0f),
        Offset(-3f, -16f),
        Offset(0f, -30f),
        Offset(3f, -16f),
    )

    /** Small petal diamond corners (pre-rotation, source units). */
    val SmallPetal: List<Offset> = listOf(
        Offset(0f, 0f),
        Offset(-2f, -10f),
        Offset(0f, -18f),
        Offset(2f, -10f),
    )

    /** Cyan hexagon nucleus corners (pre-rotation, source units). */
    val HexPoints: List<Offset> = listOf(
        Offset(0f, -5f),
        Offset(4.33f, -2.5f),
        Offset(4.33f, 2.5f),
        Offset(0f, 5f),
        Offset(-4.33f, 2.5f),
        Offset(-4.33f, -2.5f),
    )

    /** Five clock positions (degrees) for the big petals. */
    val BigPetalAngles: IntArray = intArrayOf(0, 72, 144, 216, 288)

    /** Five clock positions (degrees) for the small petals. */
    val SmallPetalAngles: IntArray = intArrayOf(36, 108, 180, 252, 324)

    /** Source-unit radius of the rose's outermost pixel — the tip of
     *  a big petal at 30 source units below centre. Use for sizing
     *  bloom expansions and amulet hit-targets. */
    const val OuterRadiusSourceUnits: Float = 30f

    /**
     * Build a filled-path representation of a single petal at the
     * given angle, scaled to render units, centred on (cx, cy) of
     * the destination canvas. Returns a fresh [Path] — callers
     * normally reuse a single Path with `.reset()` between petals
     * inside their per-frame draw to keep allocations down (see
     * [WallpaperRenderer.drawPetal] and `RoseAmulet`).
     */
    fun petalPath(
        diamond: List<Offset>,
        angleDegrees: Float,
        cx: Float,
        cy: Float,
        scale: Float,
        out: Path = Path(),
    ): Path {
        val r = angleDegrees * (PI / 180.0).toFloat()
        val c = cos(r)
        val s = sin(r)
        out.reset()
        diamond.forEachIndexed { i, p ->
            val rx = p.x * c - p.y * s
            val ry = p.x * s + p.y * c
            val x = cx + rx * scale
            val y = cy + ry * scale
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }

    /**
     * Build a filled-path representation of the hexagon nucleus,
     * centred on (cx, cy), scaled to render units. Like [petalPath]
     * the caller can reuse a Path between frames to avoid garbage.
     */
    fun hexPath(
        cx: Float,
        cy: Float,
        scale: Float,
        out: Path = Path(),
    ): Path {
        out.reset()
        HexPoints.forEachIndexed { i, p ->
            val x = cx + p.x * scale
            val y = cy + p.y * scale
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }
}
