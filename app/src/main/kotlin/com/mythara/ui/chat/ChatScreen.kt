package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mythara.mic.ContinuousSpeechRecognition
import com.mythara.mic.MicBroker
import com.mythara.mic.SpeechRecognition
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.JetBrainsMono
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark

/**
 * How long the continuous-voice mode waits after the user stops
 * talking before sending the accumulated utterance to the agent.
 * Set per user request: lets multi-clause thoughts ("Hey Lumi,
 * what's the weather, actually also tell me ...") concatenate
 * before the agent fires.
 */
private const val SILENCE_TIMEOUT_MS = 5_000L

/**
 * Main chat surface. Pulls state from [ChatViewModel] and renders the
 * Crush-styled timeline + composer + (when the assistant is streaming)
 * a live bubble that grows token-by-token.
 *
 * Push-to-talk + TTS land in M3; for M2 we use a plain text composer so
 * MiniMax integration is testable end-to-end before adding voice on top.
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    /**
     * When null (compact / single-pane), the chat-header drawer pill
     * opens an in-screen ModalBottomSheet. When non-null (two-pane
     * layout) the parent provides this callback to route to the
     * right pane instead — so the drawer renders inline next to the
     * chat, matching how People / Settings / About appear in that
     * mode.
     */
    onOpenAppDrawer: (() -> Unit)? = null,
    /** Same pattern for the lifeline timeline grid. */
    onOpenTimeline: (() -> Unit)? = null,
    /** Same pattern for the cross-device tasks screen. */
    onOpenTasks: (() -> Unit)? = null,
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val insets = WindowInsets.systemBars.asPaddingValues()

    // Continuous-mode driver. Two behavioural notes the original v1
    // didn't get right:
    //   1. Submit-on-pause, not submit-on-Final. Soda's idea of
    //      end-of-utterance fires after ~1-2s of silence; the user
    //      wanted a 5s window so multi-sentence thoughts ("Hey Lumi,
    //      ... actually, can you also ...") concatenate into one
    //      query before the agent kicks in.
    //   2. Mute the mic while Lumi is speaking out loud. Otherwise
    //      the on-device recogniser transcribes the assistant's own
    //      TTS reply and the loop runs away from itself. We key the
    //      LaunchedEffect on `!ui.speaking` so the recogniser is torn
    //      down when TTS starts and recreated when it ends — Soda
    //      bring-up is fast enough (~100ms) that this is invisible
    //      to a conversational cadence.
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) vm.setContinuousMode(false)
    }
    LaunchedEffect(ui.continuousMode, ui.speaking) {
        if (!ui.continuousMode) return@LaunchedEffect
        if (ui.speaking) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        // Coordinate with the mic broker so Observe / Lumi-listen can't
        // steal the mic mid-utterance. If acquire fails, flip the toggle
        // back off — UI will then show the conflict via the same flow.
        if (!vm.micBroker.acquire(MicBroker.Client.CONTINUOUS_CHAT)) {
            vm.setContinuousMode(false)
            return@LaunchedEffect
        }

        // Pending-utterance buffer. Each Soda Final appends to it;
        // a 5-second silence (no Partial since the last word) triggers
        // a submit. Partials reset the timer. This lets the user pause
        // mid-thought without the agent firing prematurely.
        val pending = StringBuilder()
        var commitJob: kotlinx.coroutines.Job? = null
        fun resetCommitTimer() {
            commitJob?.cancel()
            commitJob = launch {
                kotlinx.coroutines.delay(SILENCE_TIMEOUT_MS)
                val text = pending.toString().trim()
                if (text.isNotEmpty()) {
                    pending.clear()
                    if (!ui.thinking && !ui.speaking) {
                        // Continuous-mode utterances are voice input —
                        // flag for short conversational reply.
                        vm.submit(text, fromVoice = true)
                    }
                }
            }
        }

        try {
            ContinuousSpeechRecognition.listenContinuously(ctx).collect { ev ->
                when (ev) {
                    is SpeechRecognition.Event.Partial -> {
                        // User is still speaking — push the silence-timer
                        // out. The Final will land in a moment.
                        commitJob?.cancel()
                    }
                    is SpeechRecognition.Event.Final -> {
                        val text = ev.text.trim()
                        if (text.isNotBlank()) {
                            if (pending.isNotEmpty()) pending.append(' ')
                            pending.append(text)
                            resetCommitTimer()
                        }
                    }
                    is SpeechRecognition.Event.Error -> {
                        if (!ContinuousSpeechRecognition.isTransient(ev.code)) {
                            android.util.Log.w("Mythara/Chat", "continuous SR error: ${ev.message}")
                        }
                    }
                    else -> { /* Ready / EndOfSpeech — no-op */ }
                }
            }
        } finally {
            // Always release the mic when the collector unwinds, whether
            // from toggling off, screen exit, or TTS-paused recompose.
            vm.micBroker.release(MicBroker.Client.CONTINUOUS_CHAT)
        }
    }

    // ----------------------------------------------------------------
    // Pixel Buds / Digital-Assistant-tap path. When MainActivity fires
    // VoiceActionStore.fire(...) we kick off a one-shot
    // SpeechRecognition listen, gather the user's utterance, and
    // submit() it the same way the mic button does. Works whether
    // Mythara is brought to foreground from cold start (ACTION_ASSIST
    // launches the activity) or already in foreground (onNewIntent).
    LaunchedEffect(Unit) {
        vm.voiceActions.triggers.collect { trigger ->
            // Same permission + mic-broker handshake as the continuous
            // mode collector above. We only acquire when we have the
            // mic permission AND no other client (Observe / wake /
            // continuous chat) holds the mic.
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@collect
            }
            if (ui.thinking || ui.speaking) {
                // Lumi is mid-reply; ignore the tap. Dropping > queuing
                // because the user would be confused if a tap from 10
                // seconds ago suddenly opened the mic right after
                // hearing the previous answer.
                android.util.Log.d("Mythara/Chat", "voice trigger ignored: thinking=${ui.thinking} speaking=${ui.speaking}")
                return@collect
            }
            if (!vm.micBroker.acquire(MicBroker.Client.CONTINUOUS_CHAT)) {
                android.util.Log.w("Mythara/Chat", "voice trigger: mic busy")
                return@collect
            }
            android.util.Log.d("Mythara/Chat", "voice trigger from ${trigger.source}")
            // Start a parallel raw-PCM recorder so AcousticAnalyzer can
            // extract pitch / energy / speaking-rate from the same
            // utterance the SpeechRecognizer is transcribing. On
            // devices that refuse concurrent capture, start() returns
            // false and we proceed with text-only mood scoring.
            val pcmRecorder = com.mythara.mic.VoicePcmRecorder(ctx)
            val pcmStarted = pcmRecorder.start()
            try {
                // One-shot listen. Wait for the first Final or Error.
                val terminal: SpeechRecognition.Event? = SpeechRecognition
                    .listen(ctx)
                    .firstOrNull { ev ->
                        ev is SpeechRecognition.Event.Final ||
                            ev is SpeechRecognition.Event.Error
                    }
                val captured = if (pcmStarted) pcmRecorder.stop() else null
                when (terminal) {
                    is SpeechRecognition.Event.Final -> {
                        val text = terminal.text.trim()
                        if (text.isNotEmpty()) {
                            vm.submitVoice(
                                text = text,
                                pcm = captured?.pcm,
                                pcmSampleRate = captured?.sampleRate ?: 0,
                            )
                        }
                    }
                    is SpeechRecognition.Event.Error ->
                        android.util.Log.w("Mythara/Chat", "voice trigger SR error: ${terminal.message}")
                    else -> { /* upstream cancelled before terminal — no-op */ }
                }
            } finally {
                pcmRecorder.release()
                vm.micBroker.release(MicBroker.Client.CONTINUOUS_CHAT)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(insets),
    ) {
        var appDrawerOpen by remember { mutableStateOf(false) }
        var timelineOpen by remember { mutableStateOf(false) }
        var tasksOpen by remember { mutableStateOf(false) }
        // Two-pane mode hands us [onOpenAppDrawer] / [onOpenTimeline]
        // / [onOpenTasks] so those surfaces land in the right pane.
        // Single-pane leaves them null and we toggle local bottom sheets.
        val openDrawer: () -> Unit = onOpenAppDrawer ?: { appDrawerOpen = true }
        val openTimeline: () -> Unit = onOpenTimeline ?: { timelineOpen = true }
        val openTasks: () -> Unit = onOpenTasks ?: { tasksOpen = true }
        ChatHeader(
            onOpenSettings = onOpenSettings,
            onOpenPeople = onOpenPeople,
            onOpenAppDrawer = openDrawer,
            onOpenTimeline = openTimeline,
            onOpenTasks = openTasks,
            thinking = ui.thinking,
            continuousMode = ui.continuousMode,
            onToggleContinuous = { vm.setContinuousMode(!ui.continuousMode) },
        )

        if (appDrawerOpen && onOpenAppDrawer == null) {
            com.mythara.ui.launcher.AppDrawerSheet(onDismiss = { appDrawerOpen = false })
        }
        if (timelineOpen && onOpenTimeline == null) {
            com.mythara.ui.lifeline.TimelineGridSheet(onDismiss = { timelineOpen = false })
        }
        if (tasksOpen && onOpenTasks == null) {
            com.mythara.ui.tasks.TasksScreenSheet(onDismiss = { tasksOpen = false })
        }

        // Lifeline filter chip strip. Visible only when there ARE
        // foreign-device photos in the timeline — no point offering
        // a filter that wouldn't do anything on a single-device feed.
        if (ui.hasRemoteLifeline) {
            LifelineFilterChips(
                current = ui.lifelineFilter,
                onSelect = { vm.setLifelineFilter(it) },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (ui.items.isEmpty() && ui.streaming.isNullOrEmpty()) {
                EmptyStateHero(thinking = ui.thinking)
            } else {
                Transcript(
                    items = ui.items,
                    streaming = ui.streaming,
                    // Show the gradient-rolodex thinking indicator at
                    // the bottom of the timeline while Lumi is working
                    // but the first streamed token hasn't landed yet.
                    // Once streaming text shows, the indicator hides
                    // so the user reads the actual reply.
                    thinkingVisible = ui.thinking && ui.streaming.isNullOrEmpty(),
                )
            }

            ui.errorBanner?.let { msg ->
                Banner(text = msg, color = MytharaColors.Sriracha, onDismiss = vm::dismissError,
                    align = Alignment.TopCenter)
            }
            if (ui.needsApiKey) {
                Banner(
                    text = "${Glyph.AccentBar} paste your MiniMax API key in Settings to start chatting.",
                    color = MytharaColors.Mustard, onDismiss = vm::dismissMissingKey,
                    align = Alignment.TopCenter,
                )
            }
        }

        Composer(
            // The Composer distinguishes mic-driven vs typed input
            // — both call this callback but with different
            // `fromVoice` flags so the agent loop can produce a
            // voice-friendly short reply when the user spoke.
            onSubmit = { text, fromVoice -> vm.submit(text, fromVoice) },
            enabled = !ui.thinking,
        )
    }

    // ConfirmationGate dialog overlay. Renders the topmost pending
    // request from the gate; subsequent requests queue and pop after
    // the current one resolves. We collect from a SharedFlow but
    // also read currentRequest as a snapshot fallback so the dialog
    // survives a recompose (e.g. theme change mid-prompt).
    val pendingRequest by vm.confirmationGate.pending.collectAsState(initial = null)
    val request = pendingRequest ?: vm.confirmationGate.currentRequest
    if (request != null) {
        ConfirmationDialog(
            request = request,
            onResolve = { decision, always ->
                vm.resolveConfirmation(request, decision, always)
            },
        )
    }
}

@Composable
private fun ChatHeader(
    onOpenSettings: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenAppDrawer: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenTasks: () -> Unit,
    thinking: Boolean,
    continuousMode: Boolean,
    onToggleContinuous: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${if (thinking) Glyph.Ellipsis else Glyph.DiamondFilled} mythara",
            style = MaterialTheme.typography.labelLarge.copy(
                color = MytharaColors.Charple, fontWeight = FontWeight.Bold,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Voice-chat toggle. ◇ when off, ● in Bok when on — same
            // motif as the wake-word panel's "listening" indicator so
            // the user reads "live mic" consistently across surfaces.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (continuousMode) MytharaColors.Bok else MytharaColors.Surface)
                    .border(
                        1.dp,
                        if (continuousMode) MytharaColors.Bok else MytharaColors.SurfaceHigh,
                        CircleShape,
                    )
                    .clickable(onClick = onToggleContinuous)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (continuousMode) "${Glyph.Dot} voice on" else "${Glyph.CircleOutline} voice off",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = if (continuousMode) MytharaColors.Bg else MytharaColors.FgMute,
                    ),
                )
            }
            Spacer(Modifier.size(8.dp))
            // People / analytics pill — Charple-bordered to draw the
            // eye since this is the surface the user opens to prep
            // for a conversation. Sits left of the app-drawer pill
            // and settings.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.Charple, CircleShape)
                    .clickable(onClick = onOpenPeople)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondFilled} people",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.Charple),
                )
            }
            Spacer(Modifier.size(8.dp))
            // Timeline pill — Bok mint. Tap opens the dedicated
            // lifeline grid screen (months → photos grid). Inline
            // photos still appear in the chat scrollback; this is the
            // "browse my memory" surface for skimming.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.Bok, CircleShape)
                    .clickable(onClick = onOpenTimeline)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondFilled} memory",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.Bok),
                )
            }
            Spacer(Modifier.size(8.dp))
            // App drawer pill — Mustard yellow. Tap opens the launcher-
            // style grid of installed apps. Mythara is registered as a
            // HOME-category Activity in the manifest, so the user can
            // set it as their default launcher; this drawer fills the
            // role a normal launcher's app drawer plays.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.Mustard, CircleShape)
                    .clickable(onClick = onOpenAppDrawer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondFilled} apps",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.Mustard),
                )
            }
            Spacer(Modifier.size(8.dp))
            // Tasks pill — Malibu blue. Tap opens the cross-device
            // task list. The count badge appears only when there are
            // pending/claimed/running tasks anywhere in the cluster.
            TasksPill(onClick = onOpenTasks)
            Spacer(Modifier.size(8.dp))
            // Settings — gear icon only. Pills above each carry a
            // distinct visible accent (Bok / Mustard / Charple /
            // Malibu); Settings is the muted "tap to configure"
            // affordance so it stays out of the visual hierarchy.
            // Compact icon-only also saves horizontal space on
            // compact phones now that the header is six pills deep.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.SurfaceHigh, CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "⚙",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.FgMute),
                )
            }
        }
    }
}

