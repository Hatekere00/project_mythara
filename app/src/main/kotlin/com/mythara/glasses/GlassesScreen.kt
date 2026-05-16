package com.mythara.glasses

/**
 * Screens Mythara renders onto the Meta Display Glasses.
 *
 * Each variant is a pure data class — describes WHAT to show, not
 * HOW. The [GlassesDatFacade.render] implementation is the one place
 * that translates these into the DAT display DSL (flexBox / text /
 * button / icon / image / video).
 *
 * Screens are stacked in [GlassesScreenStore]; a "back" event pops
 * the current screen. The "root" of the stack is [Root] — what the
 * user sees when no other screen is active.
 */
sealed interface GlassesScreen {
    /** Default home: shows a "Mythara" header + buttons for the
     *  six primary actions (PTT, photo, recognize, tone, favorites,
     *  insight). Tap = publish a [GlassesEvent]. */
    object Root : GlassesScreen

    /** While the user is holding PTT — pulsing mic icon + partial
     *  transcript fading in. */
    data class PttListening(val partial: String) : GlassesScreen

    /** Streamed agent reply — word-by-word render to the glasses
     *  display. Falls back to phone TTS / BT audio sink for voice. */
    data class LiveTranscript(val partial: String, val final: String?) : GlassesScreen

    /** Contact profile card shown after a successful face match.
     *  Displays avatar URL + name + a few key traits + last-met
     *  timestamp. Swipe events change which subpane is visible. */
    data class ProfileCard(
        val nameKey: String,
        val displayName: String,
        val avatarUri: String?,
        val toneLabel: String?,
        val keyPoints: List<String>,
        val lastInteractionMs: Long?,
        val subpane: ProfilePane = ProfilePane.Summary,
    ) : GlassesScreen {
        enum class ProfilePane { Summary, RecentTalks, SharedInterests }
    }

    /** Rotating insight ticker — mood + intervention + top concern
     *  (same 60-char snapshot the watch face shows). */
    data class InsightTicker(val text: String) : GlassesScreen

    /** Favorites list — scrollable cards. Each tap publishes
     *  [GlassesEvent.OpenContact] which pushes a ProfileCard. */
    data class FavoritesList(val items: List<FavoriteEntry>) : GlassesScreen {
        data class FavoriteEntry(
            val nameKey: String,
            val displayName: String,
            val avatarUri: String?,
            val tone: String?,
        )
    }

    /** Brief confirmation after a photo capture — thumbnail + caption
     *  (when ready) for 2 s, then auto-pops back to Root. */
    data class PhotoMemoryToast(
        val lifelineId: Long,
        val thumbnailUri: String?,
        val caption: String?,
    ) : GlassesScreen

    /** Error / offline state. */
    data class Error(val message: String) : GlassesScreen
}
