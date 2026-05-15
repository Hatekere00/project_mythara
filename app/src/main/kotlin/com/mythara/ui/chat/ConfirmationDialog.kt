package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mythara.agent.ConfirmationGate
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Modal Compose dialog that renders one [ConfirmationGate.ConfirmRequest]
 * and reports the user's Allow / Deny decision back via callbacks.
 *
 * Sticks to the same Crush-ish styling as the chat surface — bordered
 * Surface card, JetBrains Mono body, Charple accent, Sriracha for
 * the cancel action. No system AlertDialog so the chrome stays
 * on-theme.
 *
 * If the request's allowlistKey is non-null we show an
 * "always allow this" toggle row above the buttons; ticking it
 * persists the decision so subsequent same-key calls skip the
 * prompt. Use sparingly — the allowlist editor in Settings lets the
 * user revoke.
 */
@Composable
fun ConfirmationDialog(
    request: ConfirmationGate.ConfirmRequest,
    onResolve: (decision: ConfirmationGate.Decision, alwaysAllow: Boolean) -> Unit,
) {
    var alwaysAllow by remember(request.id) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onResolve(ConfirmationGate.Decision.Deny, false) },
        properties = DialogProperties(
            // Tap-outside dismisses as Deny (safe default). Back
            // button same. The agent loop interprets Deny as
            // "user_canceled" and gives the user back to the model.
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MytharaColors.Surface)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(14.dp))
                .padding(18.dp),
        ) {
            Text(
                text = "${Glyph.DiamondFilled} ${request.toolName}",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = request.title,
                style = MaterialTheme.typography.titleMedium.copy(color = MytharaColors.Fg),
            )
            if (request.body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = request.body,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MytharaColors.Fg),
                )
            }

            // Show the "always allow" toggle for per-call allowlist
            // prompts AND for critical-action prompts — the latter
            // de-lists the app from the critical list entirely.
            if (request.allowlistKey != null || request.criticalPkg != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { alwaysAllow = !alwaysAllow }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (alwaysAllow) Glyph.CircleFilled else Glyph.CircleOutline,
                        color = if (alwaysAllow) MytharaColors.Charple else MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(end = 8.dp))
                    Text(
                        text = if (request.criticalPkg != null) {
                            "always allow — stop treating this app as critical"
                        } else {
                            "always allow this"
                        },
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onResolve(ConfirmationGate.Decision.Deny, false) }) {
                    Text("${Glyph.Cross} deny", color = MytharaColors.Sriracha)
                }
                Spacer(Modifier.padding(end = 4.dp))
                Button(
                    onClick = { onResolve(ConfirmationGate.Decision.Allow, alwaysAllow) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Check} allow")
                }
            }
        }
    }
}
