package no.nordicsemi.android.blinky.ble

import android.util.Base64
import com.google.protobuf.ByteString
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.StreamObserver
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.android.blinky.spec.GrpcStatusStore
import timber.log.Timber
import traini.ConversationServiceGrpc
import traini.TrainiProto

object GrpcAudioClient {
    private const val TAG = "GrpcAudioClient"
    private val USER_ID_KEY: Metadata.Key<String> =
        Metadata.Key.of("user-id", Metadata.ASCII_STRING_MARSHALLER)
    private val SESSION_ID_KEY: Metadata.Key<String> =
        Metadata.Key.of("session-id", Metadata.ASCII_STRING_MARSHALLER)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var channel: ManagedChannel? = null
    private var requestObserver: StreamObserver<TrainiProto.AudioChunk>? = null
    private var reconnectDelayMs = 1000L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Long = 1
    private var silenceJob: Job? = null
    private var silenceSeq: Long = 2_000_000L
    private var senderJob: Job? = null
    @Volatile private var reconnecting: Boolean = false
    @Volatile private var connectivityLogging: Boolean = false
    @Volatile private var closing: Boolean = false
    @Volatile private var closingStream: Boolean = false
    private var connectivityListener: ((ConnectivityState) -> Unit)? = null

    private var host: String = "3.94.247.3"
    private var port: Int = 50051
    private var userId: String = "demo_user"
    private var heartbeatIntervalMs: Long = 60_000L
    private var sentCounter: Long = 0

