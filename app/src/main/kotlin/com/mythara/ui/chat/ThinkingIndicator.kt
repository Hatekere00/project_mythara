package com.mythara.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.theme.JetBrainsMono
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay

/**
 * "Mythara is thinking…" indicator with the Charple→Bok gradient
 * (the same brand gradient as the MYTHARA wordmark). Cycles
 * through a small rolodex of phrases every ~1.6s so the user
 * sees life rather than a static "thinking…" string.
 *
 * The gradient itself animates — Charple → Bok sweeps left-to-right
 * across the text via a translate offset on a linearGradient brush
 * applied through SpanStyle. Combined with the rolodex phrase
 * cycle, it reads as "Mythara is alive and working", not "your phone
 * froze".
 *
 * Rendered in the chat surface between submit() and the first
 * streamed delta; suppressed once streaming text starts arriving.
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
) {
    // 1. Phrase rolodex. Reads like a friend updating you on what
    //    they're doing. ~1.6s per phrase — slow enough to read,
    //    fast enough to feel responsive.
    var phraseIdx by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PHRASE_INTERVAL_MS)
            phraseIdx = (phraseIdx + 1) % PHRASES.size
        }
    }
    val phrase = PHRASES[phraseIdx]

    // 2. Animated gradient. Slide the Charple→Bok colours across
    //    the line; the band is wider than the text so it never
    //    feels like a hard edge entering/leaving.
    val transition = rememberInfiniteTransition(label = "thinking-gradient")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(GRADIENT_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "thinking-sweep",
    )

    val gradientWidthPx = 800f
    val startX = -gradientWidthPx + (sweep * (gradientWidthPx * 2))
    val brush: Brush = Brush.linearGradient(
        colors = listOf(
            MytharaColors.Charple,
            MytharaColors.Bok,
            MytharaColors.Charple,
        ),
        start = Offset(startX, 0f),
        end = Offset(startX + gradientWidthPx, 0f),
    )

    val annotated: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(brush = brush)) {
            append(phrase)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = annotated,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val PHRASE_INTERVAL_MS = 1_600L
private const val GRADIENT_PERIOD_MS = 2_400

private val PHRASES = listOf(
    "Mythara is thinking…",
    "Reading the room…",
    "Composing a reply…",
    "Looking it up…",
    "Pulling things together…",
    "Just a sec…",
    "Mythara is working…",
)
