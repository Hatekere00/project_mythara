package com.mythara.memory.graph

/**
 * Controlled vocabulary for GraphEdge.predicate.
 *
 * Why this exists: GraphTurnExtractor accepts whatever the LLM emits
 * for a predicate. Across runs that drifts wildly — `likes`, `enjoys`,
 * `is_a_fan_of`, `loves` all show up for the same conceptual
 * relationship. The graph becomes hard to query ("show everyone who
 * lives in Vancouver" misses every `lives_at` / `resides_in` / `from`
 * row) and the People + Insights surfaces render the same predicate
 * five different ways.
 *
 * This module pins the vocabulary to ~30 canonical predicates and
 * provides [normalize] that maps freeform LLM output into one of them.
 * Falls through to the original (lowercased, snake-cased) string when
 * no canonical match is found — we'd rather keep a non-canonical
 * predicate than discard the fact.
 *
 * Two surfaces hit normalize:
 *
 *   1. WRITE-TIME — GraphTurnExtractor calls normalize() right
 *      before constructing an EdgeRecord. New edges are always
 *      canonical from now on.
 *   2. BACKFILL — PredicateNormalizationRunner walks every existing
 *      edge once and rewrites the predicate column. Idempotent;
 *      safe to re-run.
 *
 * The vocabulary is intentionally small. Adding a predicate is
 * cheap (one entry in [CANONICAL] + any synonyms in [SYNONYMS]);
 * dropping one is harder because it would orphan existing rows.
 * Stay conservative — only add when a new concept genuinely
 * doesn't fit any existing predicate's semantics.
 */
object PredicateVocabulary {

    /** Canonical predicate set. Every normalized edge.predicate is
     *  one of these — or, when no match exists, the original lower-
     *  snake input (preserved for forward-compat). */
    val CANONICAL: Set<String> = linkedSetOf(
        // ─── Relationships (people-people) ───────────────────────
        "knows",            // generic acquaintance
        "friend_of",        // personal friendship
        "family_of",        // generic kinship (use specific when known)
        "married_to",
        "partner_of",       // romantic, unmarried
        "parent_of",
        "child_of",
        "sibling_of",
        "colleague_of",     // works-with relationship
        "manager_of",       // hierarchy
        "reports_to",       // hierarchy (inverse of manager_of)

        // ─── Preferences / sentiment ─────────────────────────────
        "likes",
        "dislikes",
        "loves",            // intense positive (people, things, activities)
        "hates",            // intense negative
        "prefers",          // choice over alternatives
        "interested_in",    // topic affinity

        // ─── Work / affiliation ──────────────────────────────────
        "works_at",         // employer / organization
        "founded",          // created / started (company, project)
        "owns",
        "member_of",        // group / club / org
        "studies",          // educational pursuit
        "attended",         // past event / school

        // ─── Location / temporal ─────────────────────────────────
        "lives_in",
        "born_in",
        "located_at",       // generic place attachment
        "visited",          // past presence
        "scheduled_for",    // future time
        "happened_at",      // past time

        // ─── Communication / interaction ─────────────────────────
        "contacted",        // messaged / called / met IRL
        "mentioned",        // referenced in conversation

        // ─── Possession / state ──────────────────────────────────
        "has",              // generic possession / attribute
        "plays",            // sports / games / instruments
        "speaks",           // languages
    )

