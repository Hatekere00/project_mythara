package com.mythara.linux

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * JSch [Proxy] implementation that opens a [VsockChannel] instead
 * of a TCP socket. Lets us SSH directly into the Android 15 Linux
 * Terminal Debian VM without needing the Terminal app's port-
 * forwarding UI to bridge the vsock to localhost:22.
 *
 * Wired into a JSch session via:
 *   `session.setProxy(VsockProxy(cid = …))`
 *
 * The host/port args passed to [connect] are IGNORED — vsock has
 * no IP routing, and JSch's API requires us to accept them
 * regardless. The actual destination is the (cid, port) pair this
 * proxy was constructed with.
 *
 * JSch then drives SSH framing over [getInputStream] /
 * [getOutputStream]. No SSH internals to write ourselves — we're
 * just lending JSch our transport.
 */
class VsockProxy(
    private val cid: Int,
    private val sshPort: Int = 22,
) : Proxy {

    private var channel: VsockChannel? = null

    override fun connect(
        socketFactory: SocketFactory?,
        host: String,
        port: Int,
        timeout: Int,
    ) {
        // host/port are SSH-config-side semantics; the actual
        // destination is (cid, sshPort). timeout is in milliseconds
        // per JSch's docs; not propagated since AF_VSOCK connect
        // returns near-instantly on success or fails immediately.
        channel = VsockChannel.connect(cid, sshPort)
    }

    override fun getInputStream(): InputStream =
        channel?.inputStream ?: error("VsockProxy.connect() not called")

    override fun getOutputStream(): OutputStream =
        channel?.outputStream ?: error("VsockProxy.connect() not called")

    /** JSch's Proxy interface requires a Socket reference for the
     *  rare case it wants to set TCP options — vsock has none, so
     *  we hand back an unconnected stub. JSch only reads / writes
     *  via the streams above. */
    override fun getSocket(): Socket = Socket()

    override fun close() {
        runCatching { channel?.close() }
        channel = null
    }
}
