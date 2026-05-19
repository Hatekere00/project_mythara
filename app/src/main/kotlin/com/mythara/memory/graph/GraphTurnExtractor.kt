package com.mythara.memory.graph

import android.util.Log
import com.mythara.ai.ModelRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM-driven entity + edge extractor that reads a finished
 * (user, agent) exchange and writes Graphiti-style temporal facts
 * into [GraphMemoryRepository].
 *
 * This is the WRITER for the graph memory substrate (the
 * GraphEntity / GraphEdge tables shipped earlier). The substrate
 * is the schema + the canonicalised insert API; this extractor is
 * what produces the rows.
 *
 * Two outputs per call:
 *   1. **Entities** — concrete things mentioned in the exchange
 *      that the agent should remember as first-class nodes
 *      (people, places, projects, recurring events). Each entity
 *      has a name and a kind ("person", "place", "project",
 *      "event", "topic").
 *   2. **Edges** — relationships between those entities asserted
 *      by the conversation ("Anurag works_at Anthropic", "the
 *      Mom-meeting is_at the cafe"). Each edge has a subject, a
 *      predicate, an object, and optionally a fact-text quote
 *      from the source for provenance.
 *
 * Routing: light path (local Gemma) — like the todo extractors,
 * this runs on every user turn and cost discipline matters more
 * than nuance. The model sees a short prompt + the exchange and
 * returns a small JSON object with two arrays.
 *
 * Failure mode: graceful degradation. Non-JSON / partial output
 * just produces fewer rows; nothing breaks the agent loop.
 *
 * What it explicitly does NOT do:
 *   - Run on auto-continue / auto-reply / auto-triage / notif
 *     turns. Same skip logic as TodoIntentExtractor — those
 *     turns aren't user-initiated semantic exchanges.
 *   - Try to do supersession (close prior edges). The substrate
 *     supports it via [GraphMemoryRepository.supersedeEdges] but
 *     the extractor takes the safer "always assert, never close"
 *     stance for v1; closing happens in a future periodic
 *     reconciliation pass.
 *   - Extract self entities (where the user is the subject) —
 *     covered by the SelfPersonaBuilder pipeline already.
 */
@Singleton
class GraphTurnExtractor @Inject constructor(
    private val router: ModelRouter,
    private val graph: GraphMemoryRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns the count of entities + edges newly written. 0 when
     * the model returns nothing or is unavailable; counts are
     * additive across the two collections.
     */
    suspend fun extract(userText: String, agentReply: String): Int = withContext(Dispatchers.IO) {
        if (userText.isBlank() || agentReply.isBlank()) return@withContext 0
        val prompt = buildPrompt(userText, agentReply)
        val raw = runCatching { router.runRaw(prompt, maxLen = 1200, heavy = false) }
            .getOrNull()
            ?.trim()
        if (raw.isNullOrBlank()) return@withContext 0

        val obj = parseRoot(raw) ?: return@withContext 0
        val entityRecords = parseEntities(obj["entities"] as? JsonArray)
        val edgeRecords = parseEdges(obj["edges"] as? JsonArray)
        if (entityRecords.isEmpty() && edgeRecords.isEmpty()) return@withContext 0

        val now = System.currentTimeMillis()

        // First pass: entities. Build a name→id map so the edge
        // pass can resolve subject/object refs.
        val nameToId = mutableMapOf<String, String>()
        var entitiesWritten = 0
        for (e in entityRecords) {
            val id = runCatching {
                graph.recordEntity(
                    name = e.name,
                    kind = e.kind,
                    conf = e.conf,
                    nowMs = now,
                )
            }.getOrDefault("")
            if (id.isNotBlank()) {
                nameToId[e.name.lowercase().trim()] = id
                entitiesWritten++
            }
        }

        // Second pass: edges. Edge subject/object can be either a
        // literal entity name we just wrote OR a free-text name the
        // model emitted without a corresponding entity declaration —
        // in that case we lazily create a "thing"-kind entity for it
        // so the edge has well-formed endpoints.
        var edgesWritten = 0
        for (e in edgeRecords) {
            val sId = nameToId[e.subject.lowercase().trim()]
                ?: runCatching {
                    graph.recordEntity(name = e.subject, kind = "thing", conf = 0.6f, nowMs = now)
                }.getOrDefault("")
            val oId = nameToId[e.`object`.lowercase().trim()]
                ?: runCatching {
                    graph.recordEntity(name = e.`object`, kind = "thing", conf = 0.6f, nowMs = now)
                }.getOrDefault("")
            if (sId.isBlank() || oId.isBlank()) continue
            val ok = runCatching {
                graph.recordEdge(
                    subjectId = sId,
                    predicate = e.predicate,
                    objectId = oId,
                    factText = e.factText,
                    conf = e.conf,
                    nowMs = now,
                )
            }.getOrDefault(false)
            if (ok) edgesWritten++
        }

        Log.i(TAG, "graph extract: $entitiesWritten entities, $edgesWritten edges")
        entitiesWritten + edgesWritten
    }

    private fun buildPrompt(userText: String, agentReply: String): String = buildString {
        append("You are extracting STRUCTURED FACTS from one exchange in a personal-AI conversation. ")
        append("Output JSON ONLY — no preamble, no markdown fences. Empty arrays are fine.\n\n")
        append("Output schema (STRICT):\n")
        append("{\n")
        append("  \"entities\": [\n")
        append("    {\"name\": \"...\", \"kind\": \"person|place|project|event|topic|thing\"}\n")
        append("  ],\n")
        append("  \"edges\": [\n")
        append("    {\"subject\": \"...\", \"predicate\": \"snake_case_verb\", \"object\": \"...\", \"fact\": \"short quote\"}\n")
        append("  ]\n")
        append("}\n\n")
        append("Rules — STRICT:\n")
        append("  1. Only extract things ACTUALLY mentioned in the exchange. Don't invent.\n")
        append("  2. Skip entities about the user themselves (\"I\", \"me\", \"my\"). The user's self-profile is tracked separately.\n")
        append("  3. Predicates are snake_case verbs (works_at, lives_in, married_to, scheduled_for, owns, prefers, attended).\n")
        append("  4. Each entity name max 60 chars; each edge fact-text max 200 chars.\n")
        append("  5. Cap at 6 entities + 6 edges total. Pick the highest-signal ones.\n")
        append("  6. Skip transient noise (greetings, filler, tool-call descriptions).\n\n")
        append("--- USER MESSAGE ---\n")
        append(userText.take(800)).append("\n\n")
        append("--- ASSISTANT REPLY ---\n")
        append(agentReply.take(800)).append("\n\n")
        append("--- OUTPUT (JSON object only) ---\n")
    }

    private fun parseRoot(raw: String): JsonObject? {
        val cleaned = raw
            .substringAfter("```json", raw)
            .substringAfter("```", raw)
            .substringBefore("```")
            .trim()
            .let { extractFirstObject(it) ?: it }
        return runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
    }

    private fun parseEntities(arr: JsonArray?): List<EntityRecord> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                val kind = o["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "thing"
                if (name.isBlank() || name.length > 80) null
                else EntityRecord(name = name, kind = kind, conf = 0.85f)
            }.getOrNull()
        }.distinctBy { it.name.lowercase() }.take(6)
    }

    private fun parseEdges(arr: JsonArray?): List<EdgeRecord> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val s = o["subject"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                val rawPred = o["predicate"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@mapNotNull null
                // Canonicalize the predicate against the controlled
                // vocabulary at write time so the graph stays clean
                // going forward. The backfill runner handles old
                // rows. Non-vocab predicates fall through unchanged
                // (cleaned form) — we keep the fact rather than drop
                // it just because the LLM coined a new term.
                val p = PredicateVocabulary.normalize(rawPred)
                val obj = o["object"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                val fact = o["fact"]?.jsonPrimitive?.contentOrNull?.trim()?.take(200)
                if (s.isBlank() || p.isBlank() || obj.isBlank()) null
                else EdgeRecord(subject = s, predicate = p, `object` = obj, factText = fact, conf = 0.8f)
            }.getOrNull()
        }.take(6)
    }

    private fun extractFirstObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private data class EntityRecord(val name: String, val kind: String, val conf: Float)
    private data class EdgeRecord(
        val subject: String,
        val predicate: String,
        val `object`: String,
        val factText: String?,
        val conf: Float,
    )

    companion object {
        private const val TAG = "Mythara/GraphExtractor"
    }
}
