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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `list_dir` — list children of a directory under an allowed root.
 *
 * Returns JSON: `{path, entries: [{name, size, mtime_ms, is_dir}]}`.
 * Default cap of 200 entries; pagination not implemented (the agent
 * can grep names if it needs filtering).
 *
 * Same root allowlist as `read_file` / `write_file`.
 */
@Singleton
class ListDirTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "list_dir"
    override val description = "List entries in a directory under Mythara's allowed paths."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute directory path under an allowed root.")
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "Max entries to return. Default 200, max 1000.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pathStr = args["path"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (pathStr.isBlank()) return ToolResult.fail("path must be non-empty")
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 200).coerceIn(1, 1_000)

        val dir = File(pathStr).canonicalFile
        if (!isUnderAllowedRoot(dir)) return ToolResult.fail("path_not_allowed: $pathStr")
        if (!dir.exists()) return ToolResult.fail("not_found: $pathStr")
        if (!dir.isDirectory) return ToolResult.fail("not_a_directory: $pathStr")

        return withContext(Dispatchers.IO) {
            runCatching {
                val all = dir.listFiles() ?: return@runCatching ToolResult.fail("listFiles_returned_null")
                val sorted = all.sortedBy { it.name.lowercase() }
                val truncated = sorted.size > limit
                val slice = sorted.take(limit)
                val entriesJson = slice.joinToString(",") { f ->
                    """{"name":"${f.name.escape()}","size":${f.length()},"mtime_ms":${f.lastModified()},"is_dir":${f.isDirectory}}"""
                }
                ToolResult.ok(
                    """{"path":"${dir.absolutePath.escape()}","count":${slice.size},"truncated":$truncated,"entries":[$entriesJson]}""",
                )
            }.getOrElse { ToolResult.fail("list_error: ${it.message ?: it.javaClass.simpleName}") }
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

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
