package com.mythara.agent.tools

import android.content.Context
import android.provider.Settings
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.ShizukuService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `list_cosmetic_options` — surface the cosmetic allowlist + current
 * values so the agent knows what it can change.
 *
 * Reads each setting via `Settings.System.getString` /
 * `Settings.Secure.getString` / `Settings.Global.getString`. These
 * reads do NOT require Shizuku — any app can read most system
 * settings. Writes (via [CosmeticTool]) are the ones that need the
 * shell-UID grant.
 */
@Singleton
class ListCosmeticTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuService,
) : Tool {
    override val name = "list_cosmetic_options"
    override val description = "List allowlisted cosmetic keys, their value-domain hints, and current device values."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val state = shizuku.state(context.packageManager).name
        val entries = CosmeticTool.ALLOWLIST.entries.joinToString(",") { (key, spec) ->
            val current = runCatching {
                when (spec.namespace) {
                    "system" -> Settings.System.getString(context.contentResolver, spec.settingKey)
                    "secure" -> Settings.Secure.getString(context.contentResolver, spec.settingKey)
                    "global" -> Settings.Global.getString(context.contentResolver, spec.settingKey)
                    else -> null
                }
            }.getOrNull().orEmpty()
            """{"key":"$key","namespace":"${spec.namespace}","setting":"${spec.settingKey}","current":"${current.escape()}","hint":"${spec.valueHint.escape()}"}"""
        }
        return ToolResult.ok(
            """{"shizuku_state":"$state","options":[$entries]}""",
        )
    }

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
