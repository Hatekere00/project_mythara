package com.mythara.ui.scaffold

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.mythara.ui.anim.RoseTransitions

/**
 * Per-route transition presets for the NavHost `enterTransition` /
 * `exitTransition` lambdas (Phase E wiring).
 *
 * Three families:
 *   • Sibling — peer routes (Chat ↔ Insights ↔ Face ↔ People).
 *     Horizontal slide + fade, 220 ms.
 *   • Drilldown — Settings sub-panels, AboutMe, Memory. Pure fade
 *     180 ms (the Charple curtain flash is a separate scaffold-level
 *     overlay, not part of the route transform itself).
 *   • Modal — SecretSettings, Canvas, MiniMaxSignIn. Scale 0.96→1.0
 *     + fade, 220 ms.
 *
 * Each family is exposed as both an `EnterTransition` and an
 * `ExitTransition` — pass them as lambdas to `composable(...)` blocks
 * in MytharaRoot.kt.
 *
 * Family selection per route lives in [TransitionFamily.forRoute] —
 * a single map keeps the classification next to the routes themselves
 * so adding a new destination in MytharaRoot has an obvious matching
 * entry to update.
 */
object MytharaNavTransitions {

    val SiblingEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(
            animationSpec = tween(RoseTransitions.SIBLING_DURATION_MS),
            initialOffsetX = { full -> (full * SIBLING_FRACTION).toInt() },
        ) + fadeIn(tween(RoseTransitions.SIBLING_DURATION_MS))
    }

    val SiblingExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(
            animationSpec = tween(RoseTransitions.SIBLING_DURATION_MS),
            targetOffsetX = { full -> -(full * SIBLING_FRACTION).toInt() },
        ) + fadeOut(tween(RoseTransitions.SIBLING_DURATION_MS))
    }

    val DrilldownEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(tween(RoseTransitions.DRILLDOWN_DURATION_MS))
    }

    val DrilldownExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(tween(RoseTransitions.DRILLDOWN_DURATION_MS))
    }

    val ModalEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(initialScale = 0.96f, animationSpec = tween(RoseTransitions.MODAL_DURATION_MS)) +
            fadeIn(tween(RoseTransitions.MODAL_DURATION_MS))
    }

    val ModalExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(targetScale = 0.96f, animationSpec = tween(RoseTransitions.MODAL_DURATION_MS)) +
            fadeOut(tween(RoseTransitions.MODAL_DURATION_MS))
    }

    /** Sibling slide offset = 1/12 of the screen width, calibrated to
     *  match the existing swipe-left/right gesture feel. */
    private const val SIBLING_FRACTION = 1f / 12f
}

/**
 * Which transition family a route uses. Keep this in sync with the
 * `Routes` object in MytharaRoot.kt.
 *
 * The default is [Sibling] for peer navigation; [Drilldown] for
 * settings sub-panels + content drills; [Modal] for full-bleed
 * surfaces that read as "this is a temporary takeover".
 */
enum class TransitionFamily {
    Sibling,
    Drilldown,
    Modal,
    ;

    companion object {
        /** Single source of truth for route → family classification.
         *  Unknown routes default to [Sibling]. Add a debug log on
         *  mis-classification in Phase E so a missing route surfaces
         *  immediately. */
        fun forRoute(route: String): TransitionFamily = when (route) {
            // Peer surfaces accessible via swipe-left/right on the rose.
            // Home is the landing hub + the swipe anchor.
            "home", "chat", "insights", "face", "people" -> Sibling
            // Drill-into surfaces — settings + content body screens.
            "settings", "aboutMe", "memory", "tasks", "notes",
            "musicVocab", "permissions", "audit", "usage", "dashboard",
            "triage", "glassesMemory" -> Drilldown
            // Modal-ish — full-bleed or auth-style takeovers.
            "secretSettings", "canvas", "miniMaxSignIn", "about" -> Modal
            else -> Sibling
        }
    }
}
