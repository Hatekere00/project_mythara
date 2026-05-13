package com.mythara.memory.devices

import android.util.Log
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.github.GitHubClient
import com.mythara.memory.github.GitHubClient.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push / pull side of the device-to-device messaging protocol.
 *
 * Layout in the memory repo:
 *
 *   device_messages/
 *     inbox/<recipient_id>.jsonl   — append-only, one wire-record
 *                                    per line, sent BY any device,
 *                                    received BY recipient_id
 *     cursors/<reader_id>.json     — last seen message id per peer
 *                                    so re-pulls are idempotent
 *
 * Push flow ([flush]):
 *   1. Pull every "pending" outbox row from the local DB
 *   2. Group by toDevice
 *   3. For each group: read the remote inbox file, append + merge,
 *      writeFileMerging back. Conflict-tolerant — concurrent writes
 *      from two senders both retain via line union.
 *   4. Mark local rows as "sent"
 *
 * Pull flow ([fetch]):
 *   1. Read THIS device's inbox file
 *   2. Parse lines into wire records, skip any whose id is already
 *      in the local DB (deduped on insert by primary key)
 *   3. Insert new ones with status "received"
 *   4. Call [DeviceMessageHandler.handleReceived] for each new row
 *   5. Update the cursor file with the highest message id per peer
 *
 * Concurrency: two devices writing to the same inbox at the same
 * time → GitHub Contents API can race. writeFileMerging refetches +
 * line-unions on 409. Lost-write window is the round-trip; in
 * practice both lines survive the next sync.
 */
