package com.mythara.ui.anim

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compose transition presets the new chrome uses to animate between
 * route changes + per-screen content swaps.
 *
 * Three families — see plan §C / Phase E:
 *   • [SiblingHorizontal]  — peer routes (Chat ↔ Insights ↔ Face ↔
 *     People). Slide 32 dp + fade, 220 ms. Mirrors the existing swipe-
 *     left/right gesture vocabulary.
 *   • [DrilldownFade]      — Settings sub-panels, AboutMe, Memory.
 *     Pure fade-through with a 180 ms duration (the curtain Charple
 *     flash is rendered by the scaffold as an overlay, not by the
 *     content transform itself).
 *   • [ModalScaleFade]     — SecretSettings, Canvas, MiniMaxSignIn.
 *     Scale 0.96 → 1.0 + fade. Reads as the rose blooming into a
 *     full screen.
 *
 * Each family is exposed as a [ContentTransform] usable directly
 * with [AnimatedContent], and as a route-category enum in
 * [com.mythara.ui.scaffold.MytharaNavTransitions] for the NavHost
 * `enterTransition` / `exitTransition` integration in Phase E.
 */
object RoseTransitions {
    /** Sibling-route default duration. */
    const val SIBLING_DURATION_MS = 220

    /** Drilldown default duration. */
    const val DRILLDOWN_DURATION_MS = 180

    /** Modal-ish default duration. */
    const val MODAL_DURATION_MS = 220

    /** Horizontal slide offset for sibling transitions. */
    private const val SIBLING_OFFSET_DP = 32

    val SiblingHorizontal: ContentTransform =
        (slideInHorizontally(
            animationSpec = tween(SIBLING_DURATION_MS),
            initialOffsetX = { full -> (SIBLING_OFFSET_DP * (full / 360f)).toInt() },
        ) + fadeIn(tween(SIBLING_DURATION_MS))) togetherWith
            (slideOutHorizontally(
                animationSpec = tween(SIBLING_DURATION_MS),
                targetOffsetX = { full -> -(SIBLING_OFFSET_DP * (full / 360f)).toInt() },
            ) + fadeOut(tween(SIBLING_DURATION_MS)))

    val DrilldownFade: ContentTransform =
        fadeIn(tween(DRILLDOWN_DURATION_MS)) togetherWith fadeOut(tween(DRILLDOWN_DURATION_MS))

    val ModalScaleFade: ContentTransform =
        (scaleIn(initialScale = 0.96f, animationSpec = tween(MODAL_DURATION_MS)) +
            fadeIn(tween(MODAL_DURATION_MS))) togetherWith
            (scaleOut(targetScale = 0.96f, animationSpec = tween(MODAL_DURATION_MS)) +
                fadeOut(tween(MODAL_DURATION_MS)))
}

/**
 * Convenience wrapper that animates content swaps inside a screen
 * (e.g. the scaffold body changing layout on `transitionKey` change).
 *
 * Defaults to [RoseTransitions.DrilldownFade]. Pass a different
 * transform for explicit control.
 *
 * Use this INSIDE a screen for body swaps. NavHost-level route
 * transitions are wired separately in `MytharaNavTransitions`
 * (Phase E) so navigation animations stay consistent app-wide.
 */
@Composable
fun <T> AnimatedContentSwap(
    targetState: T,
    modifier: Modifier = Modifier,
    transform: ContentTransform = RoseTransitions.DrilldownFade,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { transform },
        label = "rose-transition",
    ) { state -> content(state) }
}
