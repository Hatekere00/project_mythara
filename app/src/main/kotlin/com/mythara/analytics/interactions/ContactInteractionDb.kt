package com.mythara.analytics.interactions

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
 * Per-contact interaction log (Capability Expansion v3 Phase 7).
 *
 * One row per discrete interaction between the user and a contact,
 * with the kind (message, call, physical meet via glasses, mention)
 * + source attribution (notification observer, agent action, glasses
 * face-match, etc.) + optional location + optional cross-references
 * back to the originating Lifeline / Audit row.
 *
 * Why a separate DB rather than reusing the LearningVault:
 *   • Schema is rigid, not faceted — queries like "latest 20
 *     interactions with Sarah" are a single indexed SELECT.
 *   • Doesn't intermingle with the agent's "knowledge" rows; this
 *     is a behavioural log.
 *   • Can be wiped / exported independently for privacy.
 *
 * [ConversationMessageWriter] (existing) writes to both the vault
 * (analytics flow) AND this table going forward. A one-shot
 * [InteractionBackfillWorker] populates historical rows from the
 * vault + AuditRepository on first run.
 */
@Entity(
    tableName = "contact_interactions",
    indices = [Index(value = ["name_key", "ts_ms"])],
)
data class ContactInteractionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "ts_ms") val tsMs: Long,
    /** `message_sent` / `message_received` / `call_outgoing` /
     *  `call_incoming` / `physical_meet` / `mention` */
    @ColumnInfo(name = "kind") val kind: String,
    /** `glasses` / `notification` / `manual` / `agent_action` */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "lat") val lat: Double? = null,
    @ColumnInfo(name = "lng") val lng: Double? = null,
    @ColumnInfo(name = "place_label") val placeLabel: String? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "ref_lifeline_id") val refLifelineId: Long? = null,
    @ColumnInfo(name = "ref_audit_id") val refAuditId: Long? = null,
)

@Dao
interface ContactInteractionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ContactInteractionRow): Long

    @Query(
        """
        SELECT * FROM contact_interactions
        WHERE name_key = :nameKey
        ORDER BY ts_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listForContact(nameKey: String, limit: Int = 50): List<ContactInteractionRow>

    @Query(
        """
        SELECT * FROM contact_interactions
        WHERE name_key = :nameKey AND kind = :kind
        ORDER BY ts_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listForContactByKind(
        nameKey: String,
        kind: String,
        limit: Int = 50,
    ): List<ContactInteractionRow>

    /** All physical-meet rows across all contacts, newest first.
     *  Backs the GlassesMemoryScreen's "recent meetings" section. */
    @Query(
        """
        SELECT * FROM contact_interactions
        WHERE kind = 'physical_meet'
        ORDER BY ts_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun listPhysicalMeets(limit: Int = 100): List<ContactInteractionRow>

    @Query("SELECT COUNT(*) FROM contact_interactions")
    suspend fun count(): Int

    @Query("DELETE FROM contact_interactions WHERE name_key = :nameKey")
    suspend fun deleteForContact(nameKey: String): Int

    @Query("DELETE FROM contact_interactions")
    suspend fun clear()
}

@Database(entities = [ContactInteractionRow::class], version = 1, exportSchema = false)
abstract class ContactInteractionDb : RoomDatabase() {
    abstract fun dao(): ContactInteractionDao
}

@Singleton
class ContactInteractionRepository @Inject constructor(
    @ApplicationContext ctx: Context,
) {
    private val db: ContactInteractionDb = Room.databaseBuilder(
        ctx, ContactInteractionDb::class.java, "mythara_interactions.db",
    ).fallbackToDestructiveMigration().build()
    val dao: ContactInteractionDao = db.dao()
}
