package com.mythara.ui.amulet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.MytharaColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The summon-anywhere version of the rose amulet + constellation.
 *
 * Replaces the older bottom-anchored persistent amulet that competed
 * for the same screen real-estate as the chat composer (it overlapped
 * the input field on every keystroke). The new model:
 *
 *   - Amulet is HIDDEN by default. Nothing on screen until invoked.
 *   - User long-presses anywhere on screen for ~LONG_PRESS_MS ms.
 *   - Amulet appears AT the press point and the constellation fans
 *     out 360° around it.
 *   - Tap the central rose → cycle to the NEXT page (pages wrap).
 *   - Tap a chip → execute its action / navigate.
 *   - Tap the scrim (anywhere outside chip + rose) → dismiss.
 *
 * The amulet is now PAGINATED — consecutive taps on the central
 * rose cycle through pages of chips. The PageProvider lambda lets
 * the host wire whatever pages it wants (typically: navigation
 * destinations → PTT actions → more secondary screens → installed
 * apps split into 8-per-page groups).
 *
 * Anchor position is clamped so the full constellation ring stays
 * on-screen.
 *
 * The same sinks the persistent amulet read from
 * (LiveWallpaperPulseSink, MoodSink) still drive the central rose's
 * pulse + tint when shown — so the amulet keeps its identity as
 * "your physiological brand mark" even though it's transient.
 */

/**
 * One page of the paginated amulet. Each page is a list of chips
 * arranged around the constellation ring + a short label that
 * appears under the central rose so the user knows which page
 * they're on.
 */
data class AmuletPage(
    /** Short label shown under the rose (e.g. "menu", "ptt",
     *  "more", "apps · 1/3"). */
    val label: String,
    /** The chips to render on this page. */
    val chips: List<AmuletChip>,
)

/**
 * A single chip on an amulet page. Uniform shape across all page
 * types (navigation destination, PTT action, app launcher) so the
 * geometric layout doesn't care.
 */
data class AmuletChip(
    /** Position on the ring, in clock degrees (0° = 12 o'clock). */
    val angleDeg: Float,
    /** Short caption under the chip (≤ 8 chars typical). */
    val caption: String,
    /** Accent colour for the chip border + glyph text. */
    val accent: Color,
    /** Optional bitmap (for app icons); when non-null, rendered
     *  inside the circular chip. */
    val icon: ImageBitmap? = null,
    /** Glyph (1-2 chars) shown when [icon] is null. Initial of
     *  the caption is a reasonable default. */
    val glyph: String = caption.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
    /** Tap handler. */
    val onTap: () -> Unit,
)

@Composable
fun PopupAmulet(
    anchorPx: Offset,
    pages: List<AmuletPage>,
    amuletSizeDp: Int,
    onScrimTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) {
        // Defensive: if a host hands us no pages, just render a
        // tappable scrim so the user can dismiss.
        Box(modifier = modifier.fillMaxSize().clickable { onScrimTap() })
        return
    }
    // Center-tap cycles through pages. Wraps. The user previously
    // had two faces (Menu / PTT); now arbitrary count with the
    // same one-tap-cycles-forward gesture.
    var pageIndex by remember { mutableStateOf(0) }
    val expansion = remember { Animatable(initialValue = 0f) }
    LaunchedEffect(Unit) {
        expansion.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = OPEN_DURATION_MS),
        )
    }

    val density = LocalDensity.current
    val radiusPx = with(density) { CONSTELLATION_RADIUS_DP.dp.toPx() }
    val amuletHalfPx = with(density) { (amuletSizeDp / 2).dp.toPx() }

    val cx = anchorPx.x
    val cy = anchorPx.y

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — full canvas, fades in/out with expansion. Tap →
        // dismiss. Underneath the chips so chip taps win.
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

        // Render the active page's chips around the ring.
        val activePage = pages[pageIndex.coerceIn(0, pages.size - 1)]
        for (chip in activePage.chips) {
            val r = chip.angleDeg * (PI / 180.0).toFloat()
            val dxPx = (sin(r) * radiusPx * expansion.value)
            val dyPx = (-cos(r) * radiusPx * expansion.value)
            val chipCx = cx + dxPx
            val chipCy = cy + dyPx
            val chipHalfDp = (SLOT_SIZE_DP / 2).dp
            val chipLeftDp = with(density) { chipCx.toDp() } - chipHalfDp
            val chipTopDp = with(density) { chipCy.toDp() } - chipHalfDp

            Box(
                modifier = Modifier
                    .offset(x = chipLeftDp, y = chipTopDp)
                    .graphicsLayer { alpha = expansion.value },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(SLOT_SIZE_DP.dp)
                            .clip(CircleShape)
                            .background(MytharaColors.Surface)
                            .border(width = 1.5.dp, color = chip.accent, shape = CircleShape)
                            .clickable { chip.onTap() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chip.icon != null) {
                            Image(
                                bitmap = chip.icon,
                                contentDescription = chip.caption,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size((SLOT_SIZE_DP - 6).dp).clip(CircleShape),
                            )
                        } else {
                            Text(
                                text = chip.glyph,
                                color = chip.accent,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Text(
                        text = chip.caption,
                        color = MytharaColors.FgMute,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(SLOT_LABEL_WIDTH_DP.dp),
                    )
                }
            }
        }

        // Central rose amulet — sits at the anchor itself. Tap
        // ANYWHERE on the rose to cycle to the NEXT page. Pages
        // wrap. Scrim or repeated tap-after-last-page-and-back-
        // to-Menu doesn't dismiss; only the scrim does.
        val amuletLeftDp = with(density) { cx.toDp() } - (amuletSizeDp / 2).dp
        val amuletTopDp = with(density) { cy.toDp() } - (amuletSizeDp / 2).dp
        Box(
            modifier = Modifier
                .offset(x = amuletLeftDp, y = amuletTopDp)
                .graphicsLayer { alpha = expansion.value },
        ) {
            RoseAmulet(
                modifier = Modifier.size(amuletSizeDp.dp),
                onTap = {
                    pageIndex = (pageIndex + 1) % pages.size
                },
            )
        }

        // Page label + dot pagination indicator below the rose so
        // the user knows which page they're on and how many more
        // there are. Dots use the same accent stack as the chips
        // so the visual is consistent.
        val labelTopDp = with(density) { (cy + amuletHalfPx).toDp() } + 6.dp
        val labelLeftDp = with(density) { cx.toDp() } - 60.dp
        Column(
            modifier = Modifier
                .offset(x = labelLeftDp, y = labelTopDp)
                .graphicsLayer { alpha = expansion.value }
                .width(120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = activePage.label,
                color = MytharaColors.FgDim,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            if (pages.size > 1) {
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in pages.indices) {
                        Box(
                            modifier = Modifier
                                .size(if (i == pageIndex) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == pageIndex) MytharaColors.Charple else MytharaColors.SurfaceHigh,
                                ),
                        )
                    }
                }
            }
        }

        // Suppress unused — anchor variable lives for the layout
        // loop but the compiler can't see it.
        @Suppress("UNUSED_VARIABLE") val unusedAh = amuletHalfPx
    }
}

private const val CONSTELLATION_RADIUS_DP = 140
private const val SLOT_SIZE_DP = 44
private const val SLOT_LABEL_WIDTH_DP = 64
private const val OPEN_DURATION_MS = 220
