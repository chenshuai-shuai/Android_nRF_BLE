package no.nordicsemi.android.blinky.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import android.os.ParcelFileDescriptor
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

    private var audioCurrSeq = -1
    private var audioCurrMask = 0
    private var audioCurrFragCnt = 0
    private var audioFragBuf: Array<ByteArray?> = emptyArray()
    private var audioLastCompleteSeq = -1
    private val frameSamples = 160

    private var wavWriter: WavWriter? = null
    private val sampleRateHz = 16000
    private val channels = 1
    private val bitsPerSample = 16
    private val autoRecord = true
    private var pendingUri: android.net.Uri? = null
    private var recordFirstMs: Long = 0
    private var recordLastMs: Long = 0
    private var recordFramesWritten: Long = 0

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
        return Log.VERBOSE
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
        if (_recording.value) return
        startRecordingInternal()
    }

    override suspend fun stopRecording() {
        if (!_recording.value) return
        val writer = wavWriter
        wavWriter = null
        writer?.closeWithSampleRate(sampleRateHz)
        pendingUri?.let { finalizeDownloadUri(context, it) }
        pendingUri = null
        _recording.value = false
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
                val frameBytes = audioFragBuf.filterNotNull().fold(ByteArray(0)) { acc, b -> acc + b }
                if (frameBytes.isNotEmpty()) {
                    if (autoRecord && !_recording.value) {
                        startRecordingInternal()
                    }
                    val writer = wavWriter
                    if (writer != null) {
                        val now = SystemClock.elapsedRealtime()
                        if (recordFirstMs == 0L) {
                            recordFirstMs = now
                        }
                        writer.write(frameBytes)
                        recordFramesWritten += 1
                        recordLastMs = now
                        audioLastCompleteSeq = seq
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

    private fun startRecordingInternal() {
        if (_recording.value) return
        val name = "nrf_audio_${System.currentTimeMillis()}.wav"
        val uri = createDownloadWavUri(context, name)
        if (uri == null) {
            _lastSavedPath.value = "ERROR: create download uri failed"
            return
        }
        pendingUri = uri
        wavWriter = WavWriter(context, uri, sampleRateHz, channels, bitsPerSample)
        _lastSavedPath.value = uri.toString()
        _recording.value = true
        recordFirstMs = 0
        recordLastMs = 0
        recordFramesWritten = 0
    }

    private fun estimateRecordSampleRate(): Int {
        if (recordFirstMs == 0L || recordLastMs <= recordFirstMs || recordFramesWritten <= 0) {
            return sampleRateHz
        }
        val durationMs = recordLastMs - recordFirstMs
        if (durationMs < 200) {
            return sampleRateHz
        }
        val totalSamples = recordFramesWritten.toDouble() * frameSamples.toDouble()
        val rate = (totalSamples * 1000.0 / durationMs.toDouble()).toInt()
        return rate.coerceIn(8000, 48000)
    }

    private class WavWriter(
        context: Context,
        private val uri: android.net.Uri,
        private val defaultSampleRate: Int,
        private val channels: Int,
        private val bitsPerSample: Int,
    ) {
        private val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("openFileDescriptor failed")
        private val out = FileOutputStream(pfd.fileDescriptor)
        private val channel: FileChannel = out.channel
        private var dataSize: Long = 0
        private var firstWriteMs: Long = 0
        private var lastWriteMs: Long = 0

        init {
            channel.truncate(0)
            writeHeader(0, defaultSampleRate)
        }

        fun write(data: ByteArray) {
            if (data.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            if (firstWriteMs == 0L) {
                firstWriteMs = now
            }
            lastWriteMs = now
            channel.position(44 + dataSize)
            channel.write(ByteBuffer.wrap(data))
            dataSize += data.size
        }

        fun closeWithSampleRate(sampleRate: Int) {
            val rate = if (sampleRate > 0) sampleRate else defaultSampleRate
            writeHeader(dataSize, rate)
            channel.force(true)
            channel.close()
            out.close()
            pfd.close()
        }

        private fun estimateSampleRate(): Int {
            if (firstWriteMs == 0L || lastWriteMs <= firstWriteMs) {
                return defaultSampleRate
            }
            val durationMs = lastWriteMs - firstWriteMs
            if (durationMs < 200) {
                return defaultSampleRate
            }
            val bytesPerSample = (channels * bitsPerSample) / 8
            if (bytesPerSample <= 0) {
                return defaultSampleRate
            }
            val totalSamples = dataSize.toDouble() / bytesPerSample.toDouble()
            val rate = (totalSamples * 1000.0 / durationMs.toDouble()).toInt()
            return rate.coerceIn(8000, 48000)
        }

        private fun writeHeader(dataLen: Long, sampleRate: Int) {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val totalDataLen = dataLen + 36

            channel.position(0)
            channel.write(ByteBuffer.wrap("RIFF".toByteArray()))
            channel.write(ByteBuffer.wrap(intToLittleEndian(totalDataLen.toInt())))
            channel.write(ByteBuffer.wrap("WAVE".toByteArray()))
            channel.write(ByteBuffer.wrap("fmt ".toByteArray()))
            channel.write(ByteBuffer.wrap(intToLittleEndian(16)))
            channel.write(ByteBuffer.wrap(shortToLittleEndian(1)))
            channel.write(ByteBuffer.wrap(shortToLittleEndian(channels.toShort())))
            channel.write(ByteBuffer.wrap(intToLittleEndian(sampleRate)))
            channel.write(ByteBuffer.wrap(intToLittleEndian(byteRate)))
            channel.write(ByteBuffer.wrap(shortToLittleEndian(blockAlign.toShort())))
            channel.write(ByteBuffer.wrap(shortToLittleEndian(bitsPerSample.toShort())))
            channel.write(ByteBuffer.wrap("data".toByteArray()))
            channel.write(ByteBuffer.wrap(intToLittleEndian(dataLen.toInt())))
        }

        private fun intToLittleEndian(value: Int): ByteArray {
            return byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte()
            )
        }

        private fun shortToLittleEndian(value: Short): ByteArray {
            return byteArrayOf(
                (value.toInt() and 0xFF).toByte(),
                ((value.toInt() shr 8) and 0xFF).toByte()
            )
        }
    }

    private fun createDownloadWavUri(context: Context, displayName: String): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/nrf_audio")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return uri
    }

    private fun finalizeDownloadUri(context: Context, uri: android.net.Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }
}
