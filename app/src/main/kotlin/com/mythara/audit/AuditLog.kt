package com.mythara.audit

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only record of every agent-initiated side-effect on the device.
 *
 * Why a separate Room DB rather than a table in HistoryDb:
 *   - Independent migrations. The audit table will grow and evolve as we
 *     log more facets (cost, mood at time of call, etc); we don't want
 *     to ride alongside chat-history migrations.
 *   - "Forget everything" / "clear audit" should be a single-DB drop.
 *   - Privacy stance: the user can wipe their action log without wiping
 *     their chat history, and vice versa.
 *
 * The entry shape is small on purpose. We store a preview of args/result
 * (≤200 chars) — long tool outputs (read_screen JSON dumps, etc.) would
 * balloon the DB. The full output is already in the chat-history row
 * for the corresponding tool message if forensic detail is ever needed.
 *
 * `kind` distinguishes:
 *   - "tool_call"     — the agent fired a tool (the dominant case)
 *   - "tool_redirect" — registry redirected a deprecated composer name
 *   - "user_canceled" — confirmation gate denied
 *   - "subagent"      — spawn_agent started a worker
 *   - "system"        — anything else worth surfacing (mood flip,
 *                       persona-sync, growth-loop run)
 */
@Entity(tableName = "audit_entries")
data class AuditEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "ts_millis") val tsMillis: Long,
    val kind: String,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "args_preview") val argsPreview: String? = null,
    @ColumnInfo(name = "result_ok") val resultOk: Boolean = true,
    @ColumnInfo(name = "result_preview") val resultPreview: String? = null,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long = 0L,
    /** Free-form note for non-tool entries (e.g. "subagent spawned: research"). */
    val note: String? = null,
)

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_entries ORDER BY ts_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<AuditEntry>>

    @Query("SELECT * FROM audit_entries ORDER BY ts_millis DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 500): List<AuditEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditEntry): Long

    @Query("DELETE FROM audit_entries")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM audit_entries")
    suspend fun count(): Int

    @Query("DELETE FROM audit_entries WHERE ts_millis < :cutoffMillis")
    suspend fun pruneBefore(cutoffMillis: Long): Int
}

@Database(entities = [AuditEntry::class], version = 1, exportSchema = false)
abstract class AuditDb : RoomDatabase() {
    abstract fun entries(): AuditDao
}

@Singleton
class AuditRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: AuditDb = Room.databaseBuilder(ctx, AuditDb::class.java, "mythara_audit.db").build()
    val dao: AuditDao = db.entries()
}

/**
 * Single chokepoint for "record that this happened" calls. Used from
 * [com.mythara.agent.ToolRegistry.execute] for every tool call (and
 * elsewhere as the surface area grows). Logging happens on Dispatchers.IO
 * so a slow disk doesn't stall the tool path.
 *
 * Failures here are swallowed — we'd rather drop an audit row than crash
 * the agent loop because of a transient SQLite hiccup.
 */
@Singleton
class AuditLogger @Inject constructor(private val repo: AuditRepository) {

    suspend fun logToolCall(
        toolName: String,
        argsJson: String,
        ok: Boolean,
        output: String,
        latencyMs: Long,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                repo.dao.insert(
                    AuditEntry(
                        tsMillis = System.currentTimeMillis(),
                        kind = "tool_call",
                        toolName = toolName,
                        argsPreview = preview(argsJson),
                        resultOk = ok,
                        resultPreview = preview(output),
                        latencyMs = latencyMs,
                    ),
                )
            }
        }
    }

    suspend fun logRedirect(fromName: String, toName: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                repo.dao.insert(
                    AuditEntry(
                        tsMillis = System.currentTimeMillis(),
                        kind = "tool_redirect",
                        toolName = toName,
                        argsPreview = "from=$fromName",
                        resultOk = true,
                        resultPreview = null,
                        latencyMs = 0L,
                        note = "model called deprecated name; auto-redirected",
                    ),
                )
            }
        }
    }

    suspend fun logUserCanceled(toolName: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                repo.dao.insert(
                    AuditEntry(
                        tsMillis = System.currentTimeMillis(),
                        kind = "user_canceled",
                        toolName = toolName,
                        resultOk = false,
                        resultPreview = "user declined the confirmation prompt",
                    ),
                )
            }
        }
    }

    suspend fun logSystem(note: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                repo.dao.insert(
                    AuditEntry(
                        tsMillis = System.currentTimeMillis(),
                        kind = "system",
                        resultOk = true,
                        note = note,
                    ),
                )
            }
        }
    }

    suspend fun clear() {
        runCatching { withContext(Dispatchers.IO) { repo.dao.clear() } }
    }

    /**
     * Trim entries older than [ageMillis]. Wired into the growth/
     * background pass once we want bounded growth; for now exposed but
     * unscheduled — the audit table is small even at 1k+ entries.
     */
    suspend fun prune(ageMillis: Long) {
        runCatching {
            withContext(Dispatchers.IO) {
                repo.dao.pruneBefore(System.currentTimeMillis() - ageMillis)
            }
        }
    }

    /** Truncate long strings; the previews are display-only. */
    private fun preview(s: String?): String? {
        if (s == null) return null
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= PREVIEW_CHARS) trimmed else trimmed.take(PREVIEW_CHARS) + "…"
    }

    companion object {
        private const val PREVIEW_CHARS = 200
    }
}
