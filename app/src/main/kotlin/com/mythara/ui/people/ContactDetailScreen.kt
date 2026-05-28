package com.mythara.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.analytics.interactions.ContactInteractionRow
import com.mythara.people.ContactActions
import com.mythara.people.SystemContact
import com.mythara.people.SystemContactsRepository
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Everything the detail screen renders, fetched once on entry. */
data class ContactDetailData(
    val row: ContactProfileRow?,
    val sys: SystemContact?,
    val interactions: List<ContactInteractionRow>,
)

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val profiles: ContactProfileRepository,
    private val interactions: ContactInteractionRepository,
    private val systemContacts: SystemContactsRepository,
) : ViewModel() {

    private val _data = MutableStateFlow<ContactDetailData?>(null)
    val data: StateFlow<ContactDetailData?> = _data.asStateFlow()

    fun load(nameKey: String) {
        viewModelScope.launch {
            val row = profiles.dao.byKey(nameKey)
            val sys = systemContacts.loadAll().firstOrNull {
                it.displayName.lowercase().trim() == nameKey
            }
            val inter = interactions.dao.listForContact(nameKey, limit = 100)
            _data.value = ContactDetailData(row = row, sys = sys, interactions = inter)
        }
    }
}

/**
 * Per-contact detail (v7 P7+). Re-instates the interactions / memory
 * / Big Five view that the original PeopleScreen carried, with the
 * call / SMS / WhatsApp action chips inline at the top. Loaded by
 * [nameKey] (the canonical lowercase display-name key used across
 * Mythara's analytics stack).
 */