    private var sampleRate: Int = 24000
    private var channels: Int = 1
    private var bitDepth: Int = 16
    private var testToneSent: Boolean = false
    private var heartbeatEnabled: Boolean = false
    private var silenceEnabled: Boolean = false
    private var testToneEnabled: Boolean = false
    private var streamReady: Boolean = false
    private var pendingTestTone: Boolean = false
    private var lastServerEventMs: Long = 0L
    private var lastClientSendMs: Long = 0L
    private var serverEventTimeoutMs: Long = 30_000L
    private var watchdogJob: Job? = null
    private var watchdogEnabled: Boolean = false
    private var probeEnabled: Boolean = false
    private var probeIntervalMs: Long = 3000L
    private var probeJob: Job? = null
    private var probeSeq: Long = 10_000_000L
    private var encodeAudioAsBase64: Boolean = true
    private var formatLocked: Boolean = false
    private var allowSendBeforeReady: Boolean = true
    private var currentSessionId: String? = null
    private var audioOutputListener: ((ByteArray) -> Unit)? = null
    private var audioCompleteListener: (() -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var streamErrorListener: ((String) -> Unit)? = null
    private var audioStartListener: (() -> Unit)? = null
    private var sessionStartListener: (() -> Unit)? = null
    private var decodeAudioOutputBase64: Boolean = true
    private var autoDetectBase64Output: Boolean = true
    private var sendPaused: Boolean = false

    private val formatBuilder = TrainiProto.AudioFormat.newBuilder()
        .setSampleRate(sampleRate)
        .setChannels(channels)
        .setBitDepth(bitDepth)
        .setEncoding("pcm16")

    private data class PendingAudio(val pcm: ByteArray, val seq: Long)

    private val sendQueue = Channel<PendingAudio>(
        capacity = 2000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun configure(sampleRate: Int, channels: Int, bitDepth: Int) {
        if (formatLocked) return
        this.sampleRate = sampleRate
        this.channels = channels
        this.bitDepth = bitDepth
        formatBuilder.setSampleRate(sampleRate)
        formatBuilder.setChannels(channels)
        formatBuilder.setBitDepth(bitDepth)
    }

    fun setHeartbeatEnabled(enabled: Boolean) {
        heartbeatEnabled = enabled
    }

    fun setSilenceEnabled(enabled: Boolean) {
        silenceEnabled = enabled
    }

    fun setTestToneEnabled(enabled: Boolean) {
        testToneEnabled = enabled
        pendingTestTone = enabled && !testToneSent
    }

    fun setEncodeAudioAsBase64(enabled: Boolean) {
        encodeAudioAsBase64 = enabled
    }

    fun setFormatLocked(enabled: Boolean) {
        formatLocked = enabled
    }

    fun setAllowSendBeforeReady(enabled: Boolean) {
        allowSendBeforeReady = enabled
    }

    fun setDecodeAudioOutputBase64(enabled: Boolean) {
        decodeAudioOutputBase64 = enabled
    }

    fun setAutoDetectBase64Output(enabled: Boolean) {
        autoDetectBase64Output = enabled
    }

    fun setAudioOutputListener(listener: ((ByteArray) -> Unit)?) {
        audioOutputListener = listener
    }

    fun setAudioCompleteListener(listener: (() -> Unit)?) {
        audioCompleteListener = listener
    }

    fun setAudioStartListener(listener: (() -> Unit)?) {
        audioStartListener = listener
    }

    fun setSessionStartListener(listener: (() -> Unit)?) {
        sessionStartListener = listener
    }

    fun setErrorListener(listener: ((String) -> Unit)?) {
        errorListener = listener
    }

    fun setStreamErrorListener(listener: ((String) -> Unit)?) {
        streamErrorListener = listener
    }

    fun setUserId(userId: String) {
        val trimmed = userId.trim()
        if (trimmed.isNotEmpty()) {
            this.userId = trimmed
        }
    }

    fun setSendPaused(paused: Boolean) {
        sendPaused = paused
        if (paused) {
            drainSendQueue()
        }
    }

    fun startSession(sessionId: String) {
        currentSessionId = sessionId
        closeStream()
        start(host, port)
    }

    fun endConversation(sessionId: String) {
        val ch = channel ?: return
        val metadata = Metadata().apply {
            put(USER_ID_KEY, userId)
            put(SESSION_ID_KEY, sessionId)
        }
        val stub = ConversationServiceGrpc.newBlockingStub(ch)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
        scope.launch {
            try {
                val req = TrainiProto.EndConversationRequest.newBuilder()
                    .setSessionId(sessionId)
                    .build()
                stub.endConversation(req)
            } catch (t: Throwable) {
                Timber.e("gRPC EndConversation error: %s", t.message ?: "unknown")
            }
        }
    }

    fun setServerEventTimeoutMs(timeoutMs: Long) {
        serverEventTimeoutMs = timeoutMs.coerceAtLeast(5_000L)
    }

    fun setWatchdogEnabled(enabled: Boolean) {
        watchdogEnabled = enabled
    }

    fun setProbeEnabled(enabled: Boolean) {
        probeEnabled = enabled
    }

    fun setProbeIntervalMs(intervalMs: Long) {
        probeIntervalMs = intervalMs.coerceAtLeast(1000L)
    }

    fun start(host: String = this.host, port: Int = this.port) {
        this.host = host
        this.port = port
        startSender()
        startStream()
    }

    fun setHeartbeatIntervalMs(intervalMs: Long) {
        heartbeatIntervalMs = intervalMs.coerceAtLeast(10_000L)
        if (heartbeatJob?.isActive == true) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            startHeartbeat()
        }
    }

    fun sendAudio(pcm: ByteArray, seq: Long) {
        if (!streamReady && !allowSendBeforeReady) {
            GrpcStatusStore.incrementDropped()
            Timber.tag(TAG).w("Drop audio (not ready) seq=%d bytes=%d", seq, pcm.size)
            return
        }
        if (sendQueue.trySend(PendingAudio(pcm, seq)).isFailure) {
            GrpcStatusStore.incrementDropped()
            Timber.tag(TAG).w("Drop audio (queue full) seq=%d bytes=%d", seq, pcm.size)
        }
    }

    fun close() {
        closing = true
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        silenceJob?.cancel()
        silenceJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        probeJob?.cancel()
        probeJob = null
        try {
            requestObserver?.onCompleted()
        } catch (t: Throwable) {
            Timber.tag(TAG).w("gRPC onCompleted error: %s", t.message ?: "unknown")
        }
        requestObserver = null
        GrpcStatusStore.setState("DISCONNECTED")
        streamReady = false
        pendingTestTone = testToneEnabled && !testToneSent
        watchdogEnabled = false
    }

    private fun startStream() {
        ensureChannel()
        closing = false
        GrpcStatusStore.setState("CONNECTING")
        GrpcStatusStore.setLastMessage("connecting $host:$port")
        val metadata = Metadata().apply {
            put(USER_ID_KEY, userId)
            currentSessionId?.let {
                put(SESSION_ID_KEY, it)
            }
        }
        val ch = channel ?: return
        val stub = ConversationServiceGrpc.newStub(ch)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
        requestObserver = stub.streamConversation(object : StreamObserver<TrainiProto.ConversationEvent> {
            override fun onNext(value: TrainiProto.ConversationEvent) {
                try {
                    GrpcStatusStore.setState("CONNECTED")
                    lastServerEventMs = System.currentTimeMillis()
                    if (!streamReady) {
                        streamReady = true
                        sessionStartListener?.invoke()
                        if (pendingTestTone && !testToneSent) {
                            scope.launch {
                                delay(300)
                                sendTestToneOnce()
                            }
                        }
                    }
                    when (value.eventCase) {
                        TrainiProto.ConversationEvent.EventCase.AUDIO_OUTPUT -> {
                            val bytes = value.audioOutput.audioData.size()
                            if (bytes > 0) {
                                val msg = "audio bytes=$bytes seq=${value.audioOutput.sequenceNumber}"
                                GrpcStatusStore.setLastMessage(msg)
                Timber.tag(TAG).i("gRPC RX %s", msg)
                audioStartListener?.invoke()
                val pcm = decodeAudioData(value.audioOutput.audioData)
                audioOutputListener?.invoke(pcm)
                            }
                        }
                        TrainiProto.ConversationEvent.EventCase.AUDIO_COMPLETE -> {
                            GrpcStatusStore.setLastMessage("audio complete")
                        Timber.tag(TAG).i("gRPC RX audio complete")
                        audioCompleteListener?.invoke()
                        }
                        TrainiProto.ConversationEvent.EventCase.ERROR -> {
                            val msg = "error=${value.error.code} ${value.error.message}"
                            GrpcStatusStore.setLastMessage(msg)
                        Timber.tag(TAG).e("gRPC %s", msg)
                        errorListener?.invoke(msg)
                        }
                        else -> Unit
                    }
                } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "gRPC onNext crash")
                throw t
            }
            }

            override fun onError(t: Throwable) {
                if (closingStream) {
                    closingStream = false
                    requestObserver = null
                    streamReady = false
                    return
                }
                GrpcStatusStore.setState("DISCONNECTED")
                val msg = t.message ?: "unknown"
                GrpcStatusStore.setLastMessage("error=$msg")
                Timber.tag(TAG).e("gRPC stream error: %s", msg)
                streamErrorListener?.invoke(msg)
                requestObserver = null
                streamReady = false
                pendingTestTone = testToneEnabled && !testToneSent
                lastServerEventMs = 0L
                lastClientSendMs = 0L
                scheduleReconnect()
            }

            override fun onCompleted() {
                if (closingStream) {
                    closingStream = false
                    requestObserver = null
                    streamReady = false
                    return
                }
                GrpcStatusStore.setState("DISCONNECTED")
                GrpcStatusStore.setLastMessage("completed")
                Timber.tag(TAG).i("gRPC stream completed")
                streamErrorListener?.invoke("completed")
                requestObserver = null
                streamReady = false
                pendingTestTone = testToneEnabled && !testToneSent
                lastServerEventMs = 0L
                lastClientSendMs = 0L
                scheduleReconnect()
            }
        })

