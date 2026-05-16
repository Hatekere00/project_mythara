package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.UserNameStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserNamePanelViewModel @Inject constructor(
    private val store: UserNameStore,
) : ViewModel() {
    val name: StateFlow<String> = store.nameFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "")

    fun setName(value: String) {
        viewModelScope.launch { store.setName(value) }
    }
}

/**
 * "What should I call you?" panel. Persists the user's preferred
 * name; AgentLoop injects it as a one-line system message so Mythara
 * uses it sparingly (greetings, callbacks) — never sycophantically.
 *
 * Empty value clears the preference and reverts the agent to
 * generic address.
 */
@Composable
fun UserNamePanel(vm: UserNamePanelViewModel = hiltViewModel()) {
    val name by vm.name.collectAsState()
    var draft by remember { mutableStateOf(name) }
    LaunchedEffect(name) { draft = name }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} what should I call you?",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text("e.g. Ankur, A, or just leave blank", color = MytharaColors.FgDim) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MytharaColors.Fg,
                unfocusedTextColor = MytharaColors.Fg,
                focusedBorderColor = MytharaColors.Charple,
                unfocusedBorderColor = MytharaColors.SurfaceHigh,
                cursorColor = MytharaColors.Charple,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { scope.launch { vm.setName(draft) } },
                enabled = draft != name,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text("${Glyph.Check} save")
            }
            if (name.isNotBlank()) {
                Text(
                    text = "current: $name",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} Mythara will use this name sparingly — as a greeting, acknowledgement, or occasional callback. Never sycophantically. Leave blank to stay generic.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
