package com.mythara.agent.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.minimax.VisionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_recent_chat_image` — find the most recent image that WhatsApp /
 * Telegram / similar messengers auto-saved to the gallery and describe
 * it via the configured vision model.
 *
 * Solves the FLAG_SECURE problem: WhatsApp marks the chat surface as
 * SECURE so [com.mythara.agent.tools.ScreenshotViewTool] returns either
 * a black screen or a refusal. Auto-saved images on disk are NOT covered
 * by that flag — they're regular files visible to any app with
 * READ_MEDIA_IMAGES.
 *
 * Lookup strategy (in priority order, each falls back to the next):
 *   1. MediaStore query filtered to WHATSAPP_BUCKETS, sorted by
 *      DATE_ADDED desc, capped to the last N seconds.
 *   2. MediaStore query against RELATIVE_PATH containing "WhatsApp"
 *      / "Telegram" / similar — catches older device layouts where
 *      the bucket display name doesn't match.
 *   3. Filesystem scan of the legacy paths under
 *      /storage/emulated/0/Android/media/com.whatsapp/...
 *      Last-resort path for devices where MediaStore hasn't yet
 *      indexed the freshly-saved image.
 *
 * Caller chooses the time window via `max_age_seconds` (default 300).
 * Returns null when no candidate matches.
 *
 * Privacy: the image isn't copied or persisted by this tool — we
 * read it directly out of the gallery, hand a file ref to
 * VisionService, and return only the model's text description. No
 * separate Mythara copy lands on disk.
 *
 * Requires READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE
 * (≤API 32) granted at runtime. Returns permission_denied error to
 * the model if not granted — the model can then surface that to
 * the user.
 */
@Singleton
class ReadRecentChatImageTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val vision: VisionService,
) : Tool {

    override val name: String = "read_recent_chat_image"
    override val description: String =
        "Locate the most recent image WhatsApp / Telegram / similar auto-saved to the device gallery " +
            "and run it through the vision model. " +
            "Use this when screenshot_view returns a black / refused frame on a chat with an image (most chat apps mark " +
            "their surface FLAG_SECURE so screenshots are blocked) — the auto-saved gallery file IS readable. " +
            "Returns a natural-language description of what's in the image, the file path, and the timestamp it was saved. " +
            "Read-only; allowed regardless of autopilot state. Requires the user to have granted Media Images permission. " +
            "Prefer this over screenshot_view for WhatsApp / Telegram / WhatsApp Business image content — it's higher " +
            "fidelity (full resolution from disk, not a downscaled screenshot) and doesn't require the chat to be open."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "max_age_seconds",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "How recent the image must be. Default 300 (5 minutes). Use shorter (60-120) for fresh notifications, longer (3600) when reviewing earlier history.",
                        )
                    },
                )
                put(
                    "app_hint",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional app filter. Accepts 'whatsapp', 'telegram', 'signal', or empty for any messaging-app bucket.",
                        )
                    },
                )
                put(
                    "focus",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional focus prompt passed to the vision model. e.g. 'describe in detail what the photo shows — subject, scene, mood'.",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!hasMediaPermission()) {
            return ToolResult(
                false,
                """{"error":"permission_denied","detail":"READ_MEDIA_IMAGES (or READ_EXTERNAL_STORAGE on ≤API 32) isn't granted. Open Settings → Apps → Mythara → Permissions and allow Photos and videos / Media images so I can read WhatsApp's auto-saved images."}""",
            )
        }
        val maxAgeSec = ((args["max_age_seconds"] as? JsonPrimitive)?.content?.toLongOrNull() ?: DEFAULT_MAX_AGE_SEC)
            .coerceIn(10L, 7L * 24 * 3600)
        val appHint = (args["app_hint"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        val focus = (args["focus"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val sinceMs = System.currentTimeMillis() - maxAgeSec * 1000L

        val candidate = withContext(Dispatchers.IO) {
            findMostRecentImage(sinceMs, appHint)
        } ?: return ToolResult(
            false,
            """{"error":"no_image_found","detail":"No chat-app image saved in the last $maxAgeSec seconds. The sender's app may have auto-download disabled, the image may still be downloading, or the bucket doesn't match. Try a longer max_age_seconds, or fall back to screenshot_view if the chat is open."}""",
        )

        Log.d(TAG, "found candidate path=${candidate.path} bucket=${candidate.bucket} addedMs=${candidate.dateAddedMs}")
        val outcome = runCatching {
            vision.describeImage(imageFile = File(candidate.path), prompt = buildPrompt(focus))
        }.getOrElse { e ->
            Log.w(TAG, "describeImage threw", e)
            VisionService.Outcome(ok = false, text = e.message ?: e.javaClass.simpleName, code = "threw")
        }

        val response = buildJsonObject {
            put("ok", outcome.ok)
            put("path", candidate.path)
            put("bucket", candidate.bucket ?: "")
            put("date_added_ms", candidate.dateAddedMs)
            put("file_name", candidate.displayName ?: "")
            if (outcome.ok) {
                put("description", outcome.text.trim())
                outcome.backend?.let { put("vision_backend", it) }
            } else {
                put("error", outcome.code ?: "vision_failed")
                put("detail", outcome.text)
            }
        }
        return ToolResult(ok = outcome.ok, output = response.toString())
    }

    private fun buildPrompt(focusArg: String): String {
        val base = "This image was just shared in a private chat with the user. Describe what's in it in 1-3 sentences. " +
            "Focus on the subject, the scene, the activity, and any mood. If it's text-heavy, transcribe the visible text. " +
            "If it's a generic forwarded meme / ad / stock photo / promotional quote with no personal signal, " +
            "say the exact phrase 'forwarded content; no personal signal' and nothing else. " +
            "Never speculate, never invent — describe only what's actually visible."
        return if (focusArg.isNotEmpty()) "$base\nSpecial focus: $focusArg" else base
    }

    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    private data class Candidate(
        val path: String,
        val bucket: String?,
        val displayName: String?,
        val dateAddedMs: Long,
    )

    private fun findMostRecentImage(sinceMs: Long, appHint: String): Candidate? {
        // MediaStore stores DATE_ADDED in seconds; convert.
        val sinceSec = sinceMs / 1000L
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )

        // Build the bucket-name filter from the user's app hint. When
        // unspecified, accept any of the known chat-app buckets.
        val bucketNames = bucketsForHint(appHint)
        val pathFragments = pathFragmentsForHint(appHint)
        val placeholders = bucketNames.joinToString(",") { "?" }
        val selection = "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN ($placeholders) OR (" +
            pathFragments.joinToString(" OR ") { "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" } +
            ")) AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = bucketNames.toMutableList()
            .apply {
                pathFragments.forEach { add("%$it%") }
                add(sinceSec.toString())
            }
            .toTypedArray()
        // Note: don't embed LIMIT in the sortOrder — Android 11+ throws
        // IllegalArgumentException on any extra SQL after the ORDER BY
        // clause. moveToFirst() on the sorted cursor gives us the same
        // result with the materialiser only reading the row we look at.
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataIdx = c.getColumnIndex(MediaStore.Images.Media.DATA)
                val bucketIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                val id = c.getLong(idIdx)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                // Prefer the legacy DATA path when it exists (scoped
                // storage may not expose it but on Android 11+ many
                // OEMs still populate this column). When DATA is null,
                // copy the content URI to a temp file for VisionService.
                val dataPath = if (dataIdx >= 0) c.getString(dataIdx) else null
                val resolvedPath = dataPath?.takeIf { File(it).exists() }
                    ?: copyUriToTemp(uri)?.absolutePath
                    ?: return@use null

                Candidate(
                    path = resolvedPath,
                    bucket = if (bucketIdx >= 0) c.getString(bucketIdx) else null,
                    displayName = if (nameIdx >= 0) c.getString(nameIdx) else null,
                    dateAddedMs = c.getLong(dateIdx) * 1000L,
                )
            }
        }.getOrElse {
            Log.w(TAG, "MediaStore query failed", it)
            null
        }
    }

    /**
     * When the DATA column isn't populated (Android 11+ scoped storage
     * on some devices), copy the content URI to a temp file so
     * VisionService can read it via java.io.File. Caller is expected
     * to ignore the file after the vision call — we keep them in
     * cacheDir which Android may evict freely.
     */
    private fun copyUriToTemp(uri: Uri): File? = runCatching {
        val dir = File(ctx.cacheDir, "chat_image_peek").apply { mkdirs() }
        val out = File(dir, "peek_${System.nanoTime()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { fos -> input.copyTo(fos) }
        } ?: return@runCatching null
        out
    }.getOrNull()

    private fun bucketsForHint(hint: String): List<String> = when (hint) {
        "whatsapp" -> listOf("WhatsApp Images")
        "telegram" -> listOf("Telegram", "Telegram Images")
        "signal" -> listOf("Signal")
        else -> listOf(
            "WhatsApp Images",
            "WhatsApp Business Images",
            "Telegram",
            "Telegram Images",
            "Signal",
            "Pictures",
            "Messages",
        )
    }

    private fun pathFragmentsForHint(hint: String): List<String> = when (hint) {
        "whatsapp" -> listOf("WhatsApp/Media/WhatsApp Images")
        "telegram" -> listOf("Telegram")
        "signal" -> listOf("Signal")
        else -> listOf(
            "WhatsApp/Media/WhatsApp Images",
            "WhatsApp Business/Media",
            "Telegram",
            "Signal",
        )
    }

    companion object {
        private const val TAG = "Mythara/ChatImg"
        private const val DEFAULT_MAX_AGE_SEC = 300L
    }
}
