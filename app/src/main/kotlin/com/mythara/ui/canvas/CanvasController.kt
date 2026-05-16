package com.mythara.ui.canvas

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide state for the agent-driven Canvas surface.
 *
 * The Canvas is the agent's visual channel — wherever the agent
 * wants to communicate visually (an image, an explainer card, a
 * mini-canvas game, a breath-pacing visual, a tic-tac-toe board),
 * it writes to this controller and the [CanvasScreen] picks it up.
 *
 * Two flows of data:
 *
 *   - **Agent → Canvas (renders):** the agent tools
 *     ([com.mythara.agent.tools.RenderCanvasTool],
 *     [com.mythara.agent.tools.UpdateCanvasTool]) push HTML to
 *     [latestRender] / JS snippets to [pendingJsQueue]. CanvasScreen
 *     observes and applies.
 *
 *   - **Canvas → Agent (input):** the [CanvasBridge] JavaScriptInterface
 *     captures `window.mythara.sendInput(json)` calls and posts to
 *     [inputChannel]. [com.mythara.agent.tools.ReadCanvasInputTool]
 *     receives via `inputChannel.receive()` with a timeout.
 *
 * Singleton (Hilt) so agent tools (which live in the agent module),
 * the chat ViewModel, AND the CanvasScreen all see the same state
 * without a complicated wiring layer.
 *
 * Thread-safety: StateFlow + Channel are both safe for concurrent
 * publish/subscribe across coroutine contexts.
 */
@Singleton
class CanvasController @Inject constructor() {

    /** A single render the Canvas should display. Replaces any
     *  previous render — only the latest matters. Null = show the
     *  ambient default (mood-driven). */
    data class Render(
        val mode: RenderMode,
        /** Inline HTML body for [RenderMode.Inline], absolute file
         *  path / file:// URL for [RenderMode.File], plain URL for
         *  [RenderMode.External]. */
        val payload: String,
        /** When false, CanvasScreen clears back to ambient on
         *  navigation away. */
        val retain: Boolean = false,
        /** Monotonic timestamp so collect() always fires for a new
         *  render even if payload is identical. */
        val ts: Long = System.nanoTime(),
    )

    enum class RenderMode {
        /** Inline HTML body — `webView.loadData(...)`. JS bridge
         *  enabled. */
        Inline,

        /** Absolute file path — `webView.loadUrl("file://...")`.
         *  JS bridge enabled (we wrote the file). */
        File,

        /** External HTTPS URL — `webView.loadUrl(url)`. JS bridge
         *  DISABLED for safety. */
        External,
    }

    private val _latestRender = MutableStateFlow<Render?>(null)
    val latestRender: StateFlow<Render?> = _latestRender.asStateFlow()

    /** Queue of JS snippets to evaluate against the currently-loaded
     *  WebView. Buffered so renders fired before the WebView mounts
     *  aren't lost. Capacity is unbounded; CanvasScreen drains as
     *  fast as it can `evaluateJavascript`. */
    val pendingJsQueue: Channel<String> = Channel(Channel.UNLIMITED)

    /** User input from the JS bridge. ReadCanvasInputTool consumes
     *  with a timeout — if the user doesn't interact the tool just
     *  returns an empty result. */
    val inputChannel: Channel<String> = Channel(Channel.BUFFERED)

    /** Flag the agent sets to request the UI navigate to the Canvas
     *  route on the next reachable composition. MytharaRoot observes
     *  + clears after navigating. */
    private val _navigationRequest = MutableStateFlow(false)
    val navigationRequest: StateFlow<Boolean> = _navigationRequest.asStateFlow()

    /** Push a new render. Calling [com.mythara.agent.tools.RenderCanvasTool]
     *  routes here. If [autoNavigate] is true, the UI will also pivot
     *  to the Canvas route automatically. */
    fun render(render: Render, autoNavigate: Boolean = false) {
        _latestRender.value = render
        if (autoNavigate) _navigationRequest.value = true
    }

    /** Push a JS snippet to evaluate against the current WebView.
     *  Used by [com.mythara.agent.tools.UpdateCanvasTool] to push
     *  incremental changes (game-board update, image swap, etc.)
     *  without a full re-render. */
    fun updateJs(snippet: String) {
        pendingJsQueue.trySend(snippet)
    }

    /** JS bridge calls this when the user interacts with the
     *  rendered content (button click, form submit, game move). */
    internal fun postInput(json: String) {
        inputChannel.trySend(json)
    }

    /** Called by MytharaRoot after it navigates to Canvas in
     *  response to a render request. */
    fun consumeNavigationRequest() {
        _navigationRequest.value = false
    }

    /** Clear the current render — drops back to ambient mood-driven
     *  default. Called by CanvasScreen on Dispose when retain=false. */
    fun clearIfNotRetained() {
        val current = _latestRender.value ?: return
        if (!current.retain) _latestRender.value = null
    }
}
