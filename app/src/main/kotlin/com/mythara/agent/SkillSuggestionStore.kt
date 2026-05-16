package com.mythara.agent

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot signal between consecutive chat turns: "the agent just
 * chained N automation tools — on the NEXT turn, tell the model to
 * proactively offer to save the procedure as a skill".
 *
 * Why a separate store instead of just trusting the model to remember
 * the system-prompt instruction: in practice, the model's "be brief"
 * directive dominates the SKILLS subsection, so the follow-up offer
 * rarely fires. Injecting a fresh, explicit, one-turn system message
 * is more reliable than nudging behaviour from a long static prompt.
 *
 * Lifecycle:
 *   1. AgentLoop's per-turn tool execution records each fired
 *      automation tool via [recordTool].
 *   2. When the turn completes, [maybeMarkForOffer] inspects the
 *      list; if ≥ MIN_CHAIN_LEN consecutive automation tools fired,
 *      it stashes them in [pendingOffer].
 *   3. The NEXT [AgentLoop] turn calls [consume] when building
 *      messages; if non-null it injects a system message instructing
 *      the model to offer save_skill.
 *   4. consume() clears the stash so the offer fires exactly once.
 *
 * Single-process, volatile — the offer doesn't survive an app
 * restart (and shouldn't; the user has moved on by then).
 */
@Singleton
class SkillSuggestionStore @Inject constructor() {

    /** Tool names that count as automation steps worth saving. Read-
     *  only / observation tools (get_time, read_screen, search_memory,
     *  etc.) are excluded — a skill is "do these side effects in
     *  sequence", not "look at this state". */
    private val automationTools: Set<String> = setOf(
        "open_app", "tap", "swipe", "type_text", "press_back",
        "send_sms_direct", "send_whatsapp_direct", "place_call_direct",
        "screenshot", "set_alarm", "create_calendar_event",
        "apply_cosmetic", "linux_vm", "run_shell",
    )

    /** Tools fired in the current in-flight turn. Cleared by
     *  [maybeMarkForOffer] when the turn ends. */
    private val currentTurnTools = mutableListOf<String>()

    /** Single-slot stash for the next turn's offer prompt. */
    private val pendingOffer = AtomicReference<List<String>?>(null)

    /** Called from AgentLoop after every successful tool execute. */
    @Synchronized
    fun recordTool(name: String) {
        if (name in automationTools) {
            currentTurnTools += name
        }
    }

    /** Called when a turn finishes. If the chain crossed the threshold,
     *  the tools land in [pendingOffer] for the next turn to pick up. */
    @Synchronized
    fun maybeMarkForOffer() {
        if (currentTurnTools.size >= MIN_CHAIN_LEN) {
            pendingOffer.set(currentTurnTools.toList())
        }
        currentTurnTools.clear()
    }

    /** Reset between turns regardless of whether the threshold was hit
     *  — protects against carryover when a turn errored mid-execution
     *  without calling [maybeMarkForOffer]. */
    @Synchronized
    fun resetCurrentTurn() {
        currentTurnTools.clear()
    }

    /** Pull and clear the pending offer. Returns null when nothing's
     *  queued. */
    fun consume(): List<String>? = pendingOffer.getAndSet(null)

    companion object {
        /** Minimum automation-tool chain length to suggest a skill.
         *  Three steps reliably reads as "procedure"; one or two are
         *  usually just an ad-hoc request. */
        const val MIN_CHAIN_LEN = 3
    }
}
