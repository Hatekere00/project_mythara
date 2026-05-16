package com.mythara.ui.canvas

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The `window.mythara` JavaScript-side bridge.
 *
 * Attached to the WebView via `addJavascriptInterface(bridge, "mythara")`.
 * Only attached when the WebView is loading **trusted, agent-authored**
 * content ([CanvasController.RenderMode.Inline] / [CanvasController.RenderMode.File]).
 * For external URLs the bridge is removed first via
 * `removeJavascriptInterface("mythara")` — see [CanvasScreen].
 *
 * Available JS methods (all are `@JavascriptInterface`):
 *
 *   - `mythara.sendInput(json)` — user posts structured input back
 *     to the agent. The JSON shape is whatever the agent's render
 *     code expects to consume. Example: a tic-tac-toe cell tap
 *     calls `mythara.sendInput(JSON.stringify({game:"ttt",move:5}))`.
 *
 *   - `mythara.log(level, message)` — debug logging captured into
 *     Android logcat under `Mythara/Canvas`.
 */
class CanvasBridge(
    private val controller: CanvasController,
) {

    @JavascriptInterface
    fun sendInput(json: String?) {
        if (json.isNullOrBlank()) return
        Log.d(TAG, "input: ${json.take(120)}")
        controller.postInput(json)
    }

    @JavascriptInterface
    fun log(level: String?, message: String?) {
        val msg = message ?: return
        when (level?.lowercase()) {
            "error" -> Log.e(TAG, msg)
            "warn", "warning" -> Log.w(TAG, msg)
            "info" -> Log.i(TAG, msg)
            else -> Log.d(TAG, msg)
        }
    }

    companion object {
        private const val TAG = "Mythara/Canvas"
    }
}
