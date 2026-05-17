package com.mythara.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.ui.scaffold.MytharaScaffold
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Debug-only smoke panel for QAing the UI overhaul phases.
 *
 * Renders a long button list — one per route + one per gesture +
 * one per transition family — so a tester can manually verify every
 * navigation path and gesture-handler is wired after a phase lands.
 *
 * Intentionally NOT gated by `BuildConfig.DEBUG` at the file level
 * so the redesign-in-progress can surface it on a feature-flagged
 * route during preview builds. Phase E hides it behind the command
 * palette once the rest of the chrome is in place; in Phase A it
 * can be wired into MytharaRoot under a temporary debug route.
 *
 * @param onNavigate Callback for each "go to route" button. Wire to
 *   the NavHost's `nav.navigate(route)`.
 * @param onFireGesture Callback for each "simulate gesture" button.
 *   In Phase A this is a stub (the gestures are user-driven); later
 *   phases can route it to a fake gesture dispatcher for automated
 *   end-to-end verification.
 */
@Composable
fun SmokeNavPanel(
    onNavigate: (route: String) -> Unit,
    onFireGesture: (label: String) -> Unit = {},
) {
    MytharaScaffold(title = "smoke nav", glyph = Glyph.DiamondOutline) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("◆ navigate")
            for (route in ALL_ROUTES) {
                RouteButton(route = route, onTap = { onNavigate(route) })
            }

            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel("◇ simulate gesture")
            for (label in GESTURE_LABELS) {
                GestureButton(label = label, onTap = { onFireGesture(label) })
            }

            Spacer(modifier = Modifier.height(12.dp))
            SectionLabel("⋯ self-check")
            SelfCheckCard()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MytharaColors.Charple,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun RouteButton(route: String, onTap: () -> Unit) {
    Button(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MytharaColors.Surface,
            contentColor = MytharaColors.Fg,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = Glyph.Arrow, color = MytharaColors.Charple)
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = route, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GestureButton(label: String, onTap: () -> Unit) {
    Button(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MytharaColors.SurfaceMid,
            contentColor = MytharaColors.Fg,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = "${Glyph.DiamondFilled} $label", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SelfCheckCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.SurfaceMid)
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.AccentBar} long-press anywhere outside this panel " +
                "to verify the pre-amulet ring (at ~300 ms) + the rose " +
                "amulet (at ~600 ms). Triple-tap rose for secret unlock. " +
                "PTT hold ≥ 250 ms fires the mic. Swipe left/right on the " +
                "rose to step between Chat ↔ Insights ↔ Face ↔ People.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Every navigable route on the phone app. Kept here as a flat
 *  catalog so this panel can verify they all open in one place.
 *  When MytharaRoot.Routes adds a new route, append it here. */
private val ALL_ROUTES = listOf(
    "chat",
    "settings",
    "about",
    "about-me",
    "insights",
    "secret",
    "notes",
    "people",
    "face",
    "music-vocab",
    "permissions",
    "memory",
    "tasks",
    "triage",
    "usage",
    "dashboard",
    "minimax-signin",
    "canvas",
    "audit",
    "glasses-memory",
)

/** Gesture labels the panel can simulate. The actual gestures are
 *  user-driven through the global pointer pipeline; this list is a
 *  reference for QA + a hook for future end-to-end automation. */
private val GESTURE_LABELS = listOf(
    "long-press (rose)",
    "pre-bloom ring (300 ms hold)",
    "triple-tap rose (secret unlock)",
    "swipe-left on rose",
    "swipe-right on rose",
    "swipe-up on rose (constellation)",
    "PTT hold (≥ 250 ms)",
    "drag rose to constellation chip (glide-to-select)",
)
