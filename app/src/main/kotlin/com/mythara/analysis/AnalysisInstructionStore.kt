package com.mythara.analysis

import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "self-evolving analysis" store. The agent can teach Mythara new
 * ways to conduct personality / relationship analysis — by reading a
 * technique on the web (via `web_fetch`) and distilling it, or by the
 * user describing how they want analysis done. Each instruction is a
 * durable vault row (`kind:analysis-instruction`), and the analysis
 * pipelines ([com.mythara.persona.SelfPersonaBuilder],
 * [com.mythara.persona.PersonaBuilder],
 * [com.mythara.analytics.ContactAnalyticsBuilder]) pull the applicable
 * instructions in via [promptBlock] and prepend them to their Gemma
 * prompts — so the analysis genuinely evolves as Lumi learns.
 *
 * `appliesTo` scopes an instruction:
 *  - "persona"  → the user's usage/behaviour persona pass
 *  - "contacts" → per-contact relationship analysis
 *  - "self"     → the user's own Big Five
 *  - "all"      → every analysis pass
 */
@Singleton
class AnalysisInstructionStore @Inject constructor(
    private val vault: LearningVault,
) {

    /** Distinct instruction texts applicable to [appliesTo]. */
    suspend fun list(appliesTo: String): List<String> {
        val want = setOf("applies:${appliesTo.lowercase()}", "applies:all")
        return runCatching {
            vault.listByTier(Tier.Semantic, limit = 250)
                .filter { e ->
                    val f = vault.decodeFacets(e)
                    "kind:analysis-instruction" in f && f.any { it in want }
                }
                .sortedByDescending { it.tsMillis }
                .map { it.content.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX)
        }.getOrDefault(emptyList())
    }

    /** Persist a new analysis instruction. */
    suspend fun save(instruction: String, appliesTo: String, sourceUrl: String?): Boolean {
        val text = instruction.trim()
        if (text.isEmpty()) return false
        val scope = appliesTo.trim().lowercase().takeIf { it in VALID_SCOPES } ?: "all"
        val facets = buildList {
            add("kind:analysis-instruction")
            add("topic:analysis")
            add("applies:$scope")
            sourceUrl?.takeIf { it.isNotBlank() }?.let { add("src-url:${it.take(200)}") }
        }
        return runCatching {
            vault.add(
                content = text,
                tier = Tier.Semantic,
                src = "analysis:agent-taught",
                facets = facets,
                conf = 1.0,
            )
        }.getOrDefault(false)
    }

    /** All instructions, newest first, for the agent-facing list tool. */
    suspend fun listAll(): List<Pair<String, String>> = runCatching {
        vault.listByTier(Tier.Semantic, limit = 250)
            .filter { "kind:analysis-instruction" in vault.decodeFacets(it) }
            .sortedByDescending { it.tsMillis }
            .map { e ->
                val scope = vault.decodeFacets(e)
                    .firstOrNull { it.startsWith("applies:") }
                    ?.removePrefix("applies:") ?: "all"
                scope to e.content.trim()
            }
            .take(MAX)
    }.getOrDefault(emptyList())

    /**
     * Render the applicable instructions as a prompt preamble, or an
     * empty string when there are none — callers can prepend
     * unconditionally.
     */
    suspend fun promptBlock(appliesTo: String): String {
        val items = list(appliesTo)
        if (items.isEmpty()) return ""
        return buildString {
            append("Apply these analysis guidelines the user has taught you (they take precedence):\n")
            items.forEachIndexed { i, s -> append("  ${i + 1}. ").append(s).append('\n') }
            append('\n')
        }
    }

    companion object {
        private const val MAX = 12
        val VALID_SCOPES = setOf("persona", "contacts", "self", "all")
    }
}
