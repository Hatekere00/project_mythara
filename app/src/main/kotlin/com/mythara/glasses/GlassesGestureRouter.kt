package com.mythara.glasses

import android.content.Context
import android.util.Log
import com.mythara.analytics.ContactProfileRepository
import com.mythara.data.FavoritesStore
import com.mythara.data.MusicModeStore
import com.mythara.glasses.photo.GlassesPhotoCapture
import com.mythara.voice.VoiceActionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to [GlassesDatFacade.events] and translates each
 * [GlassesEvent] into the corresponding existing Mythara action.
 *
 * Why a separate router (instead of having each subscriber consume
 * events directly): the events from the glasses are the only "input
 * surface" Mythara has from that device, so centralising the
 * translation here keeps the action policies in one place — easy to
 * audit + change without touching DAT specifics or the existing
 * voice / photo / tone subsystems.
 *
 * Started from [GlassesConnectionService] when the FGS launches.
 */
@Singleton
class GlassesGestureRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceActions: VoiceActionStore,
    private val musicMode: MusicModeStore,
    private val favoritesStore: FavoritesStore,
    private val contactRepo: ContactProfileRepository,
    private val screenStore: GlassesScreenStore,
    private val photoCapture: GlassesPhotoCapture,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            GlassesDatFacade.events.collect { event ->
                runCatching { handle(event) }
                    .onFailure { Log.w(TAG, "handle($event) threw: ${it.message}") }
            }
        }
    }

    private suspend fun handle(event: GlassesEvent) {
        Log.d(TAG, "handling glasses event: $event")
        when (event) {
            is GlassesEvent.PttStart -> {
                voiceActions.fire(VoiceActionStore.Source.GlassesPress)
                screenStore.push(GlassesScreen.PttListening(partial = ""))
            }

            is GlassesEvent.PttStop -> {
                // ChatViewModel's SpeechRecognizer auto-finalises on
                // silence; we don't need an explicit stop. Pop the
                // PttListening screen back to Root — LiveTranscript
                // gets pushed by the reply-rendering side once the
                // model streams its answer.
                screenStore.pop()
            }

            is GlassesEvent.PhotoCapture -> {
                photoCapture.captureNow()
            }

            is GlassesEvent.RecognizePerson -> {
                photoCapture.captureAndRecognize()
            }

            is GlassesEvent.ToggleToneMode -> {
                // MusicModeStore exposes a Flow + setEnabled — read
                // the current value via first() then invert.
                val current = musicMode.enabledFlow().first()
                musicMode.setEnabled(!current)
                Log.d(TAG, "tone mode → ${!current}")
            }

            is GlassesEvent.OpenFavorites -> {
                val items = runCatching {
                    favoritesStore.list().map { fav ->
                        val profile = runCatching {
                            contactRepo.dao.byKey(fav.name.lowercase())
                        }.getOrNull()
                        GlassesScreen.FavoritesList.FavoriteEntry(
                            nameKey = fav.name.lowercase(),
                            displayName = fav.name,
                            avatarUri = profile?.photoUri,
                            tone = fav.toneLabel,
                        )
                    }
                }.getOrDefault(emptyList())
                screenStore.push(GlassesScreen.FavoritesList(items))
            }

            is GlassesEvent.OpenContact -> {
                val profile = runCatching {
                    contactRepo.dao.byKey(event.nameKey)
                }.getOrNull() ?: return
                screenStore.push(
                    GlassesScreen.ProfileCard(
                        nameKey = profile.nameKey,
                        displayName = profile.displayName,
                        avatarUri = profile.photoUri,
                        toneLabel = profile.toneLabel,
                        keyPoints = runCatching {
                            // notableTraitsJson is a JSON string array;
                            // parse cheaply (avoid pulling kotlinx.json
                            // for this hot path — split is enough since
                            // the writer always produces clean JSON).
                            profile.notableTraitsJson
                                ?.removePrefix("[")?.removeSuffix("]")
                                ?.split(",")
                                ?.map { it.trim().trim('"') }
                                ?.filter { it.isNotBlank() }
                                ?.take(3)
                                .orEmpty()
                        }.getOrDefault(emptyList()),
                        lastInteractionMs = profile.lastInteractionMs.takeIf { it > 0 },
                    ),
                )
            }

            is GlassesEvent.Swipe -> {
                // Update the current screen's subpane if applicable.
                val current = screenStore.current.value
                if (current is GlassesScreen.ProfileCard) {
                    val nextPane = when (current.subpane) {
                        GlassesScreen.ProfileCard.ProfilePane.Summary ->
                            if (event.direction == GlassesEvent.Direction.Left) {
                                GlassesScreen.ProfileCard.ProfilePane.RecentTalks
                            } else {
                                GlassesScreen.ProfileCard.ProfilePane.SharedInterests
                            }
                        GlassesScreen.ProfileCard.ProfilePane.RecentTalks ->
                            GlassesScreen.ProfileCard.ProfilePane.SharedInterests
                        GlassesScreen.ProfileCard.ProfilePane.SharedInterests ->
                            GlassesScreen.ProfileCard.ProfilePane.Summary
                    }
                    screenStore.replaceTop(current.copy(subpane = nextPane))
                }
            }

            is GlassesEvent.Back -> screenStore.pop()
        }
    }

    companion object {
        private const val TAG = "Mythara/GlassesRouter"
    }
}
