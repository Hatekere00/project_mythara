package com.mythara.branding

/**
 * Mood → visual-intervention mapping for the in-app Canvas surface
 * (`com.mythara.ui.canvas.CanvasScreen`). Companion to [MoodPalette]
 * which already maps mood → wallpaper gradient stops.
 *
 * Goal: when the user opens the Canvas with no agent prompt, render
 * an ambient visual that's tuned to their current emotional state.
 * Calming palette + slow breath pacing for anxiety, warm encouragement
 * for low mood, grounding palette for over-arousal. The visual is the
 * agent's body language — same role the wallpaper colour drift plays
 * elsewhere.
 *
 * ## Ethical posture (read me before modifying)
 *
 * The user calls this "visual mind control / behavior modification" —
 * the in-code paradigm here is **biofeedback-style ambient
 * visualization**, never subliminal influence. Specifically:
 *
 *   - Every visual change is VISIBLE — no sub-perceptual flashes, no
 *     hidden encoded messages, no <16 ms colour pulses.
 *   - Every visual change is DISMISSIBLE — the user can navigate away,
 *     swipe to a different visual, or disable mood-driven ambient
 *     rendering from Settings → Canvas.
 *   - Every visual change is LOGGED — the [VisualIntervention] applied
 *     for each open of the Canvas is written to LearningVault with
 *     facet `kind:canvas-visual`, `mood:<label>`, so the user can
 *     audit "what is Mythara doing to me visually" via the existing
 *     memory inspector.
 *   - The breath rhythms used (4-7-8, box, 6-cpm coherent) are
 *     standard HRV-feedback techniques from clinical literature, not
 *     proprietary or hidden.
 *
 * Same posture the audio side ([com.mythara.resonance]) already takes:
 * sessions are explicit, frequencies are audible, the user can stop
 * at any time.
 *
 * ## Research basis (for future contributors)
 *
 *   - Chromotherapy / colour psychology:
 *     Birren, F. (1961). *Color Psychology and Color Therapy*.
 *     Mahnke, F. (1996). *Color, Environment, and Human Response*.
 *   - Biophilic design palette derivations (forest greens for
 *     grounding, ocean blues for calming):
 *     Kellert, S. et al. (2008). *Biophilic Design*.
 *   - Breath-pacing visual rhythms:
 *     HRV-biofeedback literature — coherent breathing at ~6 breaths/min
 *     (0.1 Hz) maximises HRV amplitude (Lehrer & Gevirtz, 2014).
 *     4-7-8 technique (Weil) for anxiety down-regulation.
 *   - Affective computing patterns:
 *     Picard, R. (1997). *Affective Computing*. MIT Press.
 *
 * Anyone extending this mapping should preserve the visible /
 * dismissible / logged invariants.
 */
internal object MoodVisualMapping {

    /** What kind of ambient render the Canvas should show for a mood. */
    enum class VisualIntervention(
        /** CSS palette name the ambient.html knows. */
        val paletteName: String,
        /** Target breath rhythm period in milliseconds (one full cycle). */
        val breathPeriodMs: Int,
        /** Human-readable label shown to the user. */
        val label: String,
        /** One-line description for the inspector. */
        val rationale: String,
    ) {
        /** Anxious / overwhelmed → cool palette, slow 4-7-8-ish pacing. */
        CalmDown(
            paletteName = "cool-teal",
            breathPeriodMs = 19_000, // 4 in / 7 hold / 8 out → ~19 s
            label = "calming",
            rationale = "Cool teal + 4-7-8 breath pacing for anxiety down-regulation.",
        ),

        /** Low / sad → warm palette, gentle pulse, encouragement render. */
        WarmEncourage(
            paletteName = "warm-amber",
            breathPeriodMs = 10_000, // coherent breathing, gentler than CalmDown
            label = "warming",
            rationale = "Warm amber + gentle pulse for low mood, with encouraging silhouette.",
        ),

        /** Excited / hyper → grounding green, longer exhale to settle. */
        GroundingForest(
            paletteName = "forest-green",
            breathPeriodMs = 14_000, // 4 in / 10 out — exhale-weighted
            label = "grounding",
            rationale = "Forest green + exhale-weighted breath for grounding over-arousal.",
        ),

        /** Frustrated / angry → cooling blue, slower than CalmDown to interrupt loop. */
        CoolingBlue(
            paletteName = "deep-ocean",
            breathPeriodMs = 22_000,
            label = "cooling",
            rationale = "Deep ocean blue + slow breath for cooling frustration loops.",
        ),

        /** Focused / neutral → minimal render, gets out of the user's way. */
        AmbientNeutral(
            paletteName = "neutral",
            breathPeriodMs = 12_000,
            label = "ambient",
            rationale = "Minimal neutral render — Mythara's brand baseline.",
        ),

        /** Happy → bright purple, no intervention, just match the energy. */
        BrightMatch(
            paletteName = "bright-purple",
            breathPeriodMs = 8_000,
            label = "bright",
            rationale = "Bright purple matching positive mood — no down-regulation.",
        ),
    }

    /** Pick the intervention for a mood label. Unknown labels →
     *  [VisualIntervention.AmbientNeutral]. */
    fun forMood(mood: String?): VisualIntervention = when (mood?.lowercase()) {
        "anxious", "overwhelmed", "stressed" -> VisualIntervention.CalmDown
        "sad", "low", "depressed", "lonely" -> VisualIntervention.WarmEncourage
        "excited", "hyper", "elated", "manic" -> VisualIntervention.GroundingForest
        "frustrated", "angry", "irritated" -> VisualIntervention.CoolingBlue
        "happy", "content", "joyful" -> VisualIntervention.BrightMatch
        else -> VisualIntervention.AmbientNeutral
    }
}
