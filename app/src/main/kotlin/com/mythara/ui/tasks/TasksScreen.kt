package com.mythara.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.queue.PendingReplyEntity
import com.mythara.agent.queue.PendingReplyRepository
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.HeartbeatSyncer
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TasksScreenViewModel @Inject constructor(
    private val repo: TaskRepository,
    private val pendingReplies: PendingReplyRepository,
    private val deviceIdStore: DeviceIdStore,
    private val heartbeat: HeartbeatSyncer,
) : ViewModel() {

    val tasks: StateFlow<List<TaskEntity>> = repo.dao.observeRecent(limit = 200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Notifications queued for auto-reply that haven't fired yet —
     *  user can manually dismiss stale ones (e.g. an old WhatsApp ping
     *  that no longer needs a reply). */
    val pendingNotifs: StateFlow<List<PendingReplyEntity>> = pendingReplies.dao.observeActive(limit = 100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun dismissPendingNotif(id: Long) {
        viewModelScope.launch { pendingReplies.dao.userDismiss(id) }
    }

    @Volatile var myDeviceId: String? = null
        private set

    init {
        viewModelScope.launch {
            myDeviceId = runCatching { deviceIdStore.id() }.getOrNull()
        }
    }

    fun createTask(
        title: String,
        body: String,
        targetDeviceId: String?,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val myId = myDeviceId ?: runCatching { deviceIdStore.id() }.getOrNull() ?: "unknown"
            repo.dao.insertIfAbsent(
                TaskEntity(
                    id = id,
                    title = title,
                    body = body,
                    requesterDeviceId = myId,
                    targetDeviceId = targetDeviceId,
                    status = TaskStatus.PENDING.name,
                    createdMs = now,
                ),
            )
            // Kick a heartbeat so the new task ships to peers + this
            // device picks it up immediately if it's targeted at us.
            heartbeat.fireNow()
        }
    }

    /** Pull-to-refresh / "check now" — kicks the heartbeat. */
    fun kick() { heartbeat.fireNow() }

    fun cancel(id: String) {
        viewModelScope.launch {
            repo.dao.markTerminal(id, TaskStatus.CANCELED.name, "canceled by user", System.currentTimeMillis())
            heartbeat.fireNow()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreenSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MytharaColors.Bg,
    ) {
        TasksScreenBody()
    }
}

@Composable
fun TasksScreenPane(onClose: () -> Unit) {
    TasksScreenBody()
}

@Composable
private fun TasksScreenBody(vm: TasksScreenViewModel = hiltViewModel()) {
    val tasks by vm.tasks.collectAsState()
    val pendingNotifs by vm.pendingNotifs.collectAsState()
    val (pending, terminal) = tasks.partition { it.status in PENDING_LIKE }
    var composing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MytharaColors.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "${Glyph.DiamondFilled} tasks",
                        color = MytharaColors.Malibu,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "${pending.size} pending · ${terminal.size} done",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    Button(
                        onClick = { vm.kick() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface,
                            contentColor = MytharaColors.Fg,
                        ),
                    ) { Text("${Glyph.Refresh} sync now") }
                    Spacer(Modifier.padding(end = 6.dp))
                    Button(
                        onClick = { composing = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Malibu,
                            contentColor = MytharaColors.Bg,
                        ),
                    ) { Text("${Glyph.DiamondFilled} new") }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (tasks.isEmpty() && pendingNotifs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${Glyph.CircleOutline} no tasks yet. Tap ${Glyph.DiamondFilled} new to add one.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Notification reply queue — surfaces what the
                    // auto-reply pipeline is about to fire on. User
                    // can tap × to skip stale ones (old WhatsApp
                    // ping that doesn't need a reply anymore).
                    if (pendingNotifs.isNotEmpty()) {
                        item(key = "h:notif-queue") {
                            SectionHeader("${Glyph.Dot} notification reply queue", count = pendingNotifs.size)
                        }
                        items(pendingNotifs, key = { "n:${it.id}" }) { n ->
                            PendingNotifRow(notif = n, onDismiss = { vm.dismissPendingNotif(n.id) })
                        }
                    }
                    if (pending.isNotEmpty()) {
                        item(key = "h:pending") {
                            SectionHeader("${Glyph.Ellipsis} pending", count = pending.size)
                        }
                        items(pending, key = { "p:${it.id}" }) { t ->
                            TaskRow(t, myId = vm.myDeviceId, onCancel = { vm.cancel(t.id) })
                        }
                    }
                    if (terminal.isNotEmpty()) {
                        item(key = "h:done") {
                            SectionHeader("${Glyph.Check} done", count = terminal.size)
                        }
                        items(terminal, key = { "d:${it.id}" }) { t ->
                            TaskRow(t, myId = vm.myDeviceId, onCancel = null)
                        }
                    }
                }
            }
        }
    }

    if (composing) {
        TaskComposer(
            myId = vm.myDeviceId,
            onCancel = { composing = false },
            onSubmit = { title, body, target ->
                vm.createTask(title, body, target)
                composing = false
            },
        )
    }
}

