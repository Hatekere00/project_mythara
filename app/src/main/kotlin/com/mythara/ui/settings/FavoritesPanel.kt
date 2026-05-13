package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.FavoritesStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val store: FavoritesStore,
) : ViewModel() {

    val favorites: StateFlow<List<FavoritesStore.Favorite>> =
        store.favoritesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(name: String, phone: String, tone: FavoritesStore.Tone) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch {
            store.upsert(
                FavoritesStore.Favorite(
                    name = n,
                    phone = phone.trim(),
                    enabled = true,
                    toneLabel = tone.label,
                ),
            )
        }
    }

    fun remove(name: String) {
        viewModelScope.launch { store.remove(name) }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(name, enabled) }
    }

    fun setTone(name: String, tone: FavoritesStore.Tone) {
        viewModelScope.launch { store.setTone(name, tone) }
    }
}

/**
 * Favorites panel — the curated list of people Mythara is allowed to
 * auto-reply to. Each row carries an enable toggle + tone picker so
 * the user can independently control "who" and "in what voice."
 *
 * Entries persist in a JSON-encoded DataStore blob; the list is small
 * (≤20 typical) so we re-render the whole thing on edits rather than
 * paginating.
 *
 * Lock-screen behaviour: this panel only mutates the store; the
 * dispatcher runs in MytharaApp scope and reads the store on every
 * notification — flipping a contact off here takes effect immediately
 * without restarting anything.
 */
@Composable
fun FavoritesPanel(vm: FavoritesViewModel = hiltViewModel()) {
    val favs by vm.favorites.collectAsState()

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
                text = "${Glyph.DiamondOutline} favorites",
                style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
            )
            Text(
                text = "${favs.size}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} contacts Mythara is allowed to auto-reply to. Pick a tone per person — friendly for close ones, professional for work, realistic to mirror how you usually write. Off-list contacts get the normal surface-and-decide notification instead. Conversations are isolated: replies to one person never leak info from another.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )

        Spacer(Modifier.height(10.dp))

        // Existing favorites.
        if (favs.isEmpty()) {
            Text(
                text = "${Glyph.CircleOutline} no favorites yet. Add someone below.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            favs.forEach { fav ->
                FavoriteRow(
                    fav = fav,
                    onToggleEnabled = { vm.setEnabled(fav.name, it) },
                    onToneChange = { vm.setTone(fav.name, it) },
                    onRemove = { vm.remove(fav.name) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        // Add new — primary path is the system contact picker; manual
        // entry stays as an expandable fallback for cases where the
        // user wants to add someone who isn't in their address book
        // (e.g. a friend whose number they have memorised but never
        // saved as a contact).
        Spacer(Modifier.height(10.dp))
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var tone by remember { mutableStateOf(FavoritesStore.Tone.Realistic) }
        var toneOpen by remember { mutableStateOf(false) }
        var manualOpen by remember { mutableStateOf(false) }

        // System contact picker. The OS grants us one-shot read access
        // to the picked contact without us holding READ_CONTACTS — same
        // pattern as the calendar add-event flow. Result pre-fills
        // name + phone so the user can pick the tone and tap add.
        val contactPicker = rememberContactPicker { picked ->
            if (picked != null) {
                name = picked.displayName
                phone = picked.phone
                // Auto-open the manual form so the user sees the
                // pre-filled fields and can adjust before adding.
                manualOpen = true
            }
        }

        Text(
            text = "${Glyph.DiamondOutline} add a favorite",
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = { contactPicker.launch(Unit) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MytharaColors.Charple,
                contentColor = MytharaColors.Fg,
            ),
        ) {
            Text("${Glyph.Arrow} pick from contacts")
        }
        Spacer(Modifier.height(6.dp))
        if (!manualOpen) {
            TextButton(onClick = { manualOpen = true }) {
                Text(
                    text = "${Glyph.Arrow} or type name + phone manually",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("name (must match how the app shows it)", color = MytharaColors.FgDim) },
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
                value = phone,
                onValueChange = { phone = it },
                singleLine = true,
                placeholder = { Text("+1 555 …", color = MytharaColors.FgDim) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
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
                Box {
                    Button(
                        onClick = { toneOpen = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface,
                            contentColor = MytharaColors.Fg,
                        ),
                    ) {
                        Text("tone: ${tone.label}  ${Glyph.Arrow}")
                    }
                    DropdownMenu(expanded = toneOpen, onDismissRequest = { toneOpen = false }) {
                        FavoritesStore.Tone.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label, color = MytharaColors.Fg) },
                                onClick = {
                                    tone = t
                                    toneOpen = false
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        vm.add(name, phone, tone)
                        name = ""
                        phone = ""
                        tone = FavoritesStore.Tone.Realistic
                        manualOpen = false
                    },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Arrow} add")
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    fav: FavoritesStore.Favorite,
    onToggleEnabled: (Boolean) -> Unit,
    onToneChange: (FavoritesStore.Tone) -> Unit,
    onRemove: () -> Unit,
) {
    var toneOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MytharaColors.Bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${if (fav.enabled) Glyph.CircleFilled else Glyph.CircleOutline}  ${fav.name}",
                    color = if (fav.enabled) MytharaColors.Fg else MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fav.phone.isNotBlank()) {
                    Text(
                        text = fav.phone,
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = { onToggleEnabled(!fav.enabled) }) {
                Text(
                    text = if (fav.enabled) "pause" else "resume",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRemove) {
                Text(
                    text = "${Glyph.Cross}",
                    color = MytharaColors.Sriracha,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Tone picker. Per-row so each contact's tone is independent.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { toneOpen = true }) {
                    Text(
                        text = "${Glyph.Arrow} tone: ${fav.tone.label}",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DropdownMenu(expanded = toneOpen, onDismissRequest = { toneOpen = false }) {
                    FavoritesStore.Tone.entries.forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = t.label + if (t == fav.tone) "  ✓" else "",
                                    color = MytharaColors.Fg,
                                )
                            },
                            onClick = {
                                onToneChange(t)
                                toneOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}
