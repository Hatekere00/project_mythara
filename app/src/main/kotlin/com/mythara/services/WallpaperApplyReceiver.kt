package com.mythara.services

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import com.mythara.branding.MytharaLiveWallpaperService
import com.mythara.branding.WallpaperRenderer
import java.io.File

/**
 * ADB-triggerable wallpaper applier. Lets the operator drop a Mythara
 * branding image onto the device's home + lockscreen with one shell
 * command (no third-party gallery / picker dance):
 *
 *   adb shell am broadcast \
 *     -a com.mythara.action.APPLY_WALLPAPER \
 *     -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \
 *     --es path /sdcard/Pictures/mythara_wallpaper.png \
 *     --es target both
 *
 * `path` may be a filesystem path OR a content:// URI. `target` is one
 * of `home`, `lock`, `both` (default `both`). Decoded via
 * [BitmapFactory], handed to [WallpaperManager.setBitmap] with the
 * matching `which` flag(s). Errors are logged; the receiver never
 * crashes the host process. Exported so adb can reach it without the
 * app being in foreground.
 *
 * The application holds [android.permission.SET_WALLPAPER] (declared in
 * the manifest); no runtime grant required.
 */
class WallpaperApplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            Log.w(TAG, "ignoring unknown action ${intent.action}")
            return
        }
        val targetArg = (intent.getStringExtra(EXTRA_TARGET) ?: "both").lowercase()

        // Live-wallpaper handoff. setWallpaperComponent() requires the
        // system-only SET_WALLPAPER_COMPONENT signature permission, so
        // we go through the user-facing chooser instead — which also
        // lets the user see the preview before committing. The chooser
        // is pre-loaded with our component so they only need to tap
        // "Set wallpaper" once. `path` extra is ignored in this mode.
        if (targetArg == "live") {
            launchLiveWallpaperPicker(context)
            return
        }

        // Auto-apply: render one frame of the live wallpaper (posture-
        // adaptive — picks the FoldInner bake on a squarish display,
        // CompactPortrait elsewhere) and set it as the home + lock
        // wallpaper without any user interaction. This is what
        // install-cluster.sh fires after every APK install so the user
        // doesn't have to manually pick the wallpaper after a deploy.
        // The renderer's full composition (gradient + static layers +
        // neurons + rose) is included — a single frame at tMs=0
        // captures the rose at rotation=0 and the hex/neurons at
        // pulse peak, which makes a nice "key art" still.
        // Optional `target=static` with `--es lock_only true` applies
        // to lock only (kept for parity with the future Settings UI).
        if (targetArg == "static") {
            applyStaticSnapshot(context, intent)
            return
        }

        // Debug-only HR injection — lets the operator drive the live
        // wallpaper's pulse rate from adb without needing the watch
        // online + Health Connect populated. Fires the same sink the
        // real ResonanceHrStore.push() does, so the rendered effect
        // is identical to a real reading.
        //   adb shell am broadcast \
        //     -a com.mythara.action.APPLY_WALLPAPER \
        //     -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \
        //     --es target test-hr --ei bpm 120
        if (targetArg == "test-hr") {
            val bpm = intent.getIntExtra(EXTRA_BPM, -1)
            if (bpm <= 0) {
                Log.w(TAG, "test-hr requires --ei bpm <int>")
                return
            }
            com.mythara.branding.LiveWallpaperPulseSink.update(bpm)
            Log.i(TAG, "test-hr injected bpm=$bpm into LiveWallpaperPulseSink")
            return
        }

        // Debug mood injection — drive the gradient palette without
        // needing to actually chat with the agent.
        //   --es target test-mood --es mood anxious
        if (targetArg == "test-mood") {
            val mood = intent.getStringExtra(EXTRA_MOOD).orEmpty()
            com.mythara.branding.MoodSink.update(mood)
            Log.i(TAG, "test-mood injected mood=$mood into MoodSink")
            return
        }

        // Debug ripple ping — fire a thought ripple from the rose
        // (or from a custom point if --ef ox/oy fractions are set).
        //   --es target test-ripple
        //   --es target test-ripple --ef ox 0.5 --ef oy 0.3
        if (targetArg == "test-ripple") {
            val ox = intent.getFloatExtra(EXTRA_ORIGIN_X, -1f)
            val oy = intent.getFloatExtra(EXTRA_ORIGIN_Y, -1f)
            com.mythara.branding.ThoughtRippleSink.ping(ox, oy)
            Log.i(TAG, "test-ripple injected at ($ox, $oy)")
            return
        }

        val pathArg = intent.getStringExtra(EXTRA_PATH)
        if (pathArg.isNullOrBlank()) {
            Log.w(TAG, "missing required extra '$EXTRA_PATH'")
            return
        }
        val whichFlags = when (targetArg) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> {
                Log.w(TAG, "unknown target '$targetArg'; defaulting to both")
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
        }

        val bitmap = runCatching {
            // Accept either content:// URIs or raw filesystem paths.
            // Filesystem paths are the common case for adb-pushed PNGs.
            if (pathArg.startsWith("content://") || pathArg.startsWith("file://")) {
                val uri = Uri.parse(pathArg)
                context.contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else {
                val f = File(pathArg)
                if (!f.exists()) {
                    Log.e(TAG, "file does not exist: $pathArg")
                    return
                }
                BitmapFactory.decodeFile(f.absolutePath)
            }
        }.getOrElse {
            Log.e(TAG, "failed to decode bitmap from '$pathArg': ${it.message}", it)
            return
        }

        if (bitmap == null) {
            Log.e(TAG, "BitmapFactory returned null for '$pathArg'")
            return
        }

        runCatching {
            val wm = WallpaperManager.getInstance(context)
            // Pass `null` for visibleCropHint to let the system center-
            // crop, and `true` for `allowBackup` so it survives a backup-
            // restore. The `which` arg gates home / lock / both.
            wm.setBitmap(bitmap, null, true, whichFlags)
            Log.i(TAG, "wallpaper applied (${bitmap.width}x${bitmap.height}, target=$targetArg)")
        }.onFailure {
            Log.e(TAG, "WallpaperManager.setBitmap failed: ${it.message}", it)
        }
    }

    /**
     * Render one frame of the live wallpaper into a Bitmap and apply
     * it via WallpaperManager.setBitmap. Posture is auto-detected by
     * the renderer from the surface (display) dimensions, so the
     * Pixel 9 Pro Fold gets the FoldInner bake when its inner display
     * is active, the cover gets the CompactPortrait bake, every other
     * phone gets CompactPortrait.
     *
     * Why "static snapshot" instead of just calling setLiveWallpaper:
     * setting an arbitrary live wallpaper component requires the
     * signature permission SET_WALLPAPER_COMPONENT which sideloaded
     * debug builds don't have. This bypass gets the user a branded
     * Mythara wallpaper applied to home + lock automatically; if they
     * want the animated version they tap once on the picker that
     * `target=live` opens.
     */
    private fun applyStaticSnapshot(context: Context, intent: Intent) {
        runCatching {
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "applyStaticSnapshot: invalid display metrics ${w}x${h}")
                return
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val renderer = WallpaperRenderer(context)
            renderer.setSize(w, h)
            // tMs=0 picks rose-rotation=0 and pulse-peak. Good "key art"
            // composition without arbitrary motion artifacts in the
            // single still frame.
            renderer.render(canvas, 0L)
            renderer.release()

            val lockOnly = intent.getBooleanExtra(EXTRA_LOCK_ONLY, false)
            val whichFlags = if (lockOnly) {
                WallpaperManager.FLAG_LOCK
            } else {
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            val wm = WallpaperManager.getInstance(context)
            wm.setBitmap(bitmap, null, true, whichFlags)
            Log.i(TAG, "static snapshot applied (${w}x${h}, lockOnly=$lockOnly)")
            bitmap.recycle()
        }.onFailure {
            Log.e(TAG, "applyStaticSnapshot failed: ${it.message}", it)
        }
    }

    /**
     * Launch the system live-wallpaper preview pre-loaded with our
     * MytharaLiveWallpaperService component. The user taps "Set
     * wallpaper" in the preview to actually apply.
     *
     * Uses ACTION_CHANGE_LIVE_WALLPAPER (Android 4.0+) — every Pixel
     * + every Wear-OS-paired phone we ship to has this. Includes
     * NEW_TASK because the receiver isn't an Activity context.
     */
    private fun launchLiveWallpaperPicker(context: Context) {
        runCatching {
            val component = ComponentName(context, MytharaLiveWallpaperService::class.java)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "launched live wallpaper picker for ${component.flattenToShortString()}")
        }.onFailure {
            Log.e(TAG, "live wallpaper picker failed: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Mythara/WallpaperApply"
        const val ACTION = "com.mythara.action.APPLY_WALLPAPER"
        const val EXTRA_PATH = "path"
        const val EXTRA_TARGET = "target"

        /** `--ei bpm <int>` — used only when target=test-hr to inject
         *  a synthetic heart-rate sample into the live wallpaper's
         *  pulse sink, so the operator can verify the HR-driven
         *  pulse rate end-to-end without the watch / Health Connect. */
        const val EXTRA_BPM = "bpm"

        /** `--es mood <label>` — used with target=test-mood to drive
         *  the gradient palette (anxious / sad / frustrated / excited
         *  / happy, anything else falls back to neutral). */
        const val EXTRA_MOOD = "mood"

        /** `--ef ox <0..1>` and `--ef oy <0..1>` — optional ripple
         *  origin in canvas-fraction coords. Defaults (-1, -1) mean
         *  "centre on the rose". */
        const val EXTRA_ORIGIN_X = "ox"
        const val EXTRA_ORIGIN_Y = "oy"

        /** `--ez lock_only true` (combined with `--es target static`) —
         *  apply the static snapshot to the lockscreen only, leaving
         *  the home wallpaper untouched. Default false = both. */
        const val EXTRA_LOCK_ONLY = "lock_only"
    }
}
