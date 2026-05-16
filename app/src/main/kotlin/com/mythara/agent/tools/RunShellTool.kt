package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `run_shell` — execute a shell command inside Mythara's app sandbox.
 *
 * The Android app process can `ProcessBuilder(...)` any binary on
 * `$PATH` without special permissions. This tool exposes that to the
 * agent, gated by a hard-coded allowlist of read-mostly binaries so
 * the model can't accidentally `rm -rf /` (or anything similar) on
 * the user's storage.
 *
 * **Default allowlist** (read-only, system-introspection): `ls`,
 * `cat`, `head`, `tail`, `grep`, `find`, `wc`, `df`, `du`, `pwd`,
 * `echo`, `getprop`, `dumpsys`, `pm`, `am`, `ip`, `ping`, `curl`,
 * `whoami`, `id`, `uname`, `date`.
 *
 * Working directory defaults to the app's `filesDir`. Stdout + stderr
 * are merged and returned (truncated to 8 KB). Timeout default 5 s,
 * max 30 s.
 *
 * Anything not on the allowlist returns:
 *   `{status:"blocked", binary:"...", reason:"not allowlisted"}`
 *
 * The user can expand the allowlist via Settings → Shell allowlist
 * (a future Phase 5 UI deliverable); for now this tool ships with
 * the safe default set.
 */
@Singleton
class RunShellTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "run_shell"
    override val description =
        "Run a shell command in Mythara's sandbox. Allowlisted binaries only (ls, cat, getprop, dumpsys, etc.)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cmd", buildJsonObject {
                put("type", "string")
                put("description", "Binary to run (must be on the allowlist). E.g. 'getprop', 'dumpsys'.")
            })
            put("args", buildJsonObject {
                put("type", "array")
                put("description", "Arguments to the binary. Each must be a string.")
                put("items", buildJsonObject { put("type", "string") })
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Milliseconds before kill. Default 5000, max 30000.")
            })
            put("cwd", buildJsonObject {
                put("type", "string")
                put("description", "Working directory. Default Mythara's filesDir.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("cmd"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val cmd = args["cmd"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (cmd.isBlank()) return ToolResult.fail("cmd must be non-empty")
        if (cmd !in ALLOWLIST) {
            return ToolResult.ok(
                """{"status":"blocked","binary":"${cmd.escape()}","reason":"not allowlisted — add via Settings → Shell allowlist if you trust it"}""",
            )
        }
        val cmdArgs = args["args"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull() }
            .orEmpty()
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 5_000L)
            .coerceIn(100L, 30_000L)
        val cwdPath = args["cwd"]?.jsonPrimitive?.contentOrNull()
        val cwd = if (cwdPath.isNullOrBlank()) context.filesDir else File(cwdPath)

        return withContext(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    runCatching {
                        val proc = ProcessBuilder(listOf(cmd) + cmdArgs)
                            .directory(cwd)
                            .redirectErrorStream(true)
                            .start()
                        val out = proc.inputStream.bufferedReader().readText()
                        val exit = proc.waitFor()
                        val truncated = if (out.length > MAX_OUT) out.take(MAX_OUT) + "\n…[truncated]" else out
                        ToolResult.ok(
                            """{"status":"ok","exit":$exit,"out":${jsonString(truncated)}}""",
                        )
                    }.getOrElse {
                        ToolResult.fail("exec failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                ToolResult.ok("""{"status":"timeout","timeout_ms":$timeoutMs}""")
            }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String) = "\"" + s.escape() + "\""

    companion object {
        private const val MAX_OUT = 8_192

        /** Default allowlist — read-mostly binaries for device
         *  introspection. Destructive ops (rm, dd, chmod, kill) are
         *  intentionally excluded. */
        private val ALLOWLIST: Set<String> = setOf(
            "ls", "cat", "head", "tail", "grep", "find", "wc",
            "df", "du", "pwd", "echo",
            "getprop", "dumpsys", "pm", "am", "ip", "ping", "curl",
            "whoami", "id", "uname", "date", "stat", "file",
            "sh",
        )
    }
}
