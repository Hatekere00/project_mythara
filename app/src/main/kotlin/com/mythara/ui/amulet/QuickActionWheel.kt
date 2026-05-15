package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors

/**
 * 4-segment radial wheel triggered by long-press on the rose amulet.
 *
 * Surfaces the same four inline composer tools that live at the
 * bottom of [com.mythara.ui.chat.ChatScreen] (mic, STT-mute, music
 * mode, continuous voice) but in a thumb-reachable wheel around the
 * amulet — so the user never has to stretch to the composer when the
 * amulet is one-handed-friendly.
 *
 * Visually similar to [Constellation]: the amulet's centre is the
 * pivot, four chips radiate outward at fixed clock positions
 * (1:30 / 4:30 / 7:30 / 10:30 — the gaps between the constellation's
 * primary positions so the two layers don't visually clash).
 *
 * Tap the scrim or the amulet to dismiss. Tap a segment → fire its
 * action and dismiss.
 */
@Composable
fun QuickActionWheel(
    open: Boolean,
    actions: List<QuickAction>,
    amuletBottomPaddingDp: Int,
    amuletSizeDp: Int,
    onActionTap: (QuickAction) -> Unit,
    onScrimTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expansion = remember { Animatable(initialValue = 0f) }
    LaunchedEffect(open) {
        expansion.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(durationMillis = if (open) 220 else 140),
        )
    }
    if (!open && expansion.value <= 0.001f) return

    val density = LocalDensity.current
    val radiusPx = with(density) { WHEEL_RADIUS_DP.dp.toPx() }
    val chipBottomPadding = (amuletBottomPaddingDp + amuletSizeDp / 2 -
        SEGMENT_SIZE_DP / 2).coerceAtLeast(0)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg.copy(alpha = 0.55f * expansion.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onScrimTap,
                ),
        )
        actions.forEachIndexed { i, action ->
            val angleDeg = WHEEL_ANGLES.getOrElse(i) { 0 }.toFloat()
            val r = angleDeg * (Math.PI / 180.0).toFloat()
            val dx = (Math.sin(r.toDouble()).toFloat()) * radiusPx * expansion.value
            val dy = (-Math.cos(r.toDouble()).toFloat()) * radiusPx * expansion.value
            // offset() (not graphicsLayer translation) — see
            // Constellation.kt for the same fix rationale: hit-test
            // must follow visual position so taps land on segments
            // rather than the scrim.
            val dxDp = with(density) { dx.toDp() }
            val dyDp = with(density) { dy.toDp() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = chipBottomPadding.dp)
                    .offset(x = dxDp, y = dyDp)
                    .graphicsLayer { alpha = expansion.value },
            ) {
                WheelSegment(action = action, onTap = { onActionTap(action) })
            }
        }
    }
}

@Composable
private fun WheelSegment(action: QuickAction, onTap: () -> Unit) {
    val accent = if (action.active) MytharaColors.Bok else MytharaColors.Charple
    Box(
        modifier = Modifier
            .size(SEGMENT_SIZE_DP.dp)
            .clip(CircleShape)
            .background(MytharaColors.Surface)
            .border(width = 1.5.dp, color = accent, shape = CircleShape)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.glyph,
            color = accent,
            style = MaterialTheme.typography.titleLarge,
            fontSize = 18.sp,
        )
    }
}

/**
 * One segment of the [QuickActionWheel]. `glyph` is the unicode
 * symbol shown inside the segment circle. `active` tints the chip
 * Bok-green (matches the inline composer's "on" state convention).
 * `id` is just an identifying string — the parent dispatches on it.
 */
data class QuickAction(
    val id: String,
    val glyph: String,
    val active: Boolean = false,
)

/** Stable IDs the parent matches against in the dispatch lambda.
 *  Kept as constants so call sites don't drift on a typo. */
object QuickActionIds {
    const val Mic = "mic"
    const val SttMute = "stt-mute"
    const val MusicMode = "music-mode"
    const val ContinuousVoice = "continuous-voice"
}

// 4 segments at 1:30, 4:30, 7:30, 10:30 — gaps in the Constellation's
// primary 1:12 / 2:24 / 3:36 / 4:48 / etc. positions so the two
// overlays never compete for the same screen pixel when (theoretically)
// shown together.
private val WHEEL_ANGLES = intArrayOf(45, 135, 225, 315)
private const val WHEEL_RADIUS_DP = 90
private const val SEGMENT_SIZE_DP = 56
