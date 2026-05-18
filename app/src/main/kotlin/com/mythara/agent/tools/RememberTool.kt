package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.HeartbeatSyncer
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `remember` — durably store something the user explicitly asked Mythara
 * to remember.
 *
 * This is the tool to reach for whenever the user says "remember
 * that…", "don't forget…", "keep in mind…", "note that…". It:
 *
 *  1. Writes the fact to the SEMANTIC (durable) tier of the
 *     LearningVault, WITH an embedding — crucial, because SemanticRecall
 *     only scans vault rows that have an embedding. Without it the fact
 *     would be stored but never surface in answers.
 *  2. Fires an IMMEDIATE cross-device sync (HeartbeatSyncer.fireNow)
 *     instead of waiting for the next 5-minute heartbeat, so a
 *     user-asked memory lands on every Mythara install right away.
 *
 * The result: an explicit user-stated fact becomes a first-class
 * source informing future answers across the whole device cluster.
 */
@Singleton
class RememberTool @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    /** dagger.Lazy — HeartbeatSyncer transitively pulls in the agent stack. */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "remember"
    override val description: String =
        "Durably remember a fact about the user or one of their contacts. Use PROACTIVELY whenever the " +
            "user shares ANY personal information — birthdays, anniversaries, preferences, family members, " +
            "addresses, milestones, opinions — even if they didn't say 'remember this'. " +
            "Default to remembering; the user can always wipe later. " +
            "Phrase `content` as a clear standalone third-person sentence " +
            "(e.g. 'Sarah's birthday is March 5', 'The user is vegan', 'The user's wifi password is hunter2'). " +
            "Set `target` to 'self' (default) or 'contact:<name_key>' (lower-snake) so the People + AboutMe " +
            "panels can route it to the right card. Set `kind` to one of " +
            "birthday / anniversary / preference / fact / contact-info / location / milestone (defaults to 'fact'). " +
            "For dates pass `value` in ISO form (YYYY-MM-DD or MM-DD when the year is unknown)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "content",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The fact to remember, as a clear standalone third-person sentence " +
                                "(e.g. 'Sarah's birthday is March 5', 'The user is vegan').",
                        )
                    },
                )
                put(
                    "target",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Who this fact is about. 'self' (default) for the user themselves, " +
                                "or 'contact:<name_key>' for a contact (lowercase, hyphens for spaces, " +
                                "e.g. 'contact:sarah', 'contact:mom', 'contact:roselyn-mathew').",
                        )
                    },
                )
                put(
                    "kind",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Structured fact type so the People / AboutMe UI can render it. One of: " +
                                "birthday, anniversary, preference, fact (default), contact-info, location, milestone.",
                        )
                    },
                )
                put(
                    "value",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional structured value for the fact. For birthday/anniversary use ISO date " +
                                "(YYYY-MM-DD or MM-DD when the year is unknown). For preference/contact-info " +
                                "any short value like 'vegan', 'sarah@example.com', '+15551234567'.",
                        )
                    },
                )
                put(
                    "topic",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional short hyphenated topic slug for grouping (e.g. 'wifi', 'family', 'travel').",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("content")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val content = (args["content"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult(false, """{"error":"missing_content"}""")
        val topic = (args["topic"] as? JsonPrimitive)?.content?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }
        val target = (args["target"] as? JsonPrimitive)?.content?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "self"
        val kind = (args["kind"] as? JsonPrimitive)?.content?.trim()
            ?.lowercase()
            ?.takeIf { it in ALLOWED_KINDS }
            ?: "fact"
        val rawValue = (args["value"] as? JsonPrimitive)?.content?.trim()
        // Normalize date values to ISO (MM-DD or YYYY-MM-DD) so the
        // UI can render birthdays + anniversaries consistently. Free-
        // form values (preferences etc.) pass through unchanged.
        val value = when (kind) {
            "birthday", "anniversary" -> rawValue?.let { normalizeIsoDate(it) }
            else -> rawValue
        }

        // Embed so SemanticRecall can surface this on future turns —
        // recall only scans vault rows that HAVE an embedding.
        val embedding = if (embedder.isReady()) {
            runCatching { embedder.embed(content) }.getOrNull()
        } else {
            null
        }

        val facets = buildList {
            add("kind:$kind")
            // user-stated is the source — distinguishes user-told
            // facts from extracted ones (kind:trait, kind:health-snapshot etc.).
            add("kind:user-stated")
            add("src:user-asked")
            // Routing facet: "target:self" or "target:contact:<key>".
            // Strip a "contact:" prefix if the user passed only a
            // bare contact name without the "target:" prefix.
            val targetFacet = when {
                target == "self" -> "target:self"
                target.startsWith("contact:") -> "target:$target"
                else -> "target:contact:${target.replace(Regex("[^a-z0-9]+"), "-").trim('-')}"
            }
            add(targetFacet)
            // Mirror "contact:<key>" so existing People-screen queries
            // (which look for that facet) pick this row up too.
            if (targetFacet.startsWith("target:contact:")) {
                add(targetFacet.removePrefix("target:"))
            }
            if (!value.isNullOrBlank()) add("value:$value")
            if (topic != null) add("topic:$topic")
        }
        val stored = runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "user:remember",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 1.0,
            )
        }.getOrElse {
            Log.w(TAG, "vault.add failed: ${it.message}")
            false
        }
        if (!stored) {
            return ToolResult(
                false,
                """{"error":"store_failed","detail":"Couldn't persist the memory — it may have been blank after scrubbing."}""",
            )
        }

        // Immediate cross-device sync — user-asked memories shouldn't
        // wait for the next 5-minute heartbeat.
        runCatching { heartbeat.get().fireNow() }
            .onFailure { Log.w(TAG, "immediate sync kick failed: ${it.message}") }

        Log.d(TAG, "remembered: \"${content.take(80)}\" (embedded=${embedding != null})")
        val payload = buildJsonObject {
            put("ok", true)
            put("stored", content)
            put("embedded", embedding != null)
            put("syncing", true)
            put(
                "detail",
                buildString {
                    append("Stored in long-term memory")
                    if (embedding != null) {
                        append(" — it will inform future answers via recall")
                    } else {
                        append(" (embedder still warming up — recall kicks in once it's ready)")
                    }
                    append(", and an immediate cross-device sync was kicked off.")
                },
            )
        }
        return ToolResult(true, payload.toString())
    }

    /** Accept a handful of common date phrasings and return the ISO
     *  form. Returns null when we can't confidently parse — caller
     *  then keeps the human phrasing as-is in the value facet. */
    private fun normalizeIsoDate(raw: String): String? {
        val s = raw.trim()
        // Already ISO?
        Regex("""^\d{4}-\d{2}-\d{2}$""").matchEntire(s)?.let { return s }
        Regex("""^\d{2}-\d{2}$""").matchEntire(s)?.let { return s }
        // "MM/DD" or "MM/DD/YYYY"
        Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{4}))?$""").matchEntire(s)?.let { m ->
            val (mm, dd, yy) = m.destructured
            val month = mm.padStart(2, '0')
            val day = dd.padStart(2, '0')
            return if (yy.isNotEmpty()) "$yy-$month-$day" else "$month-$day"
        }
        // "March 5" / "March 5, 2024" / "Mar 5"
        val months = mapOf(
            "january" to "01", "jan" to "01",
            "february" to "02", "feb" to "02",
            "march" to "03", "mar" to "03",
            "april" to "04", "apr" to "04",
            "may" to "05",
            "june" to "06", "jun" to "06",
            "july" to "07", "jul" to "07",
            "august" to "08", "aug" to "08",
            "september" to "09", "sep" to "09", "sept" to "09",
            "october" to "10", "oct" to "10",
            "november" to "11", "nov" to "11",
            "december" to "12", "dec" to "12",
        )
        Regex("""^([A-Za-z]+)\s+(\d{1,2})(?:,?\s+(\d{4}))?$""").matchEntire(s)?.let { m ->
            val (name, dd, yy) = m.destructured
            val month = months[name.lowercase()] ?: return null
            val day = dd.padStart(2, '0')
            return if (yy.isNotEmpty()) "$yy-$month-$day" else "$month-$day"
        }
        return null
    }

    companion object {
        private const val TAG = "Mythara/Remember"

        /** The structured kinds the People + AboutMe UI knows how to
         *  render distinctly. Anything else passes through as "fact". */
        private val ALLOWED_KINDS = setOf(
            "birthday",
            "anniversary",
            "preference",
            "fact",
            "contact-info",
            "location",
            "milestone",
        )
    }
}
