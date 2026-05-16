package com.mythara.face

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Match a query face embedding against the precomputed
 * [ContactFaceIndex].
 *
 * Algorithm: brute-force cosine-distance scan across every stored
 * embedding (one per contact-photo source). For each contact, take
 * the BEST (lowest distance) match across all of their embeddings,
 * then return contacts whose best distance is below
 * [MATCH_THRESHOLD], sorted by best distance.
 *
 * Brute force is fine at the scale we care about: even 500 contacts
 * with 1 embedding each is 500 dot-products on 128-D vectors — a
 * fraction of a millisecond. If the index grows past 10k embeddings
 * we'd swap to ANN (e.g. Annoy / FAISS), but that's far beyond the
 * realistic per-user contact count.
 *
 * Threshold tuning: 0.65 is a reasonable starting point for
 * MobileFaceNet — gives ~5% false-positive rate per face on the LFW
 * benchmark. The match worker uses a stricter 0.55 for the silent
 * photo-override path (Phase 6 decision) so a poor capture doesn't
 * permanently alter a contact's avatar.
 */
@Singleton
class ContactFaceMatcher @Inject constructor(
    private val index: ContactFaceIndex,
) {
    suspend fun match(
        queryEmbedding: FloatArray,
        topK: Int = 3,
        threshold: Float = MATCH_THRESHOLD,
    ): List<MatchCandidate> {
        if (queryEmbedding.isEmpty()) return emptyList()
        val rows = runCatching { index.dao.listAll() }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()

        // best per nameKey
        val byContact = mutableMapOf<String, Float>()
        for (row in rows) {
            val emb = row.embedding.toEmbeddingFloats()
            if (emb.size != queryEmbedding.size) continue
            val d = FaceEmbedder.cosineDistance(queryEmbedding, emb)
            val cur = byContact[row.nameKey]
            if (cur == null || d < cur) byContact[row.nameKey] = d
        }
        return byContact.entries
            .filter { it.value <= threshold }
            .sortedBy { it.value }
            .take(topK)
            .map { (nameKey, distance) -> MatchCandidate(nameKey, distance) }
    }

    data class MatchCandidate(val nameKey: String, val distance: Float) {
        /** Convert cosine distance ∈ [0, 2] to a 0..1 confidence
         *  score that's intuitive in UI ("87% sure"). 0 distance →
         *  1.0; threshold-line distance → 0.5; far distance → ~0. */
        val confidence: Float
            get() = (1f - (distance / 2f)).coerceIn(0f, 1f)
    }

    companion object {
        /** Cosine-distance ceiling for normal "match" calls
         *  (Phase 6 tagging worker). ~5% FPR per face on LFW. */
        const val MATCH_THRESHOLD = 0.65f

        /** Stricter ceiling for the silent photo-override path so a
         *  poor face capture doesn't lock in as the contact's avatar.
         *  Captures that match below this also generate a
         *  ProfileCard render on the glasses. */
        const val STRICT_THRESHOLD = 0.55f
    }
}
