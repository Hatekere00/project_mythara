package com.mythara.agent.queue

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted queue of incoming notifications that should trigger an
 * agent turn (favorite auto-reply or smart triage).
 *
 * Lifecycle of a row:
 *
 *   ENQUEUED ──▶ PENDING ─┐
 *                          ├──▶ IN_FLIGHT ─┬──▶ HANDLED   (terminal)
 *                          │               ├──▶ FAILED    (attempts < MAX → back to PENDING with backoff)
 *                          │               └──▶ SKIPPED   (terminal — agent declined / NOSURFACE)
 *                          └──▶ SKIPPED              (filter rejected post-enqueue)
 *
 * The queue exists because the previous fire-and-forget path lost
 * notifications in three places: (a) the SharedFlow's 16-slot
 * DROP_OLDEST buffer, (b) network failures mid-turn with no retry,
 * (c) process death between "notification arrived" and "turn complete".
 * Persisting the intent to reply BEFORE running the agent means
 * crashes are recoverable on the next launch — see [requeueStuck].
 *
 * Dedup: every row has a [dedupKey] (SHA-256 of pkg|sender|body|
 * minute-bucket). Android fires onNotificationPosted multiple times
 * for a single message (typing indicator updates, read receipts);
 * dedup stops the same body from generating five turns. The minute
 * bucket means a *repeat* of the same body 90 seconds later is
 * intentionally treated as a new message, not a duplicate.
 */
@Entity(
    tableName = "pending_replies",
    indices = [
        Index(value = ["dedup_key"], unique = true),
        Index(value = ["status", "next_attempt_ms"]),
    ],
)
data class PendingReplyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "ts_millis") val tsMillis: Long,
    @ColumnInfo(name = "pkg") val pkg: String,
    @ColumnInfo(name = "sender_title") val senderTitle: String,
    @ColumnInfo(name = "body") val body: String,
    /** [PendingReplyRoute.name] — FAVORITE_REPLY or TRIAGE. */
    @ColumnInfo(name = "route") val route: String,
    /** The fully-built turn prompt — exactly what AgentRunner.submit gets fed. */
    @ColumnInfo(name = "turn_text") val turnText: String,
    /** [PendingReplyStatus.name]. */
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    /**
     * Earliest time the drain should pick this row up again. For
     * PENDING rows freshly enqueued this is just tsMillis; for
     * FAILED-but-retryable rows it gets bumped by the exponential
     * backoff before the row flips back to PENDING.
     */
    @ColumnInfo(name = "next_attempt_ms") val nextAttemptMs: Long,
    /**
     * Set when the drain picks the row up so [requeueStuck] can spot
     * rows that have been "in flight" longer than any plausible turn
     * (process must have died) and reset them to PENDING.
     */
    @ColumnInfo(name = "in_flight_since_ms") val inFlightSinceMs: Long? = null,
)

enum class PendingReplyRoute {
    /** Sender is in [com.mythara.data.FavoritesStore] — reply with their tone. */
    FAVORITE_REPLY,

    /** Sender isn't a favorite but smart-triage is on — let the agent decide. */
    TRIAGE,
}

enum class PendingReplyStatus {
    PENDING,
    IN_FLIGHT,
    HANDLED,
    FAILED,
    SKIPPED,
}

@Dao
interface PendingReplyDao {

    /**
     * Insert if no row with the same dedupKey already exists. Returns
     * the new row id, or -1 when the insert was ignored (dup).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: PendingReplyEntity): Long

    @Query(
        """
        SELECT * FROM pending_replies
        WHERE status = :pending AND next_attempt_ms <= :nowMs
        ORDER BY next_attempt_ms ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun listReady(
        pending: String = PendingReplyStatus.PENDING.name,
        nowMs: Long,
        limit: Int = 16,
    ): List<PendingReplyEntity>

    @Query(
        """
        UPDATE pending_replies
        SET status = :inFlight, in_flight_since_ms = :nowMs, attempts = attempts + 1
        WHERE id = :id AND status = :pending
        """,
    )
    suspend fun markInFlight(
        id: Long,
        nowMs: Long,
        inFlight: String = PendingReplyStatus.IN_FLIGHT.name,
        pending: String = PendingReplyStatus.PENDING.name,
    ): Int

    @Query("UPDATE pending_replies SET status = :handled, in_flight_since_ms = NULL WHERE id = :id")
    suspend fun markHandled(id: Long, handled: String = PendingReplyStatus.HANDLED.name)

    @Query(
        """
        UPDATE pending_replies
        SET status = :skipped, in_flight_since_ms = NULL, last_error = :reason
        WHERE id = :id
        """,
    )
    suspend fun markSkipped(id: Long, reason: String, skipped: String = PendingReplyStatus.SKIPPED.name)

    /** Mark for retry — back to PENDING with bumped next_attempt_ms. */
    @Query(
        """
        UPDATE pending_replies
        SET status = :pending, in_flight_since_ms = NULL,
            last_error = :reason, next_attempt_ms = :nextMs
        WHERE id = :id
        """,
    )
    suspend fun markForRetry(
        id: Long,
        reason: String,
        nextMs: Long,
        pending: String = PendingReplyStatus.PENDING.name,
    )

