package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single fixed slot in the Constellation. Position is a clock angle
 * in degrees (0° = 12 o'clock, 90° = 3 o'clock, 180° = 6 o'clock).
 * The slot stays in the same place across sessions so muscle memory
 * works.
 *
 * `route` is the Routes constant the slot navigates to. `label` is
 * the short text shown beneath the icon. `visible` lets the parent
 * conditionally drop a slot (e.g. Music ♪ only when music mode is
 * enabled) without disrupting the rest of the ring.
 */
data class ConstellationSlot(
    val angleDeg: Float,
    val label: String,
    val route: String,
    val accent: Color,
    val visible: Boolean = true,
)

/**
 * The Constellation overlay — an arc of destination chips that
 * radiate outward from the rose amulet when the user swipes up on
 * the amulet. Each slot has a fixed clock position so the user can
 * tap a destination eyes-closed after a few uses.
 *
 * Animation: every visible slot's offset from the amulet animates
 * from 0 (collapsed onto the amulet) to its target radius
 * (CONSTELLATION_RADIUS_DP) over [OPEN_DURATION_MS] with a smooth
 * ease. Closing reverses the same motion in [CLOSE_DURATION_MS].
 *
 * The full canvas is also covered by a thin scrim so the chips pop
 * against whatever's underneath. Tap the scrim → close the
 * constellation. Tap on the rose amulet itself (handled by the
 * parent in [com.mythara.ui.MytharaRoot]) also closes it.
 */
@Composable
fun Constellation(
    slots: List<ConstellationSlot>,
    open: Boolean,
    amuletBottomPaddingDp: Int,
    amuletSizeDp: Int,
    onSlotTap: (ConstellationSlot) -> Unit,
    onScrimTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drives the radial expansion. 0f = collapsed onto the amulet,
    // 1f = fully expanded at CONSTELLATION_RADIUS_DP.
    val expansion = remember { Animatable(initialValue = 0f) }
    LaunchedEffect(open) {
        expansion.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (open) OPEN_DURATION_MS else CLOSE_DURATION_MS,
            ),
        )
    }

    // Bail before the first frame of expansion to avoid drawing the
    // scrim + dim chips when the constellation is meant to be fully
    // closed (animation finished + open=false).
    if (!open && expansion.value <= 0.001f) return

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — full-canvas tap target for "tap-to-close". Alpha
        // tracks expansion so the scrim fades in / out with the chips.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg.copy(alpha = 0.78f * expansion.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onScrimTap,
                ),
        )

        val density = LocalDensity.current
        val radiusPx = with(density) { CONSTELLATION_RADIUS_DP.dp.toPx() }
        // Park each chip's BottomCenter base offset so the chip's
        // centre starts at the amulet's centre (then graphicsLayer
        // translates by the per-slot delta). chipBottomPadding lifts
        // the chip up so its CENTER sits where the amulet's centre is.
        val chipBottomPadding = (amuletBottomPaddingDp + amuletSizeDp / 2 -
            SLOT_SIZE_DP / 2 - SLOT_LABEL_GAP_DP - SLOT_LABEL_HEIGHT_DP / 2).coerceAtLeast(0)

        slots.filter { it.visible }.forEach { slot ->
            // Convert clock-angle (0°=12, 90°=3, 180°=6) to screen
            // coords. Clock 0° points UP; screen y grows down so we
            // negate the cosine to get an upward translation for the
            // top of the ring.
            val r = slot.angleDeg * (PI / 180.0).toFloat()
            val dxFraction = sin(r)
            val dyFraction = -cos(r)
            val dxPx = (dxFraction * radiusPx * expansion.value)
            val dyPx = (dyFraction * radiusPx * expansion.value)

            // Use offset() so the chip's HIT-TEST region (not just
            // its rendered pixels) follows the radial position.
            // graphicsLayer translates pixels but Compose still
            // hit-tests at the layout-positioned bbox — taps would
            // land on the scrim instead of the chip. offset() shifts
            // both bbox + paint together. alpha can stay on
            // graphicsLayer because that's a paint-only effect.
            val dxDp = with(density) { dxPx.toDp() }
            val dyDp = with(density) { dyPx.toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = chipBottomPadding.dp)
                    .offset(x = dxDp, y = dyDp)
                    .graphicsLayer { alpha = expansion.value },
            ) {
                ConstellationSlotChip(slot = slot, onClick = { onSlotTap(slot) })
            }
        }
    }
}

@Composable
private fun ConstellationSlotChip(
    slot: ConstellationSlot,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(SLOT_SIZE_DP.dp)
                .clip(CircleShape)
                .background(MytharaColors.Surface)
                .border(width = 1.5.dp, color = slot.accent, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // First glyph of the label is the icon — minimalist and
            // consistent with the watch face's monospace aesthetic.
            // Music ♪'s natural symbol takes care of itself.
            val iconText = slot.label.firstOrNull()?.uppercaseChar()?.toString() ?: ""
            Text(
                text = iconText,
                color = slot.accent,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = slot.label,
            color = MytharaColors.FgMute,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = SLOT_LABEL_GAP_DP.dp)
                .width(SLOT_LABEL_WIDTH_DP.dp),
        )
    }
}

// ─── Layout constants ────────────────────────────────────────────────

/** Distance from the amulet centre to each chip's centre. 140 dp
 *  keeps the ring inside the screen on a 411-dp-wide Pixel and still
 *  leaves room for labels under each chip. Tablets / unfolded
 *  foldables can scale this up later (Phase 6 polish). */
private const val CONSTELLATION_RADIUS_DP = 140

private const val SLOT_SIZE_DP = 44
private const val SLOT_LABEL_GAP_DP = 4
private const val SLOT_LABEL_HEIGHT_DP = 14
private const val SLOT_LABEL_WIDTH_DP = 64

private const val OPEN_DURATION_MS = 250
private const val CLOSE_DURATION_MS = 160
