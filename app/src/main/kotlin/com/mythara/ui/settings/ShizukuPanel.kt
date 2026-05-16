package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import com.mythara.services.ShizukuService
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings panel for Shizuku — the privileged-shell shim Mythara
 * uses to apply non-invasive cosmetic Android changes (font scale,
 * dark mode, accent colour, gesture-nav style).
 *
 * Surfaces:
 *   - Current Shizuku state (not installed / not running / permission
 *     denied / ready).
 *   - Step-by-step setup instructions tailored to the current state.
 *   - "Request permission" button when running but not granted.
 */
@HiltViewModel
class ShizukuPanelViewModel @Inject constructor(
    val shizuku: ShizukuService,
) : ViewModel()

@Composable
fun ShizukuPanel(vm: ShizukuPanelViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(vm.shizuku.state(ctx.packageManager)) }

    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = vm.shizuku.state(ctx.packageManager)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} shizuku — privileged shell shim for cosmetic system changes",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(6.dp))

        val statusColor = when (state) {
            ShizukuService.State.Ready -> MytharaColors.Bok
            ShizukuService.State.PermissionDenied -> MytharaColors.Mustard
            else -> MytharaColors.Charple
        }
        Text(
            text = "${Glyph.Dot} state: ${state.name}",
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            ShizukuService.State.NotInstalled -> Text(
                text = "${Glyph.AccentBar} Install the Shizuku app from Google Play (free, by RikkaW). " +
                    "Once installed, return here.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            ShizukuService.State.NotRunning -> Text(
                text = "${Glyph.AccentBar} Open the Shizuku app and bootstrap it via wireless debugging " +
                    "(Android 11+) or adb. Wait for the green 'Running' status, then return here.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            ShizukuService.State.PermissionDenied -> {
                Text(
                    text = "${Glyph.AccentBar} Shizuku is running but Mythara doesn't have permission yet. " +
                        "Tap the button to request it.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            vm.shizuku.requestPermission()
                            state = vm.shizuku.state(ctx.packageManager)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Bg,
                    ),
                ) { Text("request permission") }
            }
            ShizukuService.State.Ready -> Text(
                text = "${Glyph.AccentBar} Shizuku ready. Mythara's `apply_cosmetic` agent tool can now " +
                    "tweak font scale, dark mode, accent colour, gesture-nav style, animation speeds, " +
                    "and the blue-light filter on demand.",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