@Composable
private fun EmptyStateHero(thinking: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MytharaWordmark(shimmer = thinking || true, fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${Glyph.AccentBar} field intelligence in your pocket.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MytharaColors.FgDim, letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "type a message ${Glyph.Arrow}",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgMute),
        )
    }
}

@Composable
private fun Transcript(
    items: List<ChatViewModel.ChatItem>,
    streaming: String?,
    thinkingVisible: Boolean = false,
) {
    val listState = rememberLazyListState()
    val streamingActive = !streaming.isNullOrEmpty()
    LaunchedEffect(items.size, streaming, thinkingVisible) {
        val extra = (if (streamingActive) 1 else 0) + (if (thinkingVisible && !streamingActive) 1 else 0)
        val target = items.size + extra
        if (target > 0) listState.animateScrollToItem(target - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ChatViewModel.ChatItem.UserText -> TextBubble(role = "you", text = item.text, isUser = true)
                is ChatViewModel.ChatItem.AssistantText -> TextBubble(role = "mythara", text = item.text, isUser = false)
                is ChatViewModel.ChatItem.Thought -> ThoughtBubble(item)
                is ChatViewModel.ChatItem.Tool -> ToolCallBubble(item)
                is ChatViewModel.ChatItem.FromOtherDevice -> FromOtherDeviceCard(item)
                is ChatViewModel.ChatItem.LifelinePhoto -> LifelineCard(item)
                is ChatViewModel.ChatItem.ReminderCard -> ReminderCard(item)
            }
        }
        if (streamingActive) {
            item("streaming") {
                TextBubble(role = "mythara", text = streaming + Glyph.AccentBar, isUser = false)
            }
        } else if (thinkingVisible) {
            // Rolodex thinking indicator with the Charple→Bok brand
            // gradient — only when nothing is streaming yet. Hides
            // the instant the first token lands so we don't double
            // up with the actual reply.
            item("thinking") {
                ThinkingIndicator()
            }
        }
    }
}

