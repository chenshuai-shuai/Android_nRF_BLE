package no.nordicsemi.android.blinky.ui.calibration.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.nordicsemi.android.blinky.spec.AttitudeSample
import no.nordicsemi.android.blinky.spec.ImuRawSample
import no.nordicsemi.android.blinky.ui.control.repository.BlinkyRepository
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Named

internal data class ImuViewerUiState(
    val quaternion: Quaternion = Quaternion.Identity,
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val moving: Boolean = false,
    val biasReady: Boolean = false,
    val paused: Boolean = false,
    val latestSample: ImuRawSample? = null,
    val sampleRateHz: Float = 0f,
    val viewerFps: Float = 0f,
)

@HiltViewModel
class ImuCalibrationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: BlinkyRepository,
    val device: BluetoothDevice,
    @Named("deviceName") val deviceName: String,
) : AndroidViewModel(context as Application) {

    private val estimator = ImuOrientationEstimator()
    private val _uiState = MutableStateFlow(ImuViewerUiState())
    internal val uiState: StateFlow<ImuViewerUiState> = _uiState.asStateFlow()
    private val sampleTimesMs = ArrayDeque<Long>()
    private val frameTimesMs = ArrayDeque<Long>()
    private var lastBoardQuaternion: Quaternion? = null
    private var filteredBoardQuaternion: Quaternion? = null
    private var boardReferenceQuaternion: Quaternion = Quaternion.Identity
    private var preferBoardAttitude: Boolean = false

    val state = repository.state
        .stateIn(viewModelScope, SharingStarted.Lazily, no.nordicsemi.android.blinky.spec.Blinky.State.LOADING)

    init {
        connect()
        viewModelScope.launch {
            repository.imuRawSample.collectLatest { sample ->
                if (sample == null) {
                    return@collectLatest
                }
                applyRawSample(sample)
            }
        }
        viewModelScope.launch {
            repository.attitudeSample.collectLatest { sample ->
                if (sample == null) {
                    return@collectLatest
                }
                applyAttitudeSample(sample)
            }
        }
    }

    fun connect() {
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            repository.connect()
        }
    }

    fun resetReference() {
        if (preferBoardAttitude) {
            boardReferenceQuaternion = lastBoardQuaternion ?: Quaternion.Identity
        } else {
            estimator.resetReference()
        }
    }

    fun togglePause() {
        val paused = !_uiState.value.paused
        _uiState.value = _uiState.value.copy(paused = paused)
        if (!paused) {
            estimator.resetAll()
        }
    }

    fun resetViewer() {
        estimator.resetAll()
        lastBoardQuaternion = null
        filteredBoardQuaternion = null
        boardReferenceQuaternion = Quaternion.Identity
        preferBoardAttitude = false
        sampleTimesMs.clear()
        frameTimesMs.clear()
        _uiState.value = ImuViewerUiState(latestSample = _uiState.value.latestSample)
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }

    private fun pushAndMeasure(window: ArrayDeque<Long>, nowMs: Long): Float {
        window.addLast(nowMs)
        val minTime = nowMs - 1000L
        while (window.size > 1 && window.first() < minTime) {
            window.removeFirst()
        }
        if (window.size < 2) {
            return 0f
        }
        val spanMs = (window.last() - window.first()).coerceAtLeast(1L)
        return ((window.size - 1) * 1000f) / spanMs.toFloat()
    }

    private fun applyRawSample(sample: ImuRawSample) {
        val sampleClockMs = if (sample.deviceTimestampMs > 0L) sample.deviceTimestampMs else sample.receivedAtMs
        val sampleRateHz = pushAndMeasure(sampleTimesMs, sampleClockMs)
        val viewerFps = pushAndMeasure(frameTimesMs, System.currentTimeMillis())
        if (_uiState.value.paused) {
            _uiState.value = _uiState.value.copy(
                latestSample = sample,
                sampleRateHz = sampleRateHz,
                viewerFps = viewerFps,
            )
            return
        }
        if (preferBoardAttitude) {
            _uiState.value = _uiState.value.copy(
                latestSample = sample,
                sampleRateHz = sampleRateHz,
                viewerFps = viewerFps,
            )
            return
        }

        val snapshot = estimator.onSample(sample)
        _uiState.value = _uiState.value.copy(
            quaternion = snapshot.displayQuaternion,
            rollDeg = snapshot.rollDeg,
            pitchDeg = snapshot.pitchDeg,
            yawDeg = snapshot.yawDeg,
            moving = snapshot.moving,
            biasReady = snapshot.biasReady,
            latestSample = sample,
            sampleRateHz = sampleRateHz,
            viewerFps = viewerFps,
        )
    }

    private fun applyAttitudeSample(sample: AttitudeSample) {
        preferBoardAttitude = true
        val viewerFps = pushAndMeasure(frameTimesMs, System.currentTimeMillis())
        if (_uiState.value.paused) {
            _uiState.value = _uiState.value.copy(viewerFps = viewerFps)
            return
        }

        val boardQuat = Quaternion(
            w = sample.qwQ30.toFloat() / (1 shl 30).toFloat(),
            x = sample.qxQ30.toFloat() / (1 shl 30).toFloat(),
            y = sample.qyQ30.toFloat() / (1 shl 30).toFloat(),
            z = sample.qzQ30.toFloat() / (1 shl 30).toFloat(),
        ).normalized()
        lastBoardQuaternion = boardQuat
        val smoothingAlpha = if (sample.moving) BOARD_SMOOTHING_MOVING_ALPHA else BOARD_SMOOTHING_STILL_ALPHA
        val smoothedBoardQuat = filteredBoardQuaternion
            ?.slerpTo(boardQuat, smoothingAlpha)
            ?: boardQuat
        filteredBoardQuaternion = smoothedBoardQuat
        val displayQuat = (boardReferenceQuaternion.conjugate() * smoothedBoardQuat).normalized()
        val (roll, pitch, yaw) = displayQuat.toEulerDegrees()
        _uiState.value = _uiState.value.copy(
            quaternion = displayQuat,
            rollDeg = roll,
            pitchDeg = pitch,
            yawDeg = yaw,
            moving = sample.moving,
            biasReady = sample.biasReady || sample.gyrAccuracy >= 2,
            viewerFps = viewerFps,
        )
    }

    private companion object {
        const val BOARD_SMOOTHING_STILL_ALPHA = 0.14f
        const val BOARD_SMOOTHING_MOVING_ALPHA = 0.32f
    }
}
