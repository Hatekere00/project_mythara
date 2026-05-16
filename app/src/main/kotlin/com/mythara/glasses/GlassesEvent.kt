package com.mythara.glasses

/**
 * Events produced by glasses-side input (rendered DAT display button
 * clicks routed back to the phone). The neural band doesn't expose
 * raw gesture data in DAT SDK 0.7 — instead, button-tap callbacks
 * from the rendered Display UI are what reaches us.
 *
 * The Mythara root screen rendered on the glasses contains buttons
 * for each of these events; tapping a button calls
 * [GlassesDatFacade.publishEvent] which propagates here.
 *
 * Subscribed by [GlassesGestureRouter]; the router translates each
 * event into a Mythara action (fire voice trigger, capture photo,
 * toggle tone mode, etc.) without the rest of the app needing to
 * know about DAT specifics.
 */
sealed interface GlassesEvent {
    /** User tapped the PTT button — start mic + transcript stream. */
    object PttStart : GlassesEvent

    /** User released the PTT button — stop mic + submit transcript. */
    object PttStop : GlassesEvent

    /** Take a photo from the glasses camera and add to lifeline.
     *  Triggers face-analysis worker after capture. */
    object PhotoCapture : GlassesEvent

    /** Take a photo + run face match + render the matched contact's
     *  ProfileCard on the glasses. */
    object RecognizePerson : GlassesEvent

    /** Toggle Music Mode (tone-based replies vs TTS). */
    object ToggleToneMode : GlassesEvent

    /** Open the Favorites list screen. */
    object OpenFavorites : GlassesEvent

    /** Navigate within the current screen (e.g. profile-card swipe
     *  between recent-talks / shared-interests). */
    data class Swipe(val direction: Direction) : GlassesEvent

    /** User tapped a specific favorite or contact card. */
    data class OpenContact(val nameKey: String) : GlassesEvent

    /** Dismiss / back. */
    object Back : GlassesEvent

    enum class Direction { Left, Right, Up, Down }
}
