package com.mythara.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mythara.MainActivity
import com.mythara.R
import com.mythara.ui.system.DynamicIsland
import com.mythara.ui.system.rememberCutoutRect
import com.mythara.ui.theme.MytharaTheme

/**
 * Foreground service that draws the Mythara Dynamic Island as a
 * SYSTEM-OVERLAY WINDOW — so the same pill the user sees inside
 * Mythara is also visible:
 *   - over every other app
 *   - on the home screen
 *   - on the lock screen (best-effort; see Android Z-order notes
 *     below)
 *
 * Why a service:
 *   Compose UI inside an Activity dies when the Activity stops.
 *   To keep a window painted across the OS surface we need either
 *   a service or a launcher, both of which can hold a
 *   WindowManager view independent of any Activity lifecycle.
 *
 * Permission posture:
 *   Requires SYSTEM_ALERT_WINDOW (the user flips it in
 *   Settings → Special app access → "Display over other apps").
 *   The manifest permission is the install-time grant; the
 *   per-app toggle is runtime via [Settings.canDrawOverlays].
 *   Caller (typically [com.mythara.ui.permissions.PermissionsScreen])
 *   should gate the start() call on
 *   [LockscreenIslandService.canRender].
 *
 * Lock-screen Z-order:
 *   On Android 12+ the secure keyguard renders at TYPE_KEYGUARD,
 *   which is ABOVE TYPE_APPLICATION_OVERLAY. So when the user
 *   has a PIN/pattern/biometric lock active, the overlay won't
 *   draw on the secure surface — it appears once the user has
 *   unlocked but BEFORE entering an app. For "swipe up to unlock"
 *   styles where the lock screen is non-secure, the overlay
 *   shows immediately. We accept this trade-off rather than
 *   fight Android's lock-screen security; users who want
 *   always-visible behavior can use the in-app status-bar pill,
 *   which the overlay mirrors.
 *
 * Tap behaviour:
 *   The pill is touch-passthrough by default (NOT_TOUCH_MODAL +
 *   NOT_FOCUSABLE) so it doesn't steal scrolls / taps from
 *   underlying apps. The interactive zone is the pill itself —
 *   tapping the rose center triggers the same animation +
 *   sink-clear the in-app version does, and a long-press routes
 *   the user into Mythara's Chat.
 */
class LockscreenIslandService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // ComposeOwner's setup happens lazily in
        // [ComposeOwner.ensureReady] — that path correctly
        // sequences performAttach + performRestore BEFORE moving
        // the lifecycle to RESUMED, which is what
        // SavedStateRegistry's contract requires (the old in-
        // class-init path called performAttach AFTER the apply
        // block had already set the lifecycle to RESUMED →
        // "Restarter must be created only during owner's
        // initialization stage" crash).
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        ensureOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        // ComposeOwner is process-wide (singleton object) and may
        // be reused if the service restarts. We deliberately don't
        // tear its lifecycle down here — moving it to DESTROYED
        // would break a subsequent restart of this service since
        // SavedStateRegistry can't be re-attached.
    }

    /* ------------------------------------------------- foreground */

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mythara overlay",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps the Mythara Dynamic Island visible everywhere"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Mythara")
            .setContentText("Dynamic Island is live")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    /* ------------------------------------------------- overlay window */

    private fun ensureOverlay() {
        if (overlayView != null) return
        if (!canRender(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay disabled")
            return
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        // Layout flags: pass-through touches (NOT_FOCUSABLE +
        // NOT_TOUCH_MODAL) so we don't break scrolls / taps below;
        // LAYOUT_NO_LIMITS so we can position above the system
        // status-bar inset; LAYOUT_IN_SCREEN so coordinates are
        // screen-space; SHOW_WHEN_LOCKED so the overlay attempts
        // to render on lock screens (best-effort — see service
        // doc for Z-order caveats).
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            // Push the overlay down by 0 — we want it at the very
            // top of the screen so it lines up with where the
            // in-app status bar would be. The DynamicIsland
            // composable handles its own cutout-aware vertical
            // centering via rememberCutoutRect().
            x = 0
            y = 0
        }

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MytharaTheme {
                    val cutout = rememberCutoutRect()
                    DynamicIsland(cutout = cutout)
                }
            }
        }

        // Compose-in-overlay-window plumbing: the ComposeView needs
        // a LifecycleOwner + SavedStateRegistryOwner attached via
        // ViewTree owners, otherwise it crashes on first frame.
        // ComposeOwner.ensureReady() runs lazily on first access
        // and sequences the SavedStateRegistry attach+restore
        // before transitioning the lifecycle to RESUMED.
        ComposeOwner.ensureReady()
        view.setViewTreeLifecycleOwner(ComposeOwner)
        view.setViewTreeSavedStateRegistryOwner(ComposeOwner)

        runCatching { wm.addView(view, params) }
            .onSuccess {
                overlayView = view
                Log.i(TAG, "overlay attached")
            }
            .onFailure { Log.w(TAG, "overlay attach failed: ${it.message}") }
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager?.removeViewImmediate(v) }
        }
        overlayView = null
        windowManager = null
    }

    /* --- Compose hosting glue: single LifecycleOwner + SavedStateOwner ---
     *
     * SavedStateRegistry's contract: performAttach() and
     * performRestore() MUST be called while the lifecycle is in
     * the INITIALIZED state. The previous version's `apply` block
     * was setting `currentState = RESUMED` BEFORE the
     * SavedStateRegistryController had been attached, which
     * threw "Restarter must be created only during owner's
     * initialization stage" the first time anything touched the
     * object.
     *
     * Fix: lazy init under a one-shot flag. ensureReady() runs
     * the attach+restore while the lifecycle is still INITIALIZED,
     * then promotes to RESUMED in a single pass. Idempotent —
     * subsequent calls no-op.
     */
    private object ComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ssrc = SavedStateRegistryController.create(this)
        @Volatile private var initialized = false

        fun ensureReady() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                // 1) lifecycle is INITIALIZED by default; attach
                //    + restore the SavedStateRegistry now.
                ssrc.performAttach()
                ssrc.performRestore(null)
                // 2) Now promote through CREATED → STARTED →
                //    RESUMED so Compose's ViewTreeLifecycleOwner
                //    sees a fully-resumed lifecycle on first paint.
                reg.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                initialized = true
            }
        }

        override val lifecycle: androidx.lifecycle.Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry
    }

    companion object {
        private const val TAG = "Mythara/IslandOverlay"
        private const val CHANNEL_ID = "mythara_island_overlay"
        private const val NOTIFICATION_ID = 7711

        /** True when the user has flipped the per-app overlay
         *  toggle (Settings → Special app access → Display over
         *  other apps). Caller checks this before calling start. */
        fun canRender(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(ctx)

        /** Idempotent start. Caller should gate on [canRender].
         *
         *  Wrapped in runCatching because Android 12+ throws
         *  [android.app.ForegroundServiceStartNotAllowedException]
         *  when the caller is in BG context (e.g. Application.
         *  onCreate, or any non-foreground startup). The right
         *  fix is to call from an Activity, but a stray BG call
         *  shouldn't crash the whole app — silently skipping the
         *  overlay is preferable to taking the process down. */
        fun start(ctx: Context) {
            runCatching {
                val intent = Intent(ctx, LockscreenIslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }.onFailure { Log.w(TAG, "FGS start failed (likely BG-start restriction): ${it.message}") }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LockscreenIslandService::class.java))
        }

        /** Open the system Settings page where the user grants
         *  the SYSTEM_ALERT_WINDOW permission for Mythara. */
        fun requestPermission(ctx: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
}