    /** Synonym → canonical map. Keys are pre-normalised (lowercase
     *  snake_case) so the lookup is a single hash check. Add entries
     *  here when you see a recurring LLM-emitted phrasing that fits
     *  an existing canonical predicate but doesn't match it
     *  textually. */
    private val SYNONYMS: Map<String, String> = mapOf(
        // knows / friend
        "acquainted_with" to "knows",
        "is_friends_with" to "friend_of",
        "friends_with" to "friend_of",
        "best_friend_of" to "friend_of",
        "buddy_of" to "friend_of",
        "close_to" to "friend_of",

        // family
        "related_to" to "family_of",
        "relative_of" to "family_of",
        "is_married_to" to "married_to",
        "wife_of" to "married_to",
        "husband_of" to "married_to",
        "spouse_of" to "married_to",
        "boyfriend_of" to "partner_of",
        "girlfriend_of" to "partner_of",
        "dating" to "partner_of",
        "is_dating" to "partner_of",
        "seeing" to "partner_of",
        "engaged_to" to "partner_of",
        "father_of" to "parent_of",
        "mother_of" to "parent_of",
        "dad_of" to "parent_of",
        "mom_of" to "parent_of",
        "son_of" to "child_of",
        "daughter_of" to "child_of",
        "kid_of" to "child_of",
        "brother_of" to "sibling_of",
        "sister_of" to "sibling_of",

        // work
        "boss_of" to "manager_of",
        "supervisor_of" to "manager_of",
        "managed_by" to "reports_to",
        "supervisor_is" to "reports_to",
        "coworker_of" to "colleague_of",
        "co_worker_of" to "colleague_of",
        "coworkers_with" to "colleague_of",
        "team_member_with" to "colleague_of",
        "works_with" to "colleague_of",
        "employed_by" to "works_at",
        "works_for" to "works_at",
        "is_employed_by" to "works_at",
        "job_at" to "works_at",
        "employee_of" to "works_at",
        "started" to "founded",
        "created" to "founded",
        "co_founded" to "founded",
        "owner_of" to "owns",
        "possesses" to "owns",
        "is_member_of" to "member_of",
        "belongs_to" to "member_of",
        "part_of" to "member_of",
        "alumnus_of" to "attended",
        "alumna_of" to "attended",
        "graduated_from" to "attended",
        "went_to" to "attended",
        "studied_at" to "attended",
        "studying" to "studies",
        "learning" to "studies",
        "is_studying" to "studies",

        // preferences
        "enjoys" to "likes",
        "is_a_fan_of" to "likes",
        "fan_of" to "likes",
        "favorite" to "likes",
        "favourite" to "likes",
        "into" to "likes",
        "really_likes" to "loves",
        "adores" to "loves",
        "obsessed_with" to "loves",
        "cant_stand" to "hates",
        "can_not_stand" to "hates",
        "doesnt_like" to "dislikes",
        "does_not_like" to "dislikes",
        "not_a_fan_of" to "dislikes",
        "would_rather" to "prefers",
        "curious_about" to "interested_in",
        "passionate_about" to "interested_in",
        "into_topic" to "interested_in",

        // location
        "lives_at" to "lives_in",
        "resides_in" to "lives_in",
        "resides_at" to "lives_in",
        "from" to "lives_in",         // ambiguous (could be born_in) — go with lives_in
        "based_in" to "lives_in",
        "located_in" to "located_at",
        "at_location" to "located_at",
        "born_at" to "born_in",
        "place_of_birth" to "born_in",
        "native_of" to "born_in",
        "has_visited" to "visited",
        "went_to_place" to "visited",
        "travelled_to" to "visited",
        "traveled_to" to "visited",

        // temporal
        "happening_at" to "scheduled_for",
        "planned_for" to "scheduled_for",
        "scheduled_at" to "scheduled_for",
        "occurred_at" to "happened_at",
        "took_place_at" to "happened_at",

        // communication
        "messaged" to "contacted",
        "texted" to "contacted",
        "called" to "contacted",
        "phoned" to "contacted",
        "spoke_to" to "contacted",
        "talked_to" to "contacted",
        "met_with" to "contacted",
        "met" to "contacted",
        "referenced" to "mentioned",
        "talked_about" to "mentioned",
        "discussed" to "mentioned",

        // possession / state
        "owns_a" to "owns",
        "has_a" to "has",
        "carries" to "has",
        "plays_the" to "plays",
        "plays_a" to "plays",
        "speaks_the" to "speaks",
        "fluent_in" to "speaks",
        "knows_language" to "speaks",
    )

    /** Normalise an LLM-emitted predicate into a canonical form.
     *
     *  Pipeline (each step short-circuits on hit):
     *    1. Lowercase + trim + collapse whitespace → snake_case.
     *    2. Exact match against [CANONICAL] → keep as-is.
     *    3. Exact match against [SYNONYMS] → return the mapped
     *       canonical.
     *    4. Strip leading "is_" / "was_" / "be_" qualifier — many
     *       drift forms ("is_married_to", "was_employed_by") boil
     *       down to a canonical once that prefix is removed.
     *    5. Strip trailing "_of" then retry canonical/synonyms;
     *       handles "owner" → "owns" via the SYNONYMS table.
     *    6. Return the cleaned input untouched (preserves info
     *       even when we can't classify).
     *
     *  Edge case — empty / whitespace input returns "unknown" so
     *  the row still has a primary-key-friendly value. */
    fun normalize(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9_]"), "")
            .trim('_')
        if (cleaned.isBlank()) return "unknown"

        // Step 2 — exact canonical.
        if (cleaned in CANONICAL) return cleaned
        // Step 3 — exact synonym.
        SYNONYMS[cleaned]?.let { return it }

        // Step 4 — strip leading qualifier and retry.
        val noLeading = listOf("is_", "was_", "be_", "being_").firstNotNullOfOrNull { prefix ->
            cleaned.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }
        if (noLeading != null) {
            if (noLeading in CANONICAL) return noLeading
            SYNONYMS[noLeading]?.let { return it }
        }

        // Step 5 — strip trailing "_of" and retry; also try with "_to".
        listOf("_of", "_to", "_with", "_in").forEach { suffix ->
            if (cleaned.endsWith(suffix)) {
                val stem = cleaned.removeSuffix(suffix)
                if (stem in CANONICAL) return stem
                SYNONYMS[stem]?.let { return it }
            }
        }

        // Step 6 — give up, preserve the cleaned form.
        return cleaned
    }

    /** True when [predicate] is one of the canonical terms. Used by
     *  the backfill runner to count "needs normalization" vs
     *  "already canonical" without re-running normalize twice. */
    fun isCanonical(predicate: String): Boolean = predicate in CANONICAL
}
