package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.audit.AuditRepository
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * `summarize_day` — produce a structured day-in-review snapshot the
 * agent can narrate to the user.
 *
 * Pulls from three sources, all per-day:
 *
 *   1. **AuditRepository** — every tool the agent fired today
 *      (counts by tool, success rate, top 5 tools by frequency,
 *      slowest tool with latency)
 *   2. **LearningVault** — every chat turn's lexical signal,
 *      filtered by today's day window:
 *        - mood histogram (which moods dominated)
 *        - top concerns (kind:trait + dim:concern)
 *        - new preferences logged (kind:trait + dim:preference)
 *        - Big Five tilt deltas (signed score per trait)
 *        - explicit notes the user asked Mythara to remember
 *   3. **Computed** — turn count, first/last activity time, total
 *      vault rows added today
 *
 * Returns a single JSON object the agent can either narrate
 * conversationally ("today you talked about X, your top concern
 * was Y, you used Mythara for Z") or render on the Canvas as a
 * day-review card.
 *
 * Default date = today (local timezone midnight → next midnight).
 * Caller can pass `date` as YYYY-MM-DD to summarise any other
 * day in the vault.
 */
@Singleton
class DailyDigestTool @Inject constructor(
    private val vault: LearningVault,
    private val auditRepo: AuditRepository,
) : Tool {
    override val name = "summarize_day"
    override val description =
        "Produce a structured day-in-review snapshot for the user: tool activity, mood histogram, " +
            "top concerns, new preferences, Big Five deltas, explicit notes. " +
            "Defaults to today; pass date='YYYY-MM-DD' to summarise any other day."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("date", buildJsonObject {
                put("type", "string")
                put("description", "Day to summarise, YYYY-MM-DD in device local timezone. Default = today.")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val dateStr = args["date"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val (fromMs, toMs, dayLabel) = resolveDayWindow(dateStr)
            ?: return ToolResult.fail("invalid date '$dateStr' — expected YYYY-MM-DD")

        return withContext(Dispatchers.IO) {
            runCatching {
                // ── 1. AuditRepository — every tool call today ──
                val auditRows = auditRepo.dao.listBetween(fromMs, toMs)
                val toolCalls = auditRows.filter { it.kind == "tool_call" }
                val byTool = toolCalls
                    .mapNotNull { it.toolName }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                val successCount = toolCalls.count { it.resultOk }
                val failureCount = toolCalls.size - successCount
                val avgLatencyMs =
                    if (toolCalls.isEmpty()) 0L
                    else toolCalls.sumOf { it.latencyMs } / toolCalls.size
                val slowest = toolCalls.maxByOrNull { it.latencyMs }
                val topContacts = toolCalls
                    .mapNotNull { it.contactName }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key to it.value }

                // ── 2. LearningVault — today's lexical signal ──
                val all = mutableListOf<com.mythara.secret.observe.vault.LearningEntity>().apply {
                    runCatching { addAll(vault.listByTier(Tier.Working, limit = 400)) }
                    runCatching { addAll(vault.listByTier(Tier.Semantic, limit = 400)) }
                    runCatching { addAll(vault.listByTier(Tier.Episodic, limit = 200)) }
                }
                val todayRows = all.filter { it.tsMillis in fromMs until toMs }

                // mood histogram
                val moodHistogram = todayRows
                    .mapNotNull { entity ->
                        val f = vault.decodeFacets(entity)
                        val isMood = f.any { it.startsWith("kind:") && it.endsWith("-mood") } ||
                            f.any { it.startsWith("kind:mood") }
                        if (!isMood) null
                        else f.firstOrNull { it.startsWith("mood:") }?.removePrefix("mood:")
                    }
                    .filter { it.isNotBlank() && it != "unknown" }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value }

                // concerns
                val concerns = todayRows
                    .filter { entity ->
                        val f = vault.decodeFacets(entity)
                        "kind:trait" in f && "dim:concern" in f && "target:self" in f
                    }
                    .flatMap { entity ->
                        vault.decodeFacets(entity)
                            .filter { it.startsWith("topic:") }
                            .map { it.removePrefix("topic:") to entity.seen }
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, seens) -> seens.sum() }
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key }

                // new preferences
                val newPreferences = todayRows
                    .filter { entity ->
                        val f = vault.decodeFacets(entity)
                        "kind:trait" in f && "dim:preference" in f && "target:self" in f
                    }
                    .mapNotNull { entity ->
                        val f = vault.decodeFacets(entity)
                        val pred = f.firstOrNull { it.startsWith("predicate:") }?.removePrefix("predicate:")
                        val obj = f.firstOrNull { it.startsWith("object:") }?.removePrefix("object:")
                        if (pred != null && obj != null) "$pred $obj" else null
                    }
                    .distinct()
                    .take(10)

                // Big Five deltas
                data class B5(var score: Int = 0)
                val big5 = mutableMapOf<String, B5>()
                for (row in todayRows) {
                    val f = vault.decodeFacets(row)
                    if ("kind:trait" !in f || "dim:big5" !in f || "target:self" !in f) continue
                    val trait = f.firstOrNull { it.startsWith("trait:") }?.removePrefix("trait:") ?: continue
                    val pol = f.firstOrNull { it.startsWith("polarity:") }?.removePrefix("polarity:")
                    val sign = if (pol == "high") 1 else if (pol == "low") -1 else 0
                    big5.getOrPut(trait) { B5() }.score += sign * row.seen.coerceAtLeast(1)
                }
                val big5Deltas = big5.entries
                    .map { (trait, b) -> trait to b.score }
                    .filter { abs(it.second) >= 1 }
                    .sortedByDescending { abs(it.second) }
                    .take(5)

                // explicit notes
                val explicitNotes = todayRows
                    .filter { entity ->
                        vault.decodeFacets(entity).any { it == "kind:explicit-note" }
                    }
                    .sortedByDescending { it.tsMillis }
                    .take(5)
                    .map { it.content.take(120) }

                // first/last activity
                val timestamps = (todayRows.map { it.tsMillis } + auditRows.map { it.tsMillis })
                    .filter { it in fromMs until toMs }
                val firstMs = timestamps.minOrNull()
                val lastMs = timestamps.maxOrNull()

                // assemble JSON
                val out = StringBuilder("{")
                out.append("\"date\":\"$dayLabel\",")
                out.append("\"window\":{\"from_ms\":$fromMs,\"to_ms\":$toMs},")
                out.append("\"first_activity_ms\":${firstMs ?: 0},")
                out.append("\"last_activity_ms\":${lastMs ?: 0},")
                out.append("\"vault_rows_added\":${todayRows.size},")
                out.append("\"audit\":{")
                out.append("\"total_tool_calls\":${toolCalls.size},")
                out.append("\"success\":$successCount,")
                out.append("\"failure\":$failureCount,")
                out.append("\"avg_latency_ms\":$avgLatencyMs,")
                out.append("\"slowest_tool\":")
                if (slowest != null) {
                    out.append("{\"name\":\"${slowest.toolName?.escape().orEmpty()}\",\"latency_ms\":${slowest.latencyMs}}")
                } else out.append("null")
                out.append(",\"top_tools\":[")
                byTool.take(5).forEachIndexed { i, e ->
                    if (i > 0) out.append(',')
                    out.append("{\"name\":\"${e.key.escape()}\",\"count\":${e.value}}")
                }
                out.append("],\"top_contacts\":[")
                topContacts.forEachIndexed { i, (n, c) ->
                    if (i > 0) out.append(',')
                    out.append("{\"name\":\"${n.escape()}\",\"count\":$c}")
                }
                out.append("]},")
                out.append("\"mood\":{")
                out.append("\"histogram\":[")
                moodHistogram.forEachIndexed { i, (m, c) ->
                    if (i > 0) out.append(',')
                    out.append("{\"mood\":\"${m.escape()}\",\"count\":$c}")
                }
                out.append("]},")
                out.append("\"concerns\":[${concerns.joinToString(",") { "\"${it.escape()}\"" }}],")
                out.append("\"new_preferences\":[${newPreferences.joinToString(",") { "\"${it.escape()}\"" }}],")
                out.append("\"big5_deltas\":[")
                big5Deltas.forEachIndexed { i, (t, s) ->
                    if (i > 0) out.append(',')
                    val polarity = if (s > 0) "high" else "low"
                    out.append("{\"trait\":\"$t\",\"polarity\":\"$polarity\",\"score\":${abs(s)}}")
                }
                out.append("],\"explicit_notes\":[")
                explicitNotes.forEachIndexed { i, n ->
                    if (i > 0) out.append(',')
                    out.append(jsonString(n))
                }
                out.append("]}")
                ToolResult.ok(out.toString())
            }.getOrElse {
                ToolResult.fail("summarize_day_failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Resolve the [date] string (YYYY-MM-DD) — or today when blank —
     * to a `[fromMs, toMs)` window covering local-timezone midnight
     * to next midnight, plus a display label.
     */
    private fun resolveDayWindow(date: String): Triple<Long, Long, String>? {
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz)
        if (date.isNotBlank()) {
            val parsed = runCatching {
                val df = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
                df.parse(date)
            }.getOrNull() ?: return null
            cal.time = parsed
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val fromMs = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val toMs = cal.timeInMillis
        val label = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = tz }
            .format(Date(fromMs))
        return Triple(fromMs, toMs, label)
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
