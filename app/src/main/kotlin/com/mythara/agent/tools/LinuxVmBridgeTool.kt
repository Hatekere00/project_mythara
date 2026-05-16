package com.mythara.agent.tools

import android.os.Build
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.LinuxBridgeStore
import com.mythara.linux.VmCidDiscovery
import com.mythara.linux.VsockChannel
import com.mythara.linux.VsockProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `linux_vm` — execute a command inside the Android 15 experimental
 * Linux Terminal (Debian VM running via crosvm).
 *
 * ## How it works
 *
 * The Linux Terminal is sandboxed in its own VM, separate from the
 * host Android runtime. The only programmatic way to reach it is
 * over the network: the user opens the Terminal app once, installs
 * `openssh-server`, starts it, and configures Mythara with the SSH
 * host/port/credentials in Settings → Linux Bridge.
 *
 * This tool SSHes in using the JSch pure-Java SSH client (the
 * Android system image does NOT bundle an `ssh` binary on PATH, so
 * shelling out via ProcessBuilder fails with `No such file or
 * directory` — we MUST use a JVM SSH library).
 *
 * Auth modes (in priority order):
 *   1. **Private key (PEM)** — preferred. JSch handles RSA / DSA /
 *      ECDSA / Ed25519.
 *   2. **Password** — falls back to interactive-style password auth
 *      via UserInfo + setPassword. Works with stock OpenSSH server
 *      configurations (PasswordAuthentication yes).
 *
 * ## First-use UX
 *
 * When the user calls this tool with no SSH config saved, we return
 * a setup-guidance JSON instead of attempting the connection. The
 * agent should READ this response and surface the steps to the user
 * verbatim — do NOT hallucinate alternate setup paths.
 */
