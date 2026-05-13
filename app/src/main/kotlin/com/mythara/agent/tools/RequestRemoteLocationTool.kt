package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.MemorySyncScheduler
import com.mythara.memory.devices.DeviceMessageEntity
import com.mythara.memory.devices.DeviceMessageHandler
import com.mythara.memory.devices.DeviceMessageKind
import com.mythara.memory.devices.DeviceMessageRepository
import com.mythara.memory.devices.DeviceMessageStatus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `request_remote_location` — ask another Mythara install to report
 * its current cached GPS fix. Communication runs over the existing
 * memory-sync repo: this device drops a `LocationRequest` into the
 * target's `device_messages/inbox/<target>.jsonl`, the target's next
 * sync pulls it, the handler answers with a `LocationResponse`, this
 * device's next sync pulls the response.
 *
 * Timing: the round-trip is bounded by the slower device's sync
 * cadence (default ~1h via MemorySyncWorker; user can also tap "sync
 * now"). To make the chat-side experience feel snappy, this tool:
 *
 *   1. Enqueues the LocationRequest locally + marks it pending.
 *   2. Fires an immediate one-shot sync via MemorySyncScheduler so
 *      the request lands in the repo right away.
 *   3. Polls the local DB for a matching response for up to
 *      [MAX_WAIT_MS]. During this window we kick periodic
 *      one-shot syncs so the response pulls in as soon as the
 *      target answers.
 *   4. Returns the latest fix when one lands, or "still waiting"
 *      with the request id when the wait runs out.
 *
 * The user can re-poll for the response later by asking Lumi
 * again — the same requestId is found and the latest matching
 * response is returned.
 *
 * Requires:
 *   - Memory sync configured + enabled (GitHub PAT + repo set)
 *   - Target device id (the receiver must be running Mythara
 *     against the same repo)
 */
@Singleton
class RequestRemoteLocationTool @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val deviceMessages: DeviceMessageRepository,
    private val scheduler: MemorySyncScheduler,
) : Tool {

    override val name: String = "request_remote_location"
    override val description: String =
        "Ask another of the user's Mythara installs (a phone / tablet / foldable signed into the same memory-sync repo) " +
            "to report its current cached GPS fix. Communicates over the memory repo, not over the network directly. " +
            "Returns the location when the target answers (typically within one sync cycle of the target device) " +
            "or returns the request id with a 'still waiting' status when no response arrives in the wait window. " +
            "Use when the user asks 'where's my other phone' / 'find my tablet' / similar. " +
            "Requires memory sync configured and the target device id (search the audit log or Settings → People for device IDs)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "target_device_id",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Stable device id of the Mythara install to query. Get this from the audit log's dev:xxxxxx tags or the People screen.",
                        )
                    },
                )
                put(
                    "max_wait_seconds",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Seconds to poll for the response before returning 'still waiting'. Default 30, max 120. Use 0 to fire-and-return immediately.",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("target_device_id"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val target = (args["target_device_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult(false, """{"error":"missing_target_device_id"}""")
        val maxWaitSec = ((args["max_wait_seconds"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 30L)
            .coerceIn(0L, 120L)

        val myId = deviceIdStore.id()
        if (target == myId) {
            return ToolResult(
                false,
                """{"error":"target_is_self","detail":"Can't request location from this device — use get_location instead."}""",
            )
        }

        val requestId = UUID.randomUUID().toString()
        val request = DeviceMessageEntity(
            id = requestId,
            tsMillis = System.currentTimeMillis(),
            fromDevice = myId,
            toDevice = target,
            kind = DeviceMessageKind.LOCATION_REQUEST,
            requestId = requestId,
            payloadJson = "{}",
            status = DeviceMessageStatus.PENDING,
        )
        deviceMessages.dao.insertIfAbsent(request)
        Log.d(TAG, "enqueued location_request $requestId → $target")

        // Fire a sync right away so the request lands in the repo.
        runCatching { scheduler.fireNow(force = true) }

        // Poll the local DB for a matching response. Re-kick the
        // sync periodically so the response pulls in promptly.
        val deadline = System.currentTimeMillis() + maxWaitSec * 1000L
        var lastSyncKick = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val responses = deviceMessages.dao.byRequestId(requestId)
                .filter { it.kind == DeviceMessageKind.LOCATION_RESPONSE && it.fromDevice == target }
            if (responses.isNotEmpty()) {
                val newest = responses.maxByOrNull { it.tsMillis } ?: continue
                return ToolResult(
                    true,
                    """{"ok":true,"request_id":${JsonPrimitive(requestId)},"target":${JsonPrimitive(target)},"response":${newest.payloadJson},"response_ts":${newest.tsMillis}}""",
                )
            }
            delay(POLL_INTERVAL_MS)
            // Re-kick the sync every RESYNC_INTERVAL_MS so we don't
            // wait for the next periodic tick.
            if (System.currentTimeMillis() - lastSyncKick > RESYNC_INTERVAL_MS) {
                runCatching { scheduler.fireNow(force = true) }
                lastSyncKick = System.currentTimeMillis()
            }
        }
        // No response in the wait window — the request stays in the
        // repo so a later poll (or the user asking again) can pick
        // up the response when it lands.
        return ToolResult(
            true,
            """{"ok":true,"status":"still_waiting","request_id":${JsonPrimitive(requestId)},"target":${JsonPrimitive(target)},"detail":"Request sent. Target device hasn't synced yet — ask again later to fetch the response."}""",
        )
    }

    companion object {
        private const val TAG = "Mythara/RemoteLoc"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val RESYNC_INTERVAL_MS = 8_000L
        private const val MAX_WAIT_MS = 30_000L
    }
}
