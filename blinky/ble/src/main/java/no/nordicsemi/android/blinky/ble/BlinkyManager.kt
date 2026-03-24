package no.nordicsemi.android.blinky.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import no.nordicsemi.android.blinky.spec.GpsData
import no.nordicsemi.android.blinky.spec.GpsState
import no.nordicsemi.android.blinky.spec.GrpcStatusStore
import timber.log.Timber
import java.io.ByteArrayOutputStream

class BlinkyManager(
    context: Context,
    device: BluetoothDevice
): Blinky by BlinkyManagerImpl(context, device)

private class BlinkyManagerImpl(
    context: Context,
    private val device: BluetoothDevice,
): BleManager(context), Blinky {
    private data class DownlinkTiming(
        val sid: Int,
        var grpcFirstMs: Long = 0L,
        var grpcCompleteMs: Long = 0L,
        var blePlayStartMs: Long = 0L,
        var blePlayEndMs: Long = 0L,
        var nrfPlayDoneMs: Long = 0L
    )
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dataLogger = DataLogger(context)

    init {
        dataLogger.setOnSaved { msg ->
            appendRxMessage("SAVE $msg")
            // Ensure each saved file has at least one record of each sensor (if available).
            lastPpgLine?.let { dataLogger.appendSnapshot(it) }
            lastImuLine?.let { dataLogger.appendSnapshot(it) }
            lastImuRawLine?.let { dataLogger.appendSnapshot(it) }
            lastGpsLine?.let { dataLogger.appendSnapshot(it) }
        }
    }

    // Re-map: LED -> RX (write without response), Button -> TX (notify)
    private var ledCharacteristic: BluetoothGattCharacteristic? = null
    private var buttonCharacteristic: BluetoothGattCharacteristic? = null
    private var gattRef: BluetoothGatt? = null

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
    private val _realtimeServiceEnabled = MutableStateFlow(false)
    override val realtimeServiceEnabled = _realtimeServiceEnabled.asStateFlow()
    private val _gpsData = MutableStateFlow<GpsData?>(null)
    override val gpsData = _gpsData.asStateFlow()
    private val _gpsState = MutableStateFlow(GpsState.UNAVAILABLE)
    override val gpsState = _gpsState.asStateFlow()

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
    private var downlinkSeq: Int = 1
    private var downlinkStreamJob: Job? = null
    private var downlinkPlayDoneTimeoutJob: Job? = null
    private val downlinkFrameLock = Any()
    private val downlinkFrameQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var downlinkPcmCarry = ByteArray(0)
    @Volatile private var downlinkGrpcEos: Boolean = false
    @Volatile private var downlinkBleStarted: Boolean = false
    @Volatile private var downlinkJitterMode: Boolean = false
    @Volatile private var downlinkStrictBufferedMode: Boolean = false
    private val downlinkReplyLock = Any()
    private val downlinkReplyChunks: ArrayList<ByteArray> = ArrayList()
    private var downlinkReplyBytes: Int = 0
    @Volatile private var downlinkReplyActive: Boolean = false
    private var downlinkAdaptiveStartFrames: Int = 40
    private var downlinkGrpcChunkCount: Int = 0
    private var downlinkAdpcmIndex: Int = 0
    private var downlinkGrpcFirstChunkMs: Long = 0L
    private var downlinkGrpcLastChunkMs: Long = 0L
    private var downlinkGrpcAudioMs: Long = 0L
    private var downlinkGrpcMaxGapMs: Long = 0L
    private var nrfPlaybackPending: Boolean = false
    @Volatile private var nrfMicPaused: Boolean = false
    @Volatile private var nrfBufferFull: Boolean = false
    @Volatile private var nrfReady: Boolean = false
    private val downlinkTestEnabled = false
    private val nrfSpeakerPreferBufferedPlayback = true
    private var downlinkTestJob: Job? = null
    private val playDoneSignal = Channel<Unit>(Channel.CONFLATED)
    private var downlinkTimingSeq: Int = 0
    private var downlinkTiming: DownlinkTiming? = null
    @Volatile private var downlinkAudioSeen: Boolean = false
    private val simulatedHalfDuplexLoopEnabled = false
    private val simulatedUplinkWindowMs = 10_000L
    private var simulatedUplinkAccumMs = 0L
    private var simulatedRound = 0
    private var simulatedClip16k: ByteArray? = null

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
    private var nrfSpeakerVolumePercent = 420
    private val playbackFrameBytes = (sampleRateHz / 50) * channels * (bitsPerSample / 8)
    private var playbackStartFrames = 20  // ~400ms
    private var playbackLowFrames = 10    // ~200ms
    private val playbackMaxFrames = 1200  // ~24s cap
    private val playbackMinStartFrames = 10
    private val playbackMinLowFrames = 5
    private val playbackMaxStartFrames = 40
    private val playbackMaxLowFrames = 20
    private var lastPpgMsgMs: Long = 0L
    private var lastImuMsgMs: Long = 0L
    private var lastImuRawMsgMs: Long = 0L
    private var lastGpsMsgMs: Long = 0L
    private var lastSensorGrpcLogMs: Long = 0L
    @Volatile private var lastPpgLine: String? = null
    @Volatile private var lastImuLine: String? = null
    @Volatile private var lastImuRawLine: String? = null
    @Volatile private var lastGpsLine: String? = null
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private var locationCallback: LocationCallback? = null
    private var gpsCurrentToken: CancellationTokenSource? = null
    private var platformLocationListener: LocationListener? = null

    private var recordBuffer = ByteArrayOutputStream()
    private var recordingActive = false
    private val useSpeexDsp = true
    private val speexDsp = SpeexDspProcessor(
        sampleRate = 16000,
        frameSize = 160,
        profile = SpeexDspProcessor.PROFILE_UPLINK
    )
    private val downlinkSpeexDsp = SpeexDspProcessor(
        sampleRate = 16000,
        frameSize = 160,
        profile = SpeexDspProcessor.PROFILE_DOWNLINK
    )
    private val downlinkResampler = SpeexResampler(inputRate = 24000, outputRate = 16000, channels = 1, quality = 8)
    private var downlinkHpX1 = 0f
    private var downlinkHpY1 = 0f
    private var downlinkLpY1 = 0f
    private var downlinkCompEnv = 0f
    private var uplinkNoiseFloor = 300f
    private var uplinkGainSmooth = 1.0f

    private fun logDownlinkTiming(tag: String) {
        val t = downlinkTiming ?: return
        val grpcMs = if (t.grpcFirstMs > 0L && t.grpcCompleteMs > 0L) t.grpcCompleteMs - t.grpcFirstMs else -1L
        val bleTxMs = if (t.blePlayStartMs > 0L && t.blePlayEndMs > 0L) t.blePlayEndMs - t.blePlayStartMs else -1L
        val totalMs = if (t.grpcFirstMs > 0L && t.nrfPlayDoneMs > 0L) t.nrfPlayDoneMs - t.grpcFirstMs else -1L
        Timber.tag("GrpcAudioClient").i(
            "DL_TIMING[%s] sid=%d grpc_first=%d grpc_done=%d ble_start=%d ble_end=%d play_done=%d grpc_ms=%d ble_tx_ms=%d total_ms=%d",
            tag, t.sid, t.grpcFirstMs, t.grpcCompleteMs, t.blePlayStartMs, t.blePlayEndMs, t.nrfPlayDoneMs,
            grpcMs, bleTxMs, totalMs
        )
    }

    private fun autoResumeRealtimeUplink() {
        if (!_realtimeServiceEnabled.value || sessionId == null || !_conversationSessionReady.value) {
            return
        }
        _conversationState.value = ConversationState.TALKING
        _waitingResponseSeconds.value = 0L
        lastSpeechMs = System.currentTimeMillis()
        GrpcAudioClient.setSendPaused(false)
        updateKeepalive()
        Timber.tag("GrpcAudioClient").i("Realtime service: uplink resumed")
    }

    private companion object {
        private const val UPLINK_MAGIC0 = 0xC3
        private const val UPLINK_MAGIC1 = 0x5C
        private const val UPLINK_HDR_LEN = 14
        private const val UPLINK_VER = 1
        private const val AUDIO_CODEC_PCM16_LE = 1
        private const val AUDIO_CODEC_IMA_ADPCM_8K = 2
        private const val AUDIO_CODEC_HDR_LEN = 8
        private const val NRF_DOWNLINK_USE_PCM16 = false
        private const val NRF_DOWNLINK_PCM_FRAME_SAMPLES = 160
        private const val NRF_DOWNLINK_PCM_FRAME_BYTES = NRF_DOWNLINK_PCM_FRAME_SAMPLES * 2
        private const val NRF_DOWNLINK_START_FRAMES = 12
        private const val NRF_DOWNLINK_START_FRAMES_NORMAL = 48
        private const val NRF_DOWNLINK_START_FRAMES_JITTER = 96
        private const val NRF_DOWNLINK_START_FRAMES_SLOW = 140
        private const val NRF_DOWNLINK_MAX_START_WAIT_MS = 1800L
        private const val NRF_DOWNLINK_RESUME_FRAMES = 12
        private const val NRF_DOWNLINK_TX_INTERVAL_MS = 8L
        private const val APP_DATA_PART_PPG = 3
        private const val APP_DATA_PART_IMU = 4
    }

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
                if (bytes != null && bytes.size >= UPLINK_HDR_LEN && handleUplinkPacket(bytes)) {
                    return
                }
                if (bytes != null && handleRawSensorPacket(bytes)) {
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
        GrpcSensorClient.stop()
        stopGpsUpdates()
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
        recordBuffer.reset()
        recordingActive = false
        _recording.value = false

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
        gattRef = gatt
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
        dataLogger.start()
        // Enable notifications for the TX characteristic (from nRF to App).
        setNotificationCallback(buttonCharacteristic).with(buttonCallback)
        enableNotifications(buttonCharacteristic).enqueue()

        // Some devices (e.g., Redmi) need a delayed MTU request to accept 247.
        scope.launch {
            val targets = intArrayOf(247, 247, 247)
            val delays = longArrayOf(1200L, 2000L, 3000L)
            for (i in targets.indices) {
                delay(delays[i])
                requestMtu(targets[i])
                    .with { _, mtu -> Timber.tag("BLE").i("MTU updated: %d (req=%d)", mtu, targets[i]) }
                    .enqueue()
            }
        }
        // Prefer 2M PHY to maximize throughput (Android O+).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            scope.launch {
                delay(1500L)
                try {
                    gattRef?.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                    Timber.tag("BLE").i("PHY setPreferredPhy(2M) requested")
                } catch (t: Throwable) {
                    Timber.tag("BLE").w("PHY request failed: %s", t.message ?: "unknown")
                }
            }
        }
        requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH).enqueue()
        // Align gRPC audio format with nRF mic settings (16kHz, mono, 16-bit).
        GrpcAudioClient.configure(sampleRateHz, channels, bitsPerSample)
        // Use one shared identity across audio and sensor gRPC streams.
        val grpcDeviceId = "CollarOne"
        GrpcAudioClient.setUserId(grpcDeviceId)
        Timber.i("Audio downlink mode: NRF_SPEAKER_PLAYBACK")
        GrpcSensorClient.configure(
            host = "traini-inference-nlb-1e17132c99147402.elb.us-east-1.amazonaws.com",
            port = 50051,
            deviceId = grpcDeviceId,
            intervalMs = 1000L
        )
        GrpcSensorClient.start()
        startGpsUpdates()
        simulatedUplinkAccumMs = 0L
        simulatedRound = 0
        simulatedClip16k = generateTestPattern16k()
        if (simulatedHalfDuplexLoopEnabled) {
            Timber.tag("GrpcAudioClient").i("Sim half-duplex enabled: uplink_window=%dms downlink_clip=5000ms", simulatedUplinkWindowMs)
        }

        if (downlinkTestEnabled) {
            startDownlinkTest()
        }
    }

    override fun onServicesInvalidated() {
        ledCharacteristic = null
        buttonCharacteristic = null
        dataLogger.stop()
        downlinkTestJob?.cancel()
        downlinkTestJob = null
        speexDsp.close()
        downlinkSpeexDsp.close()
        downlinkResampler.close()
        GrpcSensorClient.stop()
        stopGpsUpdates()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Throwable) {
            false
        }
    }

    private fun startGpsUpdates() {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            val reqPerms = pkgInfo.requestedPermissions?.joinToString() ?: "(none)"
            Timber.i("GPS perms in manifest for %s: %s", context.packageName, reqPerms)
            appendRxMessage("GPS_DIAG manifest=$reqPerms")
        } catch (t: Throwable) {
            Timber.w("GPS perm inspect failed: %s", t.message ?: "unknown")
            appendRxMessage("GPS_DIAG manifest_read_failed=${t.message ?: "unknown"}")
        }

        val fineGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        Timber.i("GPS runtime perm status fine=%s coarse=%s pkg=%s", fineGranted, coarseGranted, context.packageName)
        appendRxMessage("GPS_DIAG runtime fine=$fineGranted coarse=$coarseGranted pkg=${context.packageName}")

        if (!hasLocationPermission()) {
            _gpsState.value = GpsState.PERMISSION_DENIED
            return
        }
        if (!isLocationEnabled()) {
            _gpsState.value = GpsState.LOCATION_OFF
            return
        }
        if (locationCallback != null) {
            return
        }

        _gpsState.value = GpsState.SEARCHING

        // 1) Try cached fused location immediately.
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        publishGps(loc)
                    }
                }
        } catch (_: Throwable) {
        }

        // 2) Force one-shot high-accuracy fix (helps first fix on some devices).
        try {
            val token = CancellationTokenSource()
            gpsCurrentToken = token
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        publishGps(loc)
                    }
                }
        } catch (_: Throwable) {
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(1500L)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                publishGps(loc)
            }
        }

        locationCallback = cb
        try {
            fusedLocationClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            _gpsState.value = GpsState.PERMISSION_DENIED
            locationCallback = null
        } catch (t: Throwable) {
            Timber.w("GPS start failed: %s", t.message ?: "unknown")
            _gpsState.value = GpsState.UNAVAILABLE
            locationCallback = null
        }

        // 3) Fallback: platform LocationManager updates (GNSS/network direct).
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            val listener = LocationListener { loc ->
                publishGps(loc)
            }
            platformLocationListener = listener
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: Throwable) {
            }
            try {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopGpsUpdates() {
        val cb = locationCallback ?: return
        try {
            fusedLocationClient.removeLocationUpdates(cb)
        } catch (_: Throwable) {
        }
        locationCallback = null
        try {
            gpsCurrentToken?.cancel()
        } catch (_: Throwable) {
        }
        gpsCurrentToken = null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = platformLocationListener
        if (lm != null && listener != null) {
            try {
                lm.removeUpdates(listener)
            } catch (_: Throwable) {
            }
        }
        platformLocationListener = null
    }

    private fun publishGps(loc: Location) {
        val gps = GpsData(
            lat = loc.latitude,
            lon = loc.longitude,
            altM = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            provider = loc.provider,
            timestampMs = loc.time,
        )
        _gpsData.value = gps
        _gpsState.value = GpsState.READY
        GrpcSensorClient.updateGps(gps.lat, gps.lon)
        val gpsLine =
            "${System.currentTimeMillis()},GPS,lat=${gps.lat},lon=${gps.lon},acc=${gps.accuracyM ?: -1f},spd=${gps.speedMps ?: -1f},alt=${gps.altM ?: -1.0}"
        dataLogger.append(gpsLine)
        lastGpsLine = gpsLine

        val now = System.currentTimeMillis()
        if (now - lastGpsMsgMs >= 2000L) {
            appendRxMessage(
                "GPS lat=%.6f lon=%.6f acc=%.1fm".format(
                    gps.lat,
                    gps.lon,
                    gps.accuracyM ?: -1f,
                )
            )
            lastGpsMsgMs = now
        }
        if (now - lastSensorGrpcLogMs >= 5000L) {
            appendRxMessage("SENSOR_GRPC gps forwarded")
            lastSensorGrpcLogMs = now
        }
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

    override fun refreshGps() {
        stopGpsUpdates()
        startGpsUpdates()
    }

    override suspend fun startRecording() {
        recordBuffer.reset()
        recordingActive = true
        _recording.value = true
        _lastSavedPath.value = "RECORDING_STARTED"
    }

    override suspend fun stopRecording() {
        if (!recordingActive) {
            _recording.value = false
            return
        }
        recordingActive = false
        _recording.value = false

        val pcm = recordBuffer.toByteArray()
        recordBuffer.reset()
        if (pcm.isNotEmpty()) {
            val stats = pcmStats(pcm)
            Timber.tag("AudioWav").i(
                "save wav: peak=%d rms=%d bytes=%d",
                stats.first,
                stats.second,
                pcm.size
            )
            val res = AudioWavWriter.writePcm16leToWav(
                context = appContext,
                pcm16le = pcm,
                sampleRate = 16000,
                channels = 1,
                prefix = "nrf_audio_manual"
            )
            if (res != null) {
                _lastSavedPath.value = res.displayPath
                appendRxMessage("SAVE_AUDIO ${res.displayPath} peak=${stats.first} rms=${stats.second}")
            }
        }
    }

    override suspend fun startConversation() {
        if (downlinkTestEnabled) return
        if (_conversationState.value != ConversationState.IDLE) return
        _realtimeServiceEnabled.value = true
        sessionId = "session_${System.currentTimeMillis()}"
        _conversationSessionId.value = sessionId
        _conversationSessionReady.value = false
        if (_conversationState.value == ConversationState.TALKING) {
            pendingTalk = true
        }
        sessionStartMs = System.currentTimeMillis()
        lastSpeechMs = sessionStartMs
        _conversationState.value = ConversationState.CONNECTING
        playbackAllowed = false
        playbackDraining = false
        playbackForceDrain = false
        if (!useNrfSpeaker) {
            ensureAudioTrack()
        }
        GrpcAudioClient.startSession(sessionId!!)
        sendPrimerFrame()
        GrpcAudioClient.setAudioStartListener {
            if (_conversationState.value != ConversationState.IDLE) {
                _conversationState.value = ConversationState.WAITING_RESPONSE
                startWaitingCountdown()
            }
            if (!nrfMicPaused) {
                sendBleControl("APP_MIC_PAUSE")
                nrfMicPaused = true
            }
            GrpcAudioClient.setSendPaused(true)
            if (useNrfSpeaker) {
                downlinkTimingSeq += 1
                downlinkTiming = DownlinkTiming(sid = downlinkTimingSeq)
                downlinkPlayDoneTimeoutJob?.cancel()
                downlinkPlayDoneTimeoutJob = null
                synchronized(downlinkFrameLock) {
                    downlinkFrameQueue.clear()
                    downlinkPcmCarry = ByteArray(0)
                    downlinkGrpcEos = false
                }
                downlinkAudioSeen = false
                nrfBufferFull = false
                nrfReady = false
                nrfPlaybackPending = false
                downlinkBleStarted = false
                downlinkSpeexDsp.reset()
                resetDownlinkSpeechPipeline()
                resetDownlinkReplyBuffer()
                downlinkReplyActive = true
            }
            updateKeepalive()
        }
        GrpcAudioClient.setSessionStartListener {
            _conversationSessionReady.value = true
            sessionDelayJob?.cancel()
            sessionDelayJob = null
            if (_realtimeServiceEnabled.value || pendingTalk) {
                pendingTalk = false
                _conversationState.value = ConversationState.TALKING
                GrpcAudioClient.setSendPaused(false)
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
                val now = SystemClock.elapsedRealtime()
                downlinkAudioSeen = true
                if (downlinkTiming?.grpcFirstMs == 0L) {
                    downlinkTiming?.grpcFirstMs = now
                }
                updateDownlinkIngressStats(now, pcm)
                if (downlinkStrictBufferedMode) {
                    appendDownlinkReplyChunk(pcm)
                } else {
                    val pcm16 = prepareDownlinkPcm16k(pcm)
                    enqueueDownlinkStream(pcm16)
                }
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
                val hasPendingAudio = synchronized(downlinkFrameLock) {
                    downlinkFrameQueue.isNotEmpty() || downlinkPcmCarry.isNotEmpty()
                }
                if (!downlinkAudioSeen && !hasPendingAudio && !downlinkBleStarted) {
                    Timber.tag("GrpcAudioClient").w("gRPC audio complete without audio payload")
                    downlinkReplyActive = false
                    downlinkTiming = null
                    if (nrfMicPaused) {
                        sendBleControl("APP_MIC_RESUME")
                        nrfMicPaused = false
                    }
                    autoResumeRealtimeUplink()
                    return@setAudioCompleteListener
                }
                downlinkTiming?.grpcCompleteMs = SystemClock.elapsedRealtime()
                logDownlinkTiming("grpc_complete")
                downlinkReplyActive = false
                if (downlinkStrictBufferedMode) {
                    prepareBufferedDownlinkPlayback()
                } else {
                    enqueueDownlinkEos()
                }
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
                        autoResumeRealtimeUplink()
                    }
                }
            }
            if (!useNrfSpeaker && nrfMicPaused) {
                sendBleControl("APP_MIC_RESUME")
                nrfMicPaused = false
            }
            if (!useNrfSpeaker) {
                autoResumeRealtimeUplink()
            }
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
        requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH).enqueue()
        startRealtimeService()
    }

    override suspend fun stopTalking() {
        if (downlinkTestEnabled) return
        stopRealtimeService()
    }

    override suspend fun endConversation() {
        if (downlinkTestEnabled) return
        if (_conversationState.value == ConversationState.IDLE) return
        _realtimeServiceEnabled.value = false
        stopMicCapture()
        if (nrfMicPaused) {
            sendBleControl("APP_MIC_RESUME")
            nrfMicPaused = false
        }
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
        downlinkPlayDoneTimeoutJob?.cancel()
        downlinkPlayDoneTimeoutJob = null
        if (downlinkStreamJob?.isActive == true || nrfPlaybackPending) {
            Timber.tag("GrpcAudioClient").w("gRPC error during NRF downlink; keep BLE send in progress")
        } else {
            nrfPlaybackPending = false
            synchronized(downlinkFrameLock) {
                downlinkFrameQueue.clear()
                downlinkPcmCarry = ByteArray(0)
                downlinkGrpcEos = false
            }
            resetDownlinkReplyBuffer()
            downlinkReplyActive = false
            downlinkBleStarted = false
            downlinkStreamJob?.cancel()
            downlinkStreamJob = null
        }
        if (id != null) {
            GrpcAudioClient.endConversation(id)
        }
        releaseAudioTrack()
        stopKeepalive()
    }

    override suspend fun startRealtimeService() {
        if (downlinkTestEnabled) return
        _realtimeServiceEnabled.value = true
        requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH).enqueue()
        if (sessionId == null || _conversationState.value == ConversationState.IDLE) {
            startConversation()
            return
        }
        if ((useNrfSpeaker && nrfPlaybackPending) ||
            (!useNrfSpeaker && (playbackDraining || playbackQueuedBytes > 0))) {
            return
        }
        if (_conversationSessionReady.value) {
            autoResumeRealtimeUplink()
        } else {
            pendingTalk = true
        }
    }

    override suspend fun stopRealtimeService() {
        _realtimeServiceEnabled.value = false
        endConversation()
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
                    val decoded = decodeNrfAudioFrame(frameBytes)
                    if (decoded != null) {
                        maybeTriggerSimulatedDownlink(decoded.first, decoded.second)
                        val processedBase = if (useSpeexDsp && decoded.second == 16000) {
                            speexDsp.processPcm16le(decoded.first)
                        } else {
                            decoded.first
                        }
                        val processed = if (decoded.second == 16000) {
                            enhanceUplinkVoicePcm16le(processedBase)
                        } else {
                            processedBase
                        }
                        handleManualRecord(processed, decoded.second)
                        val payload = when (decoded.second) {
                            sampleRateHz -> processed
                            8000 -> resample8kTo24k(processed)
                            16000 -> resample16kTo24k(processed)
                            else -> resample16kTo24k(processed)
                        }
                        scope.launch {
                            if (_conversationState.value == ConversationState.TALKING) {
                                GrpcAudioClient.sendAudio(payload, sendSeq)
                            }
                        }
                    } else {
                        dropped++
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

    private fun maybeTriggerSimulatedDownlink(pcm: ByteArray, sampleRate: Int) {
        if (!simulatedHalfDuplexLoopEnabled) return
        if (!isReady) return
        if (sampleRate <= 0 || pcm.isEmpty()) return
        val samples = pcm.size / 2
        if (samples <= 0) return
        val frameMs = (samples * 1000L) / sampleRate
        if (frameMs <= 0) return
        simulatedUplinkAccumMs += frameMs
        if (simulatedUplinkAccumMs < simulatedUplinkWindowMs) return
        if (downlinkStreamJob?.isActive == true || nrfPlaybackPending) return

        val clip = simulatedClip16k ?: generateTestPattern16k().also { simulatedClip16k = it }
        simulatedUplinkAccumMs = 0L
        simulatedRound += 1
        Timber.tag("GrpcAudioClient").i(
            "Sim half-duplex round=%d trigger: uplink>=%dms, downlink=%d bytes",
            simulatedRound,
            simulatedUplinkWindowMs,
            clip.size
        )
        enqueueDownlinkStream(clip)
        enqueueDownlinkEos()
    }

    private fun decodeNrfAudioFrame(frame: ByteArray): Pair<ByteArray, Int>? {
        if (frame.isEmpty()) return null
        val codec = frame[0].toInt() and 0xFF
        if (codec == AUDIO_CODEC_PCM16_LE) {
            if (frame.size <= 1) return null
            return Pair(frame.copyOfRange(1, frame.size), nrfSampleRateHz)
        }
        if (codec != AUDIO_CODEC_IMA_ADPCM_8K || frame.size < AUDIO_CODEC_HDR_LEN + 1) {
            return null
        }

        val sampleRateKHz = frame[1].toInt() and 0xFF
        val sampleRate = sampleRateKHz * 1000
        val sampleCount = frame[7].toInt() and 0xFF
        if (sampleCount < 2) return null

        val nibbleCount = sampleCount - 1
        val needBytes = (nibbleCount + 1) / 2
        if (frame.size < AUDIO_CODEC_HDR_LEN + needBytes) {
            return null
        }

        val stepTable = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
        val indexTable = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        var predictor = leI16(frame, 4)
        var index = (frame[6].toInt() and 0xFF).coerceIn(0, 88)
        val out = ByteArray(sampleCount * 2)
        out[0] = (predictor and 0xFF).toByte()
        out[1] = ((predictor shr 8) and 0xFF).toByte()

        var outSample = 1
        var inOff = AUDIO_CODEC_HDR_LEN
        var lowNibble = true
        var packed = 0
        while (outSample < sampleCount) {
            if (lowNibble) {
                packed = frame[inOff].toInt() and 0xFF
                inOff++
            }
            val nibble = if (lowNibble) (packed and 0x0F) else ((packed shr 4) and 0x0F)
            lowNibble = !lowNibble

            val step = stepTable[index]
            var diff = step shr 3
            if ((nibble and 0x04) != 0) diff += step
            if ((nibble and 0x02) != 0) diff += step shr 1
            if ((nibble and 0x01) != 0) diff += step shr 2
            predictor = if ((nibble and 0x08) != 0) predictor - diff else predictor + diff
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + indexTable[nibble]).coerceIn(0, 88)

            val o = outSample * 2
            out[o] = (predictor and 0xFF).toByte()
            out[o + 1] = ((predictor shr 8) and 0xFF).toByte()
            outSample++
        }
        return Pair(out, sampleRate)
    }

    private fun enhanceUplinkVoicePcm16le(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2 || (pcm16.size and 1) != 0) return pcm16

        val out = ByteArray(pcm16.size)
        val samples = pcm16.size / 2
        var peak = 0f
        var sumSq = 0f

        for (i in 0 until samples) {
            val s = leI16(pcm16, i * 2).toFloat()
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            sumSq += s * s
        }

        if (samples == 0) return pcm16

        val rms = kotlin.math.sqrt(sumSq / samples.toFloat())
        val speechThreshold = kotlin.math.max(650f, uplinkNoiseFloor * 2.3f)
        val speechLikely = peak >= 2200f || rms >= speechThreshold

        uplinkNoiseFloor = if (speechLikely) {
            uplinkNoiseFloor * 0.995f + rms * 0.005f
        } else {
            uplinkNoiseFloor * 0.94f + rms * 0.06f
        }.coerceIn(180f, 2200f)

        val targetRms = 9500f
        val rawGain = if (speechLikely && rms > 1f) {
            (targetRms / rms).coerceIn(1.15f, 4.2f)
        } else {
            1.0f
        }

        uplinkGainSmooth = if (rawGain > uplinkGainSmooth) {
            uplinkGainSmooth * 0.80f + rawGain * 0.20f
        } else {
            uplinkGainSmooth * 0.92f + rawGain * 0.08f
        }.coerceIn(1.0f, 4.2f)

        val gain = uplinkGainSmooth
        for (i in 0 until samples) {
            val s = leI16(pcm16, i * 2).toFloat()
            val a = kotlin.math.abs(s)
            var y = if (speechLikely || a >= speechThreshold * 0.70f) {
                s * gain
            } else {
                s * 0.92f
            }

            val ay = kotlin.math.abs(y)
            if (ay > 18000f) {
                val excess = ay - 18000f
                val compressed = 18000f + excess * 0.35f
                y = if (y >= 0f) compressed else -compressed
            }
            y = y.coerceIn(-30000f, 30000f)

            val iv = y.toInt()
            out[i * 2] = (iv and 0xFF).toByte()
            out[i * 2 + 1] = ((iv shr 8) and 0xFF).toByte()
        }

        return out
    }

    private fun encodeNrfDownlinkAdpcmFrame(pcm16: ByteArray): ByteArray? {
        if (pcm16.size < 4 || (pcm16.size and 1) != 0) return null
        val sampleCount = pcm16.size / 2
        if (sampleCount < 2 || sampleCount > 255) return null

        val stepTable = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
        val indexTable = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        var predictor = leI16(pcm16, 0)
        val initialIndex = downlinkAdpcmIndex.coerceIn(0, 88)
        var index = initialIndex
        val nibbleCount = sampleCount - 1
        val dataBytes = (nibbleCount + 1) / 2
        val out = ByteArray(AUDIO_CODEC_HDR_LEN + dataBytes)
        out[0] = AUDIO_CODEC_IMA_ADPCM_8K.toByte()
        out[1] = 16
        out[2] = 1
        out[3] = 4
        out[4] = (predictor and 0xFF).toByte()
        out[5] = ((predictor shr 8) and 0xFF).toByte()
        out[6] = initialIndex.toByte()
        out[7] = sampleCount.toByte()

        var outOff = AUDIO_CODEC_HDR_LEN
        var lowNibble = true
        var packed = 0
        for (sampleIdx in 1 until sampleCount) {
            var diff = leI16(pcm16, sampleIdx * 2) - predictor
            var nibble = 0
            var step = stepTable[index]
            var vpdiff = step shr 3

            if (diff < 0) {
                nibble = 0x08
                diff = -diff
            }
            if (diff >= step) {
                nibble = nibble or 0x04
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                nibble = nibble or 0x02
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                nibble = nibble or 0x01
                vpdiff += step
            }

            predictor = if ((nibble and 0x08) != 0) predictor - vpdiff else predictor + vpdiff
            predictor = predictor.coerceIn(-32768, 32767)
            index = (index + indexTable[nibble and 0x0F]).coerceIn(0, 88)

            if (lowNibble) {
                packed = nibble and 0x0F
                lowNibble = false
            } else {
                packed = packed or ((nibble and 0x0F) shl 4)
                out[outOff++] = packed.toByte()
                packed = 0
                lowNibble = true
            }
        }
        if (!lowNibble) {
            out[outOff++] = packed.toByte()
        }
        downlinkAdpcmIndex = index
        return if (outOff == out.size) out else out.copyOf(outOff)
    }

    private fun handleManualRecord(pcm: ByteArray, sampleRate: Int) {
        if (!recordingActive) return
        if (pcm.isEmpty()) return
        val pcm16 = when (sampleRate) {
            16000 -> pcm
            8000 -> upsample8kTo16k(pcm)
            else -> return
        }
        recordBuffer.write(pcm16)
    }

    private fun upsample8kTo16k(pcm8: ByteArray): ByteArray {
        if (pcm8.isEmpty()) return pcm8
        val inSamples = pcm8.size / 2
        val out = ByteArray(inSamples * 2 * 2)
        var oi = 0
        var i = 0
        while (i + 1 < pcm8.size) {
            val lo = pcm8[i].toInt() and 0xFF
            val hi = pcm8[i + 1].toInt()
            val sample = (hi shl 8) or lo
            out[oi++] = pcm8[i]
            out[oi++] = pcm8[i + 1]
            out[oi++] = pcm8[i]
            out[oi++] = pcm8[i + 1]
            i += 2
        }
        return out
    }

    private fun encodeNrfDownlinkPcm16Frame(pcm16: ByteArray): ByteArray? {
        if (pcm16.isEmpty() || (pcm16.size and 1) != 0) return null
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0 || sampleCount > NRF_DOWNLINK_PCM_FRAME_SAMPLES) return null
        val out = ByteArray(1 + pcm16.size)
        out[0] = AUDIO_CODEC_PCM16_LE.toByte()
        System.arraycopy(pcm16, 0, out, 1, pcm16.size)
        return out
    }

    private fun encodeNrfDownlinkFrame(pcm16: ByteArray): ByteArray? {
        return if (useNrfSpeaker && NRF_DOWNLINK_USE_PCM16) {
            encodeNrfDownlinkPcm16Frame(pcm16)
        } else {
            encodeNrfDownlinkAdpcmFrame(pcm16)
        }
    }

    private fun pcmStats(pcm16: ByteArray): Pair<Int, Int> {
        var peak = 0
        var sum = 0L
        var samples = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            val v = (hi shl 8) or lo
            val s = if (v and 0x8000 != 0) v - 0x10000 else v
            val av = if (s < 0) -s else s
            if (av > peak) peak = av
            sum += (s.toLong() * s.toLong())
            samples++
            i += 2
        }
        val rms = if (samples > 0) (sum / samples).toInt() else 0
        return Pair(peak, rms)
    }

    private fun normalizeDownlinkForAdpcm(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2 || (pcm16.size and 1) != 0) return pcm16
        val (peak, rmsPower) = pcmStats(pcm16)
        if (peak <= 0 || rmsPower <= 0) return pcm16

        val rms = kotlin.math.sqrt(rmsPower.toDouble())
        val targetPeak = 32300.0
        val targetRms = 24500.0
        val maxGain = 10.0
        val gain = minOf(maxGain, targetPeak / peak.toDouble(), targetRms / rms)
        if (gain <= 1.02) return pcm16

        val out = ByteArray(pcm16.size)
        var i = 0
        while (i + 1 < pcm16.size) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            var v = (hi shl 8) or lo
            if ((v and 0x8000) != 0) v -= 0x10000

            var y = v.toDouble() * gain
            val ay = kotlin.math.abs(y)
            if (ay > 22000.0) {
                val sign = if (y >= 0.0) 1.0 else -1.0
                val over = ay - 22000.0
                val compressed = 22000.0 + over / 3.2
                y = sign * compressed
            }

            val az = kotlin.math.abs(y)
            if (az > 31200.0) {
                val over = az - 31200.0
                val compressed = 31200.0 + over * 0.015
                y = if (y >= 0.0) compressed else -compressed
            }

            var yi = y.toInt()
            if (yi > 32767) yi = 32767
            if (yi < -32768) yi = -32768
            out[i] = (yi and 0xFF).toByte()
            out[i + 1] = ((yi shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun deEmphasisPcm16(pcm16: ByteArray, alpha: Float): ByteArray {
        if (pcm16.isEmpty()) return pcm16
        val out = ByteArray(pcm16.size)
        var prev = 0.0f
        var i = 0
        while (i + 1 < pcm16.size) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            var v = (hi shl 8) or lo
            if (v and 0x8000 != 0) v -= 0x10000
            val y = v + alpha * prev
            prev = y
            var yi = y.toInt()
            if (yi > 32767) yi = 32767
            if (yi < -32768) yi = -32768
            out[i] = (yi and 0xFF).toByte()
            out[i + 1] = ((yi shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun u8(bytes: ByteArray, off: Int): Int {
        return bytes[off].toInt() and 0xFF
    }

    private fun le16(bytes: ByteArray, off: Int): Int {
        return u8(bytes, off) or (u8(bytes, off + 1) shl 8)
    }

    private fun le32(bytes: ByteArray, off: Int): Long {
        return (u8(bytes, off).toLong()) or
            (u8(bytes, off + 1).toLong() shl 8) or
            (u8(bytes, off + 2).toLong() shl 16) or
            (u8(bytes, off + 3).toLong() shl 24)
    }

    private fun leI16(bytes: ByteArray, off: Int): Int {
        val v = le16(bytes, off)
        return if ((v and 0x8000) != 0) v - 0x10000 else v
    }

    private fun imuActionName(action: Int): String {
        return when (action) {
            1 -> "still"
            2 -> "light_move"
            3 -> "walk_like"
            4 -> "run_like"
            5 -> "vigorous_move"
            else -> "unknown"
        }
    }

    private fun imuTempCenti(tempLsb: Int): Int {
        // Match nRF: temp_centi = temp_lsb * 10000 / 13248 + 2500
        return (tempLsb * 10000 / 13248) + 2500
    }

    private fun appendRxMessage(msg: String) {
        val updated = _rxMessages.value + msg
        _rxMessages.value = updated.takeLast(40)
    }

    private fun handleUplinkPacket(bytes: ByteArray): Boolean {
        if (u8(bytes, 0) != UPLINK_MAGIC0 || u8(bytes, 1) != UPLINK_MAGIC1) {
            return false
        }
        val ver = u8(bytes, 2)
        if (ver != UPLINK_VER) {
            return true
        }
        val part = u8(bytes, 3)
        val payloadLen = le16(bytes, 4)
        if (payloadLen < 0 || (UPLINK_HDR_LEN + payloadLen) > bytes.size) {
            return true
        }
        val payloadOff = UPLINK_HDR_LEN

        when (part) {
            APP_DATA_PART_PPG -> {
                if (payloadLen >= 14) {
                    val hr = leI16(bytes, payloadOff + 2)
                    val conf = leI16(bytes, payloadOff + 4)
                    val snr = leI16(bytes, payloadOff + 6)
                    val frameId = le32(bytes, payloadOff + 8)
                    val hrv = if (payloadLen >= 18) leI16(bytes, payloadOff + 16) else 0
                    val hrvConf = if (payloadLen >= 20) leI16(bytes, payloadOff + 18) else 0
                    val now = System.currentTimeMillis()
                    if (hr in 20..260) {
                        GrpcSensorClient.updatePpg(hr, hrv)
                    }
                    val ppgLine =
                        "${now},PPG,hr=${hr},hrv=${hrv},hrv_conf=${hrvConf},conf=${conf},snr=${snr},frame=${frameId}"
                    dataLogger.append(ppgLine)
                    lastPpgLine = ppgLine
                    if (now - lastPpgMsgMs >= 1000L) {
                        lastPpgMsgMs = now
                        appendRxMessage("PPG hr=${hr} hrv=${hrv} hrv_conf=${hrvConf} conf=${conf} snr=${snr} frame=${frameId}")
                    }
                }
                return true
            }
            APP_DATA_PART_IMU -> {
                if (payloadLen >= 10) {
                    val ver = u8(bytes, payloadOff)
                    val type = u8(bytes, payloadOff + 1)
                    val seq = le16(bytes, payloadOff + 2)
                    val now = System.currentTimeMillis()
                    if (type == 2 && payloadLen >= 10) {
                        val action = u8(bytes, payloadOff + 4)
                        val conf = u8(bytes, payloadOff + 5)
                        val imuLine =
                            "${now},IMU,seq=${seq},action=${imuActionName(action)},conf=${conf}"
                        dataLogger.append(imuLine)
                        lastImuLine = imuLine
                        if (now - lastImuMsgMs >= 1000L) {
                            lastImuMsgMs = now
                            appendRxMessage("IMU seq=${seq} action=${imuActionName(action)} conf=${conf}")
                        }
                        } else if (type == 3 && payloadLen >= 22) {
                            val ax = leI16(bytes, payloadOff + 4)
                            val ay = leI16(bytes, payloadOff + 6)
                            val az = leI16(bytes, payloadOff + 8)
                            val gx = leI16(bytes, payloadOff + 10)
                            val gy = leI16(bytes, payloadOff + 12)
                            val gz = leI16(bytes, payloadOff + 14)
                            val tLsb = leI16(bytes, payloadOff + 16)
                            val tC = imuTempCenti(tLsb)
                            GrpcSensorClient.addImuSample(
                                seq = seq,
                                ax = ax,
                                ay = ay,
                                az = az,
                                gx = gx,
                                gy = gy,
                                gz = gz,
                                tempLsb = tLsb,
                                tempC = tC / 100.0f
                            )
                            val imuRawLine =
                                "${now},IMU_RAW,seq=${seq},ax=${ax},ay=${ay},az=${az},gx=${gx},gy=${gy},gz=${gz},temp_centi=${tC}"
                        dataLogger.append(imuRawLine)
                        lastImuRawLine = imuRawLine
                        if (now - lastImuRawMsgMs >= 1000L) {
                            lastImuRawMsgMs = now
                            appendRxMessage(
                                "IMU_RAW seq=${seq} ax=${ax} ay=${ay} az=${az} " +
                                    "gx=${gx} gy=${gy} gz=${gz} temp=${tC / 100}.${(kotlin.math.abs(tC) % 100).toString().padStart(2, '0')}"
                            )
                        }
                    } else if (ver == 1 && now - lastImuMsgMs >= 1000L) {
                        lastImuMsgMs = now
                        appendRxMessage("IMU seq=${seq} (type=${type})")
                    }
                }
                return true
            }
            else -> {
                return true
            }
        }
    }

    private fun handleRawSensorPacket(bytes: ByteArray): Boolean {
        // Fallback compatibility: allow raw sensor payloads without uplink wire header.
        if (bytes.size >= 20 && u8(bytes, 0) == 1 && u8(bytes, 1) == 1) {
            val hr = leI16(bytes, 2)
            val conf = leI16(bytes, 4)
            val snr = leI16(bytes, 6)
            val frameId = le32(bytes, 8)
            val hrv = leI16(bytes, 16)
            val hrvConf = leI16(bytes, 18)
            val now = System.currentTimeMillis()
            if (hr in 20..260) {
                GrpcSensorClient.updatePpg(hr, hrv)
            }
            if (now - lastPpgMsgMs >= 1000L) {
                lastPpgMsgMs = now
                appendRxMessage("PPG hr=${hr} hrv=${hrv} hrv_conf=${hrvConf} conf=${conf} snr=${snr} frame=${frameId} (raw)")
            }
            return true
        }

        if (bytes.size >= 10 && u8(bytes, 0) == 1 && u8(bytes, 1) == 2) {
            val seq = le16(bytes, 2)
            val action = u8(bytes, 4)
            val conf = u8(bytes, 5)
            val now = System.currentTimeMillis()
            if (now - lastImuMsgMs >= 1000L) {
                lastImuMsgMs = now
                appendRxMessage("IMU seq=${seq} action=${imuActionName(action)} conf=${conf} (raw)")
            }
            return true
        }

        return false
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

    private fun resample8kTo24k(pcm8: ByteArray): ByteArray {
        if (pcm8.size < 2) return pcm8
        val inSamples = pcm8.size / 2
        if (inSamples < 2) return pcm8
        val outSamples = inSamples * 3
        val out = ByteArray(outSamples * 2)
        var outIdx = 0
        fun getSample(idx: Int): Int {
            val lo = pcm8[idx * 2].toInt() and 0xFF
            val hi = pcm8[idx * 2 + 1].toInt()
            return (hi shl 8) or lo
        }
        for (i in 0 until (inSamples - 1)) {
            val s0 = getSample(i).toShort().toInt()
            val s1 = getSample(i + 1).toShort().toInt()
            val m1 = ((2 * s0 + s1) / 3).toShort().toInt()
            val m2 = ((s0 + 2 * s1) / 3).toShort().toInt()
            val vals = intArrayOf(s0, m1, m2)
            for (v in vals) {
                out[outIdx++] = (v and 0xFF).toByte()
                out[outIdx++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        val last = getSample(inSamples - 1).toShort().toInt()
        out[outIdx++] = (last and 0xFF).toByte()
        out[outIdx++] = ((last shr 8) and 0xFF).toByte()
        out[outIdx++] = (last and 0xFF).toByte()
        out[outIdx++] = ((last shr 8) and 0xFF).toByte()
        out[outIdx++] = (last and 0xFF).toByte()
        out[outIdx++] = ((last shr 8) and 0xFF).toByte()
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    private fun resetDownlinkSpeechPipeline() {
        downlinkResampler.reset()
        downlinkAdpcmIndex = 0
        downlinkHpX1 = 0f
        downlinkHpY1 = 0f
        downlinkLpY1 = 0f
        downlinkCompEnv = 0f
        downlinkJitterMode = false
        downlinkStrictBufferedMode = useNrfSpeaker && nrfSpeakerPreferBufferedPlayback
        downlinkAdaptiveStartFrames =
            if (downlinkStrictBufferedMode) NRF_DOWNLINK_START_FRAMES_SLOW else NRF_DOWNLINK_START_FRAMES_NORMAL
        downlinkGrpcChunkCount = 0
        downlinkGrpcFirstChunkMs = 0L
        downlinkGrpcLastChunkMs = 0L
        downlinkGrpcAudioMs = 0L
        downlinkGrpcMaxGapMs = 0L
    }

    private fun updateDownlinkIngressStats(nowMs: Long, pcm24: ByteArray) {
        val chunkAudioMs = ((pcm24.size / 2L) * 1000L) / sampleRateHz.toLong()
        if (downlinkGrpcChunkCount == 0) {
            downlinkGrpcFirstChunkMs = nowMs
            downlinkGrpcLastChunkMs = nowMs
            downlinkGrpcAudioMs = chunkAudioMs
            downlinkGrpcChunkCount = 1
            downlinkAdaptiveStartFrames = NRF_DOWNLINK_START_FRAMES_NORMAL
            return
        }

        val gapMs = nowMs - downlinkGrpcLastChunkMs
        downlinkGrpcLastChunkMs = nowMs
        downlinkGrpcAudioMs += chunkAudioMs
        downlinkGrpcChunkCount += 1
        if (gapMs > downlinkGrpcMaxGapMs) {
            downlinkGrpcMaxGapMs = gapMs
        }

        if (downlinkBleStarted) {
            return
        }

        if (useNrfSpeaker && nrfSpeakerPreferBufferedPlayback) {
            downlinkStrictBufferedMode = true
            downlinkAdaptiveStartFrames = NRF_DOWNLINK_START_FRAMES_SLOW
            return
        }

        val elapsedMs = (nowMs - downlinkGrpcFirstChunkMs).coerceAtLeast(1L)
        val ingressPermille = ((downlinkGrpcAudioMs * 1000L) / elapsedMs).toInt()
        val wasJitterMode = downlinkJitterMode
        val prevStartFrames = downlinkAdaptiveStartFrames
        when {
            downlinkGrpcMaxGapMs >= 2000L || (elapsedMs >= 2000L && ingressPermille < 600) -> {
                downlinkAdaptiveStartFrames = NRF_DOWNLINK_START_FRAMES_SLOW
                downlinkJitterMode = true
                downlinkStrictBufferedMode = true
            }
            downlinkGrpcMaxGapMs >= 800L || (elapsedMs >= 1500L && ingressPermille < 800) -> {
                downlinkAdaptiveStartFrames = NRF_DOWNLINK_START_FRAMES_SLOW
                downlinkJitterMode = true
            }
            downlinkGrpcMaxGapMs >= 450L || (elapsedMs >= 800L && ingressPermille < 950) -> {
                downlinkAdaptiveStartFrames = maxOf(downlinkAdaptiveStartFrames, NRF_DOWNLINK_START_FRAMES_JITTER)
                downlinkJitterMode = true
            }
            else -> {
                downlinkAdaptiveStartFrames = maxOf(downlinkAdaptiveStartFrames, NRF_DOWNLINK_START_FRAMES_NORMAL)
            }
        }
        if (!wasJitterMode && downlinkJitterMode) {
            Timber.tag("GrpcAudioClient").i(
                "DL mode -> %s: ingress=%d.%03dx gap=%dms audio=%dms elapsed=%dms",
                if (downlinkStrictBufferedMode) "strict_buffered" else "jitter",
                ingressPermille / 1000,
                ingressPermille % 1000,
                downlinkGrpcMaxGapMs,
                downlinkGrpcAudioMs,
                elapsedMs
            )
        } else if (prevStartFrames != downlinkAdaptiveStartFrames) {
            Timber.tag("GrpcAudioClient").i(
                "DL start target=%d frames ingress=%d.%03dx gap=%dms",
                downlinkAdaptiveStartFrames,
                ingressPermille / 1000,
                ingressPermille % 1000,
                downlinkGrpcMaxGapMs
            )
        }
    }

    private fun prepareDownlinkPcm16k(pcm24: ByteArray): ByteArray {
        val pcm16 = downlinkResampler.resamplePcm16le(pcm24)
        if (pcm16.isEmpty()) return pcm16
        return if (useNrfSpeaker) normalizeDownlinkForAdpcm(pcm16) else pcm16
    }

    private fun shapeDownlinkSpeech16k(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2) return pcm16
        val out = ByteArray(pcm16.size)
        var off = 0
        while (off + 1 < pcm16.size) {
            val lo = pcm16[off].toInt() and 0xFF
            val hi = pcm16[off + 1].toInt()
            var x = ((hi shl 8) or lo).toShort().toInt() / 32768.0f

            val hp = x - downlinkHpX1 + 0.986f * downlinkHpY1
            downlinkHpX1 = x
            downlinkHpY1 = hp

            downlinkLpY1 += 0.18f * (x - downlinkLpY1)
            var y = 0.92f * hp + 0.08f * downlinkLpY1

            val ay = kotlin.math.abs(y)
            val envCoeff = if (ay > downlinkCompEnv) 0.12f else 0.006f
            downlinkCompEnv += envCoeff * (ay - downlinkCompEnv)
            if (downlinkCompEnv > 0.55f) {
                val over = downlinkCompEnv - 0.55f
                val compressed = 0.55f + over / 2.6f
                y *= compressed / (downlinkCompEnv + 1.0e-6f)
            }

            y *= 1.08f
            y = when {
                y > 0.92f -> 0.92f + (y - 0.92f) * 0.18f
                y < -0.92f -> -0.92f + (y + 0.92f) * 0.18f
                else -> y
            }

            val s = (y * 32767.0f).toInt().coerceIn(-32768, 32767)
            out[off] = (s and 0xFF).toByte()
            out[off + 1] = ((s shr 8) and 0xFF).toByte()
            off += 2
        }
        return out
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
        enqueueDownlinkEos()
    }

    private fun resetDownlinkReplyBuffer() {
        synchronized(downlinkReplyLock) {
            downlinkReplyChunks.clear()
            downlinkReplyBytes = 0
        }
    }

    private fun appendDownlinkReplyChunk(pcm24: ByteArray) {
        if (pcm24.isEmpty()) return
        synchronized(downlinkReplyLock) {
            downlinkReplyChunks.add(pcm24.copyOf())
            downlinkReplyBytes += pcm24.size
        }
    }

    private fun prepareBufferedDownlinkPlayback() {
        val pcm24 = synchronized(downlinkReplyLock) {
            if (downlinkReplyBytes <= 0) {
                downlinkReplyChunks.clear()
                downlinkReplyBytes = 0
                null
            } else {
                ByteArray(downlinkReplyBytes).also { out ->
                    var offset = 0
                    for (chunk in downlinkReplyChunks) {
                        System.arraycopy(chunk, 0, out, offset, chunk.size)
                        offset += chunk.size
                    }
                }.also {
                    downlinkReplyChunks.clear()
                    downlinkReplyBytes = 0
                }
            }
        } ?: run {
            synchronized(downlinkFrameLock) {
                downlinkGrpcEos = true
            }
            ensureDownlinkStreamJob()
            return
        }

        val pcm16 = prepareDownlinkPcm16k(pcm24)
        if (pcm16.isEmpty()) {
            synchronized(downlinkFrameLock) {
                downlinkGrpcEos = true
            }
            ensureDownlinkStreamJob()
            return
        }

        synchronized(downlinkFrameLock) {
            val merged = if (downlinkPcmCarry.isEmpty()) {
                pcm16
            } else {
                ByteArray(downlinkPcmCarry.size + pcm16.size).also { buf ->
                    System.arraycopy(downlinkPcmCarry, 0, buf, 0, downlinkPcmCarry.size)
                    System.arraycopy(pcm16, 0, buf, downlinkPcmCarry.size, pcm16.size)
                }
            }
            var offset = 0
            while ((merged.size - offset) >= NRF_DOWNLINK_PCM_FRAME_BYTES) {
                val frame = merged.copyOfRange(offset, offset + NRF_DOWNLINK_PCM_FRAME_BYTES)
                encodeNrfDownlinkFrame(frame)?.let { downlinkFrameQueue.addLast(it) }
                offset += NRF_DOWNLINK_PCM_FRAME_BYTES
            }
            if (offset < merged.size) {
                val padded = ByteArray(NRF_DOWNLINK_PCM_FRAME_BYTES)
                System.arraycopy(merged, offset, padded, 0, merged.size - offset)
                encodeNrfDownlinkFrame(padded)?.let { downlinkFrameQueue.addLast(it) }
                downlinkPcmCarry = ByteArray(0)
            } else {
                downlinkPcmCarry = ByteArray(0)
            }
            downlinkGrpcEos = true
        }
        ensureDownlinkStreamJob()
    }

    private fun enqueueDownlinkStream(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        synchronized(downlinkFrameLock) {
            val merged = if (downlinkPcmCarry.isEmpty()) {
                pcm16
            } else {
                ByteArray(downlinkPcmCarry.size + pcm16.size).also { buf ->
                    System.arraycopy(downlinkPcmCarry, 0, buf, 0, downlinkPcmCarry.size)
                    System.arraycopy(pcm16, 0, buf, downlinkPcmCarry.size, pcm16.size)
                }
            }
            var offset = 0
            while ((merged.size - offset) >= NRF_DOWNLINK_PCM_FRAME_BYTES) {
                val frame = merged.copyOfRange(offset, offset + NRF_DOWNLINK_PCM_FRAME_BYTES)
                encodeNrfDownlinkFrame(frame)?.let { downlinkFrameQueue.addLast(it) }
                offset += NRF_DOWNLINK_PCM_FRAME_BYTES
            }
            downlinkPcmCarry = if (offset < merged.size) {
                merged.copyOfRange(offset, merged.size)
            } else {
                ByteArray(0)
            }
        }
        ensureDownlinkStreamJob()
    }

    private fun enqueueDownlinkEos() {
        synchronized(downlinkFrameLock) {
            if (downlinkPcmCarry.isNotEmpty()) {
                val padded = ByteArray(NRF_DOWNLINK_PCM_FRAME_BYTES)
                System.arraycopy(downlinkPcmCarry, 0, padded, 0, downlinkPcmCarry.size)
                encodeNrfDownlinkFrame(padded)?.let { downlinkFrameQueue.addLast(it) }
                downlinkPcmCarry = ByteArray(0)
            }
            downlinkGrpcEos = true
        }
        ensureDownlinkStreamJob()
    }

    private fun computeDownlinkTxIntervalMs(queuedFrames: Int, eos: Boolean): Long {
        if (eos) {
            return 2L
        }
        if (downlinkJitterMode) {
            return when {
                queuedFrames <= 16 -> 2L
                queuedFrames <= 48 -> 3L
                queuedFrames <= 96 -> 4L
                else -> 5L
            }
        }
        return when {
            queuedFrames <= 8 -> 3L
            queuedFrames <= 20 -> 4L
            queuedFrames >= 96 -> 6L
            queuedFrames >= 48 -> 5L
            else -> 4L
        }
    }

    private fun ensureDownlinkStreamJob() {
        if (downlinkStreamJob?.isActive == true) return
        downlinkStreamJob = scope.launch {
            var seq = downlinkSeq
            var nextTxAtMs = 0L
            while (isActive) {
                if (!downlinkBleStarted) {
                    val queuedFrames = synchronized(downlinkFrameLock) { downlinkFrameQueue.size }
                    val now = SystemClock.elapsedRealtime()
                    val waitedMs = if (downlinkGrpcFirstChunkMs > 0L) now - downlinkGrpcFirstChunkMs else 0L
                    val haveEnoughChunks = downlinkGrpcChunkCount >= 2
                    val allowStart = synchronized(downlinkFrameLock) {
                        if (downlinkStrictBufferedMode) {
                            downlinkGrpcEos && queuedFrames > 0
                        } else {
                            downlinkGrpcEos ||
                                ((queuedFrames >= downlinkAdaptiveStartFrames) &&
                                    (haveEnoughChunks || waitedMs >= NRF_DOWNLINK_MAX_START_WAIT_MS))
                        }
                    }
                    if (!allowStart) {
                        delay(2L)
                        continue
                    }
                    if (ledCharacteristic == null) {
                        synchronized(downlinkFrameLock) {
                            downlinkFrameQueue.clear()
                            downlinkPcmCarry = ByteArray(0)
                            downlinkGrpcEos = false
                        }
                        nrfPlaybackPending = false
                        GrpcAudioClient.setSendPaused(false)
                        break
                    }
                    nrfPlaybackPending = true
                    nrfBufferFull = false
                    nrfReady = false
                    sendBleControl("APP_SPK_VOL:${nrfSpeakerVolumePercent.coerceIn(0, 500)}")
                    sendBleControl("APP_READY?")
                    val ready = waitForNrfReady(3000L)
                    if (!ready) {
                        Timber.tag("GrpcAudioClient").w("BLE NRF_READY timeout (stream)")
                        synchronized(downlinkFrameLock) {
                            downlinkFrameQueue.clear()
                            downlinkPcmCarry = ByteArray(0)
                            downlinkGrpcEos = false
                        }
                        nrfPlaybackPending = false
                        GrpcAudioClient.setSendPaused(false)
                        break
                    }
                    Timber.tag("GrpcAudioClient").i(
                        "DL start mode=%s queued=%d target=%d chunks=%d wait=%dms",
                        if (downlinkStrictBufferedMode) {
                            "strict_buffered"
                        } else if (downlinkJitterMode) {
                            "jitter"
                        } else {
                            "stream"
                        },
                        queuedFrames,
                        downlinkAdaptiveStartFrames,
                        downlinkGrpcChunkCount,
                        waitedMs
                    )
                    Timber.tag("GrpcAudioClient").i("BLE APP_PLAY_START (stream)")
                    downlinkTiming?.blePlayStartMs = SystemClock.elapsedRealtime()
                    sendBleControl("APP_PLAY_START")
                    downlinkBleStarted = true
                    nextTxAtMs = 0L
                }

                if (nrfBufferFull) {
                    delay(2L)
                    continue
                }

                val encoded = synchronized(downlinkFrameLock) {
                    if (downlinkFrameQueue.isNotEmpty()) downlinkFrameQueue.removeFirst() else null
                }
                if (encoded != null) {
                    val queuedFrames = synchronized(downlinkFrameLock) { downlinkFrameQueue.size }
                    val eosPending = synchronized(downlinkFrameLock) { downlinkGrpcEos }
                    val txIntervalMs = computeDownlinkTxIntervalMs(queuedFrames, eosPending)
                    val now = SystemClock.elapsedRealtime()
                    if (nextTxAtMs == 0L) {
                        nextTxAtMs = now
                    } else if (now < nextTxAtMs) {
                        delay(nextTxAtMs - now)
                    }
                    sendBleAudioFrame(encoded, seq++)
                    val txDoneMs = SystemClock.elapsedRealtime()
                    val baseMs = if (nextTxAtMs > txDoneMs) nextTxAtMs else txDoneMs
                    nextTxAtMs = baseMs + txIntervalMs
                    continue
                }

                val eos = synchronized(downlinkFrameLock) { downlinkGrpcEos }
                if (eos) {
                    sendBleAudioEnd(seq++)
                    downlinkTiming?.blePlayEndMs = SystemClock.elapsedRealtime()
                    Timber.tag("GrpcAudioClient").i("BLE APP_PLAY_END (stream)")
                    logDownlinkTiming("ble_end")
                    downlinkPlayDoneTimeoutJob?.cancel()
                    downlinkPlayDoneTimeoutJob = scope.launch {
                        delay(2500L)
                        if (nrfPlaybackPending && !downlinkBleStarted) {
                            Timber.tag("GrpcAudioClient").w("BLE PLAY_DONE timeout (stream fallback)")
                            onNrfPlaybackDone()
                        }
                    }
                    downlinkSeq = seq
                    downlinkBleStarted = false
                    synchronized(downlinkFrameLock) {
                        downlinkGrpcEos = false
                    }
                    break
                }

                delay(1L)
            }
            downlinkStreamJob = null
        }
    }

    private suspend fun sendBleAudioFrame(payload: ByteArray, seq: Int) {
        // Current phone negotiates MTU 65 => max characteristic value length is 62 bytes.
        // Our custom fragment header already takes 8 bytes, so audio payload per BLE write
        // must stay within 54 bytes, otherwise Android silently truncates the first fragment.
        val mtuPayload = 54
        val fragCnt = ((payload.size + mtuPayload - 1) / mtuPayload).coerceAtLeast(1)
        var offset = 0
        for (frag in 0 until fragCnt) {
            val remaining = payload.size - offset
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
            System.arraycopy(payload, offset, pkt, 8, chunk)
            writeCharacteristic(
                ledCharacteristic,
                pkt,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ).suspend()
            offset += chunk
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
        downlinkPlayDoneTimeoutJob?.cancel()
        downlinkPlayDoneTimeoutJob = null
        downlinkTiming?.nrfPlayDoneMs = SystemClock.elapsedRealtime()
        logDownlinkTiming("play_done")
        nrfPlaybackPending = false
        nrfReady = false
        downlinkBleStarted = false
        downlinkReplyActive = false
        downlinkAudioSeen = false
        downlinkTiming = null
        if (downlinkTestEnabled) {
            playDoneSignal.trySend(Unit)
        }
        if (nrfMicPaused) {
            sendBleControl("APP_MIC_RESUME")
            nrfMicPaused = false
        }
        if (_conversationState.value == ConversationState.WAITING_RESPONSE) {
            autoResumeRealtimeUplink()
        }
        if (_realtimeServiceEnabled.value) {
            GrpcAudioClient.setSendPaused(false)
        }
    }

    private fun startDownlinkTest() {
        if (downlinkTestJob?.isActive == true) return
        downlinkTestJob = scope.launch {
            delay(500L)
            val tone = generateTestPattern16k()
            Timber.tag("GrpcAudioClient").i("Downlink test enabled: clip=%d ms, cadence=~5s", 5000)
            while (downlinkTestEnabled && isReady) {
                sendBleControl("APP_READY?")
                val ready = waitForNrfReady(3000L)
                if (!ready) {
                    Timber.tag("GrpcAudioClient").w("BLE NRF_READY timeout (test)")
                    delay(500L)
                    continue
                }
                sendBleControl("APP_SPK_VOL:${nrfSpeakerVolumePercent.coerceIn(0, 500)}")
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
            }
        }
    }

    private suspend fun sendTestDownlink(pcm16: ByteArray) {
        val frameBytes = NRF_DOWNLINK_PCM_FRAME_BYTES
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
            val encoded = encodeNrfDownlinkFrame(frame) ?: break
            sendBleAudioFrame(encoded, downlinkSeq++)
            offset += take
        }
    }

    private fun generateTestPattern16k(): ByteArray {
        val sr = nrfSampleRateHz
        val amp = (Short.MAX_VALUE * 0.32).toInt()
        val toneMs = 500
        val freqs = doubleArrayOf(
            440.0, 880.0, 660.0, 990.0, 550.0,
            770.0, 330.0, 1040.0, 620.0, 880.0
        ) // 10 * 500ms = 5s

        fun appendTone(freqHz: Double, durMs: Int, dst: ByteArray, startIdx: Int): Int {
            val totalSamples = (sr * durMs / 1000.0).toInt()
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

        val totalMs = toneMs * freqs.size
        val totalSamples = (sr * totalMs / 1000.0).toInt()
        val pcm = ByteArray(totalSamples * 2)
        var idx = 0
        freqs.forEach { f ->
            idx = appendTone(f, toneMs, pcm, idx)
        }
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

    fun setNrfSpeakerVolumePercent(percent: Int) {
        nrfSpeakerVolumePercent = percent.coerceIn(0, 500)
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
        if (nrfMicPaused) {
            sendBleControl("APP_MIC_RESUME")
            nrfMicPaused = false
        }
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
        downlinkPlayDoneTimeoutJob?.cancel()
        downlinkPlayDoneTimeoutJob = null
        nrfPlaybackPending = false
        downlinkBleStarted = false
        downlinkReplyActive = false
        synchronized(downlinkFrameLock) {
            downlinkFrameQueue.clear()
            downlinkPcmCarry = ByteArray(0)
            downlinkGrpcEos = false
        }
        downlinkStreamJob?.cancel()
        downlinkStreamJob = null
        stopKeepalive()
        releaseAudioTrack()
    }

    private fun startInitialSessionDelay() {
        sessionDelayJob?.cancel()
        sessionDelayJob = scope.launch {
            delay(initialSessionDelayMs)
            if (_conversationState.value == ConversationState.CONNECTING && !_conversationSessionReady.value) {
                _conversationSessionReady.value = true
                if (_realtimeServiceEnabled.value || pendingTalk) {
                    pendingTalk = false
                    _conversationState.value = ConversationState.TALKING
                    GrpcAudioClient.setSendPaused(false)
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
                            autoResumeRealtimeUplink()
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
