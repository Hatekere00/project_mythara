package com.mythara.secret.observe.speaker

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Facade over [SpeakerDao] for speaker enrollment + matching. The
 * x-vector lives on disk as little-endian float32 bytes (~512 bytes
 * per speaker at the typical 128-dim) so storage stays tiny — a
 * household of 5 enrolled speakers is ~2.5KB.
 *
 * Match semantics:
 *  - cosine similarity over L2-normalised x-vectors
 *  - best match across all enrolled speakers wins
 *  - if best similarity ≥ [MATCH_THRESHOLD] → tag as speaker:<name>
 *  - if not → no match (caller leaves the speaker facet off)
 *
 * Threshold 0.5 is a reasonable starting point for vosk-spk-0.4
 * vectors; Alpha Cephei's reference scripts use 0.45–0.55 depending
 * on the use case. Tighter (higher) = fewer false positives but more
 * "unknown" tags; looser (lower) = the reverse.
 */
@Singleton
class SpeakerVault @Inject constructor(private val dao: SpeakerDao) {

    /** A successful match returned from [matchBest]. */
    data class Match(val speaker: EnrolledSpeaker, val similarity: Float)

    fun observeAll(): Flow<List<EnrolledSpeaker>> = dao.observeAll()
    suspend fun listAll(): List<EnrolledSpeaker> = dao.listAll()
    suspend fun count(): Int = dao.listAll().size

    /**
     * Enrol a speaker by averaging one or more reference x-vectors.
     * If a speaker with the same name already exists, the existing
     * reference is averaged with the new samples (weighted by sample
     * count). This lets repeated enrollment "refine" the reference.
     */
    suspend fun enroll(name: String, samples: List<FloatArray>): EnrolledSpeaker {
        require(samples.isNotEmpty()) { "need at least one sample to enroll" }
        require(samples.all { it.isNotEmpty() }) { "empty x-vector in samples" }
        val dim = samples.first().size
        require(samples.all { it.size == dim }) { "inconsistent x-vector dimensions" }
        val newAvg = averaged(samples)
        val now = System.currentTimeMillis()

        val existing = dao.findByName(name)
        val entity = if (existing != null && existing.refVectorDim == dim) {
            val oldVec = decode(existing.refVectorBytes, dim)
            // Combined average weighted by prior + new sample counts.
            val totalNew = existing.enrollmentSampleCount + samples.size
            val combined = FloatArray(dim)
            for (i in 0 until dim) {
                combined[i] = (oldVec[i] * existing.enrollmentSampleCount + newAvg[i] * samples.size) / totalNew
            }
            val normalised = l2Normalise(combined)
            existing.copy(
                refVectorBytes = encode(normalised),
                enrollmentSampleCount = totalNew,
                enrolledAtMs = existing.enrolledAtMs, // first-enrolled timestamp stays
            )
        } else {
            EnrolledSpeaker(
                id = UUID.randomUUID().toString(),
                name = name,
                refVectorBytes = encode(l2Normalise(newAvg)),
                refVectorDim = dim,
                enrolledAtMs = now,
                lastMatchedAtMs = 0L,
                matchCount = 0,
                enrollmentSampleCount = samples.size,
            )
        }
        dao.upsert(entity)
        return entity
    }

    suspend fun delete(id: String) = dao.deleteById(id)
    suspend fun clear() = dao.clear()

    /**
     * Cosine-match [vec] against every enrolled speaker. Returns the
     * best match if it exceeds [threshold], else null. Cheap — linear
     * scan with no vector index, fine for the expected enrolled-set
     * size (typically <10 speakers per user).
     */
    suspend fun matchBest(vec: FloatArray, threshold: Float = MATCH_THRESHOLD): Match? {
        if (vec.isEmpty()) return null
        val normalised = l2Normalise(vec)
        val enrolled = dao.listAll()
        if (enrolled.isEmpty()) return null

        var best: EnrolledSpeaker? = null
        var bestSim = Float.NEGATIVE_INFINITY
        for (sp in enrolled) {
            if (sp.refVectorDim != vec.size) continue
            val ref = decode(sp.refVectorBytes, sp.refVectorDim)
            val sim = cosine(normalised, ref)
            if (sim > bestSim) {
                bestSim = sim
                best = sp
            }
        }
        return if (best != null && bestSim >= threshold) {
            Log.d(TAG, "match: ${best.name} sim=$bestSim")
            Match(best, bestSim)
        } else {
            Log.d(TAG, "no match (best ${best?.name ?: "none"} sim=$bestSim < $threshold)")
            null
        }
    }

    /** Bump match count + last-matched timestamp after a successful tag. */
    suspend fun recordMatch(id: String, ts: Long = System.currentTimeMillis()) {
        dao.bumpMatch(id, ts)
    }

    // ---- helpers ------------------------------------------------------------

    private fun averaged(samples: List<FloatArray>): FloatArray {
        val dim = samples.first().size
        val sum = FloatArray(dim)
        for (s in samples) for (i in 0 until dim) sum[i] += s[i]
        for (i in 0 until dim) sum[i] /= samples.size
        return sum
    }

    companion object {
        private const val TAG = "Mythara/SpkVault"
        /** Cosine threshold above which a match is accepted. */
        const val MATCH_THRESHOLD = 0.5f

        fun encode(vec: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in vec) buf.putFloat(f)
            return buf.array()
        }

        fun decode(bytes: ByteArray, dim: Int): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(dim) { buf.float }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "vector dim mismatch ${a.size} vs ${b.size}" }
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na.toDouble()) * sqrt(nb.toDouble())
            return if (denom == 0.0) 0f else (dot / denom).toFloat()
        }

        fun l2Normalise(v: FloatArray): FloatArray {
            var n = 0f
            for (f in v) n += f * f
            val norm = sqrt(n.toDouble()).toFloat()
            if (norm == 0f) return v.copyOf()
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}
