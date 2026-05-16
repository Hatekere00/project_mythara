package com.mythara.ui.canvas

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mythara.branding.MoodSink
import com.mythara.branding.MoodVisualMapping
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The agent's visual surface.
 *
 * Mounts a single [WebView] driven by [CanvasController]. Three load
 * modes (see [CanvasController.RenderMode]):
 *
 *   - **Ambient** (no agent render active): loads
 *     `file:///android_asset/canvas/ambient.html` and applies the
 *     palette + breath rhythm picked by [MoodVisualMapping] for the
 *     user's currently-detected mood. JS bridge attached so the page
 *     can log + receive `setInterventionLabel`.
 *
 *   - **Inline**: agent passed an HTML body — `loadData()`. JS bridge
 *     attached.
 *
 *   - **File**: agent wrote an HTML file to filesDir/canvas/ and
 *     wants it loaded — `loadUrl("file://…")`. JS bridge attached.
 *
 *   - **External**: agent passed an https URL — JS bridge REMOVED
 *     before load (we don't want third-party JS calling
 *     `window.mythara.*`), then `loadUrl()`.
 *
 * Pattern reference: `MiniMaxWebSignIn.kt` already uses the same
 * `AndroidView { WebView(...) }` pattern with a CookieManager / page-
 * finished callback. Here we're driving the WebView from an external
 * controller instead of binding to a specific URL.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    val controller: CanvasController,
) : ViewModel()

@Composable
fun CanvasScreen(
    onBack: () -> Unit,
    vm: CanvasViewModel = hiltViewModel(),
) {
    val controller = vm.controller
    val latestRender by controller.latestRender.collectAsState()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastAppliedTs by remember { mutableStateOf(0L) }

    // Pump the pendingJsQueue → webView.evaluateJavascript. Runs
    // forever while the screen is mounted; on dispose, the channel
    // stays alive (next mount drains backlog).
    LaunchedEffect(webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        for (js in controller.pendingJsQueue) {
            try {
                wv.post { wv.evaluateJavascript(js, null) }
            } catch (t: Throwable) {
                Log.w(TAG, "evaluateJavascript failed: ${t.message}")
            }
        }
    }

    // Apply renders as they arrive. Compare by `ts` so we re-apply
    // even if the payload happens to be identical to the previous
    // one (the agent might want to reset state).
    LaunchedEffect(latestRender, webViewRef) {
        val render = latestRender ?: return@LaunchedEffect
        val wv = webViewRef ?: return@LaunchedEffect
        if (render.ts == lastAppliedTs) return@LaunchedEffect
        lastAppliedTs = render.ts
        wv.post { applyRender(wv, render, controller) }
    }

    // When the screen mounts with NO agent render active, load the
    // ambient default tuned to current mood. Re-apply on every new
    // mount so a navigate-away-and-back picks up mood drift.
    LaunchedEffect(webViewRef, latestRender == null) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (latestRender == null) {
            wv.post { loadAmbient(wv, controller) }
        }
    }

    // On screen exit, clear non-retained renders so a re-entry shows
    // the ambient default rather than stale content.
    DisposableEffect(controller) {
        onDispose { controller.clearIfNotRetained() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            val mood = MoodSink.current() ?: "—"
            val intervention = MoodVisualMapping.forMood(mood)
            Text(
                text = "${Glyph.AccentBar} canvas · ${intervention.label}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            )
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Surface),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    @SuppressLint("SetJavaScriptEnabled")
                    val wv = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        // Hide scrollbars — the agent's renders should
                        // fit the viewport and external sites can deal.
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(
                                msg: android.webkit.ConsoleMessage,
                            ): Boolean {
                                Log.d(TAG, "[js] ${msg.message()} @ ${msg.lineNumber()}")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false
                        }
                        // Initial JS bridge attachment for the ambient
                        // load. applyRender() and loadAmbient() each
                        // re-attach / remove appropriately for their
                        // mode.
                        addJavascriptInterface(CanvasBridge(controller), BRIDGE_NAME)
                    }
                    webViewRef = wv
                    wv
                },
            )
        }
    }
}

/** Loads the ambient default page + sets CSS vars for the current
 *  mood-driven palette + breath rhythm. */
private fun loadAmbient(webView: WebView, controller: CanvasController) {
    val mood = MoodSink.current()
    val intervention = MoodVisualMapping.forMood(mood)
    val palette = paletteCss(intervention.paletteName)
    val breathSec = intervention.breathPeriodMs / 1000.0

    // Bridge MUST be attached for ambient (we control the page).
    runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
    webView.addJavascriptInterface(CanvasBridge(controller), BRIDGE_NAME)

    webView.loadUrl(AMBIENT_URL)

    // After the page loads, push palette + label via JS. loadUrl is
    // async so chain via a one-shot post — the asset is local and
    // tiny, ~200 ms is conservative.
    webView.postDelayed({
        val js = """
            (function() {
              var s = document.documentElement.style;
              s.setProperty('--palette-top', '${palette.topHex}');
              s.setProperty('--palette-bottom', '${palette.botHex}');
              s.setProperty('--accent', '${palette.accentHex}');
              s.setProperty('--breath-period', '${breathSec}s');
              if (window.setInterventionLabel) {
                window.setInterventionLabel('${intervention.label}');
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }, 250L)
}

/** Renders the controller's latest [CanvasController.Render]. */
private fun applyRender(
    webView: WebView,
    render: CanvasController.Render,
    controller: CanvasController,
) {
    when (render.mode) {
        CanvasController.RenderMode.Inline -> {
            runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
            webView.addJavascriptInterface(CanvasBridge(controller), BRIDGE_NAME)
            webView.loadDataWithBaseURL(
                /* baseUrl  = */ "file:///android_asset/canvas/",
                /* data     = */ render.payload,
                /* mimeType = */ "text/html",
                /* encoding = */ "utf-8",
                /* historyUrl = */ null,
            )
        }
        CanvasController.RenderMode.File -> {
            runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
            webView.addJavascriptInterface(CanvasBridge(controller), BRIDGE_NAME)
            val url = if (render.payload.startsWith("file://")) {
                render.payload
            } else {
                "file://${render.payload}"
            }
            webView.loadUrl(url)
        }
        CanvasController.RenderMode.External -> {
            // SAFETY: third-party JS must NOT see `window.mythara`.
            runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
            webView.loadUrl(render.payload)
        }
    }
}

/** CSS-friendly palette tuple for the ambient page. */
private data class PaletteCss(val topHex: String, val botHex: String, val accentHex: String)

private fun paletteCss(name: String): PaletteCss = when (name) {
    "cool-teal" -> PaletteCss("#04181c", "#0d3a44", "#6ed3e0")
    "warm-amber" -> PaletteCss("#1a0c04", "#3a2210", "#f5b860")
    "forest-green" -> PaletteCss("#031208", "#0d3320", "#7dd49a")
    "deep-ocean" -> PaletteCss("#020a1e", "#08204a", "#6694d7")
    "bright-purple" -> PaletteCss("#100620", "#3d1f60", "#c89cff")
    else -> PaletteCss("#06040c", "#2a1740", "#b187ff") // neutral Mythara
}

private const val BRIDGE_NAME = "mythara"
private const val AMBIENT_URL = "file:///android_asset/canvas/ambient.html"
private const val TAG = "Mythara/Canvas"
