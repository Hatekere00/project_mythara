package com.mythara.analytics

import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Post-turn lexical extractor that updates Mythara's running
 * estimate of the user's (and named contacts') personality
 * dimensions, values, and preferences after every chat turn.
 *
 * Runs alongside [com.mythara.agent.GraphTurnExtractor] (per the
 * v2 plan, GraphTurnExtractor invokes this after entity/edge
 * extraction). Default-on; no toggle. The user can wipe trait
 * records via the memory inspector since everything is written
 * to [LearningVault] with facet `kind:trait`.
 *
 * ## Method
 *
 * Lexical-only, regex-based, no ML deps — matches the philosophy
 * of [com.mythara.agent.mood.LexicalMoodScorer] which already
 * underpins the live wallpaper mood pipeline. Crude but cheap and
 * deterministic; surface trait records with low confidence (`conf
 * = 0.25..0.45`) so the agent + UI know not to over-claim from
 * any single turn. Estimates strengthen via dedup-reinforcement
 * in LearningVault — the same trait observation accumulates
 * `seen+=1` instead of duplicating, and UI consumers can weight
 * by `seen`.
 *
 * Five dimension families (extending the existing mood pipeline):
 *
 *   1. **Big Five** — Openness / Conscientiousness / Extraversion /
 *      Agreeableness / Neuroticism. LIWC-style category frequency
 *      → trait delta. Curated word lists per dimension; positive
 *      vs negative buckets per trait.
 *
 *   2. **Schwartz values** — achievement, security, hedonism,
 *      benevolence, self-direction, power, conformity, tradition.
 *      Single-pass keyword match.
 *
 *   3. **Preferences** — extracted from explicit predicate
 *      phrases ("I like X", "I love Y", "I hate Z", "I prefer A
 *      to B"). Predicate + object → preference record.
 *
 *   4. **Concerns** — what the user worries / thinks about most
 *      ("I'm worried about", "I keep thinking about"). Long-tail
 *      topic indicators.
 *
 *   5. **Communication style** — formal / casual / verbose /
 *      terse / emoji-heavy / question-asking / declarative. Self-
 *      report style (we're not analyzing turn structure here;
 *      that's the contact-analytics worker's job).
 *
 * ## Contact targeting
 *
 * When the turn mentions a contact ("Sarah likes jazz"), trait
 * records are written with `target:contact:<nameKey>` instead of
 * `target:self`. Goes through the same NameNormalizer the graph
 * extractor uses (case-insensitive, alias-resolved). PeopleScreen
 * + ContactProfileRepository surface them.
 */
@Singleton
class PersonaTraitExtractor @Inject constructor(
    private val vault: LearningVault,
) {

    /**
     * Process one conversation turn — the user's message + the
     * assistant's reply text + any structured signals upstream
     * extraction has already gathered.
     */
    suspend fun extract(
        userText: String,
        assistantText: String,
        mentionedContacts: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val text = userText.trim()
        if (text.isBlank()) return
        val lowered = text.lowercase()
        val tokens = lowered.tokenize()

        // ── Big Five — self ──
        for ((trait, score) in scoreBigFive(tokens)) {
            if (abs(score) < 0.5f) continue
            val polarity = if (score > 0) "high" else "low"
            val mag = abs(score).coerceAtMost(3f) / 3f
            vault.add(
                content = "self: $trait $polarity (lexical score=${"%.2f".format(score)})",
                tier = Tier.Semantic,
                src = "chat-persona",
                facets = listOf(
                    "kind:trait",
                    "dim:big5",
                    "trait:$trait",
                    "polarity:$polarity",
                    "target:self",
                ),
                conf = (0.25 + 0.20 * mag).coerceIn(0.25, 0.55),
                now = nowMs,
            )
        }

        // ── Schwartz values — self ──
        for ((value, hits) in scoreValues(tokens)) {
            if (hits == 0) continue
            vault.add(
                content = "self: values $value (lexical hits=$hits)",
                tier = Tier.Semantic,
                src = "chat-persona",
                facets = listOf(
                    "kind:trait",
                    "dim:values",
                    "value:$value",
                    "target:self",
                ),
                conf = (0.30 + 0.05 * hits).coerceIn(0.30, 0.65),
                now = nowMs,
            )
        }

        // ── Preferences — self ──
        for (pref in extractPreferences(lowered)) {
            vault.add(
                content = "self: ${pref.predicate} ${pref.obj}",
                tier = Tier.Semantic,
                src = "chat-persona",
                facets = listOf(
                    "kind:trait",
                    "dim:preference",
                    "predicate:${pref.predicate}",
                    "object:${pref.obj.take(48)}",
                    "target:self",
                ),
                conf = 0.55,
                now = nowMs,
            )
        }

        // ── Concerns — self ──
        for (concern in extractConcerns(lowered)) {
            vault.add(
                content = "self: worry — $concern",
                tier = Tier.Semantic,
                src = "chat-persona",
                facets = listOf(
                    "kind:trait",
                    "dim:concern",
                    "topic:${concern.take(48)}",
                    "target:self",
                ),
                conf = 0.40,
                now = nowMs,
            )
        }

        // ── Communication style — self ──
        val style = inferCommunicationStyle(text)
        for ((styleDim, label) in style) {
            vault.add(
                content = "self: comm-style $styleDim=$label",
                tier = Tier.Semantic,
                src = "chat-persona",
                facets = listOf(
                    "kind:trait",
                    "dim:comm-style",
                    "axis:$styleDim",
                    "label:$label",
                    "target:self",
                ),
                conf = 0.35,
                now = nowMs,
            )
        }

        // ── Contact-targeted traits ──
        for (contact in mentionedContacts.map { normalizeContact(it) }.distinct()) {
            // Preferences attributed to a contact via patterns like
            // "Sarah loves jazz" / "mom hates phone calls". This is
            // a coarse pass; the agent's structured-extraction pipeline
            // will catch more.
            for (pref in extractContactPreferences(lowered, contact)) {
                vault.add(
                    content = "$contact: ${pref.predicate} ${pref.obj}",
                    tier = Tier.Semantic,
                    src = "chat-persona",
                    facets = listOf(
                        "kind:trait",
                        "dim:preference",
                        "predicate:${pref.predicate}",
                        "object:${pref.obj.take(48)}",
                        "target:contact:$contact",
                    ),
                    conf = 0.50,
                    now = nowMs,
                )
            }
        }
    }

    // ── Big Five lexical scoring ──

    /** Per-dimension scoring: sum of (pos-word hits) - (neg-word hits).
     *  Returned scores can be negative (= leaning low on the trait) or
     *  positive (= leaning high). */
    private fun scoreBigFive(tokens: Set<String>): Map<String, Float> {
        val scores = mutableMapOf<String, Float>()
        for ((trait, lex) in BIG_FIVE_LEX) {
            val pos = lex.high.count { it in tokens }
            val neg = lex.low.count { it in tokens }
            scores[trait] = (pos - neg).toFloat()
        }
        return scores
    }

    private fun scoreValues(tokens: Set<String>): Map<String, Int> =
        VALUES_LEX.mapValues { (_, words) -> words.count { it in tokens } }

    // ── Preference extraction ──

    data class Preference(val predicate: String, val obj: String)

    private val prefPredicates = mapOf(
        "likes" to Regex("""\b(?:i (?:really )?(?:like|love|enjoy|adore)|i'?m into|i prefer)\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
        "dislikes" to Regex("""\b(?:i (?:really )?(?:hate|dislike|can'?t stand|loathe))\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
        "wants" to Regex("""\b(?:i want|i'?d like|i'm hoping for|i wish for)\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
        "avoids" to Regex("""\b(?:i avoid|i stay away from|i steer clear of)\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
    )

    private fun extractPreferences(lowered: String): List<Preference> {
        val out = mutableListOf<Preference>()
        for ((pred, regex) in prefPredicates) {
            regex.findAll(lowered).forEach { m ->
                val obj = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.length in 2..40 } ?: return@forEach
                out += Preference(pred, obj)
            }
        }
        return out.distinctBy { it.predicate to it.obj }
    }

    private fun extractContactPreferences(lowered: String, contact: String): List<Preference> {
        val out = mutableListOf<Preference>()
        val name = Regex.escape(contact.lowercase())
        val patterns = mapOf(
            "likes" to Regex("""\b$name (?:really )?(?:likes|loves|enjoys|adores)\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
            "dislikes" to Regex("""\b$name (?:really )?(?:hates|dislikes|can'?t stand)\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
            "wants" to Regex("""\b$name wants\s+([a-z][a-z0-9' \-]{1,40}?)(?:[.,;!?]|$)"""),
        )
        for ((pred, regex) in patterns) {
            regex.findAll(lowered).forEach { m ->
                val obj = m.groupValues.getOrNull(1)?.trim()?.takeIf { it.length in 2..40 } ?: return@forEach
                out += Preference(pred, obj)
            }
        }
        return out.distinctBy { it.predicate to it.obj }
    }

    // ── Concerns ──

    private val concernPatterns = listOf(
        Regex("""\b(?:i(?:'m| am) (?:really )?worried about|i keep thinking about|i can'?t stop thinking about|i'm stressed about|i'm anxious about)\s+([a-z][a-z0-9' \-]{2,40}?)(?:[.,;!?]|$)"""),
        Regex("""\b(?:my (?:biggest|main) (?:concern|worry|fear) is)\s+([a-z][a-z0-9' \-]{2,40}?)(?:[.,;!?]|$)"""),
    )

    private fun extractConcerns(lowered: String): List<String> {
        val out = mutableListOf<String>()
        for (regex in concernPatterns) {
            regex.findAll(lowered).forEach { m ->
                m.groupValues.getOrNull(1)?.trim()?.takeIf { it.length in 3..40 }?.let { out += it }
            }
        }
        return out.distinct()
    }

    // ── Communication style ──

    private fun inferCommunicationStyle(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val len = text.length
        out["length"] = when {
            len < 30 -> "terse"
            len > 240 -> "verbose"
            else -> "medium"
        }
        val emojiCount = text.count { c ->
            Character.UnicodeBlock.of(c) in EMOJI_BLOCKS
        }
        if (emojiCount > 0) out["emoji"] = if (emojiCount > 3) "heavy" else "light"
        val questions = text.count { it == '?' }
        if (questions > 0) out["mode"] = if (questions >= 2) "inquisitive" else "asking"
        val exclam = text.count { it == '!' }
        if (exclam >= 2) out["energy"] = "high"
        val formal = FORMAL_MARKERS.count { text.contains(it, ignoreCase = true) }
        val casual = CASUAL_MARKERS.count { text.contains(it, ignoreCase = true) }
        when {
            formal > casual + 1 -> out["register"] = "formal"
            casual > formal + 1 -> out["register"] = "casual"
        }
        return out
    }

    // ── Utilities ──

    private fun String.tokenize(): Set<String> = lowercase()
        .replace(Regex("[^a-z0-9'\\s]"), " ")
        .split(Regex("\\s+"))
        .asSequence()
        .map { it.trim('\'') }
        .filter { it.length in 2..24 }
        .toSet()

    private fun normalizeContact(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class TraitLex(val high: Set<String>, val low: Set<String>)

    companion object {
        // Curated lexicons. Tiny sets per trait, hand-picked common
        // English markers. Extend as field testing reveals false-
        // negatives. Keep words 4+ chars unless the marker is
        // unambiguous (e.g. "fun").
        private val BIG_FIVE_LEX: Map<String, TraitLex> = mapOf(
            "openness" to TraitLex(
                high = setOf("curious", "wonder", "imagine", "creative", "explore", "ideas", "novel", "experiment", "art", "poetry", "philosophy", "interesting", "abstract", "theory", "perspective"),
                low = setOf("routine", "usual", "normal", "boring", "tradition", "conventional", "ordinary"),
            ),
            "conscientiousness" to TraitLex(
                high = setOf("plan", "organize", "schedule", "deadline", "responsible", "careful", "discipline", "task", "todo", "structured", "thorough", "diligent", "list"),
                low = setOf("spontaneous", "impulsive", "messy", "lazy", "procrastinate", "chaotic", "unorganized", "forget"),
            ),
            "extraversion" to TraitLex(
                high = setOf("party", "friends", "people", "social", "talk", "loud", "outgoing", "energetic", "fun", "hang out", "crowd", "meetup", "exciting"),
                low = setOf("quiet", "alone", "introvert", "reserved", "shy", "solitude", "withdrawn", "private", "tired"),
            ),
            "agreeableness" to TraitLex(
                high = setOf("kind", "help", "thanks", "thank", "appreciate", "support", "care", "love", "warm", "gentle", "thoughtful", "compassion", "understanding", "sympathy"),
                low = setOf("annoying", "hate", "stupid", "idiot", "fight", "argue", "rude", "selfish", "ugh", "blame", "wrong"),
            ),
            "neuroticism" to TraitLex(
                high = setOf("worry", "anxious", "stress", "overwhelmed", "afraid", "scared", "panic", "nervous", "sad", "depressed", "frustrated", "hopeless", "tired", "exhausted"),
                low = setOf("calm", "relaxed", "fine", "good", "great", "peaceful", "happy", "content", "easy", "steady"),
            ),
        )

        private val VALUES_LEX: Map<String, Set<String>> = mapOf(
            "achievement" to setOf("succeed", "achievement", "win", "best", "excellence", "promotion", "accomplish", "goal", "ambition", "competitive"),
            "security" to setOf("safe", "security", "stable", "predictable", "protect", "insurance", "savings", "backup", "reliable"),
            "hedonism" to setOf("enjoy", "fun", "pleasure", "treat", "indulge", "tasty", "delicious", "vacation", "relax", "spa"),
            "benevolence" to setOf("help", "support", "care", "kind", "generous", "donate", "volunteer", "family", "friend"),
            "self-direction" to setOf("freedom", "independent", "decide", "choose", "myself", "personal", "creative", "explore"),
            "power" to setOf("control", "lead", "authority", "boss", "dominate", "influence", "command", "powerful"),
            "conformity" to setOf("polite", "should", "supposed", "expected", "rule", "follow", "behave", "appropriate"),
            "tradition" to setOf("tradition", "family", "heritage", "custom", "ritual", "religion", "ancestor", "respect"),
            "universalism" to setOf("equality", "justice", "world", "environment", "planet", "fair", "human", "rights"),
            "stimulation" to setOf("exciting", "adventure", "thrill", "novel", "challenge", "risk", "bold"),
        )

        private val FORMAL_MARKERS = setOf("therefore", "furthermore", "however", "regarding", "kindly", "sincerely")
        private val CASUAL_MARKERS = setOf("lol", "haha", "yeah", "nah", "gonna", "wanna", "yep", "nope", "btw")

        private val EMOJI_BLOCKS: Set<Character.UnicodeBlock> = setOfNotNull(
            Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS,
            Character.UnicodeBlock.EMOTICONS,
            Character.UnicodeBlock.SUPPLEMENTAL_SYMBOLS_AND_PICTOGRAPHS,
            Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS,
            Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS,
            Character.UnicodeBlock.DINGBATS,
        )
    }
}
