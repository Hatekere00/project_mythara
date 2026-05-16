package com.mythara.services

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Singleton wrapper around the Shizuku API.
 *
 * Shizuku ([https://shizuku.rikka.app/]) is a free, open-source shim
 * that gives ordinary apps access to a small set of system APIs
 * normally gated by signature- or system-protected permissions
 * (notably `WRITE_SECURE_SETTINGS`, parts of `IPackageManager`,
 * etc.). The user installs the Shizuku app from Play, bootstraps it
 * once via adb / wireless debugging, and apps that declare
 * Shizuku support can then make IPC calls into the shell-uid
 * Shizuku process to run those operations.
 *
 * For Mythara, we use Shizuku exclusively for the cosmetic-tweak
 * pipeline ([com.mythara.agent.tools.CosmeticTool]) so the agent can
 * apply non-invasive system changes (font scale, dark mode, accent
 * color, gesture-nav mode) without ever asking for root or modifying
 * `/system`.
 *
 * State semantics:
 *
 *   - [State.NotInstalled] — the Shizuku app isn't on the device.
 *     Cosmetic tool returns setup-card #1 (install steps).
 *   - [State.NotRunning] — installed but the Shizuku process isn't
 *     live. Setup-card #2 (start steps).
 *   - [State.PermissionDenied] — running, but the user hasn't
 *     granted Mythara access yet. Setup-card #3 (grant steps).
 *   - [State.Ready] — green light. Cosmetic operations proceed.
 */
@Singleton
class ShizukuService @Inject constructor() {

    enum class State {
        NotInstalled,
        NotRunning,
        PermissionDenied,
        Ready,
    }

    /** Snapshot the current Shizuku state. Cheap — no blocking IPC. */
    fun state(packageManager: PackageManager): State {
        val installed = isInstalled(packageManager)
        if (!installed) return State.NotInstalled
        val running = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!running) return State.NotRunning
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return if (granted) State.Ready else State.PermissionDenied
    }

    /** Whether the Shizuku app is installed on the device. */
    private fun isInstalled(pm: PackageManager): Boolean = try {
        pm.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Request Shizuku permission. Resumes with the granted state.
     *  No-op if not running. */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            if (!Shizuku.pingBinder()) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                cont.resume(true)
                return@suspendCancellableCoroutine
            }
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQ_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
            Shizuku.requestPermission(REQ_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed: ${t.message}")
            cont.resume(false)
        }
    }

    /**
     * Execute a command through Shizuku's process (which runs with
     * shell UID, so it can `settings put`, etc.).
     *
     * Shizuku's `newProcess` method is marked `@hide` (so not on the
     * Kotlin-callable public surface), but it IS the documented path
     * for ad-hoc shell exec — the official sample uses it via Java
     * reflection. We do the same: reflect into the method, invoke
     * with the command split into `sh -c <command>`. If the method
     * shape changes in a future Shizuku release the call fails and
     * we return null, the CosmeticTool degrades gracefully.
     *
     * Returns the merged stdout+stderr as a String, or null if the
     * Shizuku process refused / errored.
     */
    fun execShell(command: String): ShellResult? {
        if (!try { Shizuku.pingBinder() } catch (_: Throwable) { false }) return null
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = cls.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val proc = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null,
            ) as java.lang.Process
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            ShellResult(exit = exit, out = out, err = err)
        } catch (t: Throwable) {
            Log.w(TAG, "execShell failed: ${t.message}")
            null
        }
    }

    data class ShellResult(val exit: Int, val out: String, val err: String)

    companion object {
        private const val TAG = "Mythara/Shizuku"
        private const val REQ_CODE = 8847
    }
}
