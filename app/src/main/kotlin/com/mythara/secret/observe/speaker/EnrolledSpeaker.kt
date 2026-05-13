package com.mythara.secret.observe.speaker

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One enrolled speaker. The user records 10-ish seconds of their voice
 * via the enrollment UI; the Vosk speaker model emits an x-vector per
 * utterance during that recording, and the average becomes
 * [refVectorBytes]. At Observe time every transcript's x-vector is
 * cosine-matched against the enrolled set — best match above
 * [SpeakerVault.MATCH_THRESHOLD] gets tagged as `speaker:<name>` on
 * the resulting [com.mythara.secret.observe.vault.LearningEntity].
 *
 * Fields:
 *  - id                       UUID; primary key
 *  - name                     human-readable label ("ankur", "sarah"); unique
 *  - refVectorBytes           averaged x-vector, little-endian float32
 *  - refVectorDim             vector dimensionality (~128 for vosk-spk-0.4 today)
 *  - enrolledAtMs             when the user first enrolled
 *  - lastMatchedAtMs          most recent successful cosine match — UI shows
 *                             "last heard: 2 hours ago" so the user can spot
 *                             a drift / re-enrollment opportunity
 *  - matchCount               count of utterances that matched this speaker
 *  - enrollmentSampleCount    how many recordings averaged into refVector;
 *                             more samples = more robust reference
 */
@Entity(
    tableName = "enrolled_speakers",
    indices = [
        Index(value = ["name"], unique = true),
    ],
)
data class EnrolledSpeaker(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "ref_vector_bytes") val refVectorBytes: ByteArray,
    @ColumnInfo(name = "ref_vector_dim") val refVectorDim: Int,
    @ColumnInfo(name = "enrolled_at_ms") val enrolledAtMs: Long,
    @ColumnInfo(name = "last_matched_at_ms") val lastMatchedAtMs: Long = 0L,
    @ColumnInfo(name = "match_count") val matchCount: Int = 0,
    @ColumnInfo(name = "enrollment_sample_count") val enrollmentSampleCount: Int = 1,
) {
    // Room requires explicit equals/hashCode for entities with array fields —
    // the auto-generated equals compares ByteArray identity, not content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnrolledSpeaker) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
