package no.nordicsemi.android.blinky.ble

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import collar.CollarDataServiceGrpc
import collar.CollarProto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GrpcSensorClient {
    private const val TAG = "GrpcSensorClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var host: String = "traini-inference-nlb-1e17132c99147402.elb.us-east-1.amazonaws.com"
    private var port: Int = 50051
    private var deviceId: String = "nrf-collar-unknown"
    private var intervalMs: Long = 1000L

    private var channel: ManagedChannel? = null
    private var requestObserver: StreamObserver<CollarProto.SensorDataRequest>? = null
    private var senderJob: Job? = null
    private var reconnectBackoffMs: Long = 1000L
    private var nextReconnectAllowedAtMs: Long = 0L
    private var sentCount: Long = 0L
    private var lastSendTimeMs: Long = 0L
    private var lastError: String = ""
    private var lastSummaryMs: Long = 0L

    @Volatile private var latestHeartRate: Int? = null
    @Volatile private var latestHrv: Int? = null
    @Volatile private var latestSpo2: Float? = null
    @Volatile private var latestLat: Double? = null
    @Volatile private var latestLon: Double? = null
    @Volatile private var running: Boolean = false
    private val imuLock = Any()
    private val imuBuffer: ArrayList<ImuSampleLite> = ArrayList(128)

    private data class ImuSampleLite(
        val seq: Int,
        val ax: Int,
        val ay: Int,
        val az: Int,
        val gx: Int,
        val gy: Int,
        val gz: Int,
        val tempLsb: Int,
        val tempC: Float
    )

    fun configure(host: String, port: Int, deviceId: String, intervalMs: Long = 1000L) {
        this.host = host
        this.port = port
        this.deviceId = deviceId
        this.intervalMs = intervalMs.coerceIn(500L, 10_000L)
    }

    fun updatePpg(hr: Int, hrv: Int, spo2: Float) {
        if (hr <= 0) return
        latestHeartRate = hr
        latestHrv = if (hrv >= 0) hrv else 0
        latestSpo2 = if (spo2 >= 0f) spo2 else 0f
    }

    fun updateGps(lat: Double, lon: Double) {
        latestLat = lat
        latestLon = lon
    }

    fun addImuSample(
        seq: Int,
        ax: Int,
        ay: Int,
        az: Int,
        gx: Int,
        gy: Int,
        gz: Int,
        tempLsb: Int,
        tempC: Float
    ) {
        synchronized(imuLock) {
            if (imuBuffer.size >= 256) {
                imuBuffer.clear()
            }
            imuBuffer.add(ImuSampleLite(seq, ax, ay, az, gx, gy, gz, tempLsb, tempC))
        }
    }

    fun start() {
        if (running) return
        running = true
        if (senderJob?.isActive == true) return
        senderJob = scope.launch {
            while (isActive && running) {
                try {
                    val hr = latestHeartRate
                    val hrv = latestHrv
                    val spo2 = latestSpo2
                    val lat = latestLat
                    val lon = latestLon
                    if (hr != null && hrv != null && spo2 != null && lat != null && lon != null) {
                        ensureStream()
                        sendPacket(hr, hrv, spo2, lat, lon)
                        reconnectBackoffMs = 1000L
                        val now = System.currentTimeMillis()
                        if (now - lastSummaryMs >= 10_000L) {
                            lastSummaryMs = now
                            Timber.tag(TAG).i(
                                "[SENSOR_SUMMARY] sent_count=%d last_send_time=%d last_error=%s",
                                sentCount,
                                lastSendTimeMs,
                                if (lastError.isBlank()) "none" else lastError
                            )
                        }
                    }
                } catch (t: Throwable) {
                    val st = Status.fromThrowable(t)
                    lastError = "loop:${st.code}:${st.description ?: t.message ?: "unknown"}"
                    Timber.tag(TAG).w(
                        "send loop error code=%s desc=%s msg=%s",
                        st.code,
                        st.description ?: "",
                        t.message ?: "unknown"
                    )
                    resetStream()
                    delay(reconnectBackoffMs)
                    reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(15_000L)
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        running = false
        senderJob?.cancel()
        senderJob = null
        closeStream()
        closeChannel()
    }

    private fun ensureChannel(): ManagedChannel {
        val ch = channel
        if (ch != null && !ch.isShutdown && !ch.isTerminated) {
            return ch
        }
        val newCh = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .enableRetry()
            .maxInboundMessageSize(4 * 1024 * 1024)
            .keepAliveWithoutCalls(true)
            .build()
        channel = newCh
        Timber.tag(TAG).i("channel ready %s:%d", host, port)
        return newCh
    }

    private fun ensureStream() {
        val now = System.currentTimeMillis()
        if (now < nextReconnectAllowedAtMs) {
            return
        }
        if (requestObserver != null) return
        val stub = CollarDataServiceGrpc.newStub(ensureChannel())
        requestObserver = stub.streamSensorData(object : StreamObserver<CollarProto.StreamAck> {
            override fun onNext(value: CollarProto.StreamAck) {
                Timber.tag(TAG).i(
                    "ack success=%s processed=%d msg=%s",
                    value.success,
                    value.packetsProcessed,
                    value.message
                )
            }

            override fun onError(t: Throwable) {
                val st = Status.fromThrowable(t)
                lastError = "stream:${st.code}:${st.description ?: t.message ?: "unknown"}"
                Timber.tag(TAG).w(
                    "stream error code=%s desc=%s msg=%s",
                    st.code,
                    st.description ?: "",
                    t.message ?: "unknown"
                )
                requestObserver = null
                // UNAVAILABLE usually means transport/channel issue. Recreate channel and back off.
                if (st.code == Status.Code.UNAVAILABLE) {
                    closeChannel()
                    nextReconnectAllowedAtMs = System.currentTimeMillis() + reconnectBackoffMs
                    reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30_000L)
                }
            }

            override fun onCompleted() {
                Timber.tag(TAG).i("stream completed")
                requestObserver = null
            }
        })
        Timber.tag(TAG).i("stream opened")
    }

    private fun sendPacket(hr: Int, hrv: Int, spo2: Float, lat: Double, lon: Double) {
        val ts = isoUtcNow()
        val imuSamples = synchronized(imuLock) {
            if (imuBuffer.isEmpty()) {
                emptyList()
            } else {
                val copy = imuBuffer.toList()
                imuBuffer.clear()
                copy
            }
        }
        val req = CollarProto.SensorDataRequest.newBuilder()
            .setDeviceId(deviceId)
            .setTimestamp(ts)
            .setHeartRate(hr)
            .setHrv(hrv)
            .setSpo2(spo2)
            .setGps(
                CollarProto.GpsCoordinates.newBuilder()
                    .setLat(lat)
                    .setLon(lon)
                    .build()
            )
            .addAllImuSamples(
                imuSamples.map {
                    CollarProto.IMUSample.newBuilder()
                        .setSeq(it.seq)
                        .setAxLsb(it.ax)
                        .setAyLsb(it.ay)
                        .setAzLsb(it.az)
                        .setGxLsb(it.gx)
                        .setGyLsb(it.gy)
                        .setGzLsb(it.gz)
                        .setTempLsb(it.tempLsb)
                        .setTempC(it.tempC)
                        .build()
                }
            )
            .build()
        val obs = requestObserver ?: return
        try {
            obs.onNext(req)
            sentCount++
            lastSendTimeMs = System.currentTimeMillis()
            lastError = ""
            // Successful send means path is healthy again; clear backoff window.
            reconnectBackoffMs = 1000L
            nextReconnectAllowedAtMs = 0L
            Timber.tag(TAG).i(
                "[SENSOR_TX] device_id=%s ts=%s hr=%d hrv=%d spo2=%.1f lat=%.6f lon=%.6f imu=%d",
                deviceId,
                ts,
                hr,
                hrv,
                spo2,
                lat,
                lon,
                imuSamples.size
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w("onNext failed: %s", t.message ?: "unknown")
            val st = Status.fromThrowable(t)
            lastError = "onNext:${st.code}:${st.description ?: t.message ?: "unknown"}"
            if (st.code == Status.Code.UNAVAILABLE) {
                closeChannel()
                nextReconnectAllowedAtMs = System.currentTimeMillis() + reconnectBackoffMs
                reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30_000L)
            }
            resetStream()
        }
    }

    private fun resetStream() {
        try {
            requestObserver?.onCompleted()
        } catch (_: Throwable) {
        }
        requestObserver = null
    }

    private fun closeStream() {
        try {
            requestObserver?.onCompleted()
        } catch (_: Throwable) {
        }
        requestObserver = null
    }

    private fun closeChannel() {
        try {
            channel?.shutdownNow()
        } catch (_: Throwable) {
        }
        channel = null
    }

    private fun isoUtcNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
