package no.nordicsemi.android.blinky.ble

import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var channel: ManagedChannel? = null
    private var requestObserver: StreamObserver<TrainiProto.AudioChunk>? = null
    private var reconnectDelayMs = 1000L
    private var heartbeatJob: Job? = null
    private var heartbeatSeq: Long = 1
    private var senderJob: Job? = null
    @Volatile private var reconnecting: Boolean = false

    private var host: String = "44.223.77.196"
    private var port: Int = 8080
    private var heartbeatIntervalMs: Long = 60_000L

    private val formatBuilder = TrainiProto.AudioFormat.newBuilder()
        .setSampleRate(16000)
        .setChannels(1)
        .setBitDepth(16)
        .setEncoding("PCM")

    private data class PendingAudio(val pcm: ByteArray, val seq: Long)

    private val sendQueue = Channel<PendingAudio>(
        capacity = 2000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun configure(sampleRate: Int, channels: Int, bitDepth: Int) {
        formatBuilder.setSampleRate(sampleRate)
        formatBuilder.setChannels(channels)
        formatBuilder.setBitDepth(bitDepth)
    }

    fun start(host: String = this.host, port: Int = this.port) {
        this.host = host
        this.port = port
        if (channel != null) return
        startSender()
        connect()
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
        if (sendQueue.trySend(PendingAudio(pcm, seq)).isFailure) {
            GrpcStatusStore.incrementDropped()
        }
    }

    fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            requestObserver?.onCompleted()
        } catch (t: Throwable) {
            Timber.w("gRPC onCompleted error: %s", t.message ?: "unknown")
        }
        requestObserver = null
        channel?.shutdownNow()
        channel = null
        GrpcStatusStore.setState("DISCONNECTED")
    }

    private fun connect() {
        GrpcStatusStore.setState("CONNECTING")
        channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()
        val stub = ConversationServiceGrpc.newStub(channel)
        requestObserver = stub.streamConversation(object : StreamObserver<TrainiProto.ConversationEvent> {
            override fun onNext(value: TrainiProto.ConversationEvent) {
                GrpcStatusStore.setState("CONNECTED")
                when (value.eventCase) {
                    TrainiProto.ConversationEvent.EventCase.AUDIO_OUTPUT -> {
                        val bytes = value.audioOutput.audioData.size()
                        if (bytes > 0) {
                            val msg = "audio bytes=$bytes seq=${value.audioOutput.sequenceNumber}"
                            GrpcStatusStore.setLastMessage(msg)
                            Timber.i("gRPC RX %s", msg)
                        }
                    }
                    TrainiProto.ConversationEvent.EventCase.AUDIO_COMPLETE -> {
                        GrpcStatusStore.setLastMessage("audio complete")
                        Timber.i("gRPC RX audio complete")
                    }
                    TrainiProto.ConversationEvent.EventCase.ERROR -> {
                        val msg = "error=${value.error.code} ${value.error.message}"
                        GrpcStatusStore.setLastMessage(msg)
                        Timber.e("gRPC %s", msg)
                    }
                    else -> Unit
                }
            }

            override fun onError(t: Throwable) {
                GrpcStatusStore.setState("DISCONNECTED")
                val msg = t.message ?: "unknown"
                GrpcStatusStore.setLastMessage("error=$msg")
                Timber.e("gRPC stream error: %s", msg)
                requestObserver = null
                scheduleReconnect()
            }

            override fun onCompleted() {
                GrpcStatusStore.setState("DISCONNECTED")
                GrpcStatusStore.setLastMessage("completed")
                Timber.i("gRPC stream completed")
                requestObserver = null
                scheduleReconnect()
            }
        })

        // Send ping chunk to validate link.
        sendAudio(byteArrayOf(0x00, 0x00), 0)
        startHeartbeat()
    }

    private fun startSender() {
        if (senderJob?.isActive == true) return
        senderJob = scope.launch {
            for (item in sendQueue) {
                val observer = requestObserver
                if (observer == null) {
                    continue
                }
                val nowMs = System.currentTimeMillis()
                val ts = TrainiProto.Timestamp.newBuilder()
                    .setSeconds(nowMs / 1000L)
                    .setNanos(((nowMs % 1000L) * 1_000_000L).toInt())
                    .build()
                val chunk = TrainiProto.AudioChunk.newBuilder()
                    .setAudioData(ByteString.copyFrom(item.pcm))
                    .setFormat(formatBuilder.build())
                    .setSequenceNumber(item.seq)
                    .setTimestamp(ts)
                    .build()
                try {
                    observer.onNext(chunk)
                } catch (t: Throwable) {
                    Timber.e("gRPC send error: %s", t.message ?: "unknown")
                    requestObserver = null
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnecting) return
        reconnecting = true
        scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(10_000L)
            close()
            connect()
            reconnecting = false
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatIntervalMs)
                val seq = heartbeatSeq++
                sendAudio(byteArrayOf(0x00, 0x00), seq)
                Timber.d("gRPC heartbeat seq=%d", seq)
            }
        }
    }
}
