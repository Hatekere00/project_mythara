package com.mythara.linux

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Open a virtio-vsock socket from Mythara's process to a VM running
 * inside Android's virtualization framework (Android 15+ Linux
 * Terminal, Pixel-managed VMs, etc.).
 *
 * ## Why this exists
 *
 * Android's Linux Terminal VM is sandboxed in crosvm and reachable
 * from the host ONLY via `AF_VSOCK` — there is no TCP/IP bridge.
 * The traditional "ssh to localhost:22" path therefore requires the
 * user to manually add a port-forwarding entry in the Terminal app's
 * settings UI, which is fiddly.
 *
 * This class opens the vsock socket directly from Mythara, no
 * port-forwarding step required. JSch can then run an SSH session
 * over the resulting [InputStream] / [OutputStream] by wiring this
 * into a custom `com.jcraft.jsch.Proxy` impl (see [VsockProxy]).
 *
 * ## API requirements
 *
 * Uses [android.system.VmSocketAddress] which is API 34+ (Android
 * 14). On older devices [SUPPORTED] returns false and [connect]
 * throws. The user's phones are Android 15/16 so this is a
 * non-issue for the original Capability Expansion v2 deployment;
 * the version guard is here for forward-portability.
 */
class VsockChannel private constructor(
    private val fd: FileDescriptor,
) : Closeable {

    val inputStream: InputStream = FileInputStream(fd)
    val outputStream: OutputStream = FileOutputStream(fd)

    @Volatile private var closed = false

    fun isOpen(): Boolean = !closed

    override fun close() {
        if (closed) return
        closed = true
        runCatching { Os.close(fd) }.onFailure {
            Log.w(TAG, "Os.close failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "Mythara/Vsock"

        /** True iff the running Android build supports the public
         *  VmSocketAddress API used by [connect]. */
        val SUPPORTED: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        /** AF_VSOCK constant. Not exposed via OsConstants until API 35
         *  (Android 15), so we hardcode the well-known kernel value
         *  (40) as a fallback for the API 34 case. */
        private val AF_VSOCK: Int = runCatching {
            // OsConstants.AF_VSOCK exists from API 35.
            OsConstants::class.java.getField("AF_VSOCK").getInt(null)
        }.getOrDefault(40)

        /**
         * Open a vsock socket and connect to the VM identified by
         * [cid] on [port].
         *
         * @throws UnsupportedOperationException if the device is on
         *   Android 13 or older (no VmSocketAddress).
         * @throws android.system.ErrnoException if the connect fails
         *   (e.g. VM not running, sshd not listening, no permission).
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun connect(cid: Int, port: Int): VsockChannel {
            if (!SUPPORTED) {
                throw UnsupportedOperationException(
                    "vsock requires Android 14+ (current API ${Build.VERSION.SDK_INT})",
                )
            }
            // SOCK_STREAM = 1 (reliable, in-order); standard for SSH.
            val fd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0)
            try {
                // VmSocketAddress(svmPort, svmCid)
                val addr = android.system.VmSocketAddress(port, cid)
                Os.connect(fd, addr)
                Log.d(TAG, "vsock connected cid=$cid port=$port fd=$fd")
                return VsockChannel(fd)
            } catch (t: Throwable) {
                runCatching { Os.close(fd) }
                throw t
            }
        }
    }
}
