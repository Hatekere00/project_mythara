package com.mythara.branding

/**
 * Process-wide sink for the user's most recent detected mood label.
 * Read by [WallpaperRenderer] each frame to slowly lerp the gradient
 * stops between mood-coded palettes so the wallpaper visibly drifts
 * through the day as the user's emotional weather changes.
 *
 * Decoupled from the mood source the same way [LiveWallpaperPulseSink]
 * is decoupled from the HR source — anything that detects a mood
 * (lexical scorer in [com.mythara.agent.mood.ChatMoodTracker], the
 * acoustic fusion path, future passive observers) can publish via:
 *
 *   MoodSink.update("calm")   // any of the labels in MoodPalette
 *
 * Stale-after defaults to 30 min. Beyond that the renderer falls back
 * to the "neutral" palette (the original deep-purple gradient) so a
 * phone left untouched all day doesn't keep rendering a stale 9 a.m.
 * "anxious" wallpaper.
 *
 * Volatile-only — single Int / String / Long writes are atomic on the
 * JVM and the renderer is fine with reading a slightly-out-of-sync
 * (label, ts) pair (worst case is one frame of stale staleness).
 */
object MoodSink {
    @Volatile private var label: String? = null
    @Volatile private var ts: Long = 0L

    /** Observable mirror of [label] so the home-hub mood tile (and any
     *  future reactive UI) can render the live mood without polling.
     *  Emits the lowercase label or null when cleared. */
    private val _moodFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val moodFlow: kotlinx.coroutines.flow.StateFlow<String?> = _moodFlow

    fun update(mood: String?) {
        if (mood.isNullOrBlank()) return
        label = mood.lowercase()
        ts = System.currentTimeMillis()
        _moodFlow.value = label
    }

    fun current(maxStaleMs: Long = STALE_AFTER_MS): String? {
        if (ts == 0L) return null
        return if (System.currentTimeMillis() - ts <= maxStaleMs) label else null
    }

    /** 30-minute window — wider than the LiveWallpaperPulseSink's
     *  3 min because mood doesn't shift every minute the way HR does;
     *  the wallpaper feeling like "you" 25 min after your last chat
     *  is a feature, not a bug. */
    const val STALE_AFTER_MS = 30L * 60 * 1000
}
