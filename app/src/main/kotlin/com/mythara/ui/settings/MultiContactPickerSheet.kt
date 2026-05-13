package com.mythara.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PickableContact(
    val lookupKey: String,
    val displayName: String,
    val phone: String,
)

/**
 * Modal bottom sheet for picking SEVERAL contacts at once. Built
 * specifically for the user-aliases flow ("which of these is me?"),
 * but kept generic so future flows can reuse it.
 *
 * Why a custom sheet rather than the system contact picker:
 *   - System ACTION_PICK with Phone.CONTENT_TYPE returns ONE contact.
 *     Adding 5 old numbers means tapping the button 5 times. That's
 *     hostile UX when the user is doing identity setup.
 *   - This sheet enumerates contacts ourselves via ContactsContract
 *     (one query, ~30ms for 1000 contacts), shows them with
 *     checkboxes, and the user taps "add selected" to commit all.
 *
 * Permission: requires READ_CONTACTS. We trigger the runtime prompt
 * the first time the sheet opens; on denial the sheet shows a
 * permission-explainer with a "request again" button.
 *
 * One row per PHONE NUMBER (not per contact) so users with multiple
 * numbers attached to one contact card can pick the specific ones
 * that are theirs. Duplicates collapsed by lookup key + phone digits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiContactPickerSheet(
    title: String = "pick contacts",
    onDismiss: () -> Unit,
    onApply: (List<PickableContact>) -> Unit,
) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var contacts by remember { mutableStateOf<List<PickableContact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return@LaunchedEffect
        }
        loading = true
        val loaded = withContext(Dispatchers.IO) { loadContacts(ctx) }
        contacts = loaded
        loading = false
    }

    val filtered = remember(contacts, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) contacts
        else contacts.filter {
            it.displayName.lowercase().contains(q) || it.phone.contains(q)
        }
    }
    val selectedCount = selected.count { it.value }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MytharaColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} $title",
                    style = MaterialTheme.typography.titleSmall.copy(color = MytharaColors.Fg),
                )
                if (selectedCount > 0) {
                    Text(
                        text = "$selectedCount selected",
                        color = MytharaColors.Charple,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (!permissionGranted) {
                Text(
                    text = "${Glyph.AccentBar} Mythara needs Contacts permission to list your address book. Grant it once below; the read is local — nothing is sent anywhere.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${Glyph.Arrow} grant Contacts")
                }
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("search by name or number…", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            when {
                loading -> {
                    Text(
                        text = "${Glyph.Ellipsis} loading contacts…",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                filtered.isEmpty() -> {
                    Text(
                        text = "${Glyph.CircleOutline} no contacts match.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.lookupKey + ":" + it.phone }) { c ->
                            val key = c.lookupKey + ":" + c.phone
                            ContactCheckRow(
                                contact = c,
                                checked = selected[key] == true,
                                onToggle = { selected[key] = !(selected[key] ?: false) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val chosen = filtered.filter { c ->
                            selected[c.lookupKey + ":" + c.phone] == true
                        }
                        onApply(chosen)
                        onDismiss()
                    },
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                        disabledContainerColor = MytharaColors.Surface,
                        disabledContentColor = MytharaColors.FgDim,
                    ),
                ) {
                    Text("${Glyph.Check} add $selectedCount")
                }
            }
        }
    }
}

@Composable
private fun ContactCheckRow(
    contact: PickableContact,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) MytharaColors.Surface else MytharaColors.Bg)
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (checked) Glyph.CircleFilled else Glyph.CircleOutline,
            color = if (checked) MytharaColors.Charple else MytharaColors.FgMute,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.padding(end = 4.dp)) {
            Text(
                text = contact.displayName,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (contact.phone.isNotBlank()) {
                Text(
                    text = contact.phone,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun loadContacts(ctx: Context): List<PickableContact> {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    val out = mutableListOf<PickableContact>()
    val seen = HashSet<String>()
    runCatching {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            val keyIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val key = if (keyIdx >= 0) c.getString(keyIdx).orEmpty() else ""
                val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                val num = if (numIdx >= 0) c.getString(numIdx).orEmpty() else ""
                if (name.isBlank() || num.isBlank()) continue
                val digits = num.filter { it.isDigit() }
                val dedupKey = "$key|$digits"
                if (!seen.add(dedupKey)) continue
                out.add(PickableContact(lookupKey = key, displayName = name, phone = num))
            }
        }
    }
    return out
}
