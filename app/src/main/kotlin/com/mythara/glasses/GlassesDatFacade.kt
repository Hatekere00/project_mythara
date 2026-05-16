package com.mythara.glasses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin façade over the Meta DAT SDK so the rest of Mythara doesn't
 * directly import `com.meta.wearable.dat.*`.
 *
 * ## Why this exists
 *
 * The Meta DAT SDK lives on GitHub Packages. Pulling it requires a
 * GitHub personal-access token with `read:packages` scope which the
 * v3 development cluster doesn't have configured yet. Until the
 * token is in place + the three `implementation(libs.mwdat.*)` lines
 * in `app/build.gradle.kts` are uncommented, every method here
 * returns a no-op result (logs once) so the rest of v3 keeps
 * building + running without DAT.
 *
 * When the user does configure the token, the entire wire-up to
 * the real SDK happens INSIDE this file — every other v3 file
 * (gesture router, photo ingester, face pipeline, glasses memory
 * screen, etc.) is already plumbed against this façade.
 *
 * ## What to wire when the SDK is available
 *
 * Replace the TODO bodies with the corresponding DAT calls — the
 * signatures already match what the SDK exposes. The DAT skill
 * docs (~/.claude/plugins/cache/mwdat-android-marketplace/...)
 * cover the exact patterns:
 *
 *   • `initializeIfAvailable(ctx)` →
 *       Wearables.initialize(ctx).onFailure { ... }
 *
 *   • `startRegistration(activity)` →
 *       Wearables.startRegistration(activity)
 *
 *   • `connectionState` →
 *       Wearables.registrationState.map { it -> GlassesConnectionState.from(it) }
 *
 *   • `createSession()` →
 *       Wearables.createSession(AutoDeviceSelector(filter = { it.isDisplayCapable() }))
 *
 *   • `capturePhoto()` →
 *       stream.capturePhoto().map { it.data → Bitmap }
 *
 *   • `renderToDisplay(screen)` →
 *       display.sendContent { ... } translating GlassesScreen into
 *       the DAT display DSL (flexBox/text/button/icon/image/video)
 */
object GlassesDatFacade {

    private const val TAG = "Mythara/GlassesDAT"
    private var initializedOnce = false

    /** Last-known device-link / pairing state. UI panels collect this
     *  to surface "not connected" / "paired" / "session running" hints. */
    private val _connectionState = MutableStateFlow(GlassesConnectionState.NotInitialized)
    val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    /** Latest event from the glasses-side input router (button taps in
     *  the rendered display UI). Subscribers (GlassesGestureRouter)
     *  translate these into Mythara actions (PTT, photo, recognize,
     *  toggle tone, etc.). */
    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    /** True iff the DAT SDK classpath is present + initialized. False
     *  during the stub phase (no GITHUB_TOKEN / commented-out deps). */
    fun isAvailable(): Boolean = false   // TODO(metaDat): return Wearables.isInitialized

    fun initializeIfAvailable(context: Context) {
        if (initializedOnce) return
        initializedOnce = true
        // TODO(metaDat): wrap in try/catch around Class.forName check
        //   try {
        //     com.meta.wearable.dat.core.Wearables.initialize(context)
        //         .onFailure { e, _ -> Log.w(TAG, "init failed: ${e.description}") }
        //     _connectionState.value = GlassesConnectionState.Initialized
        //   } catch (_: ClassNotFoundException) {
        //     Log.i(TAG, "DAT SDK not on classpath — glasses features disabled")
        //   }
        Log.i(TAG, "DAT facade stubbed — uncomment mwdat deps + fill in this file to enable glasses")
        _connectionState.value = GlassesConnectionState.NotInitialized
    }

    fun startRegistration(activity: android.app.Activity) {
        // TODO(metaDat): Wearables.startRegistration(activity)
        Log.w(TAG, "startRegistration called but DAT facade is stubbed")
    }

    fun startUnregistration(activity: android.app.Activity) {
        // TODO(metaDat): Wearables.startUnregistration(activity)
        Log.w(TAG, "startUnregistration called but DAT facade is stubbed")
    }

    /** Open a session + start the camera stream + the display
     *  capability. Returns true on success. Returns false (no-op +
     *  log) when the facade is stubbed. */
    suspend fun startSession(): Boolean {
        // TODO(metaDat):
        //   val session = Wearables.createSession(AutoDeviceSelector(
        //     filter = { it.isDisplayCapable() })).getOrElse { return false }
        //   session.start()
        //   stream = session.addStream(StreamConfiguration(VideoQuality.MEDIUM, 24))
        //     .getOrElse { return false }
        //   stream.start().getOrElse { return false }
        //   display = session.addDisplay().getOrElse { return false }
        //   _connectionState.value = GlassesConnectionState.SessionActive
        //   return true
        Log.w(TAG, "startSession called but DAT facade is stubbed")
        return false
    }

    /** Stop everything; called on FGS shutdown. */
    fun stopSession() {
        // TODO(metaDat):
        //   stream?.stop(); display?.let { session?.removeDisplay() }
        //   session?.stop(); _connectionState.value = GlassesConnectionState.Disconnected
        Log.w(TAG, "stopSession called but DAT facade is stubbed")
    }

    /** Capture a still from the glasses POV. Returns null when the
     *  facade is stubbed or when capture fails. */
    suspend fun capturePhoto(): Bitmap? {
        // TODO(metaDat):
        //   stream?.capturePhoto()?.fold(
        //     onSuccess = { BitmapFactory.decodeByteArray(it.data, 0, it.data.size) },
        //     onFailure = { _, _ -> null }
        //   )
        Log.w(TAG, "capturePhoto called but DAT facade is stubbed")
        return null
    }

    /** Render a [GlassesScreen] onto the glasses display. Returns
     *  true on success. Translates the screen into the DAT display
     *  DSL (flexBox / text / button / icon / image / video). */
    suspend fun render(screen: GlassesScreen): Boolean {
        // TODO(metaDat): see GlassesScreenRenderer.kt sibling file
        Log.d(TAG, "render(${screen::class.simpleName}) — stubbed, would render to glasses")
        return false
    }

    /** Called by the (TODO-wired) display button-click handlers to
     *  publish events back to Mythara's event bus. While stubbed,
     *  the in-app GlassesPanel can fire these directly for testing. */
    fun publishEvent(event: GlassesEvent) {
        _events.tryEmit(event)
    }
}

/** Lifecycle phases the rest of Mythara can render UI from. */
enum class GlassesConnectionState {
    /** DAT not on classpath OR Wearables.initialize hasn't been called. */
    NotInitialized,
    /** SDK initialized; not yet paired with Meta AI / no devices. */
    Initialized,
    /** Paired with Meta AI; glasses discoverable but not in active session. */
    Paired,
    /** Active DeviceSession + Stream + Display capability attached. */
    SessionActive,
    /** Session ended (user removed glasses, BT dropped, etc.). */
    Disconnected,
    /** Error state — surface description via GlassesPanel. */
    Error,
}
