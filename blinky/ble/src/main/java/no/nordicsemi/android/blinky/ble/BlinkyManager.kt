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
import kotlinx.coroutines.channels.Channel
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
    private val _conversationSessionReady = MutableStateFlow(false)
    override val conversationSessionReady = _conversationSessionReady.asStateFlow()

    private var sessionId: String? = null
    private var sessionStartMs: Long = 0L
    private var lastSpeechMs: Long = 0L
    private var idleTimeoutMs: Long = 5 * 60 * 1000L
    private var maxSessionMs: Long = 55 * 60 * 1000L
    private var waitingTimeoutMs: Long = 30 * 1000L
    private var waitingJob: Job? = null
    private var sessionJob: Job? = null
    private var keepaliveJob: Job? = null
    private var talkJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var talkSeq: Long = 1L
    private var pendingTalk: Boolean = false
    private var sessionDelayJob: Job? = null
    private var initialSessionDelayMs: Long = 8000L
    private var playbackJob: Job? = null
    private val playbackLock = Any()
    private val playbackQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var playbackQueuedBytes: Int = 0
    @Volatile private var playbackDraining: Boolean = false
    @Volatile private var lastAudioRxMs: Long = 0L
    @Volatile private var playbackAllowed: Boolean = false
    @Volatile private var playbackForceDrain: Boolean = false
    private var downlinkJob: Job? = null
    private val downlinkChunks: ArrayList<ByteArray> = ArrayList()
    private var downlinkSeq: Int = 1
    private var nrfPlaybackPending: Boolean = false
    @Volatile private var nrfBufferFull: Boolean = false
    @Volatile private var nrfReady: Boolean = false
    private val downlinkTestEnabled = false
    private var downlinkTestJob: Job? = null
    private val playDoneSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var pendingSessionReset: Boolean = false
    @Volatile private var pendingSessionResetId: String? = null
    private var connectivityListenerSet: Boolean = false

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
    private val useNrfSpeaker = true
    private val playbackFrameBytes = (sampleRateHz / 50) * channels * (bitsPerSample / 8)
    private var playbackStartFrames = 20  // ~400ms
    private var playbackLowFrames = 10    // ~200ms
    private val playbackMaxFrames = 1200  // ~24s cap
    private val playbackMinStartFrames = 10
    private val playbackMinLowFrames = 5
    private val playbackMaxStartFrames = 40
    private val playbackMaxLowFrames = 20

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
                if (text == "BUF_FULL") {
                    nrfBufferFull = true
                    return
                }
                if (text == "BUF_LOW") {
                    nrfBufferFull = false
                    return
                }
                if (text == "PLAY_DONE") {
                    onNrfPlaybackDone()
                    return
                }
                if (text == "NRF_READY") {
                    nrfReady = true
                    Timber.tag("GrpcAudioClient").i("BLE NRF_READY")
                    return
                }
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

        if (downlinkTestEnabled) {
            startDownlinkTest()
        }
    }

    override fun onServicesInvalidated() {
        ledCharacteristic = null
        buttonCharacteristic = null
        downlinkTestJob?.cancel()
        downlinkTestJob = null
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
        if (downlinkTestEnabled) return
        if (_conversationState.value != ConversationState.IDLE) return
        sessionId = "session_${System.currentTimeMillis()}"
        _conversationSessionId.value = sessionId
        _conversationSessionReady.value = false
        pendingTalk = false
        sessionStartMs = System.currentTimeMillis()
        lastSpeechMs = sessionStartMs
        _conversationState.value = ConversationState.CONNECTING
        playbackAllowed = false
        playbackDraining = false
        playbackForceDrain = false
        ensureAudioTrack()
        GrpcAudioClient.startSession(sessionId!!)
        sendPrimerFrame()
        GrpcAudioClient.setAudioStartListener {
            if (_conversationState.value != ConversationState.IDLE) {
                _conversationState.value = ConversationState.WAITING_RESPONSE
                startWaitingCountdown()
            }
            GrpcAudioClient.setSendPaused(true)
            updateKeepalive()
        }
        GrpcAudioClient.setSessionStartListener {
            _conversationSessionReady.value = true
            sessionDelayJob?.cancel()
            sessionDelayJob = null
            if (pendingTalk) {
                pendingTalk = false
                _conversationState.value = ConversationState.TALKING
            } else {
                _conversationState.value = ConversationState.READY
            }
            updateKeepalive()
        }
        GrpcAudioClient.setAudioOutputListener { pcm ->
            if (_conversationState.value != ConversationState.IDLE &&
                _conversationState.value != ConversationState.WAITING_RESPONSE) {
                _conversationState.value = ConversationState.WAITING_RESPONSE
                startWaitingCountdown()
            }
            if (useNrfSpeaker) {
                val pcm16 = resample24kTo16k(pcm)
                downlinkChunks.add(pcm16)
            } else {
                enqueueAudio(pcm)
            }
        }
        GrpcAudioClient.setAudioCompleteListener {
            if (_conversationState.value != ConversationState.IDLE) {
                _conversationState.value = ConversationState.WAITING_RESPONSE
                startWaitingCountdown()
            }
            if (useNrfSpeaker) {
                startDownlinkSend()
            } else {
                // Wait until playback buffer drains before allowing next talk
                playbackAllowed = true
                playbackDraining = true
                playbackForceDrain = true
                ensureAudioTrack()
                startPlaybackLoop()
                val now = System.currentTimeMillis()
                val drained = synchronized(playbackLock) { playbackQueuedBytes == 0 }
                if (drained && now - lastAudioRxMs > 200L) {
                    playbackDraining = false
                    playbackAllowed = false
                    playbackForceDrain = false
                    if (_conversationState.value == ConversationState.WAITING_RESPONSE) {
                        _conversationState.value = ConversationState.READY
                        _waitingResponseSeconds.value = 0L
                        updateKeepalive()
                    }
                }
            }
            GrpcAudioClient.setSendPaused(false)
            updateKeepalive()
        }
        GrpcAudioClient.setErrorListener { msg ->
            Timber.e("gRPC error: %s", msg)
        }
        GrpcAudioClient.setStreamErrorListener { msg ->
            Timber.e("gRPC stream error: %s", msg)
            scope.launch { handleStreamError(msg) }
        }
        if (!connectivityListenerSet) {
            connectivityListenerSet = true
            GrpcAudioClient.setConnectivityListener { state ->
                if (state == io.grpc.ConnectivityState.READY && pendingSessionReset) {
                    pendingSessionReset = false
                    val oldId = pendingSessionResetId ?: sessionId
                    pendingSessionResetId = null
                    if (oldId != null) {
                        Timber.i("Resetting session after stream error, closing %s", oldId)
                        GrpcAudioClient.endConversation(oldId)
                    }
                    sessionId = "session_${System.currentTimeMillis()}"
                    _conversationSessionId.value = sessionId
                    _conversationSessionReady.value = false
                    _conversationState.value = ConversationState.CONNECTING
                    GrpcAudioClient.startSession(sessionId!!)
                    sendPrimerFrame()
                    startInitialSessionDelay()
                }
            }
        }
        startSessionWatchdog()
        startInitialSessionDelay()
    }

    override suspend fun startTalking() {
        if (downlinkTestEnabled) return
        val state = _conversationState.value
        if (sessionId == null) {
            startConversation()
        }
        if ((useNrfSpeaker && nrfPlaybackPending) || (!useNrfSpeaker && (playbackDraining || playbackQueuedBytes > 0))) {
            return
        }
        if (_conversationSessionReady.value) {
            _conversationState.value = ConversationState.TALKING
            lastSpeechMs = System.currentTimeMillis()
            GrpcAudioClient.setSendPaused(false)
        } else {
            pendingTalk = true
        }
        updateKeepalive()
        if (!useBleMic) {
            startMicCapture()
        }
    }

    override suspend fun stopTalking() {
        if (downlinkTestEnabled) return
        if (_conversationState.value != ConversationState.TALKING) {
            if (_conversationState.value == ConversationState.CONNECTING && pendingTalk) {
                pendingTalk = false
                endConversation()
            }
            return
        }
        if (!useBleMic) {
            stopMicCapture()
        }
        lastSpeechMs = System.currentTimeMillis()
        _conversationState.value = ConversationState.WAITING_RESPONSE
        GrpcAudioClient.setSendPaused(true)
        startWaitingCountdown()
        updateKeepalive()
    }

    override suspend fun endConversation() {
        if (downlinkTestEnabled) return
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
        _conversationSessionReady.value = false
        pendingTalk = false
        sessionDelayJob?.cancel()
        sessionDelayJob = null
        _conversationState.value = ConversationState.IDLE
        playbackAllowed = false
        playbackDraining = false
        playbackForceDrain = false
        if (downlinkJob?.isActive == true || nrfPlaybackPending) {
            Timber.tag("GrpcAudioClient").w("gRPC error during NRF downlink; keep BLE send in progress")
        } else {
            nrfPlaybackPending = false
            downlinkChunks.clear()
            downlinkJob?.cancel()
            downlinkJob = null
        }
        if (id != null) {
            GrpcAudioClient.endConversation(id)
        }
        releaseAudioTrack()
        stopKeepalive()
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

    private fun resample24kTo16k(pcm24: ByteArray): ByteArray {
        if (pcm24.size < 2) return pcm24
        val inSamples = pcm24.size / 2
        val outSamples = inSamples * 2 / 3
        val out = ByteArray(outSamples * 2)
        fun getSample(idx: Int): Int {
            val lo = pcm24[idx * 2].toInt() and 0xFF
            val hi = pcm24[idx * 2 + 1].toInt()
            return (hi shl 8) or lo
        }
        var outIdx = 0
        var inIdx = 0
        var acc = 0
        var accCount = 0
        while (inIdx < inSamples && outIdx < out.size) {
            val s = getSample(inIdx).toShort().toInt()
            acc += s
            accCount++
            // Take 2 out of every 3 samples
            if (accCount == 3) {
                val avg = (acc / 3).toShort()
                out[outIdx++] = (avg.toInt() and 0xFF).toByte()
                out[outIdx++] = ((avg.toInt() shr 8) and 0xFF).toByte()
                acc = 0
                accCount = 0
            }
            inIdx++
        }
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

    private fun startDownlinkSend() {
        if (downlinkJob?.isActive == true) return
        if (downlinkChunks.isEmpty()) {
            nrfPlaybackPending = false
            if (_conversationState.value == ConversationState.WAITING_RESPONSE) {
                _conversationState.value = ConversationState.READY
                _waitingResponseSeconds.value = 0L
                updateKeepalive()
            }
            return
        }
        if (ledCharacteristic == null) {
            downlinkChunks.clear()
            nrfPlaybackPending = false
            return
        }
        nrfPlaybackPending = true
        nrfBufferFull = false
        nrfReady = false
        downlinkJob = scope.launch {
            sendBleControl("APP_READY?")
            val ready = waitForNrfReady(3000L)
            if (!ready) {
                Timber.tag("GrpcAudioClient").w("BLE NRF_READY timeout")
                nrfPlaybackPending = false
                downlinkJob = null
                return@launch
            }
            Timber.tag("GrpcAudioClient").i("BLE APP_PLAY_START")
            sendBleControl("APP_PLAY_START")
            val frameBytes = (nrfSampleRateHz / 50) * (bitsPerSample / 8) // 20ms @16k
            var seq = downlinkSeq
            val buffer = ArrayList<ByteArray>(downlinkChunks)
            downlinkChunks.clear()
            for (chunk in buffer) {
                var offset = 0
                while (offset < chunk.size) {
                    val remaining = chunk.size - offset
                    val take = minOf(frameBytes, remaining)
                    val frame = if (take == frameBytes) {
                        chunk.copyOfRange(offset, offset + take)
                    } else {
                        val padded = ByteArray(frameBytes)
                        System.arraycopy(chunk, offset, padded, 0, take)
                        padded
                    }
                    while (nrfBufferFull) {
                        delay(10L)
                    }
                    sendBleAudioFrame(frame, seq++)
                    delay(20L)
                    offset += take
                }
            }
            sendBleAudioEnd(seq++)
            downlinkSeq = seq
            downlinkJob = null
        }
    }

    private suspend fun sendBleAudioFrame(pcm: ByteArray, seq: Int) {
        val mtuPayload = 48
        val fragCnt = ((pcm.size + mtuPayload - 1) / mtuPayload).coerceAtLeast(1)
        var offset = 0
        for (frag in 0 until fragCnt) {
            val remaining = pcm.size - offset
            val chunk = minOf(remaining, mtuPayload)
            val pkt = ByteArray(8 + chunk)
            pkt[0] = 0xA5.toByte()
            pkt[1] = 0x5A.toByte()
            pkt[2] = (seq and 0xFF).toByte()
            pkt[3] = ((seq shr 8) and 0xFF).toByte()
            pkt[4] = frag.toByte()
            pkt[5] = fragCnt.toByte()
            pkt[6] = (chunk and 0xFF).toByte()
            pkt[7] = ((chunk shr 8) and 0xFF).toByte()
            System.arraycopy(pcm, offset, pkt, 8, chunk)
            writeCharacteristic(
                ledCharacteristic,
                pkt,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ).enqueue()
            offset += chunk
            delay(2L)
        }
    }

    private fun sendBleAudioEnd(seq: Int) {
        val pkt = ByteArray(8)
        pkt[0] = 0xA5.toByte()
        pkt[1] = 0x5A.toByte()
        pkt[2] = (seq and 0xFF).toByte()
        pkt[3] = ((seq shr 8) and 0xFF).toByte()
        pkt[4] = 0
        pkt[5] = 0
        pkt[6] = 0
        pkt[7] = 0
        writeCharacteristic(
            ledCharacteristic,
            pkt,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ).enqueue()
    }

    private fun onNrfPlaybackDone() {
        nrfPlaybackPending = false
        nrfReady = false
        if (downlinkTestEnabled) {
            playDoneSignal.trySend(Unit)
        }
        if (_conversationState.value == ConversationState.WAITING_RESPONSE) {
            _conversationState.value = ConversationState.READY
            _waitingResponseSeconds.value = 0L
            updateKeepalive()
        }
    }

    private fun startDownlinkTest() {
        if (downlinkTestJob?.isActive == true) return
        downlinkTestJob = scope.launch {
            delay(500L)
            val tone = generateTestPattern16k()
            while (downlinkTestEnabled && isReady) {
                sendBleControl("APP_READY?")
                val ready = waitForNrfReady(3000L)
                if (!ready) {
                    Timber.tag("GrpcAudioClient").w("BLE NRF_READY timeout (test)")
                    delay(500L)
                    continue
                }
                sendBleControl("APP_PLAY_START")
                sendTestDownlink(tone)
                sendBleAudioEnd(downlinkSeq++)
                val done = withTimeoutOrNull<Unit>(10_000L) {
                    playDoneSignal.receive()
                }
                if (done == null) {
                    Timber.tag("GrpcAudioClient").w("BLE PLAY_DONE timeout (test)")
                }
                nrfReady = false
                delay(300L)
            }
        }
    }

    private suspend fun sendTestDownlink(pcm16: ByteArray) {
        val frameBytes = (nrfSampleRateHz / 50) * (bitsPerSample / 8)
        var offset = 0
        while (offset < pcm16.size) {
            val remaining = pcm16.size - offset
            val take = minOf(frameBytes, remaining)
            val frame = if (take == frameBytes) {
                pcm16.copyOfRange(offset, offset + take)
            } else {
                val padded = ByteArray(frameBytes)
                System.arraycopy(pcm16, offset, padded, 0, take)
                padded
            }
            while (nrfBufferFull) {
                delay(10L)
            }
            sendBleAudioFrame(frame, downlinkSeq++)
            delay(20L)
            offset += take
        }
    }

    private fun generateTestPattern16k(): ByteArray {
        val sr = nrfSampleRateHz
        val amp = (Short.MAX_VALUE * 0.35).toInt()
        val silenceMs = 40
        val toneMs = 250

        fun appendTone(freqHz: Double, dst: ByteArray, startIdx: Int): Int {
            val totalSamples = (sr * toneMs / 1000.0).toInt()
            var phase = 0.0
            val step = 2.0 * Math.PI * freqHz / sr
            var idx = startIdx
            repeat(totalSamples) {
                val v = (kotlin.math.sin(phase) * amp).toInt().toShort()
                dst[idx++] = (v.toInt() and 0xFF).toByte()
                dst[idx++] = ((v.toInt() shr 8) and 0xFF).toByte()
                phase += step
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
            }
            return idx
        }

        fun appendSilence(dst: ByteArray, startIdx: Int): Int {
            val totalSamples = (sr * silenceMs / 1000.0).toInt()
            var idx = startIdx
            repeat(totalSamples) {
                dst[idx++] = 0
                dst[idx++] = 0
            }
            return idx
        }

        // 3-tone pattern: 440 Hz -> 880 Hz -> 660 Hz, with short gaps.
        val totalMs = toneMs * 3 + silenceMs * 2
        val totalSamples = (sr * totalMs / 1000.0).toInt()
        val pcm = ByteArray(totalSamples * 2)
        var idx = 0
        idx = appendTone(440.0, pcm, idx)
        idx = appendSilence(pcm, idx)
        idx = appendTone(880.0, pcm, idx)
        idx = appendSilence(pcm, idx)
        idx = appendTone(660.0, pcm, idx)
        return if (idx == pcm.size) pcm else pcm.copyOf(idx)
    }

    private suspend fun waitForNrfReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!nrfReady && System.currentTimeMillis() < deadline) {
            delay(20L)
        }
        return nrfReady
    }

    private fun sendBleControl(cmd: String) {
        val ch = ledCharacteristic ?: return
        val data = cmd.toByteArray()
        writeCharacteristic(
            ch,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ).enqueue()
    }

    private fun updateKeepalive() {
        if (_conversationState.value == ConversationState.READY && _conversationSessionReady.value) {
            startKeepalive()
        } else {
            stopKeepalive()
        }
    }

    private fun startKeepalive() {
        if (keepaliveJob?.isActive == true) return
        val frameSamples = (sampleRateHz / 50).coerceAtLeast(1) // 20ms
        val frameBytes = frameSamples * channels * (bitsPerSample / 8)
        val silent = ByteArray(frameBytes)
        keepaliveJob = scope.launch {
            var seq = 3_000_000L
            while (true) {
                delay(1000L)
                if (_conversationState.value != ConversationState.READY || !_conversationSessionReady.value) {
                    break
                }
                GrpcAudioClient.sendAudio(silent, seq++)
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private suspend fun handleStreamError(msg: String) {
        stopMicCapture()
        sessionJob?.cancel()
        sessionJob = null
        waitingJob?.cancel()
        waitingJob = null
        _waitingResponseSeconds.value = 0L
        sessionDelayJob?.cancel()
        sessionDelayJob = null
        pendingTalk = false
        if (msg.contains("completed", ignoreCase = true)) {
            pendingSessionReset = false
            pendingSessionResetId = null
            sessionId = null
            _conversationSessionId.value = null
            _conversationSessionReady.value = false
            _conversationState.value = ConversationState.IDLE
        } else if (msg.contains("INTERNAL", ignoreCase = true) ||
            msg.contains("frame handler", ignoreCase = true) ||
            msg.contains("UNAVAILABLE", ignoreCase = true) ||
            msg.contains("IOException", ignoreCase = true) ||
            msg.contains("End of stream", ignoreCase = true)) {
            pendingSessionReset = true
            pendingSessionResetId = sessionId
        }
        // Do not proactively end the session or close gRPC here.
        // Let GrpcAudioClient handle reconnects; keep session id to resume.
        GrpcAudioClient.setSendPaused(true)
        _conversationSessionReady.value = false
        _conversationState.value = ConversationState.CONNECTING
        playbackAllowed = false
        playbackDraining = false
        playbackForceDrain = false
        nrfPlaybackPending = false
        downlinkChunks.clear()
        downlinkJob?.cancel()
        downlinkJob = null
        stopKeepalive()
        releaseAudioTrack()
    }

    private fun startInitialSessionDelay() {
        sessionDelayJob?.cancel()
        sessionDelayJob = scope.launch {
            delay(initialSessionDelayMs)
            if (_conversationState.value == ConversationState.CONNECTING && !_conversationSessionReady.value) {
                _conversationSessionReady.value = true
                if (pendingTalk) {
                    pendingTalk = false
                    _conversationState.value = ConversationState.TALKING
                } else {
                    _conversationState.value = ConversationState.READY
                }
                updateKeepalive()
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
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        audioTrack?.play()
        startPlaybackLoop()
    }

    private fun enqueueAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        lastAudioRxMs = System.currentTimeMillis()
        synchronized(playbackLock) {
            playbackQueue.addLast(pcm)
            playbackQueuedBytes += pcm.size
            val maxBytes = playbackMaxFrames * playbackFrameBytes
            while (playbackQueuedBytes > maxBytes && playbackQueue.isNotEmpty()) {
                val drop = playbackQueue.removeFirst()
                playbackQueuedBytes -= drop.size
            }
        }
    }

    private fun startPlaybackLoop() {
        if (playbackJob?.isActive == true) return
        playbackJob = scope.launch {
            var lastUnderrun = 0
            var lastCheckMs = System.currentTimeMillis()
            var lastStableMs = lastCheckMs
            while (true) {
                val track = audioTrack ?: break
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    break
                }
                if (!playbackAllowed) {
                    delay(10L)
                    continue
                }
                var chunk: ByteArray? = null
                var queued: Int
                synchronized(playbackLock) {
                    queued = playbackQueuedBytes
                    val startBytes = playbackStartFrames * playbackFrameBytes
                    val lowBytes = playbackLowFrames * playbackFrameBytes
                    if (playbackForceDrain || queued >= startBytes || (queued > 0 && queued >= lowBytes)) {
                        chunk = if (playbackQueue.isNotEmpty()) playbackQueue.removeFirst() else null
                        if (chunk != null) {
                            playbackQueuedBytes -= chunk!!.size
                        }
                    }
                }
                if (chunk == null) {
                    delay(10L)
                } else {
                    try {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                        var offset = 0
                        while (offset < chunk!!.size) {
                            val wrote = track.write(chunk!!, offset, chunk!!.size - offset)
                            if (wrote <= 0) break
                            offset += wrote
                        }
                    } catch (t: Throwable) {
                        Timber.e("AudioTrack write error: %s", t.message ?: "unknown")
                        break
                    }
                }
                if (playbackDraining) {
                    val now = System.currentTimeMillis()
                    val drained = synchronized(playbackLock) { playbackQueuedBytes == 0 }
                    if (drained && now - lastAudioRxMs > 200L) {
                        playbackDraining = false
                        playbackAllowed = false
                        playbackForceDrain = false
                        if (_conversationState.value == ConversationState.WAITING_RESPONSE) {
                            _conversationState.value = ConversationState.READY
                            _waitingResponseSeconds.value = 0L
                            updateKeepalive()
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastCheckMs >= 1000L) {
                    val underrun = try { track.underrunCount } catch (_: Throwable) { lastUnderrun }
                    if (underrun > lastUnderrun) {
                        playbackStartFrames = (playbackStartFrames + 5).coerceAtMost(playbackMaxStartFrames)
                        playbackLowFrames = (playbackLowFrames + 5).coerceAtMost(playbackMaxLowFrames)
                        lastStableMs = now
                    } else if (now - lastStableMs >= 5000L) {
                        playbackStartFrames = (playbackStartFrames - 1).coerceAtLeast(playbackMinStartFrames)
                        playbackLowFrames = (playbackLowFrames - 1).coerceAtLeast(playbackMinLowFrames)
                        lastStableMs = now
                    }
                    lastUnderrun = underrun
                    lastCheckMs = now
                }
            }
        }
    }

    private fun releaseAudioTrack() {
        playbackJob?.cancel()
        playbackJob = null
        synchronized(playbackLock) {
            playbackQueue.clear()
            playbackQueuedBytes = 0
        }
        try {
            audioTrack?.stop()
        } catch (_: Throwable) { }
        try {
            audioTrack?.release()
        } catch (_: Throwable) { }
        audioTrack = null
    }

}