@Composable
private fun TextBubble(role: String, text: String, isUser: Boolean) {
    val bg = if (isUser) MytharaColors.SurfaceMid else MytharaColors.Surface
    val border = if (isUser) MytharaColors.Charple else MytharaColors.SurfaceHigh
    val align = if (isUser) Alignment.End else Alignment.Start
    val accent = if (isUser) MytharaColors.Charple else MytharaColors.Bok

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text = "${Glyph.DiamondFilled} $role",
            style = MaterialTheme.typography.labelMedium.copy(color = accent),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = text, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Composer(onSubmit: (String, Boolean) -> Unit, enabled: Boolean) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Mic button — push-to-talk via SpeechRecognizer. Partials stream into
        // the draft field; final fires submit() with fromVoice=true so the
        // agent loop produces a voice-friendly short reply.
        MicButton(
            onPartial = { draft = it },
            onFinal = {
                draft = it
                if (enabled && draft.isNotBlank()) {
                    onSubmit(draft, /* fromVoice = */ true); draft = ""
                }
            },
            onError = { /* surface later via VM event channel */ },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(MytharaColors.Surface)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled,
                singleLine = false,
                cursorBrush = SolidColor(MytharaColors.Charple),
                textStyle = TextStyle(
                    color = MytharaColors.Fg,
                    fontFamily = JetBrainsMono,
                    fontSize = 14.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                "tap mic or type…",
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled && draft.isNotBlank()) MytharaColors.Charple else MytharaColors.Surface)
                .border(1.dp, if (enabled && draft.isNotBlank()) MytharaColors.Charple else MytharaColors.SurfaceHigh, CircleShape)
                .clickable(enabled = enabled && draft.isNotBlank()) {
                    // Typed input — fromVoice=false. Long answers with
                    // markdown are fine on the chat surface, so we
                    // skip the brevity system prompt.
                    onSubmit(draft, /* fromVoice = */ false)
                    draft = ""
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (enabled) Glyph.Arrow else Glyph.Ellipsis,
                color = if (enabled && draft.isNotBlank()) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Banner(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    align: Alignment,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Three-chip filter strip for lifeline photos in the chat scrollback.
 * Shown only when there's at least one foreign-device photo in the
 * timeline (controlled by UiState.hasRemoteLifeline).
 */
@Composable
private fun LifelineFilterChips(
    current: ChatViewModel.LifelineFilter,
    onSelect: (ChatViewModel.LifelineFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${Glyph.AccentBar} photos:",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
        FilterChip(label = "all", active = current == ChatViewModel.LifelineFilter.ALL) {
            onSelect(ChatViewModel.LifelineFilter.ALL)
        }
        FilterChip(label = "this device", active = current == ChatViewModel.LifelineFilter.LOCAL) {
            onSelect(ChatViewModel.LifelineFilter.LOCAL)
        }
        FilterChip(label = "other devices", active = current == ChatViewModel.LifelineFilter.REMOTE) {
            onSelect(ChatViewModel.LifelineFilter.REMOTE)
        }
    }
}

/**
 * Tiny standalone ViewModel — just exposes the live pending-task
 * count so the chat-header pill can show a badge without ChatViewModel
 * needing to know about the tasks subsystem.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class TasksPillViewModel @javax.inject.Inject constructor(
    repo: com.mythara.tasks.TaskRepository,
) : androidx.lifecycle.ViewModel() {
    val pendingCount: StateFlow<Int> = repo.dao.pendingCountFlow()
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            0,
        )
}

@Composable
private fun TasksPill(onClick: () -> Unit) {
    val vm: TasksPillViewModel = hiltViewModel()
    val pending by vm.pendingCount.collectAsState()
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.Malibu, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (pending > 0) "${Glyph.DiamondFilled} tasks · $pending"
            else "${Glyph.DiamondFilled} tasks",
            style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.Malibu),
        )
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val border = if (active) MytharaColors.Mustard else MytharaColors.SurfaceHigh
    val txt = if (active) MytharaColors.Mustard else MytharaColors.FgMute
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MytharaColors.Surface)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text = label, color = txt, style = MaterialTheme.typography.labelSmall)
    }
}
