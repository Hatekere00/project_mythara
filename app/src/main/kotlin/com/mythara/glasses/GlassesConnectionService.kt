package com.mythara.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.MainActivity
import com.mythara.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that holds the Meta DAT session alive across
 * screen-off and process-lifecycle changes, owns the
 * [GlassesGestureRouter] subscription, and pushes screen renders
 * to the glasses display whenever the [GlassesScreenStore] state
 * changes.
 *
 * Lifecycle mirrors [LockscreenIslandService] — start on user
 * opt-in, stop when the user disconnects glasses or backgrounds
 * for a long time. While stopped, the DAT facade still receives
 * `publishEvent` calls but no rendering happens.
 */
@AndroidEntryPoint
class GlassesConnectionService : Service() {

    @Inject lateinit var router: GlassesGestureRouter
    @Inject lateinit var screenStore: GlassesScreenStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForegroundCompat()
            router.start()
            // Try to bring up the DAT session. The facade no-ops when
            // the SDK isn't on the classpath (initial commit state) so
            // this is safe to call unconditionally.
            scope.launch {
                runCatching {
                    GlassesDatFacade.startSession()
                }.onFailure {
                    Log.w(TAG, "startSession threw: ${it.message}")
                }
            }
            // Re-render to glasses on every screen-store change.
            scope.launch {
                screenStore.current.collect { screen ->
                    runCatching { GlassesDatFacade.render(screen) }
                        .onFailure { Log.w(TAG, "render failed: ${it.message}") }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { GlassesDatFacade.stopSession() }
        scope.cancel()
    }

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Mythara · glasses connected")
            .setContentText("Listening for neural-band gestures + glasses photos")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, fgsType)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mythara glasses",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Foreground service for the Meta Display Glasses session"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "Mythara/GlassesFGS"
        private const val NOTIF_ID = 9182
        private const val CHANNEL_ID = "mythara.glasses.fgs"

        fun start(ctx: Context) {
            val i = Intent(ctx, GlassesConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GlassesConnectionService::class.java))
        }
    }
}
