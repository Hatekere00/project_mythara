package com.mythara.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mythara.services.PhoneControlAccessibilityService
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Settings panel for the M5 Accessibility-driven tools. Surfaces
 * three things:
 *
 *  1. Live status pill — bound to the service's runtime instance flag.
 *  2. System-state check — Settings.Secure's enabled-accessibility list,
 *     re-read on resume since the user comes back here after toggling
 *     in Android's Accessibility settings.
 *  3. Deep-link button → Settings → Accessibility, where the user
 *     toggles Mythara on (we can't programmatically enable an
 *     accessibility service).
 */
@Composable
fun AccessibilityPanel() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtimeEnabled by PhoneControlAccessibilityService.isEnabled.collectAsState()
    // System-level "is our service in the enabled-services list?" check.
    // Re-runs on resume so toggling in system Settings flows back here.
    var listed by remember { mutableStateOf(isAccessibilityListed(ctx)) }
    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                listed = isAccessibilityListed(ctx)
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
            text = "${Glyph.DiamondOutline} screen-reading + phone control",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        val (glyph, color, label) = when {
            runtimeEnabled -> Triple(Glyph.Dot, MytharaColors.Julep, "active — read_screen tool is live")
            listed -> Triple(
                Glyph.CircleOutline, MytharaColors.Mustard,
                "enabled in system settings but not yet bound — try opening this screen again",
            )
            else -> Triple(
                Glyph.Cross, MytharaColors.Sriracha,
                "not granted — agent can't see your screen yet",
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.padding(end = 6.dp))
            Text(label, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { openAccessibilitySettings(ctx) },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ready) MytharaColors.Surface else MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text(
                text = if (ready) "${Glyph.Refresh} re-open accessibility settings"
                else "${Glyph.Arrow} open accessibility settings",
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} this is the manual Android permission step — the app can't enable accessibility services on its own. In the system settings page that opens, scroll to Mythara, toggle it on, and accept the warning. Comes back here when done.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun openAccessibilitySettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

/**
 * Check the system's enabled-services string for our component name.
 * Cheap O(1) lookup; doesn't require the service to be currently
 * bound (handles cases where it's enabled but the system hasn't
 * re-attached yet).
 */
private fun isAccessibilityListed(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    val ourName = "${ctx.packageName}/com.mythara.services.PhoneControlAccessibilityService"
    return enabled.split(':').any { it.equals(ourName, ignoreCase = true) }
}
