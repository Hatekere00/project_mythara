package com.mythara.ui.secret.speaker

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.secret.observe.AudioRecorder
import com.mythara.secret.observe.speaker.EnrolledSpeaker
import com.mythara.secret.observe.speaker.SpeakerVault
import com.mythara.secret.observe.vosk.SpeakerModelStore
import com.mythara.secret.observe.vosk.VoskAsr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive as coroutineJobActive
import javax.inject.Inject

/**
 * Drives the speaker-enrollment dialog. While recording, an
 * AudioRecorder feeds 16 kHz mono PCM into a Vosk Recognizer that
 * has the SpeakerModel attached — each final utterance yields both
 * a transcript (for live UI feedback) and an x-vector that lands in
 * [collectedVectors]. When the user is happy, [save] averages the
 * vectors and persists an [EnrolledSpeaker] via [SpeakerVault.enroll].
 *
 * Mic conflict: this owns its own AudioRecorder during enrollment,
 * which means Observe + Mythara-listen + Continuous-chat must all be
 * off. Caller (the dialog) checks before opening; runtime conflict
 * shows up as an `AudioRecord init failed` error in [state.error].
 */
@HiltViewModel
class SpeakerEnrollmentViewModel @Inject constructor(
    private val asr: VoskAsr,
    private val speakerModelStore: SpeakerModelStore,
    private val vault: SpeakerVault,
) : ViewModel() {

    data class State(
        val open: Boolean = false,
        val name: String = "",
        val recording: Boolean = false,
        val partial: String = "",
        val samplesCollected: Int = 0,
        val error: String? = null,
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Live list of currently-enrolled speakers, for the panel UI. */
    val enrolled: StateFlow<List<EnrolledSpeaker>> = vault.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val collectedVectors = mutableListOf<FloatArray>()
    private var recordJob: Job? = null

    fun openDialog() {
        if (!asr.isReady()) {
            _state.value = State(open = true, error = "Vosk language model not downloaded")
            return
        }
        if (!speakerModelStore.isExtracted()) {
            _state.value = State(open = true, error = "Speaker model not downloaded")
            return
        }
        _state.value = State(open = true)
    }

    fun closeDialog() {
        stopRecording()
        collectedVectors.clear()
        _state.value = State()
    }

    fun setName(value: String) {
        _state.update { it.copy(name = value, error = null) }
    }

    fun startRecording() {
        if (_state.value.recording) return
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(error = "Pick a name first") }
            return
        }
        collectedVectors.clear()
        _state.update {
            it.copy(recording = true, partial = "", samplesCollected = 0, error = null)
        }
        recordJob = viewModelScope.launch(Dispatchers.IO) {
            runRecord()
        }
    }

    fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        _state.update { it.copy(recording = false, partial = "") }
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank() || collectedVectors.isEmpty()) {
            _state.update { it.copy(error = "Record at least one utterance first") }
            return
        }
        stopRecording()
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { vault.enroll(s.name.trim(), collectedVectors.toList()) }
                .onSuccess {
                    Log.d(TAG, "enrolled ${it.name} with ${it.enrollmentSampleCount} samples")
                    collectedVectors.clear()
                    _state.value = State() // close dialog on success
                }
                .onFailure { e ->
                    Log.e(TAG, "enroll failed: ${e.message}", e)
                    _state.update {
                        it.copy(saving = false, error = e.message ?: "enroll failed")
                    }
                }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { vault.delete(id) }
        }
    }

    override fun onCleared() {
        stopRecording()
        super.onCleared()
    }

    /**
     * The recording loop. Mirrors ObserveSession's audio path but
     * scoped to a single enrollment session: every final transcript
     * with a non-null x-vector adds to [collectedVectors] and bumps
     * `samplesCollected`. Partials surface as live text feedback.
     */
    private suspend fun runRecord() {
        val recorder = AudioRecorder()
        if (!recorder.start()) {
            _state.update {
                it.copy(
                    recording = false,
                    error = "Microphone busy — close Observe / Mythara-listen / voice mode and try again",
                )
            }
            return
        }
        val recognizer = runCatching { asr.newRecognizer() }.getOrElse { e ->
            recorder.release()
            _state.update {
                it.copy(recording = false, error = "Recognizer init: ${e.message}")
            }
            return
        }
        val buf = ShortArray(recorder.readFrameSamples)
        // Vosk only marks an utterance "final" when it detects an
        // end-of-utterance silence gap. If the user reads continuously
        // (no pauses), acceptWaveForm never returns true and the spk
        // x-vector is never emitted — the save button would stay
        // disabled. To fix: every FORCE_FLUSH_MS, call finalResult
        // ourselves to flush whatever's accumulated, capture the spk,
        // and continue. We still honour natural utterance boundaries
        // when they happen — both paths feed into captureFromJson.
        var lastFlushMs = System.currentTimeMillis()
        fun captureFromJson(json: String) {
            val text = asr.parseText(json)
            val spk = asr.parseSpk(json)
            if (spk != null && spk.isNotEmpty()) {
                collectedVectors += spk
                _state.update {
                    it.copy(
                        samplesCollected = collectedVectors.size,
                        partial = text.take(120),
                    )
                }
                Log.d(TAG, "captured sample #${collectedVectors.size} (spk dim=${spk.size})")
            } else if (text.isNotBlank()) {
                Log.d(TAG, "got text but no spk vector — keep talking for ~1 more second")
                _state.update { it.copy(partial = text.take(120)) }
            }
        }
        try {
            while (coroutineContext.coroutineJobActive) {
                val n = recorder.read(buf)
                if (n <= 0) continue
                val isFinal = recognizer.acceptWaveForm(buf, n)
                val now = System.currentTimeMillis()
                if (isFinal) {
                    captureFromJson(recognizer.result)
                    lastFlushMs = now
                } else if (now - lastFlushMs >= FORCE_FLUSH_MS) {
                    // Continuous talk path — manually flush so we don't
                    // need to wait for a natural silence.
                    captureFromJson(recognizer.finalResult)
                    lastFlushMs = now
                } else {
                    val partial = runCatching {
                        org.json.JSONObject(recognizer.partialResult)
                            .optString("partial", "")
                    }.getOrDefault("")
                    if (partial.isNotBlank()) {
                        _state.update { it.copy(partial = partial.take(120)) }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) {
                Log.e(TAG, "enrollment loop crashed: ${t.message}", t)
                _state.update {
                    it.copy(recording = false, error = t.message ?: "recording failed")
                }
            }
        } finally {
            runCatching { recognizer.close() }
            recorder.stop()
            recorder.release()
        }
    }

    companion object {
        private const val TAG = "Mythara/SpkEnroll"
        /**
         * Force a Vosk `finalResult` flush every N ms during recording so
         * each chunk of speech yields an x-vector even if the user
         * doesn't pause. 3s captures a couple of natural utterances per
         * flush, which the SpeakerVault then averages on save.
         */
        private const val FORCE_FLUSH_MS = 3_000L
    }
}
