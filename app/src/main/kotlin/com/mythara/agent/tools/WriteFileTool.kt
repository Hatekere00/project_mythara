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
 * `write_file` — write a text file to one of Mythara's allowed
 * filesystem roots (same root list as `read_file`).
 *
 * Modes:
 *   - `create`: fail if the file already exists.
 *   - `overwrite`: replace existing content.
 *   - `append`: append to end of file (create if missing).
 *
 * Returns: `{path, bytes_written, mode}` on success.
 *
 * Refuses to write outside the allowed roots — never touches arbitrary
 * filesystem locations.
 */
@Singleton
class WriteFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "write_file"
    override val description =
        "Write a text file under Mythara's allowed paths. Modes: create, overwrite, append."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute file path. Must be under an allowed root.")
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Text content to write. UTF-8.")
            })
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "'create' (fail if exists), 'overwrite' (replace), 'append'. Default 'create'.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pathStr = args["path"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val content = args["content"]?.jsonPrimitive?.contentOrNull().orEmpty()
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "create"
        if (pathStr.isBlank()) return ToolResult.fail("path must be non-empty")

        val file = File(pathStr).canonicalFile
        if (!isUnderAllowedRoot(file)) {
            return ToolResult.fail("path_not_allowed: $pathStr — must be under filesDir, cacheDir, externalFilesDir, or Downloads")
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                when (mode) {
                    "create" -> {
                        if (file.exists()) return@runCatching ToolResult.fail("exists: $pathStr (use mode=overwrite to replace)")
                        file.writeText(content, Charsets.UTF_8)
                    }
                    "overwrite" -> file.writeText(content, Charsets.UTF_8)
                    "append" -> file.appendText(content, Charsets.UTF_8)
                    else -> return@runCatching ToolResult.fail("mode must be create|overwrite|append")
                }
                ToolResult.ok(
                    """{"path":"${file.absolutePath.escape()}","bytes_written":${content.toByteArray(Charsets.UTF_8).size},"mode":"$mode"}""",
                )
            }.getOrElse { ToolResult.fail("write_error: ${it.message ?: it.javaClass.simpleName}") }
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
