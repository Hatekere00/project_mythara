package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_file` — read a text file from one of Mythara's allowed
 * filesystem roots.
 *
 * Roots:
 *   - app `filesDir`  (private; user's notes the agent has written)
 *   - app `cacheDir`  (transient)
 *   - app `externalFilesDir(null)` (per-app external)
 *   - `/sdcard/Download/` (user-managed; requires media perms but
 *     reads via standard File I/O since this is the public Downloads
 *     directory on Android 11+)
 *
 * Any path outside these roots is rejected. Default max read is
 * 64 KB; the agent can override up to 256 KB.
 *
 * Returns JSON: `{path, size, sha, mime, content}` — the agent can
 * see what it just read and reason about it.
 */
@Singleton
class ReadFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "read_file"
    override val description =
        "Read a text file from Mythara's allowed paths (filesDir, cacheDir, Downloads). UTF-8 only."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute file path. Must be under an allowed root.")
            })
            put("max_bytes", buildJsonObject {
                put("type", "integer")
                put("description", "Max bytes to read. Default 65536, max 262144.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pathStr = args["path"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (pathStr.isBlank()) return ToolResult.fail("path must be non-empty")
        val maxBytes = (args["max_bytes"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 65_536)
            .coerceIn(1, 262_144)

        val file = File(pathStr).canonicalFile
        if (!isUnderAllowedRoot(file)) {
            return ToolResult.fail("path_not_allowed: $pathStr — must be under filesDir, cacheDir, externalFilesDir, or /sdcard/Download/")
        }
        if (!file.exists()) return ToolResult.fail("not_found: $pathStr")
        if (file.isDirectory) return ToolResult.fail("is_directory: use list_dir instead")
        if (!file.canRead()) return ToolResult.fail("not_readable: $pathStr")

        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
                val truncated = file.length() > maxBytes
                val content = String(bytes, Charsets.UTF_8)
                val sha = MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }
                val mime = guessMime(file.name)
                ToolResult.ok(
                    """{"path":"${file.absolutePath.escape()}","size":${file.length()},"sha":"$sha","mime":"$mime","truncated":$truncated,"content":${jsonString(content)}}""",
                )
            }.getOrElse { ToolResult.fail("read_error: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun isUnderAllowedRoot(file: File): Boolean {
        val roots = listOfNotNull(
            context.filesDir.canonicalFile,
            context.cacheDir.canonicalFile,
            context.getExternalFilesDir(null)?.canonicalFile,
            File("/sdcard/Download").canonicalFile,
            File("/storage/emulated/0/Download").canonicalFile,
        )
        val target = file.canonicalPath
        return roots.any { target.startsWith(it.canonicalPath) }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "yaml", "yml" -> "application/yaml"
        "kt" -> "text/x-kotlin"
        "py" -> "text/x-python"
        "js" -> "application/javascript"
        else -> "application/octet-stream"
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
