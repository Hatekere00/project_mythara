package com.mythara.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.services.NotificationActionStore
import com.mythara.services.NotificationAutoProcessStore
import com.mythara.services.NotificationListener
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tiny VM for the two notification-mode toggles (read-aloud +
 * smart auto-action). Lives next to the panel because it's the
 * only consumer.
 */
@HiltViewModel
class NotificationAutoProcessViewModel @Inject constructor(
    private val store: NotificationAutoProcessStore,
    private val actionStore: NotificationActionStore,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = store.enabledFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    val autoActionEnabled: StateFlow<Boolean> = actionStore.enabledFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }

    fun setAutoActionEnabled(value: Boolean) {
        viewModelScope.launch { actionStore.setEnabled(value) }
    }
}

/**
 * Settings panel for the M5 NotificationListener. Mirrors the
 * AccessibilityPanel shape — runtime-bound flag + system-listed
 * check + deep-link to system settings. Notification access lives
 * under a different system intent action than Accessibility.
 *
 * The auto-process toggle below the system-access state controls
 * whether new notifications get auto-routed through the agent loop
 * (and read aloud) the moment they arrive.
 */
@Composable
fun NotificationAccessPanel(
    vm: NotificationAutoProcessViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtimeEnabled by NotificationListener.isEnabled.collectAsState()
    val autoProcess by vm.enabled.collectAsState()
    val autoAction by vm.autoActionEnabled.collectAsState()
    var listed by remember { mutableStateOf(isNotificationAccessListed(ctx)) }
    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listed = isNotificationAccessListed(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    val ready = runtimeEnabled || listed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} notification access",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        val (glyph, color, label) = when {
            runtimeEnabled -> Triple(
                Glyph.Dot, MytharaColors.Julep,
                "active — read_notifications tool can see your status bar",
            )
            listed -> Triple(
                Glyph.CircleOutline, MytharaColors.Mustard,
                "granted in system settings but not yet bound — re-open this screen",
            )
            else -> Triple(
                Glyph.Cross, MytharaColors.Sriracha,
                "not granted — agent can't read your notifications yet",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(end = 6.dp))
            Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { openNotificationAccessSettings(ctx) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ready) MytharaColors.Surface else MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text(
                text = if (ready) "${Glyph.Refresh} re-open notification access settings"
                else "${Glyph.Arrow} open notification access settings",
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} the system page that opens lets you grant individual apps access to your notifications. Find Mythara, toggle it on, accept the warning. Buffer is in-memory only — notifications never get persisted or synced.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = ready) { vm.setEnabled(!autoProcess) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (autoProcess) Glyph.CircleFilled else Glyph.CircleOutline,
                color = when {
                    !ready -> MytharaColors.FgDim
                    autoProcess -> MytharaColors.Charple
                    else -> MytharaColors.FgMute
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "auto-read new notifications aloud",
                color = if (ready) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = "${Glyph.AccentBar} when on, every new notification (skipping ongoing pings and Mythara's own) is funnelled through the agent — Mythara summarises in ≤15 words and speaks it out. Reply 'NOSURFACE' from the model silently drops noise. Burns MiniMax tokens on every push, so it's off by default.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )

        // ----- Smart auto-action toggle -----
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = ready) { vm.setAutoActionEnabled(!autoAction) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (autoAction) Glyph.CircleFilled else Glyph.CircleOutline,
                color = when {
                    !ready -> MytharaColors.FgDim
                    autoAction -> MytharaColors.Charple
                    else -> MytharaColors.FgMute
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "smart auto-action (learn what to dismiss)",
                color = if (ready) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = "${Glyph.AccentBar} Mythara watches which apps' notifications you usually swipe away. After 3+ dismisses with a 70%+ dismissal rate per app, it auto-dismisses new ones from that app before they distract you. Ask Mythara 'what did you dismiss?' to see the log. Doesn't auto-reply to messages yet — that's coming.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun openNotificationAccessSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

/**
 * Check the system's enabled-listeners string for our component name.
 * Same one-line check as the AccessibilityPanel — works without the
 * service being currently bound, so the panel shows the right state
 * during the brief window after the user toggles in system settings
 * but before Android re-binds our service.
 */
private fun isNotificationAccessListed(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        "enabled_notification_listeners",
    ).orEmpty()
    val ours = ComponentName(ctx.packageName, "com.mythara.services.NotificationListener")
        .flattenToString()
    return enabled.split(':').any { it.equals(ours, ignoreCase = true) }
}
