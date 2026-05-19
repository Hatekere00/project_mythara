package com.mythara.memory.graph

import android.util.Log
import com.mythara.memory.HeartbeatSyncer
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot backfill that walks every GraphEdge in this device's
 * vault and rewrites the `predicate` column to its canonical form
 * via [PredicateVocabulary.normalize].
 *
 * Why this exists separately from the write-time normalization:
 * the GraphTurnExtractor change only affects NEW edges. Without a
 * backfill, every fact extracted before this commit lands with its
 * original free-form predicate (`is_a_fan_of`, `lives_at`, …) and
 * queries that rely on canonical predicates still miss them. This
 * runner closes the gap in one pass.
 *
 * Pattern: mirrors [com.mythara.analytics.PeopleCleanupRunner] +
 * [com.mythara.lifeline.RecaptionAllRunner] — same Idle / Running /
 * Done / Failed state shape so SecretSettingsScreen reuses the
 * existing runner-panel composable.
 *
 * Cross-device sync: changed predicates ride the existing
 * GraphMemorySync path (edges have `synced = false` on rewrite),
 * so a backfill on Pixel 10 propagates the cleaner predicates to
 * the Fold / watch on the next heartbeat tick.
 */
@Singleton
class PredicateNormalizationRunner @Inject constructor(
    /** DAO directly — the repo's dao field is private. The runner
     *  is intentionally a sibling of the repo, not a consumer of
     *  its higher-level operations, because the repo's recordEdge
     *  path always INSERTS rather than rewrites. */
    private val dao: GraphMemoryDao,
    private val heartbeat: Lazy<HeartbeatSyncer>,
) {

    /** Per-pass summary surfaced in the panel's Done state. */
    data class Report(
        val totalScanned: Int,
        /** Edges whose predicate was rewritten to a canonical term. */
        val normalized: Int,
        /** Edges whose predicate was already canonical (no-op). */
        val alreadyCanonical: Int,
        /** Edges whose predicate had no canonical match — kept as-is
         *  (cleaned to snake_case) so the fact isn't lost. The top
         *  unmapped predicates are listed in [topUnmapped] so the
         *  user can decide whether to extend the vocabulary. */
        val unmapped: Int,
        /** Most common unmapped predicates with their counts. Useful
         *  signal for "what predicates does the LLM keep emitting
         *  that we should add to the canonical list?" */
        val topUnmapped: List<Pair<String, Int>>,
        val durationMs: Long,
    )

    sealed interface State {
        data object Idle : State
        data class Running(val attempted: Int, val total: Int, val normalized: Int) : State
        data class Done(val report: Report) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    /** Kick off a backfill pass. Idempotent — a second start() while
     *  the first is running is a no-op. Re-running after completion
     *  IS safe (canonical predicates short-circuit at the
     *  isCanonical check). */
    fun start() {
        if (isRunning()) {
            Log.d(TAG, "backfill already running; ignoring duplicate start")
            return
        }
        val started = System.currentTimeMillis()
        _state.value = State.Running(0, 0, 0)
        job = scope.launch {
            runCatching {
                val edges = runCatching { dao.listAllEdges() }.getOrDefault(emptyList())
                _state.value = State.Running(0, edges.size, 0)
                var normalized = 0
                var alreadyCanonical = 0
                var unmapped = 0
                val unmappedCounts = mutableMapOf<String, Int>()

                edges.forEachIndexed { index, e ->
                    val newPredicate = PredicateVocabulary.normalize(e.predicate)
                    when {
                        newPredicate == e.predicate && PredicateVocabulary.isCanonical(newPredicate) -> {
                            alreadyCanonical++
                        }
                        newPredicate == e.predicate -> {
                            // Unchanged AND not canonical — vocabulary
                            // doesn't cover this term. Count it so the
                            // user sees what's worth adding.
                            unmapped++
                            unmappedCounts.merge(newPredicate, 1, Int::plus)
                        }
                        else -> {
                            // Predicate changed → new edge ID (it
                            // hashes subject+predicate+object+validAt).
                            // Atomic replace keeps the graph
                            // consistent across the change.
                            val newId = computeEdgeId(
                                e.subjectId, newPredicate, e.objectId, e.validAtMs,
                            )
                            val newEdge = e.copy(
                                id = newId,
                                predicate = newPredicate,
                                synced = false, // re-publish via MemorySync
                            )
                            runCatching { dao.replaceEdge(e.id, newEdge) }
                                .onFailure {
                                    Log.w(TAG, "replaceEdge failed for ${e.id}: ${it.message}")
                                }
                            // Also count the normalized predicate as
                            // canonical now (it just became one).
                            if (PredicateVocabulary.isCanonical(newPredicate)) normalized++
                            else {
                                // Unusual: cleanup+stripping yielded
                                // a different but still non-canonical
                                // form. Count under unmapped with the
                                // new term.
                                unmapped++
                                unmappedCounts.merge(newPredicate, 1, Int::plus)
                            }
                        }
                    }
                    if (index % 25 == 0 || index == edges.lastIndex) {
                        _state.update { State.Running(index + 1, edges.size, normalized) }
                    }
                }

                val topUnmapped = unmappedCounts.entries
                    .sortedByDescending { it.value }
                    .take(10)
                    .map { it.key to it.value }

                val report = Report(
                    totalScanned = edges.size,
                    normalized = normalized,
                    alreadyCanonical = alreadyCanonical,
                    unmapped = unmapped,
                    topUnmapped = topUnmapped,
                    durationMs = System.currentTimeMillis() - started,
                )
                _state.value = State.Done(report)
                Log.d(TAG, "backfill done: $report")
                // Push the rewritten predicates out to peers
                // immediately so cross-device queries are consistent.
                runCatching { heartbeat.get().fireNow() }
                    .onFailure { Log.w(TAG, "post-backfill sync kick failed: ${it.message}") }
            }.onFailure { e ->
                Log.w(TAG, "backfill failed: ${e.message}", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { current ->
            if (current is State.Running) {
                State.Failed("Cancelled at ${current.attempted}/${current.total}")
            } else current
        }
    }

    /** Move the panel back to Idle after the user dismisses a
     *  Done / Failed badge. */
    fun acknowledge() {
        if (!isRunning()) _state.value = State.Idle
    }

    /** Re-derive an edge primary key the same way
     *  [GraphMemoryRepository.recordEdge] does. Kept inline so the
     *  runner has no private-visibility coupling with the repo. */
    private fun computeEdgeId(s: String, p: String, o: String, validAtMs: Long): String =
        "edg_" + sha8("$s|$p|$o|$validAtMs")

    private fun sha8(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        private const val TAG = "Mythara/PredNorm"
    }
}
