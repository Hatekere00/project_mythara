package com.mythara.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.AgentLoop
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.mic.Tts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt VM driving the chat surface. Owns:
 *  - the user-facing message list (derived from Room via [HistoryRepository])
 *  - an in-flight streaming buffer (when the assistant is actively talking)
 *  - the "thinking" state machine for the wordmark shimmer
 *  - the "needs API key" hint (raised when AgentLoop returns MissingApiKey)
 *
 * Settings UI is decoupled; this VM does not own region/key/model state.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: AgentLoop,
    history: HistoryRepository,
    private val tts: Tts,
) : ViewModel() {
    init { tts.init() }


    data class UiState(
        val messages: List<UiMessage> = emptyList(),
        val streaming: String? = null,
        val thinking: Boolean = false,
        val needsApiKey: Boolean = false,
        val errorBanner: String? = null,
    )

    data class UiMessage(
        val id: Long,
        val role: String,
        val text: String,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // Observe persisted history and refresh the message list whenever it changes.
        history.dao.observeAll()
            .map { rows -> rows.map { it.toUi() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = emptyList())
            .let { hot ->
                viewModelScope.launch {
                    hot.collect { list -> _ui.update { it.copy(messages = list) } }
                }
            }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(thinking = true, streaming = "", needsApiKey = false, errorBanner = null) }
        viewModelScope.launch {
            agent.submit(text).collect { turn ->
                when (turn) {
                    is AgentLoop.Turn.Delta -> _ui.update {
                        it.copy(streaming = (it.streaming ?: "") + turn.text)
                    }
                    is AgentLoop.Turn.Finished -> {
                        _ui.update { it.copy(streaming = null, thinking = false) }
                        // Speak the assistant reply via native TTS. M3 default;
                        // a Settings toggle for MiniMax T2A lands later.
                        tts.speak(turn.finalText)
                    }
                    is AgentLoop.Turn.Error -> _ui.update {
                        it.copy(streaming = null, thinking = false, errorBanner = turn.message)
                    }
                    is AgentLoop.Turn.MissingApiKey -> _ui.update {
                        it.copy(streaming = null, thinking = false, needsApiKey = true)
                    }
                }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(errorBanner = null) }
    fun dismissMissingKey() = _ui.update { it.copy(needsApiKey = false) }

    private fun MessageRow.toUi(): UiMessage = UiMessage(
        id = id, role = role, text = content.orEmpty(),
    )
}