@Composable
private fun PendingNotifRow(notif: PendingReplyEntity, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.Mustard, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Glyph.DiamondOutline} ${notif.senderTitle}",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = notif.route.lowercase(),
                color = MytharaColors.Mustard,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (notif.body.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = notif.body,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDate(notif.tsMillis),
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = "${notif.pkg} · ${notif.status.lowercase()}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            if (notif.attempts > 0) {
                Spacer(Modifier.padding(end = 6.dp))
                Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = "attempt ${notif.attempts}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.Cross} skip (don't auto-reply)",
            color = MytharaColors.Sriracha,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { onDismiss() },
        )
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = MytharaColors.FgMute,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.padding(end = 8.dp))
        Text(
            text = "($count)",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TaskRow(task: TaskEntity, myId: String?, onCancel: (() -> Unit)?) {
    val statusColor = when (task.status) {
        TaskStatus.DONE.name -> MytharaColors.Julep
        TaskStatus.FAILED.name -> MytharaColors.Sriracha
        TaskStatus.RUNNING.name -> MytharaColors.Citron
        TaskStatus.CLAIMED.name -> MytharaColors.Mustard
        TaskStatus.CANCELED.name -> MytharaColors.FgDim
        else -> MytharaColors.Malibu
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = task.title,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = task.status.lowercase(),
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (task.body.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = task.body,
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        val target = task.targetDeviceId?.takeLast(6) ?: "any device"
        val isMine = task.requesterDeviceId == myId
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDate(task.createdMs),
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = if (isMine) "from this device" else "from ${task.requesterDeviceId.takeLast(6)}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = "→ $target",
                color = MytharaColors.Mustard,
                style = MaterialTheme.typography.labelSmall,
            )
            if (task.claimedByDeviceId != null) {
                Spacer(Modifier.padding(end = 6.dp))
                Text("│", color = MytharaColors.SurfaceHigh, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = "claimed: ${task.claimedByDeviceId.takeLast(6)}",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (task.resultText != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.AccentBar} ${task.resultText}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onCancel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Glyph.Cross} cancel",
                color = MytharaColors.Sriracha,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { onCancel() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskComposer(
    myId: String?,
    onCancel: () -> Unit,
    onSubmit: (title: String, body: String, target: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var targetDevice by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MytharaColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "${Glyph.DiamondFilled} new task",
                color = MytharaColors.Malibu,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                placeholder = { Text("title (e.g. remind mom about dinner)", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Malibu,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Malibu,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("body / details (optional)", color = MytharaColors.FgDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Malibu,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Malibu,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = targetDevice,
                onValueChange = { targetDevice = it },
                singleLine = true,
                placeholder = { Text("target device id (blank = any device)", color = MytharaColors.FgDim) },
                keyboardOptions = KeyboardOptions.Default,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Mustard,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Mustard,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Glyph.AccentBar} ask Mythara 'what devices do I have' to see device ids. Leave blank to let any device pick this up.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Surface,
                        contentColor = MytharaColors.FgMute,
                    ),
                ) { Text("cancel") }
                Spacer(Modifier.padding(end = 8.dp))
                Button(
                    onClick = {
                        onSubmit(title.trim(), body.trim(), targetDevice.trim().ifBlank { null })
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Malibu,
                        contentColor = MytharaColors.Bg,
                    ),
                    enabled = title.isNotBlank(),
                ) { Text("create task") }
            }
        }
    }
}

private val PENDING_LIKE = setOf(
    TaskStatus.PENDING.name,
    TaskStatus.CLAIMED.name,
    TaskStatus.RUNNING.name,
)

private fun formatDate(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    val sdf = if (diff < 24L * 3600 * 1000) {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    } else if (diff < 7L * 24 * 3600 * 1000) {
        SimpleDateFormat("EEE HH:mm", Locale.getDefault())
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault())
    }
    return sdf.format(Date(ms))
}