    /** Terminal failure — out of retries. */
    @Query(
        """
        UPDATE pending_replies
        SET status = :failed, in_flight_since_ms = NULL, last_error = :reason
        WHERE id = :id
        """,
    )
    suspend fun markFailedTerminal(id: Long, reason: String, failed: String = PendingReplyStatus.FAILED.name)

    /**
     * Any IN_FLIGHT row older than the stuck threshold is assumed to
     * have been orphaned by a process death. Reset it to PENDING with
     * a 0ms backoff so the next drain pass picks it up immediately.
     */
    @Query(
        """
        UPDATE pending_replies
        SET status = :pending, in_flight_since_ms = NULL,
            last_error = COALESCE(last_error, '') || ' [recovered from stuck IN_FLIGHT]'
        WHERE status = :inFlight AND (in_flight_since_ms IS NULL OR in_flight_since_ms < :cutoffMs)
        """,
    )
    suspend fun requeueStuck(
        cutoffMs: Long,
        pending: String = PendingReplyStatus.PENDING.name,
        inFlight: String = PendingReplyStatus.IN_FLIGHT.name,
    ): Int

    @Query("SELECT COUNT(*) FROM pending_replies WHERE status = :pending OR status = :inFlight")
    suspend fun pendingCount(
        pending: String = PendingReplyStatus.PENDING.name,
        inFlight: String = PendingReplyStatus.IN_FLIGHT.name,
    ): Int

    /** Live PENDING + IN_FLIGHT + FAILED-with-retries-left rows so the
     *  user can see what's queued and manually nuke stale entries. */
    @Query(
        """
        SELECT * FROM pending_replies
        WHERE status IN (:pending, :inFlight)
        ORDER BY ts_millis DESC
        LIMIT :limit
        """,
    )
    fun observeActive(
        limit: Int = 100,
        pending: String = PendingReplyStatus.PENDING.name,
        inFlight: String = PendingReplyStatus.IN_FLIGHT.name,
    ): kotlinx.coroutines.flow.Flow<List<PendingReplyEntity>>

    /** User manually cancels a queued notification reply. */
    @Query(
        """
        UPDATE pending_replies
        SET status = :skipped, in_flight_since_ms = NULL,
            last_error = COALESCE(last_error, '') || ' [user-dismissed]'
        WHERE id = :id
        """,
    )
    suspend fun userDismiss(id: Long, skipped: String = PendingReplyStatus.SKIPPED.name)

    /** GC rows older than the retention window so the table doesn't grow forever. */
    @Query("DELETE FROM pending_replies WHERE status IN (:handled, :failed, :skipped) AND ts_millis < :cutoffMs")
    suspend fun gcOldTerminal(
        cutoffMs: Long,
        handled: String = PendingReplyStatus.HANDLED.name,
        failed: String = PendingReplyStatus.FAILED.name,
        skipped: String = PendingReplyStatus.SKIPPED.name,
    ): Int
}

@Database(entities = [PendingReplyEntity::class], version = 1, exportSchema = false)
abstract class PendingReplyDb : RoomDatabase() {
    abstract fun dao(): PendingReplyDao
}

@Singleton
class PendingReplyRepository @Inject constructor(@ApplicationContext ctx: Context) {
    private val db: PendingReplyDb = Room.databaseBuilder(
        ctx, PendingReplyDb::class.java, "mythara_pending_replies.db",
    ).build()
    val dao: PendingReplyDao = db.dao()
}
