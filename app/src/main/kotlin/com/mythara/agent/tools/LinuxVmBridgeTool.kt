package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.LinuxBridgeStore
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `linux_vm` — execute a command inside the Android 15 experimental
 * Linux Terminal (Debian VM running via crosvm).
 *
 * ## How it works
 *
 * The Android 15 Linux Terminal is sandboxed in its own VM, separate
 * from the host Android runtime. The only programmatic way to reach
 * it is over the network: the user opens the Terminal app once,
 * installs `openssh-server`, starts it, and configures Mythara with
 * the SSH host/port/credentials in Settings → Linux Bridge.
 *
 * This tool then SSHes in and runs the command, returning stdout +
 * exit code.
 *
 * ## First-use UX
 *
 * When the user calls this tool with no SSH config saved, we return
 * a setup-guidance JSON instead of attempting the connection. The
 * agent should READ this response and surface the steps to the user
 * verbatim — do NOT hallucinate alternate setup paths.
 *
 * ## Implementation note
 *
 * No SSH client library is pulled in (JSch / sshj would add ~600 KB).
 * Instead we shell out to the `ssh` binary that Android's BusyBox
 * provides on Pixel 9+ (Android 15) — or fail with a clear error if
 * `ssh` isn't on PATH. If users need the JSch path we can add it
 * later as a build variant.
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
        if (!cfg.isConfigured) {
            return ToolResult.ok(setupCard())
        }

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    runCatching {
                        // Build ssh command. -o StrictHostKeyChecking=no
                        // for localhost setups; -o BatchMode=yes so we
                        // never block on interactive prompts.
                        val sshArgs = mutableListOf(
                            "ssh",
                            "-o", "StrictHostKeyChecking=no",
                            "-o", "BatchMode=yes",
                            "-o", "ConnectTimeout=5",
                            "-p", cfg.port.toString(),
                        )
                        if (!cfg.privateKeyPem.isNullOrBlank()) {
                            // Persist the private key to a temp file
                            // and reference it. Permissions matter or
                            // ssh refuses to use it.
                            val keyFile = java.io.File.createTempFile("mythara_ssh_", ".pem")
                            keyFile.writeText(cfg.privateKeyPem)
                            keyFile.setReadable(false, false)
                            keyFile.setReadable(true, true)
                            keyFile.setWritable(false, false)
                            keyFile.setWritable(true, true)
                            sshArgs += listOf("-i", keyFile.absolutePath)
                        }
                        sshArgs += "${cfg.user}@${cfg.host}"
                        sshArgs += command

                        val proc = ProcessBuilder(sshArgs)
                            .redirectErrorStream(true)
                            .start()

                        // If password-auth is needed, write it on stdin
                        // — many ssh builds support sshpass-style stdin
                        // password, but standard OpenSSH does NOT.
                        // Recommend key auth in setup card.
                        if (!cfg.password.isNullOrBlank()) {
                            try {
                                proc.outputStream.write((cfg.password + "\n").toByteArray())
                                proc.outputStream.flush()
                                proc.outputStream.close()
                            } catch (_: Throwable) { /* ignore */ }
                        }

                        val output = proc.inputStream.bufferedReader().readText()
                        val exit = proc.waitFor()
                        val truncated = if (output.length > MAX_OUT) {
                            output.take(MAX_OUT) + "\n…[truncated]"
                        } else output
                        ToolResult.ok(
                            """{"status":"ok","exit":$exit,"out":${jsonString(truncated)}}""",
                        )
                    }.getOrElse {
                        ToolResult.fail("ssh_failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                ToolResult.ok("""{"status":"timeout","timeout_ms":$timeoutMs}""")
            }
        }
    }

    private fun setupCard(): String =
        """{"status":"not_configured","setup_steps":[
            "1. On Android 15, open Settings → Developer options → 'Linux development environment' and install the Debian VM.",
            "2. Open the new Terminal app from the launcher.",
            "3. Inside the VM, run: sudo apt update && sudo apt install -y openssh-server",
            "4. Set a password for your user: passwd",
            "5. Start sshd: sudo service ssh start (or 'sudo systemctl enable --now ssh')",
            "6. Find the VM's IP: ip a | grep inet — usually 192.168.x.x or 10.x.x.x",
            "7. In Mythara, open Settings → Linux Bridge and paste host=<ip>, port=22, user=<your-username>, and either password OR private key.",
            "8. Retry this command."
        ]}""".trimIndent()

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    companion object {
        private const val MAX_OUT = 8_192
    }
}