@Singleton
class LinuxVmBridgeTool @Inject constructor(
    private val bridge: LinuxBridgeStore,
) : Tool {
    override val name = "linux_vm"
    override val description =
        "Run a command inside the Android 15 Linux Terminal (Debian VM) via SSH. " +
            "Requires one-time setup (openssh-server in the VM + creds in Mythara Settings)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "Shell command to run inside the Debian VM. Quotes preserved.")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Milliseconds before kill. Default 15000, max 120000.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("command"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (command.isBlank()) return ToolResult.fail("command must be non-empty")
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 15_000L)
            .coerceIn(500L, 120_000L)

        val cfg = bridge.current()
        Log.d(
            TAG,
            "linux_vm invoked: host=${cfg.host} port=${cfg.port} user=${cfg.user} " +
                "auth=${if (!cfg.privateKeyPem.isNullOrBlank()) "key" else if (!cfg.password.isNullOrBlank()) "password" else "none"} " +
                "configured=${cfg.isConfigured} command=${command.take(120)}",
        )
        if (!cfg.isConfigured) {
            Log.d(TAG, "no SSH config saved — returning setup card")
            return ToolResult.ok(setupCard())
        }

        // NOTE: 127.0.0.1 IS the correct host on Android 15, but ONLY
        // when the user has added a port-forwarding entry inside the
        // Terminal app (Settings → Port forwarding → add port 22).
        // The Linux Terminal VM has NO IP bridge to the Android host
        // — it uses virtio-vsock for transport. The Terminal app's
        // port-forwarding feature is what translates host-side TCP
        // to vsock under the hood. Without that entry, the connect
        // will fail with ConnectException; the agent should surface
        // the setup card in that case.

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    runCatching {
                        runSsh(cfg, command, timeoutMs.toInt())
                    }.getOrElse { t ->
                        Log.w(TAG, "ssh exec failed: ${t.javaClass.simpleName}: ${t.message}", t)
                        ToolResult.fail(
                            "ssh_failed: ${t.message ?: t.javaClass.simpleName}",
                        )
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "ssh exec timed out after ${timeoutMs}ms")
                ToolResult.ok("""{"status":"timeout","timeout_ms":$timeoutMs}""")
            }
        }
    }

    private fun runSsh(
        cfg: LinuxBridgeStore.Config,
        command: String,
        timeoutMs: Int,
    ): ToolResult {
        val useVsock = cfg.host.equals("vsock", ignoreCase = true) ||
            cfg.host.startsWith("vsock://", ignoreCase = true) ||
            cfg.host.startsWith("vsock:", ignoreCase = true)
        Log.d(
            TAG,
            "JSch connecting ${cfg.user}@${cfg.host}:${cfg.port} " +
                "transport=${if (useVsock) "vsock" else "tcp"}",
        )
        val jsch = JSch()

        // Identity (private key) is preferred when set. JSch accepts
        // the key bytes directly via addIdentity(name, privKey, pub,
        // passphrase). We only support unencrypted PEM keys for now —
        // generating one in the VM with `ssh-keygen -N ""` is the
        // documented path.
        if (!cfg.privateKeyPem.isNullOrBlank()) {
            runCatching {
                jsch.addIdentity(
                    /* name = */ "mythara_linux_bridge",
                    /* prvkey = */ cfg.privateKeyPem.trim().toByteArray(Charsets.UTF_8),
                    /* pubkey = */ null,
                    /* passphrase = */ null,
                )
            }.onFailure {
                return ToolResult.fail("key_load_failed: ${it.message}")
            }
        }

        // For vsock transport, JSch's host arg is decorative — the
        // real address (cid, port) lives in VsockProxy. We pass
        // "vsock-target" so any log line that prints host shows
        // something readable.
        val sessionHost = if (useVsock) "vsock-target" else cfg.host
        val session: Session = jsch.getSession(cfg.user, sessionHost, cfg.port)

        if (useVsock) {
            if (!VsockChannel.SUPPORTED) {
                return ToolResult.fail(
                    "vsock_unsupported: requires Android 14+; this device is on API ${Build.VERSION.SDK_INT}",
                )
            }
            val cid = VmCidDiscovery.discover()
                ?: return ToolResult.fail(
                    "vm_not_running: no crosvm_debian process found. Open the Linux Terminal app to start the VM, then retry.",
                )
            Log.d(TAG, "vsock transport — cid=$cid sshPort=${cfg.port}")
            session.setProxy(VsockProxy(cid = cid, sshPort = cfg.port))
        }

        if (cfg.privateKeyPem.isNullOrBlank() && !cfg.password.isNullOrBlank()) {
            session.setPassword(cfg.password)
        }

        // Disable strict host-key checking — every connect is to the
        // user's own localhost VM, never a remote server. Saves us
        // from juggling a known_hosts file across reinstalls.
        val props = Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", if (!cfg.privateKeyPem.isNullOrBlank()) "publickey,password" else "password,publickey")
        }
        session.setConfig(props)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (t: Throwable) {
            // On vsock connect-failure, invalidate the CID cache so
            // the next attempt re-scans /proc — covers the case where
            // the VM was restarted with a new CID.
            if (useVsock) VmCidDiscovery.invalidate()
            throw t
        }
        Log.d(TAG, "JSch session connected; opening exec channel")

        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val outStream = ByteArrayOutputStream()
            val errStream = ByteArrayOutputStream()
            channel.outputStream = ByteArrayOutputStream() // ignore stdin
            channel.setErrStream(errStream)
            channel.outputStream = outStream
            channel.connect(timeoutMs)

            // Wait for the command to finish. JSch's channel.isClosed
            // becomes true when the remote side signals EOF.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            val exit = if (channel.isClosed) channel.exitStatus else -1
            val merged = outStream.toString(Charsets.UTF_8) + errStream.toString(Charsets.UTF_8)
            val truncated = if (merged.length > MAX_OUT) merged.take(MAX_OUT) + "\n…[truncated]" else merged
            Log.d(TAG, "JSch exec finished: exit=$exit out=${merged.length}b")
            channel.disconnect()
            return ToolResult.ok(
                """{"status":"ok","exit":$exit,"out":${jsonString(truncated)}}""",
            )
        } finally {
            runCatching { session.disconnect() }
        }
    }

    private fun setupCard(): String =
        """{"status":"not_configured","setup_steps":[
            "1. On Android 15+, open Settings → Developer options → 'Linux development environment' and install the Debian VM.",
            "2. Open the new Terminal app from the launcher.",
            "3. Inside the VM, run: sudo apt update && sudo apt install -y openssh-server",
            "4. Start sshd: sudo service ssh start (or 'sudo systemctl enable --now ssh')",
            "5. (Recommended) Generate an SSH key: ssh-keygen -t ed25519 -f ~/mythara_key -N '' && cat ~/mythara_key.pub >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && cat ~/mythara_key",
            "6. Open Mythara → Settings → 'linux bridge'. RECOMMENDED: set host='vsock' — Mythara will open a vsock socket directly to the VM (zero port-forwarding setup required). Set port=22, user=droid, paste the private key into the key field. Save.",
            "7. Retry this command.",
            "8. (Fallback for Android 13 and older, which don't expose vsock) Inside the Terminal app's menu → 'Port forwarding' → add port 22, then set host=127.0.0.1 in Mythara instead of 'vsock'."
        ]}""".trimIndent()

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    companion object {
        private const val TAG = "Mythara/LinuxVM"
        private const val MAX_OUT = 8_192
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val POLL_INTERVAL_MS = 100L
    }
}
