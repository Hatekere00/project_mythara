package com.mythara.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mythara's window-reading service. Granted via system Settings →
 * Accessibility (user-driven, can't be auto-granted by the app).
 *
 * Today it exposes:
 *   - [currentRootNode] — the AccessibilityNodeInfo of whatever's
 *     in the foreground. Read-only snapshot for the `read_screen`
 *     agent tool.
 *   - [isEnabled] — process-wide observable so the Settings panel
 *     can show a status pill without polling.
 *
 * M6 will grow this with `dispatchGesture(...)` plumbing for the
 * `tap` / `swipe` / `type_text` tools. The xml/accessibility_service_config
 * already declares `canPerformGestures=true` so the user only grants
 * the permission once and gets both read + gesture surface together.
 *
 * Lifecycle: Android wires this up automatically once the user
 * enables it in Accessibility settings. `onServiceConnected()` fires
 * when the system attaches us; `onDestroy()` when the user toggles
 * us off or the system kills the service.
 */
class PhoneControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        Log.d(TAG, "service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
            _isEnabled.value = false
        }
        Log.d(TAG, "service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Snapshot model — we don't react to events live. The
        // `read_screen` tool pulls [rootInActiveWindow] on demand.
        // This callback exists because the service contract requires it.
    }

    override fun onInterrupt() {
        // System asked us to stop processing temporarily. No-op for a
        // snapshot-on-demand service; the next read_screen call will
        // succeed once interruption clears.
    }

    /** Snapshot of the foreground window's root node. Null if the
     *  service hasn't been granted by the user or no window is active. */
    fun currentRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    companion object {
        private const val TAG = "Mythara/A11y"

        /** Live process-wide handle. Null when the service isn't
         *  currently bound (user hasn't enabled it, or system killed it). */
        @Volatile var instance: PhoneControlAccessibilityService? = null
            private set

        private val _isEnabled = MutableStateFlow(false)

        /** Observable enabled-state for UI status pills. */
        val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    }
}