        // Optional traffic generators are disabled by default to avoid
        // pushing audio before the upstream websocket is ready.
        if (heartbeatEnabled) {
            startHeartbeat()
        }
        if (silenceEnabled) {
            startSilencePackets()
        }
        pendingTestTone = testToneEnabled && !testToneSent
        startWatchdog()
        if (probeEnabled) {
            startProbe()
        }
    }

    private fun ensureChannel() {
        val ch = channel
        if (ch != null && (ch.isShutdown || ch.isTerminated)) {
            channel = null
        }
        if (channel != null) return
        Timber.tag(TAG).i("gRPC connecting to %s:%d", host, port)
        channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()
        startConnectivityLogging()
    }

    private fun closeStream() {
        try {
            closingStream = true
            requestObserver?.onCompleted()
        } catch (t: Throwable) {
            Timber.tag(TAG).w("gRPC onCompleted error: %s", t.message ?: "unknown")
        }
        requestObserver = null
        streamReady = false
        pendingTestTone = testToneEnabled && !testToneSent
    }

    private fun startSender() {
        if (senderJob?.isActive == true) return
        senderJob = scope.launch {
            for (item in sendQueue) {
                if (sendPaused) {
                    GrpcStatusStore.incrementDropped()
                    continue
                }
                val observer = requestObserver
                if (observer == null || (!streamReady && !allowSendBeforeReady)) {
                    GrpcStatusStore.incrementDropped()
                    Timber.tag(TAG).w("Drop audio (observer) seq=%d bytes=%d", item.seq, item.pcm.size)
                    continue
                }
                val nowMs = System.currentTimeMillis()
                val ts = TrainiProto.Timestamp.newBuilder()
                    .setSeconds(nowMs / 1000L)
                    .setNanos(((nowMs % 1000L) * 1_000_000L).toInt())
                    .build()
                val chunk = TrainiProto.AudioChunk.newBuilder()
                    .setAudioData(encodeAudioData(item.pcm))
                    .setFormat(formatBuilder.build())
                    .setSequenceNumber(item.seq)
                    .setTimestamp(ts)
                    .build()
                try {
                    observer.onNext(chunk)
                    lastClientSendMs = nowMs
                    sentCounter++
                    if (sentCounter % 50L == 0L) {
                        Timber.tag(TAG).i("gRPC TX packets=%d lastSeq=%d bytes=%d", sentCounter, item.seq, item.pcm.size)
                    }
                } catch (t: Throwable) {
                    Timber.tag(TAG).e("gRPC send error: %s", t.message ?: "unknown")
                    requestObserver = null
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (closing) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(10_000L)
            startStream()
            reconnectDelayMs = 1000L
            reconnectJob = null
        }
    }

    private fun drainSendQueue() {
        while (true) {
            val res = sendQueue.tryReceive()
            if (!res.isSuccess) break
        }
    }

    private fun startConnectivityLogging() {
        val ch = channel ?: return
        if (connectivityLogging) return
        connectivityLogging = true
        fun logState(state: ConnectivityState) {
            Timber.tag(TAG).i("gRPC channel state -> %s", state)
            GrpcStatusStore.setLastMessage("channel state=$state")
            connectivityListener?.invoke(state)
        }
        fun watch() {
            val state = ch.getState(false)
            logState(state)
            ch.notifyWhenStateChanged(state) {
                watch()
            }
        }
        watch()
    }

    fun setConnectivityListener(listener: ((ConnectivityState) -> Unit)?) {
        connectivityListener = listener
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatIntervalMs)
                val seq = heartbeatSeq++
                sendAudio(byteArrayOf(0x00, 0x00), seq)
                Timber.tag(TAG).d("gRPC heartbeat seq=%d", seq)
            }
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (true) {
                delay(1000L)
                if (!watchdogEnabled) continue
                if (!streamReady) continue
                if (lastClientSendMs == 0L || lastServerEventMs == 0L) continue
                val idleMs = System.currentTimeMillis() - lastServerEventMs
                if (idleMs > serverEventTimeoutMs) {
                    streamReady = false
                    GrpcStatusStore.setLastMessage("server event timeout")
                    Timber.tag(TAG).w("gRPC server event timeout: %dms, pausing audio", idleMs)
                    scheduleReconnect()
                }
            }
        }
    }

    private fun startProbe() {
        if (probeJob?.isActive == true) return
        probeJob = scope.launch {
            while (true) {
                delay(probeIntervalMs)
                val observer = requestObserver ?: continue
                val nowMs = System.currentTimeMillis()
                val ts = TrainiProto.Timestamp.newBuilder()
                    .setSeconds(nowMs / 1000L)
                    .setNanos(((nowMs % 1000L) * 1_000_000L).toInt())
                    .build()
                val frameSamples = (sampleRate / 50).coerceAtLeast(1) // 20ms
                val bytesPerSample = (bitDepth / 8).coerceAtLeast(1)
                val frameBytes = frameSamples * channels * bytesPerSample
                val pcm = ByteArray(frameBytes)
                val chunk = TrainiProto.AudioChunk.newBuilder()
                    .setAudioData(encodeAudioData(pcm))
                    .setFormat(formatBuilder.build())
                    .setSequenceNumber(probeSeq++)
                    .setTimestamp(ts)
                    .build()
                try {
                    observer.onNext(chunk)
                    Timber.tag(TAG).i("gRPC probe sent seq=%d bytes=%d", probeSeq - 1, pcm.size)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e("gRPC probe error: %s", t.message ?: "unknown")
                }
            }
        }
    }

    private fun encodeAudioData(pcm: ByteArray): ByteString {
        if (!encodeAudioAsBase64) {
            return ByteString.copyFrom(pcm)
        }
        val base64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        return ByteString.copyFromUtf8(base64)
    }

    private fun decodeAudioData(data: ByteString): ByteArray {
        val raw = data.toByteArray()
        if (!decodeAudioOutputBase64) return raw
        val text = try { data.toStringUtf8() } catch (_: Throwable) { return raw }
        if (autoDetectBase64Output && !isProbablyBase64(text)) {
            return raw
        }
        return try {
            Base64.decode(text, Base64.DEFAULT)
        } catch (_: Throwable) {
            raw
        }
    }

    private fun isProbablyBase64(s: String): Boolean {
        if (s.length < 16) return false
        if (s.length % 4 != 0) return false
        for (ch in s) {
            val ok = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '+' || ch == '/' || ch == '=' || ch == '\n' || ch == '\r'
            if (!ok) return false
        }
        return true
    }

    private fun startSilencePackets() {
        if (silenceJob?.isActive == true) return
        val frameSamples = (sampleRate / 50).coerceAtLeast(1) // 20ms frames
        val bytesPerSample = (bitDepth / 8).coerceAtLeast(1)
        val frameBytes = frameSamples * channels * bytesPerSample
        val silent = ByteArray(frameBytes)
        silenceJob = scope.launch {
            while (true) {
                delay(20L)
                val seq = silenceSeq++
                sendAudio(silent, seq)
                Timber.tag(TAG).i("gRPC TX silence seq=%d bytes=%d", seq, silent.size)
            }
        }
    }

    private fun sendTestToneOnce(durationMs: Long = 1000L, freqHz: Double = 440.0) {
        if (testToneSent) return
        testToneSent = true
        if (channels != 1 || bitDepth != 16) {
            Timber.tag(TAG).w("Test tone expects 16-bit mono; current ch=%d depth=%d", channels, bitDepth)
        }
        val frameSamples = (sampleRate / 50).coerceAtLeast(1) // 20ms frames
        val totalSamples = (sampleRate * durationMs / 1000L).toInt().coerceAtLeast(frameSamples)
        val amplitude = (Short.MAX_VALUE * 0.25).toInt()
        var phase = 0.0
        val phaseStep = 2.0 * Math.PI * freqHz / sampleRate
        var seq = 1_000_000L
        var sentSamples = 0
        while (sentSamples < totalSamples) {
            val samplesThisFrame = minOf(frameSamples, totalSamples - sentSamples)
            val pcm = ByteArray(samplesThisFrame * 2)
            var outIdx = 0
            repeat(samplesThisFrame) {
                val v = (kotlin.math.sin(phase) * amplitude).toInt().toShort()
                pcm[outIdx++] = (v.toInt() and 0xFF).toByte()
                pcm[outIdx++] = ((v.toInt() shr 8) and 0xFF).toByte()
                phase += phaseStep
                if (phase > 2.0 * Math.PI) {
                    phase -= 2.0 * Math.PI
                }
            }
            sendAudio(pcm, seq++)
            sentSamples += samplesThisFrame
        }
        val msg = "test tone sent ${durationMs}ms @ ${freqHz}Hz"
        GrpcStatusStore.setLastMessage(msg)
        Timber.tag(TAG).i("gRPC %s", msg)
    }
}
