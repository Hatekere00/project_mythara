package com.mythara.ui.amulet

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.mythara.ui.anim.ParticlePalettes
import com.mythara.ui.anim.ParticleRing

/**
 * Pre-amulet bloom — the particle ring that forms around the touch
 * point BEFORE the rose amulet commits.
 *
 * Lifecycle (timeline-wise):
 *   • t=0 ms       Long-press begins.
 *   • t=300 ms     GlobalLongPress fires `onPreBloom(downPos)`.
 *                  Caller mounts PreAmuletRing(downPos). Haptic
 *                  GESTURE_START fires here ("I heard you").
 *   • t=300→600 ms Ring expands 0 → 96 px on FastOutSlowInEasing,
 *                  particles fade in via progress 0 → 1.
 *   • t=600 ms     GlobalLongPress fires `onLongPress(downPos)`.
 *                  Caller mounts PopupAmulet at same position; haptic
 *                  LONG_PRESS fires. Ring continues a one-frame cyan
 *                  Bok flash + dismisses while the amulet's own
 *                  OPEN_DURATION_MS (250 ms) expansion takes over.
 *                  Visually the rose appears to bloom out of the
 *                  particle stream rather than pop into existence.
 *   • If released  Ring fades out over 150 ms and nothing else fires
 *     between 300–600 ms.
 *
 * Why it's safe: PopupAmulet, RoseAmulet, RoseGeometry, Constellation,
 * QuickActionWheel are NOT modified. The only call-graph change is
 * the optional `onPreBloom` callback added to GlobalLongPress.
 *
 * @param anchor Touch position in root-Box pixels.
 * @param committed When true, the ring is in its hand-off frame —
 *   show the cyan flash + start dismissing. Caller flips this to
 *   true the moment PopupAmulet mounts.
 * @param onDismissed Called after the dismiss animation completes
 *   so the caller can null out its `preBloomPos` state.
 */
@Composable
fun PreAmuletRing(
    anchor: Offset,
    committed: Boolean,
    onDismissed: () -> Unit,
) {
    val view = LocalView.current
    val expansion = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val visibleAlpha = remember { Animatable(0f) }

    // Phase 1: grow the ring 0 → 1 over GROW_DURATION_MS as soon as
    // we mount. Haptic GESTURE_START fires at mount time too.
    LaunchedEffect(anchor) {
        runCatching { view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START) }
        visibleAlpha.snapTo(0f)
        visibleAlpha.animateTo(1f, tween(FADE_IN_MS, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(anchor) {
        expansion.animateTo(1f, tween(GROW_DURATION_MS, easing = FastOutSlowInEasing))
    }

    // Phase 2: commit — single cyan flash, then fade the ring out as
    // the amulet's own expansion takes over (PopupAmulet has its own
    // OPEN_DURATION_MS = 250 ms; we overlap with it so the hand-off
    // feels continuous, not stepped).
    LaunchedEffect(committed) {
        if (committed) {
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            // Single bright flash that washes out over ~150 ms.
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_FADE_MS, easing = FastOutSlowInEasing))
        }
    }

    // Phase 3: dismiss — when committed OR when the caller explicitly
    // wants to abort (anchor will simply unmount this composable).
    // We just observe the commit and run a graceful fade. The caller
    // unmounts us by setting preBloomPos = null after onDismissed.
    LaunchedEffect(committed) {
        if (committed) {
            visibleAlpha.animateTo(0f, tween(DISMISS_FADE_MS, easing = FastOutSlowInEasing))
            onDismissed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The Charple/Lavender bloom ring.
        ParticleRing(
            center = anchor,
            radius = RING_RADIUS_DP.dp,
            count = PARTICLE_COUNT,
            palette = ParticlePalettes.BloomDefault,
            progress = expansion.value,
            alphaMul = visibleAlpha.value,
        )
        // Brighter cyan halo that flashes for one frame at commit
        // and washes out — gives the user a clear "this took" signal
        // before the rose blooms out.
        if (flash.value > 0f) {
            ParticleRing(
                center = anchor,
                radius = (RING_RADIUS_DP + 8).dp,
                count = PARTICLE_COUNT / 2,
                palette = ParticlePalettes.Bok,
                progress = 1f,
                alphaMul = flash.value,
            )
        }
    }
}

/** Visual constants. The ring radius matches the PopupAmulet's
 *  initial rose ring so the hand-off lines up without a snap. */
private const val GROW_DURATION_MS = 300
private const val FADE_IN_MS = 200
private const val FLASH_FADE_MS = 150
private const val DISMISS_FADE_MS = 180
private const val RING_RADIUS_DP = 64
private const val PARTICLE_COUNT = 24
