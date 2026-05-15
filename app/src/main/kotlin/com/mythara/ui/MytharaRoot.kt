package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.auth.AuthState
import com.mythara.ui.about.AboutMeScreen
import com.mythara.ui.about.AboutScreen
import com.mythara.ui.amulet.AMULET_BOTTOM_MARGIN_DP
import com.mythara.ui.amulet.AMULET_SIZE_DP
import com.mythara.ui.amulet.Constellation
import com.mythara.ui.amulet.ConstellationSlot
import com.mythara.ui.amulet.QuickAction
import com.mythara.ui.amulet.QuickActionIds
import com.mythara.ui.amulet.QuickActionWheel
import com.mythara.ui.amulet.RoseAmulet
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.auth.AuthGate
import com.mythara.ui.auth.AuthViewModel
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.fold.FoldPosture
import com.mythara.ui.fold.RoseBloomOverlay
import com.mythara.ui.fold.rememberFoldPosture
import com.mythara.ui.permissions.PermissionsScreen
import com.mythara.ui.triage.NotificationTriageScreen
import com.mythara.ui.dashboard.DashboardLayout
import com.mythara.ui.face.FaceScreen
import com.mythara.ui.insights.InsightsScreen
import com.mythara.ui.notes.NotesScreen
import com.mythara.ui.onboarding.OnboardingScreen
import com.mythara.ui.util.isTabletDisplay
import com.mythara.ui.secret.SecretSettingsScreen
import com.mythara.ui.secret.SecretUnlockDialog
import com.mythara.ui.settings.SettingsScreen
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaTheme

/**
 * Compose root. Owns the theme. Pivots between the AuthGate and the
 * main NavHost based on [AuthViewModel.state]. The NavHost is only
 * instantiated when the app is Unlocked — that way ChatViewModel /
 * SettingsViewModel never initialise until after auth, so no
 * background flows (history observation, MiniMax client warm-ups) run
 * before the user has authenticated.
 *
 * @param onUnlockRequest Invoked when the user taps "unlock" on the gate.
 *                       The Activity launches BiometricPrompt and flips
 *                       AuthManager → Unlocked on success.
 * @param authErrorMessage Message to surface on the gate from the last
 *                       unsuccessful attempt (e.g., "screen lock missing").
 */
