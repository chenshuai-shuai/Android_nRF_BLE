package no.nordicsemi.android.blinky.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.getCharacteristic
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.blinky.ble.data.ButtonCallback
import no.nordicsemi.android.blinky.ble.data.LedCallback
import no.nordicsemi.android.blinky.ble.data.LedData
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.spec.AudioStats
import no.nordicsemi.android.blinky.spec.BlinkySpec
import no.nordicsemi.android.blinky.spec.ConversationState
import no.nordicsemi.android.blinky.spec.GrpcStatusStore
import timber.log.Timber

class BlinkyManager(
    context: Context,
    device: BluetoothDevice
): Blinky by BlinkyManagerImpl(context, device)

private class BlinkyManagerImpl(
    context: Context,
    private val device: BluetoothDevice,
): BleManager(context), Blinky {
    private val scope = CoroutineScope(Dispatchers.IO)

    // Re-map: LED -> RX (write without response), Button -> TX (notify)
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null

    private val _ledState = MutableStateFlow(false)
    override val ledState = _ledState.asStateFlow()

    private val _buttonState = MutableStateFlow(false)
    override val buttonState = _buttonState.asStateFlow()

    private val _rxMessages = MutableStateFlow<List<String>>(emptyList())
    override val rxMessages = _rxMessages.asStateFlow()

    private val _audioStats = MutableStateFlow(AudioStats())
    override val audioStats = _audioStats.asStateFlow()

    private val _recording = MutableStateFlow(false)
    override val recording = _recording.asStateFlow()

    private val _lastSavedPath = MutableStateFlow<String?>(null)
    override val lastSavedPath = _lastSavedPath.asStateFlow()

    override val grpcState = GrpcStatusStore.state
    override val grpcLastMessage = GrpcStatusStore.lastMessage

    private val _conversationState = MutableStateFlow(ConversationState.IDLE)
    override val conversationState = _conversationState.asStateFlow()
    private val _conversationSessionId = MutableStateFlow<String?>(null)
    override val conversationSessionId = _conversationSessionId.asStateFlow()
    private val _waitingResponseSeconds = MutableStateFlow(0L)
    override val waitingResponseSeconds = _waitingResponseSeconds.asStateFlow()

    private var sessionId: String? = null
    private var sessionStartMs: Long = 0L
    private var lastSpeechMs: Long = 0L
    private var idleTimeoutMs: Long = 5 * 60 * 1000L
    private var maxSessionMs: Long = 55 * 60 * 1000L
    private var waitingTimeoutMs: Long = 30 * 1000L
    private var waitingJob: Job? = null
    private var sessionJob: Job? = null
    private var talkJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var talkSeq: Long = 1L

    private var audioCurrSeq = -1
    private var audioCurrMask = 0
    private var audioCurrFragCnt = 0
    private var audioFragBuf: Array<ByteArray?> = emptyArray()
    private var audioLastCompleteSeq = -1
    private val sampleRateHz = 24000
    private val nrfSampleRateHz = 16000
    private val channels = 1
    private val bitsPerSample = 16
    private val useBleMic = true

    override val state = stateAsFlow()
        .map {
            when (it) {
                is ConnectionState.Connecting,
                is ConnectionState.Initializing -> Blinky.State.LOADING
                is ConnectionState.Ready -> Blinky.State.READY
                is ConnectionState.Disconnecting,
                is ConnectionState.Disconnected -> Blinky.State.NOT_AVAILABLE
            }
        }
        .stateIn(scope, SharingStarted.Lazily, Blinky.State.NOT_AVAILABLE)


    private val buttonCallback by lazy {
        object : ButtonCallback() {
            override fun onMessageReceived(device: BluetoothDevice, data: Data) {
                val bytes = data.value
                if (bytes != null && bytes.size >= 8 && handleAudioPacket(bytes)) {
                    return
                }

                val text = data.getStringValue(0) ?: ""
                if (text.isNotEmpty()) {
                    val updated = _rxMessages.value + text
                    _rxMessages.value = updated.takeLast(20)
                }
            }
        }
    }

    private val ledCallback by lazy {
        object : LedCallback() {
            override fun onLedStateChanged(device: BluetoothDevice, state: Boolean) {
                _ledState.tryEmit(state)
            }
        }
    }

    override suspend fun connect() = connect(device)
        .retry(3, 300)
        .useAutoConnect(false)
        .timeout(3000)
        .suspend()

    override fun release() {
        // Cancel all coroutines.
        scope.cancel()
        sessionJob?.cancel()
        sessionJob = null
        waitingJob?.cancel()
        waitingJob = null
        if (!useBleMic) {
            stopMicCapture()
        }
        releaseAudioTrack()
        sessionId?.let { GrpcAudioClient.endConversation(it) }
        GrpcAudioClient.close()
        sessionId = null
        _conversationSessionId.value = null
        _waitingResponseSeconds.value = 0L
        _conversationState.value = ConversationState.IDLE

        val wasConnected = isReady
        // If the device wasn't connected, it means that ConnectRequest was still pending.
        // Cancelling queue will initiate disconnecting automatically.
        cancelQueue()

        // If the device was connected, we have to disconnect manually.
        if (wasConnected) {
            disconnect().enqueue()
        }
    }

    override suspend fun turnLed(state: Boolean) {
        // Keep legacy behavior for now, but send as Write Without Response.
        writeCharacteristic(
            ledCharacteristic,
            LedData.from(state),
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ).suspend()

        _ledState.value = state
    }

    override fun log(priority: Int, message: String) {
        Timber.log(priority, message)
    }

    override fun getMinLogPriority(): Int {
        // By default, the library logs only INFO or
        // higher priority messages. You may change it here.
        return Log.INFO
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Get the custom BLE service from the gatt object.
        gatt.getService(BlinkySpec.BLINKY_SERVICE_UUID)?.apply {
            // RX characteristic (Write Without Response).
            ledCharacteristic = getCharacteristic(
                BlinkySpec.BLINKY_LED_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            )
            // TX characteristic (Notify).
            buttonCharacteristic = getCharacteristic(
                BlinkySpec.BLINKY_BUTTON_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
            )

            // Return true if all required characteristics are supported.
            return ledCharacteristic != null && buttonCharacteristic != null
        }
        return false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun initialize() {
        // Enable notifications for the TX characteristic (from nRF to App).
        setNotificationCallback(buttonCharacteristic).with(buttonCallback)
        enableNotifications(buttonCharacteristic).enqueue()

        requestMtu(247).enqueue()
        requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH).enqueue()

        // Align gRPC audio format with nRF mic settings (16kHz, mono, 16-bit).
        GrpcAudioClient.configure(sampleRateHz, channels, bitsPerSample)
    }

    override fun onServicesInvalidated() {
        ledCharacteristic = null
        buttonCharacteristic = null
    }

    override suspend fun sendMessage(text: String) {
        if (text.isEmpty()) return
        val data = text.encodeToByteArray()
        writeCharacteristic(
            ledCharacteristic,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ).suspend()
    }

    override suspend fun startRecording() {
        // Recording to WAV is disabled in this mode (App only forwards).
        _recording.value = false
        _lastSavedPath.value = "RECORDING_DISABLED"
    }

    override suspend fun stopRecording() {
        _recording.value = false
    }

    override suspend fun startConversation() {
        if (_conversationState.value != ConversationState.IDLE) return
        sessionId = "session_${System.currentTimeMillis()}"
        _conversationSessionId.value = sessionId
        sessionStartMs = System.currentTimeMillis()
        lastSpeechMs = sessionStartMs
        _conversationState.value = ConversationState.CONNECTING
        ensureAudioTrack()
        GrpcAudioClient.startSession(sessionId!!)
        sendPrimerFrame()
        GrpcAudioClient.setAudioOutputListener { pcm ->
            playAudio(pcm)
        }
        GrpcAudioClient.setAudioCompleteListener {
            if (_conversationState.value == ConversationState.WAITING_RESPONSE) {
                _conversationState.value = ConversationState.READY
                _waitingResponseSeconds.value = 0L
            }
        }
        GrpcAudioClient.setErrorListener { msg ->
            Timber.e("gRPC error: %s", msg)
        }
        startSessionWatchdog()
        _conversationState.value = ConversationState.READY
    }

    override suspend fun startTalking() {
        val state = _conversationState.value
        if (state != ConversationState.READY) return
        if (sessionId == null) {
            startConversation()
        }
        _conversationState.value = ConversationState.TALKING
        lastSpeechMs = System.currentTimeMillis()
        if (!useBleMic) {
            startMicCapture()
        }
    }

    override suspend fun stopTalking() {
        if (_conversationState.value != ConversationState.TALKING) return
        if (!useBleMic) {
            stopMicCapture()
        }
        lastSpeechMs = System.currentTimeMillis()
        _conversationState.value = ConversationState.WAITING_RESPONSE
        startWaitingCountdown()
    }

    override suspend fun endConversation() {
        if (_conversationState.value == ConversationState.IDLE) return
        stopMicCapture()
        sessionJob?.cancel()
        sessionJob = null
        waitingJob?.cancel()
        waitingJob = null
        _waitingResponseSeconds.value = 0L
        val id = sessionId
        sessionId = null
        _conversationSessionId.value = null
        _conversationState.value = ConversationState.IDLE
        if (id != null) {
            GrpcAudioClient.endConversation(id)
        }
        GrpcAudioClient.close()
        releaseAudioTrack()
    }

    private fun handleAudioPacket(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        if (bytes[0] != 0xA5.toByte() || bytes[1] != 0x5A.toByte()) return false

        val seq = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val fragIdx = bytes[4].toInt() and 0xFF
        val fragCnt = bytes[5].toInt() and 0xFF
        val payloadLen = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val payloadStart = 8
        if (payloadLen < 0 || payloadStart + payloadLen > bytes.size) return true

        var stats = _audioStats.value
        var dropped = stats.droppedFrames
        var frames = stats.frames

        if (audioCurrSeq != seq) {
            if (audioCurrSeq >= 0 && audioCurrFragCnt > 0) {
                val maskAll = (1 shl audioCurrFragCnt) - 1
                if (audioCurrMask != maskAll) {
                    dropped++
                }
            }
            audioCurrSeq = seq
            audioCurrFragCnt = fragCnt
            audioCurrMask = 0
            audioFragBuf = Array(fragCnt.coerceAtMost(30)) { null }
        }

        if (fragCnt in 1..30) {
            if (fragIdx < 30) {
                audioCurrMask = audioCurrMask or (1 shl fragIdx)
                if (fragIdx < audioFragBuf.size) {
                    audioFragBuf[fragIdx] = bytes.copyOfRange(payloadStart, payloadStart + payloadLen)
                }
            }
            val maskAll = (1 shl fragCnt) - 1
            if (audioCurrMask == maskAll) {
                frames++
                val parts = audioFragBuf.filterNotNull()
                var totalLen = 0
                for (p in parts) {
                    totalLen += p.size
                }
                val frameBytes = ByteArray(totalLen)
                var offset = 0
                for (p in parts) {
                    System.arraycopy(p, 0, frameBytes, offset, p.size)
                    offset += p.size
                }
                if (frameBytes.isNotEmpty()) {
                    audioLastCompleteSeq = seq
                    val sendSeq = seq.toLong()
                    val payload = if (nrfSampleRateHz == sampleRateHz) {
                        frameBytes
                    } else {
                        resample16kTo24k(frameBytes)
                    }
                    scope.launch {
                        if (_conversationState.value == ConversationState.TALKING) {
                            GrpcAudioClient.sendAudio(payload, sendSeq)
                        }
                    }
                }
                audioCurrSeq = -1
                audioCurrFragCnt = 0
                audioCurrMask = 0
                audioFragBuf = emptyArray()
            }
        }

        stats = stats.copy(
            packets = stats.packets + 1,
            bytes = stats.bytes + payloadLen.toLong(),
            frames = frames,
            droppedFrames = dropped,
            lastSeq = seq
        )
        _audioStats.value = stats
        return true
    }

    private fun sendPrimerFrame() {
        val frameSamples = (sampleRateHz / 50).coerceAtLeast(1) // 20ms
        val frameBytes = frameSamples * channels * (bitsPerSample / 8)
        val pcm = ByteArray(frameBytes)
        GrpcAudioClient.sendAudio(pcm, 0L)
    }

    private fun resample16kTo24k(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2) return pcm16
        val samples = pcm16.size / 2
        val outSamples = samples * 3 / 2
        val out = ByteArray(outSamples * 2)
        var outIdx = 0
        var i = 0
        fun getSample(idx: Int): Int {
            val lo = pcm16[idx * 2].toInt() and 0xFF
            val hi = pcm16[idx * 2 + 1].toInt()
            return (hi shl 8) or lo
        }
        while (i < samples - 1) {
            val s0 = getSample(i).toShort()
            val s1 = getSample(i + 1).toShort()
            out[outIdx++] = (s0.toInt() and 0xFF).toByte()
            out[outIdx++] = ((s0.toInt() shr 8) and 0xFF).toByte()
            if (i % 2 == 0) {
                val mid = ((s0.toInt() + s1.toInt()) / 2).toShort()
                out[outIdx++] = (mid.toInt() and 0xFF).toByte()
                out[outIdx++] = ((mid.toInt() shr 8) and 0xFF).toByte()
            }
            i++
        }
        val last = getSample(samples - 1).toShort()
        out[outIdx++] = (last.toInt() and 0xFF).toByte()
        out[outIdx++] = ((last.toInt() shr 8) and 0xFF).toByte()
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    private fun startSessionWatchdog() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            while (true) {
                delay(1000L)
                if (_conversationState.value == ConversationState.IDLE) continue
                val now = System.currentTimeMillis()
                val idle = now - lastSpeechMs
                val age = now - sessionStartMs
                if (idle > idleTimeoutMs || age > maxSessionMs) {
                    Timber.i("Ending session: idle=%dms age=%dms", idle, age)
                    endConversation()
                }
            }
        }
    }

    private fun startWaitingCountdown() {
        waitingJob?.cancel()
        waitingJob = scope.launch {
            var remaining = waitingTimeoutMs / 1000L
            _waitingResponseSeconds.value = remaining
            while (remaining > 0 && _conversationState.value == ConversationState.WAITING_RESPONSE) {
                delay(1000L)
                remaining -= 1
                _waitingResponseSeconds.value = remaining
            }
            if (_conversationState.value != ConversationState.WAITING_RESPONSE) {
                _waitingResponseSeconds.value = 0L
            }
        }
    }

    private fun startMicCapture() {
        if (talkJob?.isActive == true) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRateHz / 50 * 2)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        audioRecord?.startRecording()
        talkJob = scope.launch {
            val buffer = ByteArray(minBuf)
            while (isActive && _conversationState.value == ConversationState.TALKING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val pcm = buffer.copyOf(read)
                    GrpcAudioClient.sendAudio(pcm, talkSeq++)
                }
            }
        }
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