@Singleton
class DeviceMessageSync @Inject constructor(
    private val repo: DeviceMessageRepository,
    private val deviceIdStore: DeviceIdStore,
    private val handler: DeviceMessageHandler,
) {
    @Serializable
    data class Wire(
        val id: String,
        val ts: Long,
        val from: String,
        val to: String,
        val kind: String,
        val reqId: String? = null,
        val payload: String = "{}",
    )

    @Serializable
    data class CursorFile(
        /** peer-device-id → last seen message id we processed from them. */
        val lastSeenIds: Map<String, String> = emptyMap(),
        val updatedMs: Long = 0L,
    )

    data class Report(
        val pushed: Int,
        val pulled: Int,
        val handled: Int,
        val pushFailures: Int,
        val pullFailures: Int,
    )

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    /**
     * Full round-trip: push pending outbox rows, then pull this
     * device's inbox + run handlers. Called from MemorySync.runSync
     * once per top-level sync invocation.
     */
    suspend fun exchange(
        client: GitHubClient,
        owner: String,
        repo_: String,
        branch: String,
    ): Report {
        val myId = deviceIdStore.id()
        // PUSH pending → FETCH inbox + handle (may generate new
        // outbox rows like LocationResponse) → PUSH again so the
        // handler's responses land in the same sync. Without the
        // second push a request would take TWO full sync cycles
        // to round-trip; with it, ONE cycle suffices.
        val pushedFirst = flushOutbox(client, owner, repo_, branch)
        val (pulled, handled) = fetchInbox(client, owner, repo_, branch, myId)
        val pushedSecond = flushOutbox(client, owner, repo_, branch)
        return Report(
            pushed = pushedFirst.first + pushedSecond.first,
            pulled = pulled,
            handled = handled,
            pushFailures = pushedFirst.second + pushedSecond.second,
            pullFailures = 0,
        )
    }

    /**
     * Read this device's inbox + cursor; emit new rows + handle them.
     * Public for "poll only" callers (we don't always need to push
     * — e.g. waiting for a location response).
     */
    suspend fun fetchInbox(
        client: GitHubClient,
        owner: String,
        repoName: String,
        branch: String,
        readerDeviceId: String,
    ): Pair<Int, Int> {
        val cursorPath = "device_messages/cursors/$readerDeviceId.json"
        val inboxPath = "device_messages/inbox/$readerDeviceId.jsonl"

        val inboxRead = client.readFile(owner, repoName, inboxPath)
        if (inboxRead !is Outcome.Ok) return 0 to 0
        val lines = inboxRead.value.text.lineSequence().filter { it.isNotBlank() }
        val wires = lines.mapNotNull {
            runCatching { json.decodeFromString(Wire.serializer(), it) }.getOrNull()
        }.toList()
        if (wires.isEmpty()) return 0 to 0

        // Load existing cursor so we can advance it after handling.
        val cursorRead = client.readFile(owner, repoName, cursorPath)
        var cursor = if (cursorRead is Outcome.Ok) {
            runCatching { json.decodeFromString(CursorFile.serializer(), cursorRead.value.text) }
                .getOrDefault(CursorFile())
        } else CursorFile()

        var pulled = 0
        var handled = 0
        val newCursorById = cursor.lastSeenIds.toMutableMap()
        for (wire in wires) {
            // Skip rows already in the local DB (dedup on PK insert).
            if (repo.dao.byId(wire.id) != null) {
                // But still advance cursor — could be we processed
                // this on a previous device and just re-pulled.
                newCursorById[wire.from] = wire.id
                continue
            }
            val entity = DeviceMessageEntity(
                id = wire.id,
                tsMillis = wire.ts,
                fromDevice = wire.from,
                toDevice = wire.to,
                kind = wire.kind,
                requestId = wire.reqId,
                payloadJson = wire.payload,
                status = DeviceMessageStatus.RECEIVED,
            )
            val inserted = repo.dao.insertIfAbsent(entity)
            if (inserted >= 0) {
                pulled++
                // Run the handler synchronously inside the same sync
                // call. Handlers SHOULD be short — see
                // DeviceMessageHandler doc.
                runCatching { handler.handleReceived(entity) }
                    .onSuccess { handled++ }
                    .onFailure {
                        Log.w(TAG, "handler threw for ${wire.id}: ${it.message}")
                        repo.dao.setStatus(wire.id, DeviceMessageStatus.FAILED, it.message)
                    }
            }
            newCursorById[wire.from] = wire.id
        }

        // Write cursor back so a future re-pull doesn't redo handler
        // work. We never DELETE from the inbox file — that would
        // need a sync-adapter authority; the cursor is enough to
        // make re-pulls idempotent.
        cursor = cursor.copy(
            lastSeenIds = newCursorById,
            updatedMs = System.currentTimeMillis(),
        )
        // Cursor uses the read-fetch-write pattern with the freshly-
        // read sha so concurrent cursor updates from the same device
        // (two app instances on the same Mythara install? unlikely
        // but possible) don't 409. Whatever the merge — for cursors
        // we just want OURS to win; this is a single-author file.
        val cursorSha = if (cursorRead is Outcome.Ok) cursorRead.value.sha else null
        runCatching {
            client.writeFile(
                owner = owner,
                repo = repoName,
                path = cursorPath,
                text = json.encodeToString(CursorFile.serializer(), cursor),
                commitMessage = "mythara: cursor advance for $readerDeviceId",
                branch = branch,
                previousSha = cursorSha,
            )
        }
        return pulled to handled
    }

    /**
     * Push all pending outbox rows. Returns (pushed, failures).
     */
    private suspend fun flushOutbox(
        client: GitHubClient,
        owner: String,
        repoName: String,
        branch: String,
    ): Pair<Int, Int> {
        val pending = repo.dao.listByStatus(DeviceMessageStatus.PENDING, limit = 200)
        if (pending.isEmpty()) return 0 to 0
        var ok = 0
        var failed = 0
        val grouped = pending.groupBy { it.toDevice }
        for ((targetId, msgs) in grouped) {
            val path = "device_messages/inbox/$targetId.jsonl"
            // Read remote first to get sha + existing content for union.
            val existing = client.readFile(owner, repoName, path)
            val existingText = if (existing is Outcome.Ok) existing.value.text else ""
            val existingSha = if (existing is Outcome.Ok) existing.value.sha else null
            val newLines = msgs.joinToString("\n") { entity ->
                json.encodeToString(Wire.serializer(), entity.toWire())
            }
            val merged = lineUnionMerge(existingText, newLines)
            val result = runCatching {
                client.writeFileMerging(
                    owner = owner,
                    repo = repoName,
                    path = path,
                    text = merged,
                    commitMessage = "mythara: device-msg → $targetId (+${msgs.size})",
                    branch = branch,
                    previousSha = existingSha,
                    merge = ::lineUnionMerge,
                )
            }.getOrElse { Outcome.Error(-1, it.message ?: "writeFileMerging threw") }
            when (result) {
                is Outcome.Ok -> {
                    for (m in msgs) repo.dao.setStatus(m.id, DeviceMessageStatus.SENT)
                    ok += msgs.size
                }
                else -> {
                    Log.w(TAG, "push to $targetId failed: $result")
                    for (m in msgs) repo.dao.setStatus(m.id, DeviceMessageStatus.FAILED, "push failed")
                    failed += msgs.size
                }
            }
        }
        return ok to failed
    }

    /**
     * Conflict-merge: union of unique JSONL lines, preserving order
     * by ts within each. Used when a remote write happened between
     * our read + write.
     */
    private fun lineUnionMerge(remote: String, localAppend: String): String {
        val remoteLines = remote.lineSequence().filter { it.isNotBlank() }.toList()
        val appendLines = localAppend.lineSequence().filter { it.isNotBlank() }.toList()
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        // Use the message id as the dedup key — same id in both
        // sides means we're seeing the same write twice.
        fun keyOf(s: String): String =
            runCatching { json.decodeFromString(Wire.serializer(), s).id }.getOrNull() ?: s
        for (l in remoteLines) {
            val k = keyOf(l)
            if (seen.add(k)) out.add(l)
        }
        for (l in appendLines) {
            val k = keyOf(l)
            if (seen.add(k)) out.add(l)
        }
        return out.joinToString("\n")
    }

    private fun DeviceMessageEntity.toWire(): Wire = Wire(
        id = id,
        ts = tsMillis,
        from = fromDevice,
        to = toDevice,
        kind = kind,
        reqId = requestId,
        payload = payloadJson,
    )

    companion object {
        private const val TAG = "Mythara/DeviceSync"
    }
}