@Composable
fun MytharaRoot(
    onUnlockRequest: () -> Unit,
    /**
     * Triggered when the Secret-mode unlock dialog wants to authenticate via
     * the device biometric / credential. The Activity wires this through
     * [com.mythara.auth.AppAuth] with a Secret-specific title.
     */
    onSecretAuthRequest: (onSuccess: () -> Unit, onFailure: (String?) -> Unit) -> Unit,
    authErrorMessage: String? = null,
    /**
     * WindowSizeClass from the activity. When width is Medium or Expanded
     * (unfolded foldables, tablets, wide windows on Chrome OS / DeX),
     * MytharaRoot renders a two-pane layout — chat always on the left,
     * settings / people / about / secret on the right. Compact width
     * (typical phone portrait) keeps the existing single-pane NavHost.
     */
    windowSize: androidx.compose.material3.windowsizeclass.WindowSizeClass? = null,
) {
    val authVm: AuthViewModel = hiltViewModel()
    val authState by authVm.state.collectAsState()
    val nav = rememberNavController()

    // First-run onboarding pivot. Sits OUTSIDE the AuthGate because
    // half the steps deep-link to system Settings (Accessibility,
    // Notification access, Usage access), and bouncing back through
    // a re-lock + biometric every time would make the walkthrough
    // unusable. Once OnboardingStore.markCompleted() lands the flag
    // becomes true and subsequent launches go straight to the
    // AuthGate as normal.
    //
    // The flag is null until DataStore resolves; we render a blank
    // Bg-coloured surface during that one-frame window so the
    // AuthGate doesn't briefly flash before pivoting to onboarding.
    val rootVmEarly: RootViewModel = hiltViewModel()
    val onboardingCompleted by rootVmEarly.onboardingCompleted.collectAsState()

    MytharaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg),
        ) {
            when {
                onboardingCompleted == null -> {
                    // DataStore not resolved yet — empty bg surface for
                    // a single frame. Keeps the AuthGate from flashing.
                }
                onboardingCompleted == false -> {
                    OnboardingScreen(onComplete = { /* state flips via flow */ })
                }
                else -> when (authState) {
                is AuthState.Locked -> AuthGate(
                    onUnlock = onUnlockRequest,
                    errorMessage = authErrorMessage,
                )
                is AuthState.Unlocked -> {
                    var secretUnlockOpen by remember { mutableStateOf(false) }

                    // "Hey Lumi <query>" → navigate to Chat. The actual
                    // submission to MiniMax happens inside ChatViewModel
                    // (which collects the same wake-queries flow); our
                    // job here is just routing — pop the user from
                    // Settings / About / SecretSettings back to Chat
                    // so the agent's response is visible.
                    //
                    // Only collected while Unlocked — wakes that fire
                    // while the app is Locked (just-backgrounded) are
                    // deliberately not auto-actioned; the persistent
                    // service notification is the surface to re-engage.
                    val rootVm: RootViewModel = hiltViewModel()
                    LaunchedEffect(Unit) {
                        rootVm.wakeQueries.collect {
                            val current = nav.currentDestination?.route
                            if (current != Routes.Chat) {
                                nav.navigate(Routes.Chat) {
                                    popUpTo(Routes.Chat) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    // 3-way layout pivot:
                    //   Compact            → single-pane NavHost (phones)
                    //   Non-compact + tablet (smallestScreenWidthDp ≥ 720)
                    //                     → DashboardLayout (command center)
                    //   Non-compact + not tablet (i.e. unfolded foldable
                    //                              or wide window)
                    //                     → TwoPaneLayout (chat + welcome)
                    // The tablet check is intentionally NOT the WindowSizeClass
                    // alone — unfolded foldables also land in Medium/Expanded
                    // but should keep their existing two-pane layout (their
                    // form factor doesn't suit a six-tile dashboard).
                    val isCompact = windowSize == null ||
                        windowSize.widthSizeClass == androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val isTablet = !isCompact && ctx.isTabletDisplay()

                    // The active layout (compact / tablet / two-pane) is
                    // wrapped in a Box so the rose amulet can overlay
                    // it at the bottom-centre on every destination —
                    // chat, settings, insights, etc. The amulet stays
                    // pinned across nav transitions because it lives
                    // OUTSIDE the NavHost. Phase 1 keeps it visual-
                    // only; later phases will layer gestures on top.
                    Box(modifier = Modifier.fillMaxSize()) {
                    if (isCompact) {
                        NavHost(navController = nav, startDestination = Routes.Chat) {
                            composable(Routes.Chat) {
                                ChatScreen(
                                    onOpenSettings = { nav.navigate(Routes.Settings) },
                                    onOpenPeople = { nav.navigate(Routes.People) },
                                    onOpenFace = { nav.navigate(Routes.Face) },
                                    onOpenAboutMe = { nav.navigate(Routes.AboutMe) },
                                    onOpenInsights = { nav.navigate(Routes.Insights) },
                                    onOpenMusicVocab = { nav.navigate(Routes.MusicVocab) },
                                )
                            }
                            composable(Routes.MusicVocab) {
                                com.mythara.ui.music.MusicVocabularyScreen(
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable(Routes.Face) {
                                FaceScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.AboutMe) {
                                AboutMeScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Insights) {
                                InsightsScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Settings) {
                                SettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    onOpenAbout = { nav.navigate(Routes.About) },
                                    onOpenPeople = { nav.navigate(Routes.People) },
                                )
                            }
                            composable(Routes.People) {
                                com.mythara.ui.analytics.PeopleScreen(
                                    onBack = { nav.popBackStack() },
                                )
                            }
                            composable(Routes.About) {
                                AboutScreen(
                                    onBack = { nav.popBackStack() },
                                    onSecretRequest = { secretUnlockOpen = true },
                                )
                            }
                            composable(Routes.SecretSettings) {
                                SecretSettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    onOpenNotes = { nav.navigate(Routes.Notes) },
                                )
                            }
                            composable(Routes.Notes) {
                                NotesScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Permissions) {
                                PermissionsScreen(onBack = { nav.popBackStack() })
                            }
                            composable(Routes.Triage) {
                                NotificationTriageScreen(onBack = { nav.popBackStack() })
                            }
                        }
                    } else if (isTablet) {
                        DashboardLayout(
                            onSecretUnlockRequest = { secretUnlockOpen = true },
                        )
                    } else {
                        TwoPaneLayout(
                            onSecretUnlockRequest = { secretUnlockOpen = true },
                        )
                    }

                    // Constellation overlay — destinations radiate
                    // outward from the amulet on swipe-up. Drawn
                    // BEFORE the amulet so the amulet floats on top
                    // of the scrim and remains tappable to dismiss.
                    var constellationOpen by remember { mutableStateOf(false) }
                    var quickWheelOpen by remember { mutableStateOf(false) }
                    // Slot positions are clock-degrees (0° = 12, 90° = 3)
                    // SPREAD ACROSS THE UPPER SEMICIRCLE ONLY because
                    // the amulet sits at the bottom of the canvas — any
                    // slot at clock 6 (180°) would land off-screen below.
                    // 9 destinations × 22.5° apart from 270° (9 o'clock,
                    // far-left) through 0° (top) to 90° (3 o'clock, far-
                    // right), so the constellation fans up + outward
                    // like petals from the amulet itself.
                    //
                    // Layout convention: ADMIN on the left arc, SETTINGS
                    // at apex, INSIGHTS / HEALTH on the right arc.
                    // Permanent positions = predictable muscle memory.
                    val slots = remember {
                        listOf(
                            ConstellationSlot(270f, "me", Routes.AboutMe, MytharaColors.Malibu),
                            ConstellationSlot(292.5f, "people", Routes.People, MytharaColors.Charple),
                            ConstellationSlot(315f, "notes", Routes.Notes, MytharaColors.Bok),
                            ConstellationSlot(337.5f, "tasks", Routes.Notes, MytharaColors.Mustard),
                            ConstellationSlot(0f, "settings", Routes.Settings, MytharaColors.SurfaceHigh),
                            ConstellationSlot(22.5f, "perms", Routes.Permissions, MytharaColors.Charple),
                            ConstellationSlot(45f, "triage", Routes.Triage, MytharaColors.Charple),
                            ConstellationSlot(67.5f, "insights", Routes.Insights, MytharaColors.Bok),
                            ConstellationSlot(90f, "face", Routes.Face, MytharaColors.Bok),
                        )
                    }

                    Constellation(
                        slots = slots,
                        open = constellationOpen,
                        amuletBottomPaddingDp = AMULET_BOTTOM_MARGIN_DP.value.toInt(),
                        amuletSizeDp = AMULET_SIZE_DP.value.toInt(),
                        onSlotTap = { slot ->
                            constellationOpen = false
                            nav.navigate(slot.route) {
                                launchSingleTop = true
                            }
                        },
                        onScrimTap = { constellationOpen = false },
                    )

                    // Quick-action wheel overlay — long-press of the
                    // amulet surfaces the four inline composer tools
                    // around the rose so they're thumb-reachable
                    // without stretching to the bottom-bar.
                    val quickActions = remember {
                        listOf(
                            QuickAction(QuickActionIds.Mic, "🎤"),
                            QuickAction(QuickActionIds.SttMute, "🤫"),
                            QuickAction(QuickActionIds.MusicMode, "♪"),
                            QuickAction(QuickActionIds.ContinuousVoice, "∞"),
                        )
                    }
                    QuickActionWheel(
                        open = quickWheelOpen,
                        actions = quickActions,
                        amuletBottomPaddingDp = AMULET_BOTTOM_MARGIN_DP.value.toInt(),
                        amuletSizeDp = AMULET_SIZE_DP.value.toInt(),
                        onActionTap = { _ ->
                            // Phase 3 placeholder — wire to actual
                            // STT/mic/music toggles in a follow-up
                            // pass once the amulet flow is verified.
                            // For now, dismiss + log so the gesture
                            // path is end-to-end testable.
                            quickWheelOpen = false
                        },
                        onScrimTap = { quickWheelOpen = false },
                    )

                    // Persistent rose amulet — overlays every screen
                    // at the bottom-centre. Gestures wired:
                    //   tap         → return to chat (home)
                    //   swipe-up    → toggle constellation
                    //   long-press  → toggle quick-action wheel
                    //   triple-tap  → secret unlock
                    //   swipe L/R   → step adjacent primary screens
                    //                 (insights ↔ chat ↔ face)
                    RoseAmulet(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = AMULET_BOTTOM_MARGIN_DP)
                            .size(AMULET_SIZE_DP),
                        onTap = {
                            // If a layered overlay is open, tap on
                            // the rose closes it; otherwise navigate
                            // home (Chat). This gives the rose a
                            // single, predictable "back to ground"
                            // semantic regardless of state.
                            when {
                                quickWheelOpen -> quickWheelOpen = false
                                constellationOpen -> constellationOpen = false
                                else -> {
                                    if (nav.currentDestination?.route != Routes.Chat) {
                                        nav.navigate(Routes.Chat) {
                                            popUpTo(Routes.Chat) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        },
                        onLongPress = {
                            constellationOpen = false
                            quickWheelOpen = !quickWheelOpen
                        },
                        onSwipeUp = {
                            quickWheelOpen = false
                            constellationOpen = !constellationOpen
                        },
                        onTripleTap = {
                            constellationOpen = false
                            quickWheelOpen = false
                            secretUnlockOpen = true
                        },
                        onSwipeLeft = {
                            // Step to the "left" primary screen.
                            // Order: Chat → Insights → Face → People.
                            // A swipe LEFT on the rose moves you to
                            // the next item; swipe RIGHT goes back.
                            val current = nav.currentDestination?.route
                            val target = stepPrimary(current, forward = true)
                            if (target != null && target != current) {
                                nav.navigate(target) {
                                    launchSingleTop = true
                                    popUpTo(Routes.Chat) { inclusive = false }
                                }
                            }
                        },
                        onSwipeRight = {
                            val current = nav.currentDestination?.route
                            val target = stepPrimary(current, forward = false)
                            if (target != null && target != current) {
                                nav.navigate(target) {
                                    launchSingleTop = true
                                    popUpTo(Routes.Chat) { inclusive = false }
                                }
                            }
                        },
                    )
                    // Suppress unused warning — RoseGeometry is used
                    // by the Constellation/Amulet/Bloom imports.
                    @Suppress("UNUSED_VARIABLE") val unused = RoseGeometry.OuterRadiusSourceUnits

                    // Fold-open rose bloom — plays every time the
                    // device transitions OUT of a folded posture.
                    // Skippable on tap from the overlay itself.
                    val foldPosture by rememberFoldPosture()
                    var lastPosture by remember { mutableStateOf(foldPosture) }
                    var showBloom by remember { mutableStateOf(false) }
                    LaunchedEffect(foldPosture) {
                        // Trigger the bloom whenever the user opens
                        // the device. Closing it (back to Folded)
                        // does NOT replay the bloom — that direction
                        // is the layout collapsing, not opening.
                        if (lastPosture == FoldPosture.Folded &&
                            foldPosture != FoldPosture.Folded) {
                            showBloom = true
                        }
                        lastPosture = foldPosture
                    }
                    if (showBloom) {
                        RoseBloomOverlay(
                            biasUp = foldPosture == FoldPosture.HalfOpened,
                            onComplete = { showBloom = false },
                        )
                    }
                    } // end Box wrapping the layout pivot

                    if (secretUnlockOpen) {
                        SecretUnlockDialog(
                            onUnlocked = {
                                secretUnlockOpen = false
                                nav.navigate(Routes.SecretSettings)
                            },
                            onDismiss = { secretUnlockOpen = false },
                            onBiometricRequest = onSecretAuthRequest,
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * Ordered list of "primary" destinations the user can step between
 * via swipe-left / swipe-right on the rose amulet. Chat is the
 * anchor; the others rotate around it. Secondary screens (Settings,
 * About Me, Notes, Permissions, Triage, etc.) are reachable via the
 * Constellation but NOT via the swipe-step flow — they'd noise up
 * the gesture too much.
 */
private val PrimaryStepOrder = listOf(
    Routes.Chat,
    Routes.Insights,
    Routes.Face,
    Routes.People,
)

private fun stepPrimary(current: String?, forward: Boolean): String? {
    if (current == null) return null
    val idx = PrimaryStepOrder.indexOf(current)
    if (idx < 0) {
        // Caller is on a secondary screen (Settings / Permissions /
        // etc.) — a swipe pulls us back to Chat as the anchor.
        return Routes.Chat
    }
    val n = PrimaryStepOrder.size
    val nextIdx = if (forward) (idx + 1) % n else (idx - 1 + n) % n
    return PrimaryStepOrder[nextIdx]
}

object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
    const val About = "about"
    const val AboutMe = "about-me"
    const val Insights = "insights"
    const val SecretSettings = "secret"
    const val Notes = "notes"
    const val People = "people"
    const val Face = "face"
    const val MusicVocab = "music-vocab"
    /** Dedicated permissions screen — runtime + special perms in one place. */
    const val Permissions = "permissions"
    /** Notification triage — see auto-dismissed, mark important. */
    const val Triage = "triage"
}
