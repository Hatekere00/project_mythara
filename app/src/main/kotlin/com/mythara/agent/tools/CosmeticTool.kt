package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.ShizukuService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `apply_cosmetic` — apply a non-invasive Android system tweak via
 * Shizuku.
 *
 * Shizuku gives Mythara shell-UID access to `settings put …` (and
 * a few other privileged APIs) without root and without modifying
 * `/system`. The set of keys this tool will apply is hard-coded
 * to a "non-invasive cosmetic" allowlist — font scale, dark mode,
 * accent colour, gesture-nav style, animation scales, blue-light
 * filter. We do NOT expose package install/uninstall, location
 * spoofing, network rerouting, or anything that could brick the
 * device or surveil the user beyond what Mythara already does.
 *
 * When Shizuku is not installed / not running / permission denied,
 * the tool returns a setup-card JSON the agent should surface to
 * the user verbatim. Do not invent alternate setup steps.
 *
 * See also: [ListCosmeticTool] which lists the allowlist + current
 * values so the agent can negotiate before applying.
 */
@Singleton
class CosmeticTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuService,
) : Tool {
    override val name = "apply_cosmetic"
    override val description =
        "Apply a non-invasive Android cosmetic change (font scale, dark mode, accent color, etc.) via Shizuku."
    override val requiresConfirmation: Boolean = true

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("key", buildJsonObject {
                put("type", "string")
                put("description", "Allowlisted cosmetic key. Call list_cosmetic_options to see what's allowed.")
            })
            put("value", buildJsonObject {
                put("type", "string")
                put("description", "Value to set. Domain depends on the key (e.g. font_scale = '1.2', ui_night_mode = '2').")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("key"), JsonPrimitive("value"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val value = args["value"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (key.isBlank() || value.isBlank()) return ToolResult.fail("key + value required")

        val spec = ALLOWLIST[key] ?: return ToolResult.ok(
            """{"status":"blocked","reason":"key not in cosmetic allowlist","key":"${key.escape()}","hint":"call list_cosmetic_options for the supported set"}""",
        )
        if (!spec.validValue(value)) {
            return ToolResult.fail(
                "value_invalid: '$value' for key '$key' — expected ${spec.valueHint}",
            )
        }

        val state = shizuku.state(context.packageManager)
        when (state) {
            ShizukuService.State.NotInstalled -> return ToolResult.ok(setupCard("not_installed"))
            ShizukuService.State.NotRunning -> return ToolResult.ok(setupCard("not_running"))
            ShizukuService.State.PermissionDenied -> {
                val granted = shizuku.requestPermission()
                if (!granted) return ToolResult.ok(setupCard("permission_denied"))
            }
            ShizukuService.State.Ready -> Unit
        }

        // settings put NAMESPACE KEY VALUE  (NAMESPACE = system|secure|global)
        val cmd = "settings put ${spec.namespace} ${spec.settingKey} $value"
        return withContext(Dispatchers.IO) {
            val result = shizuku.execShell(cmd)
                ?: return@withContext ToolResult.fail("shizuku_exec_failed")
            if (result.exit == 0) {
                ToolResult.ok(
                    """{"status":"ok","key":"$key","value":"$value","namespace":"${spec.namespace}"}""",
                )
            } else {
                ToolResult.fail(
                    "settings_put_failed: exit=${result.exit} stderr=${result.err.take(200)}",
                )
            }
        }
    }

    private fun setupCard(reason: String): String = when (reason) {
        "not_installed" -> """{"status":"setup_required","reason":"shizuku_not_installed","steps":[
            "1. Install 'Shizuku' from the Play Store (free, by RikkaW).",
            "2. Open the app and follow the on-screen instructions to bootstrap via wireless debugging (Android 11+) or adb.",
            "3. Once Shizuku says it's running, ask me to apply the cosmetic again."
        ]}"""
        "not_running" -> """{"status":"setup_required","reason":"shizuku_not_running","steps":[
            "1. Open the Shizuku app.",
            "2. Tap 'Start via wireless debugging' (Android 11+) or use the adb command shown.",
            "3. Wait for the green 'Running' status.",
            "4. Ask me to apply the cosmetic again."
        ]}"""
        "permission_denied" -> """{"status":"setup_required","reason":"permission_denied","steps":[
            "1. Open the Shizuku app and confirm Mythara is allowed.",
            "2. Or re-trigger the permission prompt by asking me to apply the cosmetic again."
        ]}"""
        else -> """{"status":"setup_required","reason":"$reason"}"""
    }.trimIndent()

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")

    /** Cosmetic key → settings-namespace + validation spec. */
    data class Spec(
        val namespace: String,        // system | secure | global
        val settingKey: String,
        val valueHint: String,
        val validValue: (String) -> Boolean,
    )

    companion object {
        internal val ALLOWLIST: Map<String, Spec> = mapOf(
            "font_scale" to Spec(
                namespace = "system",
                settingKey = "font_scale",
                valueHint = "float 0.85..1.30 (e.g. '1.10' = 10% larger)",
                validValue = { it.toFloatOrNull()?.let { f -> f in 0.85f..1.30f } == true },
            ),
            "ui_night_mode" to Spec(
                namespace = "secure",
                settingKey = "ui_night_mode",
                valueHint = "0=auto, 1=light, 2=dark",
                validValue = { it in setOf("0", "1", "2") },
            ),
            "accent_color" to Spec(
                namespace = "secure",
                settingKey = "accent_color",
                valueHint = "ARGB hex like '#FFB187FF' or '#B187FF' (vendor-specific; may no-op on stock Android)",
                validValue = { it.matches(Regex("^#?[0-9a-fA-F]{6,8}$")) },
            ),
            "navigation_mode" to Spec(
                namespace = "secure",
                settingKey = "navigation_mode",
                valueHint = "0=3-button, 1=2-button, 2=gesture",
                validValue = { it in setOf("0", "1", "2") },
            ),
            "window_animation_scale" to Spec(
                namespace = "global",
                settingKey = "window_animation_scale",
                valueHint = "float 0.0..3.0 (0 = animations off, 1 = default)",
                validValue = { it.toFloatOrNull()?.let { f -> f in 0.0f..3.0f } == true },
            ),
            "transition_animation_scale" to Spec(
                namespace = "global",
                settingKey = "transition_animation_scale",
                valueHint = "float 0.0..3.0",
                validValue = { it.toFloatOrNull()?.let { f -> f in 0.0f..3.0f } == true },
            ),
            "animator_duration_scale" to Spec(
                namespace = "global",
                settingKey = "animator_duration_scale",
                valueHint = "float 0.0..3.0",
                validValue = { it.toFloatOrNull()?.let { f -> f in 0.0f..3.0f } == true },
            ),
            "night_display_activated" to Spec(
                namespace = "secure",
                settingKey = "night_display_activated",
                valueHint = "0=off, 1=on (blue-light filter)",
                validValue = { it in setOf("0", "1") },
            ),
            "display_density_forced" to Spec(
                namespace = "secure",
                settingKey = "display_density_forced",
                valueHint = "integer ppi like '440' (reboot may be needed). Use '' (empty) to reset.",
                validValue = { it.isEmpty() || it.toIntOrNull()?.let { i -> i in 200..720 } == true },
            ),
        )
    }
}
