package com.mythara.ui.settings

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.mcp.KnownMcpServer
import com.mythara.mcp.KnownMcpServers
import com.mythara.mcp.McpConfigStore
import com.mythara.mcp.McpRegistry
import com.mythara.mcp.McpServerConfig
import com.mythara.mcp.McpToolHandle
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class McpServersPanelViewModel @Inject constructor(
    private val configStore: McpConfigStore,
    private val registry: McpRegistry,
) : ViewModel() {

    val servers: StateFlow<List<McpServerConfig>> = configStore.serversFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val discoveredTools: StateFlow<List<McpToolHandle>> = registry.tools

    fun add(name: String, url: String, bearerToken: String?) {
        if (url.isBlank()) return
        viewModelScope.launch {
            configStore.upsert(
                McpServerConfig(
                    id = idFromUrl(url),
                    name = name.ifBlank { url.take(40) },
                    url = url.trim(),
                    bearerToken = bearerToken?.trim()?.ifBlank { null },
                    enabled = true,
                ),
            )
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { configStore.remove(id) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { configStore.setEnabled(id, enabled) }
    }

    fun refreshNow() {
        registry.refreshNow()
    }

    /** Stable short id derived from the URL (hash hex) — survives renames. */
    private fun idFromUrl(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }
}

/**
 * Add / remove MCP (Model Context Protocol) servers. Each server's
 * tools surface to the agent automatically — list_tools runs on
 * config change, and ToolRegistry pulls the live list on every
 * apiSchema build.
 */
@Composable
fun McpServersPanel(vm: McpServersPanelViewModel = hiltViewModel()) {
    val servers by vm.servers.collectAsState()
    val tools by vm.discoveredTools.collectAsState()
    var composing by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftUrl by remember { mutableStateOf("") }
    var draftToken by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} MCP servers",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = "${servers.size} configured · ${tools.size} tools",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} Connect external Model Context Protocol servers (HTTP-streamable). Their tools appear to Mythara alongside Mythara's native tools and can be called by name.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))

        if (servers.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no MCP servers configured.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (server in servers) {
                    ServerRow(
                        server = server,
                        toolCount = tools.count { it.serverId == server.id },
                        onToggle = { vm.setEnabled(server.id, !server.enabled) },
                        onRemove = { vm.remove(server.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = { composing = !composing },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (composing) MytharaColors.Surface else MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text(if (composing) "${Glyph.Cross} cancel" else "${Glyph.DiamondFilled} add server")
            }
            Spacer(Modifier.padding(end = 6.dp))
            Button(
                onClick = { vm.refreshNow() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Surface,
                    contentColor = MytharaColors.FgMute,
                ),
            ) {
                Text("${Glyph.Refresh} re-discover")
            }
        }

        if (composing) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                singleLine = true,
                placeholder = { Text("display name (e.g. Linear)", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                singleLine = true,
                placeholder = { Text("MCP server URL (https://…)", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = draftToken,
                onValueChange = { draftToken = it },
                singleLine = true,
                placeholder = { Text("bearer token (optional)", color = MytharaColors.FgDim) },
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
            Button(
                onClick = {
                    vm.add(draftName, draftUrl, draftToken.ifBlank { null })
                    draftName = ""
                    draftUrl = ""
                    draftToken = ""
                    composing = false
                },
                enabled = draftUrl.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) { Text("save") }
        }

        if (tools.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${Glyph.AccentBar} discovered tools:",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(4.dp))
            for (t in tools) {
                Text(
                    text = "${Glyph.Dot} ${t.originalName} — ${t.description.take(80)}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // Catalog of popular MCPs the user can add with one tap. Entries
        // already configured are hidden (no point offering "add" for
        // something the user already has). Token-required entries are
        // tagged so the user knows to fill the bearer field.
        Spacer(Modifier.height(14.dp))
        val configuredIds = servers.map { it.id }.toSet()
        val suggestions = KnownMcpServers.catalog.filter { KnownMcpServers.idFor(it.url) !in configuredIds }
        if (suggestions.isNotEmpty()) {
            Text(
                text = "${Glyph.AccentBar} suggested:",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in suggestions) {
                    SuggestionRow(
                        suggestion = s,
                        onAdd = { vm.add(s.name, s.url, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: KnownMcpServer, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = suggestion.name,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                )
                if (suggestion.needsToken) {
                    Spacer(Modifier.padding(end = 6.dp))
                    Text(
                        text = "${Glyph.Dot} token needed",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = suggestion.description,
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = "${Glyph.DiamondFilled} add",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clickable(onClick = onAdd)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ServerRow(
    server: McpServerConfig,
    toolCount: Int,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Bg)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    color = if (server.enabled) MytharaColors.Fg else MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = server.url,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (server.enabled) "${Glyph.Dot} enabled" else "${Glyph.CircleOutline} off",
                    color = if (server.enabled) MytharaColors.Julep else MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(onClick = onToggle),
                )
                Text(
                    text = "$toolCount tool${if (toolCount == 1) "" else "s"}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${Glyph.Cross} remove",
            color = MytharaColors.Sriracha,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable(onClick = onRemove),
        )
    }
}
