package no.nordicsemi.android.blinky.ui.conversation.viewmodel

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nordicsemi.android.blinky.ble.GrpcAudioClient
import no.nordicsemi.android.blinky.spec.ConversationState
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    app: Application
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ConversationState.IDLE)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _waitingSeconds = MutableStateFlow(0L)
    val waitingSeconds: StateFlow<Long> = _waitingSeconds.asStateFlow()
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    private val sampleRateHz = 24000
    private val channels = 1
    private val bitsPerSample = 16

    private var sessionStartMs: Long = 0L
    private var lastSpeechMs: Long = 0L
    private var idleTimeoutMs: Long = 5 * 60 * 1000L
    private var maxSessionMs: Long = 55 * 60 * 1000L
    private var waitingTimeoutMs: Long = 30 * 1000L

    private var sessionJob: Job? = null
    private var waitingJob: Job? = null
    private var talkJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var talkSeq: Long = 1L

    fun startConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            startConversationInternal()
        }
    }

    fun startTalking() {
        viewModelScope.launch(Dispatchers.IO) {
            startTalkingInternal()
        }
    }

    fun stopTalking() {
        viewModelScope.launch(Dispatchers.IO) {
            stopTalkingInternal()
        }
    }

    fun endConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            endConversationInternal()
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            endConversationInternal()
        }
    }

    private fun startSessionWatchdog() {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000L)
                if (_state.value == ConversationState.IDLE) continue
                val now = System.currentTimeMillis()
                val idle = now - lastSpeechMs
                val age = now - sessionStartMs
                if (idle > idleTimeoutMs || age > maxSessionMs) {
                    Timber.i("Ending session: idle=%dms age=%dms", idle, age)
                    endConversationInternal()
                }
            }
        }
    }

    private fun startWaitingCountdown() {
        waitingJob?.cancel()
        waitingJob = viewModelScope.launch(Dispatchers.IO) {
            var remaining = waitingTimeoutMs / 1000L
            _waitingSeconds.value = remaining
            while (remaining > 0 && _state.value == ConversationState.WAITING_RESPONSE) {
                delay(1000L)
                remaining -= 1
                _waitingSeconds.value = remaining
            }
            if (_state.value != ConversationState.WAITING_RESPONSE) {
                _waitingSeconds.value = 0L
            }
        }
    }

    private suspend fun startConversationInternal() {
        if (_state.value != ConversationState.IDLE) return
        val id = "session_${System.currentTimeMillis()}"
        _sessionId.value = id
        talkSeq = 1L
        sessionStartMs = System.currentTimeMillis()
        lastSpeechMs = sessionStartMs
        _state.value = ConversationState.CONNECTING
        try {
            ensureAudioTrack()
            GrpcAudioClient.startSession(id)
            sendPrimerFrame()
            GrpcAudioClient.setWatchdogEnabled(false)
            _sessionReady.value = false
            GrpcAudioClient.setSessionStartListener {
                _sessionReady.value = true
            }
            GrpcAudioClient.setAudioStartListener {
                if (_state.value == ConversationState.TALKING) {
                    viewModelScope.launch(Dispatchers.IO) {
                        stopTalkingInternal()
                    }
                }
            }
            GrpcAudioClient.setAudioOutputListener { pcm -> playAudio(pcm) }
            GrpcAudioClient.setAudioCompleteListener {
                if (_state.value == ConversationState.WAITING_RESPONSE) {
                    _state.value = ConversationState.READY
                    _waitingSeconds.value = 0L
                }
            }
            GrpcAudioClient.setErrorListener { msg ->
                Timber.e("gRPC error: %s", msg)
            }
            startSessionWatchdog()
            _state.value = ConversationState.READY
        } catch (t: Throwable) {
            Timber.e("startConversation failed: %s", t.message ?: "unknown")
            _state.value = ConversationState.IDLE
        }
    }

    private suspend fun startTalkingInternal() {
        if (_state.value == ConversationState.IDLE) {
            startConversationInternal()
        }
        if (_state.value != ConversationState.READY) return
        _state.value = ConversationState.TALKING
        lastSpeechMs = System.currentTimeMillis()
        GrpcAudioClient.setWatchdogEnabled(true)
        startMicCapture()
    }

    private suspend fun stopTalkingInternal() {
        if (_state.value != ConversationState.TALKING) return
        stopMicCapture()
        lastSpeechMs = System.currentTimeMillis()
        _state.value = ConversationState.WAITING_RESPONSE
        GrpcAudioClient.setWatchdogEnabled(false)
        startWaitingCountdown()
    }

    private suspend fun endConversationInternal() {
        if (_state.value == ConversationState.IDLE) return
        stopMicCapture()
        sessionJob?.cancel()
        sessionJob = null
        waitingJob?.cancel()
        waitingJob = null
        _waitingSeconds.value = 0L
        val id = _sessionId.value
        _sessionId.value = null
        _state.value = ConversationState.IDLE
        GrpcAudioClient.setWatchdogEnabled(false)
        if (id != null) {
            GrpcAudioClient.endConversation(id)
        }
        GrpcAudioClient.close()
        releaseAudioTrack()
        _sessionReady.value = false
    }

    private fun startMicCapture() {
        if (talkJob?.isActive == true) return
        val frameSamples = (sampleRateHz / 50).coerceAtLeast(1) // 20ms
        val frameBytes = frameSamples * channels * (bitsPerSample / 8)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameBytes * 2)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Timber.tag("GrpcAudioClient").e("AudioRecord init failed: state=%d", audioRecord?.state ?: -1)
            return
        }
        audioRecord?.startRecording()
        talkJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBuf)
            val frame = ByteArray(frameBytes)
            var pending = 0
            while (isActive && _state.value == ConversationState.TALKING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    var offset = 0
                    while (offset < read) {
                        val toCopy = minOf(frameBytes - pending, read - offset)
                        System.arraycopy(buffer, offset, frame, pending, toCopy)
                        pending += toCopy
                        offset += toCopy
                        if (pending == frameBytes) {
                            Timber.tag("GrpcAudioClient").i("Mic frame bytes=%d", frameBytes)
                            GrpcAudioClient.sendAudio(frame, talkSeq++)
                            pending = 0
                        }
                    }
                } else if (read < 0) {
                    Timber.tag("GrpcAudioClient").e("AudioRecord read error=%d", read)
                }
            }
        }
    }

    private fun sendPrimerFrame() {
        val frameSamples = (sampleRateHz / 50).coerceAtLeast(1) // 20ms
        val frameBytes = frameSamples * channels * (bitsPerSample / 8)
        val pcm = ByteArray(frameBytes)
        Timber.tag("GrpcAudioClient").i("Primer frame bytes=%d", frameBytes)
        GrpcAudioClient.sendAudio(pcm, talkSeq++)
    }

    private fun stopMicCapture() {
        talkJob?.cancel()
        talkJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun ensureAudioTrack() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRateHz / 50 * 2)
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .build()
        audioTrack?.play()
    }

    private fun playAudio(pcm: ByteArray) {
        val track = audioTrack ?: return
        track.write(pcm, 0, pcm.size)
    }

    private fun releaseAudioTrack() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
