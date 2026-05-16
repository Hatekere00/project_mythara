package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.LinuxBridgeStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings panel for the Android 15 Linux Terminal SSH bridge used
 * by `linux_vm` agent tool.
 *
 * The user opens the Linux Terminal app once, runs
 * `apt install openssh-server && service ssh start`, then pastes the
 * connection details here. Mythara then SSHes in for any
 * `linux_vm`-tool invocations from the agent.
 */
@HiltViewModel
class LinuxBridgeViewModel @Inject constructor(
    private val store: LinuxBridgeStore,
) : ViewModel() {

    private val _config = MutableStateFlow(LinuxBridgeStore.Config())
    val config: StateFlow<LinuxBridgeStore.Config> = _config.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            store.configFlow().collect { _config.value = it }
        }
    }

    fun save(host: String, portStr: String, user: String, password: String, privKey: String) {
        viewModelScope.launch {
            val cfg = LinuxBridgeStore.Config(
                host = host.trim().ifBlank { "127.0.0.1" },
                port = portStr.trim().toIntOrNull() ?: 22,
                user = user.trim().ifBlank { "droid" },
                password = password.ifBlank { null },
                privateKeyPem = privKey.ifBlank { null },
            )
            store.setConfig(cfg)
            _saved.value = "saved"
        }
    }
}

@Composable
fun LinuxBridgePanel(vm: LinuxBridgeViewModel = hiltViewModel()) {
    val cfg by vm.config.collectAsState()
    val savedMsg by vm.saved.collectAsState()

    var host by remember { mutableStateOf(cfg.host) }
    var portStr by remember { mutableStateOf(cfg.port.toString()) }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.password.orEmpty()) }
    var privKey by remember { mutableStateOf(cfg.privateKeyPem.orEmpty()) }

    LaunchedEffect(cfg) {
        host = cfg.host
        portStr = cfg.port.toString()
        user = cfg.user
        pass = cfg.password.orEmpty()
        privKey = cfg.privateKeyPem.orEmpty()
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
            text = "${Glyph.DiamondOutline} linux bridge (ssh into android 15 debian vm)",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} one-time setup: open the system Linux Terminal app, run " +
                "`sudo apt install openssh-server && sudo service ssh start`, then paste credentials " +
                "below. Mythara's `linux_vm` agent tool SSHes in to run commands.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        Row {
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("host") },
                modifier = Modifier.weight(0.65f),
                colors = textColors(),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = portStr, onValueChange = { portStr = it },
                label = { Text("port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.35f),
                colors = textColors(),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("user") },
            modifier = Modifier.fillMaxWidth(),
            colors = textColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("password (or use key below)") },
            modifier = Modifier.fillMaxWidth(),
            colors = textColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = privKey, onValueChange = { privKey = it },
            label = { Text("private key (paste PEM body — preferred over password)") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            colors = textColors(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.save(host, portStr, user, pass, privKey) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Bg,
            ),
        ) {
            Text("save")
        }
        savedMsg?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "${Glyph.DiamondFilled} $it",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun textColors() = TextFieldDefaults.colors(
    focusedTextColor = MytharaColors.Fg,
    unfocusedTextColor = MytharaColors.Fg,
    focusedContainerColor = MytharaColors.Bg,
    unfocusedContainerColor = MytharaColors.Bg,
    focusedIndicatorColor = MytharaColors.Charple,
    unfocusedIndicatorColor = MytharaColors.SurfaceHigh,
    focusedLabelColor = MytharaColors.FgMute,
    unfocusedLabelColor = MytharaColors.FgDim,
)
