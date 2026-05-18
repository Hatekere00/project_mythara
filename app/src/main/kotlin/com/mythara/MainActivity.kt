package com.mythara

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mythara.auth.AppAuth
import com.mythara.auth.AuthManager
import com.mythara.ui.MytharaRoot
import com.mythara.voice.VoiceActionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single Compose activity. Routing lives inside [MytharaRoot] — we don't
 * use multiple Activities. Extends [FragmentActivity] (not the bare
 * ComponentActivity) because androidx.biometric:1.1's `BiometricPrompt`
 * needs a FragmentActivity to host its internal fragment.
 *
 * Auth posture:
 *   - cold start: AuthManager starts Locked → AuthGate renders, user
 *     unlocks via face / fingerprint / device PIN, AuthManager → Unlocked,
 *     MytharaRoot pivots to the NavHost.
 *   - foregrounded after background: ProcessLifecycleOwner.onStop fired
 *     while we were away → AuthManager is Locked again → AuthGate shows.
 *   - process death: AuthManager singleton is recreated as Locked → cold
 *     start path applies.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var voiceActions: VoiceActionStore
    private val appAuth = AppAuth()
    private var lastAuthError: String? = null
    /**
     * Compose-observable holder for the EXTRA_OPEN_ROUTE deep
     * link the overlay's launcher icons push at us. Reading
     * `intent.getStringExtra(...)` directly from setContent is a
     * SNAPSHOT — onNewIntent updates `intent` but Compose won't
     * recompose because the field isn't observable. A
     * MutableState is. Updated by both onCreate (initial) and
     * onNewIntent (re-launch), so every overlay tap on a
     * launcher icon flows through to MytharaRoot's initialRoute
     * LaunchedEffect.
     */
    private val pendingDeepLinkRoute = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Full-screen launcher mode — Mythara is the user's home
        // launcher (HOME intent-filter on this activity), so it
        // renders its own top status strip (MytharaStatusBar) and
        // hides the system status bar entirely. The
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE means an edge-swipe
        // from the top still surfaces system bars temporarily for
        // notification access — important so the user can still
        // pull down the system shade when they need it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Explicitly opt into laying out under the display cutout
        // (camera pinhole zone) — without this, immersive mode
        // transitions can intermittently cause Compose content to
        // jump up/down as the system flips between cutout-aware
        // and cutout-avoiding layouts. ALWAYS = same behaviour
        // regardless of orientation, the right pick when our
        // status bar is custom-rendered and wraps the cutout via
        // [com.mythara.ui.system.DynamicIsland].
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        // Lock-screen Dynamic Island overlay. Started from
        // Activity.onCreate (not Application.onCreate) because
        // Android 12+ throws ForegroundServiceStartNotAllowedException
        // when an FGS is launched from BG context — Application.
        // onCreate runs in BG per the lifecycle rules even though
        // we're booting our own process. Activity.onCreate IS a
        // foreground entry → FGS start is allowed.
        // Self-gates on canRender so it no-ops when the user
        // hasn't granted SYSTEM_ALERT_WINDOW yet.
        if (com.mythara.services.LockscreenIslandService.canRender(this)) {
            com.mythara.services.LockscreenIslandService.start(this)
        }
        // Pixel Buds touch-and-hold (and any other "open the digital
        // assistant" gesture, e.g. squeeze-to-assist, swipe-up assist
        // gesture) delivers MainActivity an ACTION_ASSIST intent when
        // Mythara is set as the system default assistant app. We
        // capture it on both cold start and re-launch via singleTop.
        handleVoiceIntent(intent)

        // Process-wide lifecycle observer with grace-period auto-lock.
        // onStop fires when ALL Mythara activities are stopped — we
        // record the timestamp but stay Unlocked. onStart on the way
        // back consults AuthSettings.timeoutMs and decides whether to
        // re-lock or pass through. The user's configured timeout is
        // 5 minutes by default; "immediate" matches the old behaviour.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    authManager.markBackgrounded()
                }
                override fun onStart(owner: LifecycleOwner) {
                    lifecycleScope.launch { authManager.markForegrounded() }
                }
            },
        )

        // Seed the deep-link route from the launch intent. An
        // onNewIntent re-arrival will update this same State so
        // MytharaRoot.initialRoute recomposes + re-fires its
        // LaunchedEffect to navigate.
        pendingDeepLinkRoute.value = intent?.getStringExtra(
            com.mythara.services.LockscreenIslandService.EXTRA_OPEN_ROUTE
        )
        setContent {
            val windowSize = androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
            MytharaRoot(
                windowSize = windowSize,
                initialRoute = pendingDeepLinkRoute.value,
                onUnlockRequest = {
                    appAuth.authenticate(this, title = "Unlock Mythara") { result ->
                        when (result) {
                            AppAuth.Result.Success -> {
                                lastAuthError = null
                                authManager.unlock()
                            }
                            AppAuth.Result.Canceled -> {
                                // User dismissed the prompt — stay locked,
                                // they can hit "unlock" again.
                            }
                            is AppAuth.Result.Error -> {
                                lastAuthError = result.message
                                authManager.lock()
                            }
                        }
                    }
                },
                onSecretAuthRequest = { onSuccess, onFailure ->
                    // Same BiometricPrompt machinery; different copy so the
                    // user sees they're crossing a second-tier gate.
                    appAuth.authenticate(
                        this,
                        title = "Unlock Mythara secrets",
                        subtitle = "Authenticate to enter Observe mode",
                    ) { result ->
                        when (result) {
                            AppAuth.Result.Success -> onSuccess()
                            AppAuth.Result.Canceled -> onFailure(null)
                            is AppAuth.Result.Error -> onFailure(result.message)
                        }
                    }
                },
                authErrorMessage = lastAuthError,
            )
        }
    }

    /**
     * Re-launch path. The activity is `launchMode="singleTop"` so when
     * Mythara is already running, an assist gesture brings the same
     * MainActivity instance forward with the new intent via this
     * callback rather than a fresh onCreate.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceIntent(intent)
        // Refresh the Compose-observable deep-link slot so the
        // overlay's launcher-icon taps (which call
        // startActivity with EXTRA_OPEN_ROUTE = <route>) flow
        // through to MytharaRoot's initialRoute LaunchedEffect.
        // Without this, intent gets setIntent'd but Compose
        // doesn't see it.
        val newRoute = intent.getStringExtra(
            com.mythara.services.LockscreenIslandService.EXTRA_OPEN_ROUTE
        )
        // Force the value to update even if the new route is
        // the same as the current value — MytharaRoot's
        // LaunchedEffect keys on initialRoute so a no-change
        // wouldn't fire. Toggle through null to force re-fire.
        pendingDeepLinkRoute.value = null
        pendingDeepLinkRoute.value = newRoute
    }

    private fun handleVoiceIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val source = when (action) {
            Intent.ACTION_ASSIST -> VoiceActionStore.Source.AssistIntent
            Intent.ACTION_VOICE_COMMAND -> VoiceActionStore.Source.VoiceCommandIntent
            Intent.ACTION_WEB_SEARCH -> VoiceActionStore.Source.WebSearchIntent
            "android.intent.action.SEARCH_LONG_PRESS" -> VoiceActionStore.Source.AssistIntent
            else -> return
        }
        // Strip the action so a configuration change (rotation,
        // theme switch) doesn't re-trigger listen mode every time
        // the activity rebuilds.
        intent.action = Intent.ACTION_MAIN
        // Lock-screen path — when the user tapped "Talk" / "Reply" on
        // the QuickTalk or Reply notification while the device was
        // locked, render this activity OVER the keyguard so they
        // can complete the voice round-trip without unlocking. For
        // SECURE keyguards (PIN/pattern/biometric set) the OS still
        // gates access to sensitive surfaces, but our chat UI +
        // mic + TTS run fine over the lock.
        enableOverKeyguard()
        voiceActions.fire(source)
    }

    /** Pin the activity above the keyguard + flip the screen on so
     *  voice-triggered launches from lock-screen notifications +
     *  ASSIST gestures stay in-context. Idempotent — calling twice
     *  is a no-op for the second call. The matching teardown lives
     *  in [disableOverKeyguard] which fires once the auth manager
     *  unlocks (or the user backgrounds the activity). */
    private fun enableOverKeyguard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        // Try to dismiss the (non-secure) keyguard so a quick swipe
        // pattern doesn't break the agent flow. KeyguardManager
        // ignores this on secure lock screens — the over-keyguard
        // render is the fallback there.
        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE)
            as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true && android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {
            runCatching {
                km.requestDismissKeyguard(this, null)
            }
        }
    }
}
