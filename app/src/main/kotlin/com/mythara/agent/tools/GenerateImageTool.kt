package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `generate_image` — generate an image from a text prompt.
 *
 * Calls the MiniMax `image_generation` endpoint (reuses the user's
 * configured MiniMax API key from [SettingsStore]). The resulting
 * image URL or base64 is saved to filesDir/canvas/images/ and the
 * absolute path returned. The agent can then pass that path into
 * [RenderCanvasTool] (via an `<img src="file://…">`) to display it.
 *
 * If the user hasn't configured a MiniMax key, or if MiniMax image
 * gen is unavailable on their tier, the tool returns an actionable
 * error so the agent can suggest the configuration step rather than
 * pretending success.
 */
@Singleton
class GenerateImageTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) : Tool {
    override val name = "generate_image"
    override val description =
        "Generate an image from a text prompt via MiniMax. Returns a local file path the canvas can display."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("prompt", buildJsonObject {
                put("type", "string")
                put("description", "What to generate. Concrete + visual; e.g. 'sunset over a forest, painterly'.")
            })
            put("style", buildJsonObject {
                put("type", "string")
                put("description", "Optional style hint (e.g. 'photoreal', 'watercolor', 'low-poly'). Folded into prompt.")
            })
            put("aspect", buildJsonObject {
                put("type", "string")
                put("description", "Optional aspect: '1:1' (default), '16:9', '9:16', '4:3', '3:4'.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawPrompt = args["prompt"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (rawPrompt.isBlank()) return ToolResult.fail("prompt must be non-empty")
        val style = args["style"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val aspect = args["aspect"]?.jsonPrimitive?.contentOrNull()?.trim()?.ifBlank { null } ?: "1:1"
        val prompt = if (style.isBlank()) rawPrompt else "$rawPrompt, $style"

        val apiKey = settings.apiKeyFlow().first().orEmpty()
        if (apiKey.isBlank()) {
            return ToolResult.fail(
                "miniMax_key_not_configured: open Settings → MiniMax and paste your API key, then retry.",
            )
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                // MiniMax image-generation endpoint shape (as of 2025):
                //   POST https://api.minimax.io/v1/image_generation
                //   { "model": "image-01", "prompt": "...",
                //     "aspect_ratio": "1:1", "response_format": "url",
                //     "n": 1 }
                val body = buildJsonObject {
                    put("model", "image-01")
                    put("prompt", prompt)
                    put("aspect_ratio", aspect)
                    put("response_format", "url")
                    put("n", 1)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val req = Request.Builder()
                    .url("https://api.minimax.io/v1/image_generation")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use ToolResult.fail(
                            "image_gen_failed: http ${resp.code} — your tier may not include image generation.",
                        )
                    }
                    val text = resp.body?.string().orEmpty()
                    val urls = parseImageUrls(text)
                    if (urls.isEmpty()) return@use ToolResult.fail("image_gen_failed: no image url in response — $text")
                    val firstUrl = urls.first()
                    val downloaded = downloadImage(firstUrl)
                        ?: return@use ToolResult.fail("download_failed: $firstUrl")
                    ToolResult.ok(
                        """{"path":"${downloaded.absolutePath}","url":"$firstUrl","prompt":"${prompt.escape()}"}""",
                    )
                }
            }.getOrElse { ToolResult.fail("image_gen_error: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** Extract image URLs from the MiniMax response JSON.
     *  Shape: `{"data":{"image_urls":["https://...", ...]}, ...}` */
    private fun parseImageUrls(json: String): List<String> = runCatching {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val data = parsed["data"]?.jsonObject ?: return@runCatching emptyList<String>()
        val urls = data["image_urls"]?.jsonArray ?: return@runCatching emptyList<String>()
        urls.mapNotNull { it.jsonPrimitive.contentOrNull() }
    }.getOrElse { emptyList() }

    private fun downloadImage(url: String): File? = runCatching {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            val ext = url.substringAfterLast('.', "png").take(4).lowercase()
                .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
            val dir = File(context.filesDir, "canvas/images").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.$ext")
            file.writeBytes(bytes)
            file
        }
    }.getOrNull()

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = JSONObject.quote(this).removeSurrounding("\"")
}