@Composable
fun ContactDetailScreen(
    nameKey: String,
    vm: ContactDetailViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(nameKey) { vm.load(nameKey) }
    val data by vm.data.collectAsState()

    val displayName = data?.row?.displayName ?: data?.sys?.displayName ?: nameKey
    val phone = data?.sys?.primaryPhone ?: data?.row?.phone
    val hasWa = data?.sys?.hasWhatsApp == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("hdr") {
            HeaderCard(
                displayName = displayName,
                phone = phone,
                isFavorite = data?.row?.isFavorite == true,
                hasWhatsApp = hasWa,
                onCall = { phone?.let { ContactActions.phoneCall(ctx, it) } },
                onSms = { phone?.let { ContactActions.sms(ctx, it) } },
                onWaChat = { phone?.let { ContactActions.whatsAppChat(ctx, it) } },
            )
        }
        data?.row?.let { row ->
            item("stats") { StatsCard(row = row) }
            row.relationshipSummary?.takeIf { it.isNotBlank() }?.let {
                item("summary") { MemoryCard(title = "relationship summary", body = it) }
            }
            row.personalityInsights?.takeIf { it.isNotBlank() }?.let {
                item("insights") { MemoryCard(title = "what mythara remembers", body = it) }
            }
            row.userNotes?.takeIf { it.isNotBlank() }?.let {
                item("notes") { MemoryCard(title = "your notes", body = it) }
            }
            if (row.bigFiveSampleSize > 0 &&
                (row.openness != null || row.conscientiousness != null)
            ) {
                item("big5") { BigFiveCard(row = row) }
            }
            val notable = decodeStringList(row.notableTraitsJson)
            if (notable.isNotEmpty()) {
                item("traits") { ChipsCard(title = "notable traits", chips = notable) }
            }
            val topics = decodeStringList(row.topTopicsJson)
            if (topics.isNotEmpty()) {
                item("topics") { ChipsCard(title = "top topics", chips = topics) }
            }
        }
        val inter = data?.interactions.orEmpty()
        if (inter.isNotEmpty()) {
            item("inter-h") { SectionHeader("◆ recent interactions") }
            items(inter.take(40)) { InteractionRow(r = it) }
        }
        if (data != null && data?.row == null && inter.isEmpty()) {
            item("none") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "no analytics yet for this contact",
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// ─── Header / actions ───────────────────────────────────────────────

@Composable
private fun HeaderCard(
    displayName: String,
    phone: String?,
    isFavorite: Boolean,
    hasWhatsApp: Boolean,
    onCall: () -> Unit,
    onSms: () -> Unit,
    onWaChat: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.80f))
            .border(1.dp, MytharaColors.Charple.copy(alpha = 0.45f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigAvatar(name = displayName)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    if (isFavorite) {
                        Spacer(Modifier.size(6.dp))
                        Text("★", color = MytharaColors.Mustard, style = MaterialTheme.typography.titleMedium)
                    }
                }
                phone?.let {
                    Text(
                        text = it,
                        color = MytharaColors.FgMute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phone != null) {
                ContactActionChip("${Glyph.AccentBar} call", MytharaColors.Bok, onCall)
                ContactActionChip("✉ sms", MytharaColors.Malibu, onSms)
                if (hasWhatsApp) {
                    ContactActionChip("wa chat", MytharaColors.Charple, onWaChat)
                }
            } else {
                Text(
                    text = "no phone number on file",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun BigAvatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MytharaColors.Charple.copy(alpha = 0.32f))
            .border(2.dp, MytharaColors.Charple.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun ContactActionChip(label: String, color: Color, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

// ─── Stats / memory cards ───────────────────────────────────────────

@Composable
private fun StatsCard(row: ContactProfileRow) {
    DetailCard(title = "◆ stats") {
        StatRow("interactions", "${row.messageCount}")
        if (row.imageCount > 0) StatRow("photos shared", "${row.imageCount}")
        row.firstSeenMs.takeIf { it > 0 }?.let {
            StatRow("first seen", dateFmt.format(Date(it)))
        }
        row.lastInteractionMs.takeIf { it > 0 }?.let {
            StatRow("last interaction", dateFmt.format(Date(it)))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MytharaColors.FgMute, style = MaterialTheme.typography.bodySmall)
        Text(value, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MemoryCard(title: String, body: String) {
    DetailCard(title = "◇ $title") {
        Text(
            text = body,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BigFiveCard(row: ContactProfileRow) {
    DetailCard(title = "● big five — mythara's read") {
        TraitBar("openness", row.openness, MytharaColors.Charple)
        TraitBar("conscientiousness", row.conscientiousness, MytharaColors.Malibu)
        TraitBar("extraversion", row.extraversion, MytharaColors.Bok)
        TraitBar("agreeableness", row.agreeableness, MytharaColors.Mustard)
        TraitBar("neuroticism", row.neuroticism, MytharaColors.Sriracha)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "estimated from ${row.bigFiveSampleSize} observed facts — keep chatting to sharpen",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TraitBar(label: String, value: Double?, color: Color) {
    val v = (value ?: 0.0).coerceIn(0.0, 100.0).toFloat() / 100f
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MytharaColors.FgMute, style = MaterialTheme.typography.labelSmall)
            Text(
                text = if (value != null) "${value.toInt()}" else "—",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { v },
            color = color,
            trackColor = MytharaColors.SurfaceHigh.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ChipsCard(title: String, chips: List<String>) {
    DetailCard(title = "◇ $title") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { c ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MytharaColors.SurfaceHigh.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = c,
                        color = MytharaColors.Fg,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MytharaColors.Surface.copy(alpha = 0.65f))
            .border(1.dp, MytharaColors.SurfaceHigh.copy(alpha = 0.6f), shape)
            .padding(12.dp),
    ) {
        Text(
            text = title,
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MytharaColors.FgMute,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun InteractionRow(r: ContactInteractionRow) {
    val (glyph, color) = when (r.kind) {
        "message_sent" -> "↑ msg" to MytharaColors.Malibu
        "message_received" -> "↓ msg" to MytharaColors.Bok
        "call_outgoing" -> "↑ call" to MytharaColors.Charple
        "call_incoming" -> "↓ call" to MytharaColors.Bok
        "physical_meet" -> "● met" to MytharaColors.Citron
        "mention" -> "@ ref" to MytharaColors.Mustard
        else -> "·" to MytharaColors.FgMute
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = dateFmt.format(Date(r.tsMs)),
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodySmall,
            )
            val sub = listOfNotNull(r.source, r.placeLabel, r.note)
                .joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

private fun decodeStringList(json: String): List<String> = runCatching {
    Json { ignoreUnknownKeys = true }.decodeFromString(
        ListSerializer(String.serializer()),
        json,
    )
}.getOrDefault(emptyList())

