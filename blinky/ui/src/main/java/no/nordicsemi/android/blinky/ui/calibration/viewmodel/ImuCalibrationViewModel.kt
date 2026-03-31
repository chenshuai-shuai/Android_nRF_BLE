package no.nordicsemi.android.blinky.ui.calibration.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.nordicsemi.android.blinky.spec.AttitudeSample
import no.nordicsemi.android.blinky.spec.ImuRawSample
import no.nordicsemi.android.blinky.ui.control.repository.BlinkyRepository
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
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
    val activity: DogActivityClass = DogActivityClass.STATIC,
    val activityLabel: String = "Waiting",
    val activityConfidence: Int = 0,
    val activitySource: String = "--",
    val activityDebug: DogActivityDebugInfo = DogActivityDebugInfo(),
    val tuning: DogActivityTuning = DogActivityTuning(),
    val accuracyStats: ActivityAccuracyStats = ActivityAccuracyStats(),
)

internal data class ActivityAccuracyStats(
    val activeReference: DogActivityClass? = null,
    val totalEvaluations: Int = 0,
    val correctEvaluations: Int = 0,
    val staticTotal: Int = 0,
    val staticCorrect: Int = 0,
    val walkTotal: Int = 0,
    val walkCorrect: Int = 0,
    val runTotal: Int = 0,
    val runCorrect: Int = 0,
) {
    val overallAccuracyPercent: Int
        get() = percent(correctEvaluations, totalEvaluations)

    fun accuracyPercentFor(activity: DogActivityClass): Int = when (activity) {
        DogActivityClass.STATIC -> percent(staticCorrect, staticTotal)
        DogActivityClass.WALK -> percent(walkCorrect, walkTotal)
        DogActivityClass.RUN -> percent(runCorrect, runTotal)
    }

    fun sampleCountFor(activity: DogActivityClass): Int = when (activity) {
        DogActivityClass.STATIC -> staticTotal
        DogActivityClass.WALK -> walkTotal
        DogActivityClass.RUN -> runTotal
    }

    fun withActiveReference(activity: DogActivityClass?): ActivityAccuracyStats =
        copy(activeReference = activity)

    fun record(reference: DogActivityClass, predicted: DogActivityClass): ActivityAccuracyStats {
        val correct = reference == predicted
        return when (reference) {
            DogActivityClass.STATIC -> copy(
                totalEvaluations = totalEvaluations + 1,
                correctEvaluations = correctEvaluations + if (correct) 1 else 0,
                staticTotal = staticTotal + 1,
                staticCorrect = staticCorrect + if (correct) 1 else 0,
            )

            DogActivityClass.WALK -> copy(
                totalEvaluations = totalEvaluations + 1,
                correctEvaluations = correctEvaluations + if (correct) 1 else 0,
                walkTotal = walkTotal + 1,
                walkCorrect = walkCorrect + if (correct) 1 else 0,
            )

            DogActivityClass.RUN -> copy(
                totalEvaluations = totalEvaluations + 1,
                correctEvaluations = correctEvaluations + if (correct) 1 else 0,
                runTotal = runTotal + 1,
                runCorrect = runCorrect + if (correct) 1 else 0,
            )
        }
    }

    private fun percent(correct: Int, total: Int): Int =
        if (total <= 0) 0 else ((correct * 100f) / total.toFloat()).toInt()
}

@HiltViewModel
class ImuCalibrationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: BlinkyRepository,
    val device: BluetoothDevice,
    @Named("deviceName") val deviceName: String,
) : AndroidViewModel(context as Application) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val estimator = ImuOrientationEstimator()
    private val activityClassifier = DogActivityClassifier(loadTuning())
    private val _uiState = MutableStateFlow(ImuViewerUiState())
    internal val uiState: StateFlow<ImuViewerUiState> = _uiState.asStateFlow()
    private val sampleTimesMs = ArrayDeque<Long>()
    private val frameTimesMs = ArrayDeque<Long>()
    private var lastBoardQuaternion: Quaternion? = null
    private var filteredBoardQuaternion: Quaternion? = null
    private var boardReferenceQuaternion: Quaternion = Quaternion.Identity
    private var preferBoardAttitude: Boolean = false
    private val latestRawSample = AtomicReference<ImuRawSample?>(null)
    private val latestAttitudeSample = AtomicReference<AttitudeSample?>(null)
    private val latestViewerSnapshot = AtomicReference(ViewerSnapshot())
    private val accuracyStats = AtomicReference(ActivityAccuracyStats())
    private var lastAccuracyRecordMs = 0L

    val state = repository.state
        .stateIn(viewModelScope, SharingStarted.Lazily, no.nordicsemi.android.blinky.spec.Blinky.State.LOADING)

    init {
        _uiState.value = _uiState.value.copy(tuning = loadTuning())
        connect()
        viewModelScope.launch(Dispatchers.Default) {
            repository.imuRawSample
                .collect { sample ->
                if (sample == null) {
                    return@collect
                }
                applyRawSample(sample)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            repository.attitudeSample
                .collect { sample ->
                if (sample == null) {
                    return@collect
                }
                applyAttitudeSample(sample)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                publishViewerFrame()
                delay(VIEWER_FRAME_INTERVAL_MS)
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
        activityClassifier.reset()
        latestRawSample.set(null)
        latestAttitudeSample.set(null)
        latestViewerSnapshot.set(ViewerSnapshot())
        accuracyStats.set(ActivityAccuracyStats())
        lastAccuracyRecordMs = 0L
        sampleTimesMs.clear()
        frameTimesMs.clear()
        _uiState.value = ImuViewerUiState(
            latestSample = _uiState.value.latestSample,
            tuning = _uiState.value.tuning,
        )
    }

    internal fun updateTuning(tuning: DogActivityTuning) {
        activityClassifier.setTuning(tuning)
        saveTuning(tuning)
        _uiState.value = _uiState.value.copy(tuning = tuning)
    }

    internal fun resetTuning() {
        val tuning = DogActivityTuning()
        activityClassifier.setTuning(tuning)
        saveTuning(tuning)
        _uiState.value = _uiState.value.copy(tuning = tuning)
    }

    internal fun setReferenceActivity(activity: DogActivityClass?) {
        accuracyStats.updateAndGet { it.withActiveReference(activity) }
        _uiState.value = _uiState.value.copy(accuracyStats = accuracyStats.get())
    }

    internal fun resetAccuracyStats() {
        val activeReference = accuracyStats.get().activeReference
        accuracyStats.set(ActivityAccuracyStats(activeReference = activeReference))
        lastAccuracyRecordMs = 0L
        _uiState.value = _uiState.value.copy(accuracyStats = accuracyStats.get())
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
        latestRawSample.set(sample)
        val sampleClockMs = if (sample.deviceTimestampMs > 0L) sample.deviceTimestampMs else sample.receivedAtMs
        val sampleRateHz = pushAndMeasure(sampleTimesMs, sampleClockMs)
        if (_uiState.value.paused) {
            latestViewerSnapshot.updateAndGet { it.copy(latestSample = sample, sampleRateHz = sampleRateHz) }
            return
        }
        if (preferBoardAttitude) {
            val decision = activityClassifier.onRawSample(sample, latestAttitudeSample.get())
            val estimate = decision.estimate
            latestViewerSnapshot.updateAndGet {
                it.copy(
                    latestSample = sample,
                    sampleRateHz = sampleRateHz,
                    activity = estimate.activity,
                    activityLabel = estimate.label,
                    activityConfidence = estimate.confidence,
                    activitySource = estimate.source,
                    activityDebug = decision.debug,
                )
            }
            return
        }

        val snapshot = estimator.onSample(sample)
        val decision = activityClassifier.onRawSample(sample, latestAttitudeSample.get())
        val estimate = decision.estimate
        latestViewerSnapshot.set(
            latestViewerSnapshot.get().copy(
                quaternion = snapshot.displayQuaternion,
                rollDeg = snapshot.rollDeg,
                pitchDeg = snapshot.pitchDeg,
                yawDeg = snapshot.yawDeg,
                moving = snapshot.moving,
                biasReady = snapshot.biasReady,
                latestSample = sample,
                sampleRateHz = sampleRateHz,
                activity = estimate.activity,
                activityLabel = estimate.label,
                activityConfidence = estimate.confidence,
                activitySource = estimate.source,
                activityDebug = decision.debug,
            )
        )
    }

    private fun applyAttitudeSample(sample: AttitudeSample) {
        latestAttitudeSample.set(sample)
        preferBoardAttitude = true
        if (_uiState.value.paused) {
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
        latestViewerSnapshot.set(
            latestViewerSnapshot.get().copy(
                quaternion = displayQuat,
                rollDeg = roll,
                pitchDeg = pitch,
                yawDeg = yaw,
                moving = sample.moving,
                biasReady = sample.biasReady || sample.gyrAccuracy >= 2,
            )
        )
    }

    private fun publishViewerFrame() {
        val nowMs = System.currentTimeMillis()
        val viewerFps = pushAndMeasure(frameTimesMs, nowMs)
        val snapshot = latestViewerSnapshot.get()
        val rawSample = latestRawSample.get()
        maybeRecordAccuracy(snapshot.activity, nowMs)
        val paused = _uiState.value.paused
        _uiState.value = _uiState.value.copy(
            quaternion = snapshot.quaternion,
            rollDeg = snapshot.rollDeg,
            pitchDeg = snapshot.pitchDeg,
            yawDeg = snapshot.yawDeg,
            moving = snapshot.moving,
            biasReady = snapshot.biasReady,
            paused = paused,
            latestSample = rawSample ?: snapshot.latestSample,
            sampleRateHz = snapshot.sampleRateHz,
            viewerFps = viewerFps,
            activity = snapshot.activity,
            activityLabel = snapshot.activityLabel,
            activityConfidence = snapshot.activityConfidence,
            activitySource = snapshot.activitySource,
            activityDebug = snapshot.activityDebug,
            accuracyStats = accuracyStats.get(),
        )
    }

    private fun maybeRecordAccuracy(
        predicted: DogActivityClass,
        nowMs: Long,
    ) {
        val stats = accuracyStats.get()
        val reference = stats.activeReference ?: return
        if ((nowMs - lastAccuracyRecordMs) < ACCURACY_SAMPLE_INTERVAL_MS) {
            return
        }
        lastAccuracyRecordMs = nowMs
        accuracyStats.updateAndGet { it.record(reference, predicted) }
    }

    private fun loadTuning(): DogActivityTuning = DogActivityTuning(
        windowMs = prefs.getLong(KEY_WINDOW_MS, DogActivityTuning().windowMs),
        staticMeanAccG = prefs.getFloat(KEY_STATIC_MEAN_ACC, DogActivityTuning().staticMeanAccG),
        staticStdAccG = prefs.getFloat(KEY_STATIC_STD_ACC, DogActivityTuning().staticStdAccG),
        staticMovingShare = prefs.getFloat(KEY_STATIC_MOVING_SHARE, DogActivityTuning().staticMovingShare),
        staticMeanGyroDps = prefs.getFloat(KEY_STATIC_MEAN_GYRO, DogActivityTuning().staticMeanGyroDps),
        staticCadenceHz = prefs.getFloat(KEY_STATIC_CADENCE, DogActivityTuning().staticCadenceHz),
        walkMeanAccG = prefs.getFloat(KEY_WALK_MEAN_ACC, DogActivityTuning().walkMeanAccG),
        walkMovingShare = prefs.getFloat(KEY_WALK_MOVING_SHARE, DogActivityTuning().walkMovingShare),
        walkMeanGyroDps = prefs.getFloat(KEY_WALK_MEAN_GYRO, DogActivityTuning().walkMeanGyroDps),
        walkCadenceHz = prefs.getFloat(KEY_WALK_CADENCE, DogActivityTuning().walkCadenceHz),
        runMeanAccG = prefs.getFloat(KEY_RUN_MEAN_ACC, DogActivityTuning().runMeanAccG),
        runPeakAccG = prefs.getFloat(KEY_RUN_PEAK_ACC, DogActivityTuning().runPeakAccG),
        runMovingShare = prefs.getFloat(KEY_RUN_MOVING_SHARE, DogActivityTuning().runMovingShare),
        runStrongShare = prefs.getFloat(KEY_RUN_STRONG_SHARE, DogActivityTuning().runStrongShare),
        runMeanGyroDps = prefs.getFloat(KEY_RUN_MEAN_GYRO, DogActivityTuning().runMeanGyroDps),
        runCadenceHz = prefs.getFloat(KEY_RUN_CADENCE, DogActivityTuning().runCadenceHz),
        walkHoldMs = prefs.getLong(KEY_WALK_HOLD_MS, DogActivityTuning().walkHoldMs),
        runHoldMs = prefs.getLong(KEY_RUN_HOLD_MS, DogActivityTuning().runHoldMs),
        staticHoldMs = prefs.getLong(KEY_STATIC_HOLD_MS, DogActivityTuning().staticHoldMs),
    )

    private fun saveTuning(tuning: DogActivityTuning) {
        prefs.edit()
            .putLong(KEY_WINDOW_MS, tuning.windowMs)
            .putFloat(KEY_STATIC_MEAN_ACC, tuning.staticMeanAccG)
            .putFloat(KEY_STATIC_STD_ACC, tuning.staticStdAccG)
            .putFloat(KEY_STATIC_MOVING_SHARE, tuning.staticMovingShare)
            .putFloat(KEY_STATIC_MEAN_GYRO, tuning.staticMeanGyroDps)
            .putFloat(KEY_STATIC_CADENCE, tuning.staticCadenceHz)
            .putFloat(KEY_WALK_MEAN_ACC, tuning.walkMeanAccG)
            .putFloat(KEY_WALK_MOVING_SHARE, tuning.walkMovingShare)
            .putFloat(KEY_WALK_MEAN_GYRO, tuning.walkMeanGyroDps)
            .putFloat(KEY_WALK_CADENCE, tuning.walkCadenceHz)
            .putFloat(KEY_RUN_MEAN_ACC, tuning.runMeanAccG)
            .putFloat(KEY_RUN_PEAK_ACC, tuning.runPeakAccG)
            .putFloat(KEY_RUN_MOVING_SHARE, tuning.runMovingShare)
            .putFloat(KEY_RUN_STRONG_SHARE, tuning.runStrongShare)
            .putFloat(KEY_RUN_MEAN_GYRO, tuning.runMeanGyroDps)
            .putFloat(KEY_RUN_CADENCE, tuning.runCadenceHz)
            .putLong(KEY_WALK_HOLD_MS, tuning.walkHoldMs)
            .putLong(KEY_RUN_HOLD_MS, tuning.runHoldMs)
            .putLong(KEY_STATIC_HOLD_MS, tuning.staticHoldMs)
            .apply()
    }

    private companion object {
        const val BOARD_SMOOTHING_STILL_ALPHA = 0.14f
        const val BOARD_SMOOTHING_MOVING_ALPHA = 0.32f
        const val VIEWER_FRAME_INTERVAL_MS = 66L
        const val PREFS_NAME = "imu_activity_tuning"
        const val ACCURACY_SAMPLE_INTERVAL_MS = 1000L
        const val KEY_WINDOW_MS = "window_ms"
        const val KEY_STATIC_MEAN_ACC = "static_mean_acc"
        const val KEY_STATIC_STD_ACC = "static_std_acc"
        const val KEY_STATIC_MOVING_SHARE = "static_moving_share"
        const val KEY_STATIC_MEAN_GYRO = "static_mean_gyro"
        const val KEY_STATIC_CADENCE = "static_cadence"
        const val KEY_WALK_MEAN_ACC = "walk_mean_acc"
        const val KEY_WALK_MOVING_SHARE = "walk_moving_share"
        const val KEY_WALK_MEAN_GYRO = "walk_mean_gyro"
        const val KEY_WALK_CADENCE = "walk_cadence"
        const val KEY_RUN_MEAN_ACC = "run_mean_acc"
        const val KEY_RUN_PEAK_ACC = "run_peak_acc"
        const val KEY_RUN_MOVING_SHARE = "run_moving_share"
        const val KEY_RUN_STRONG_SHARE = "run_strong_share"
        const val KEY_RUN_MEAN_GYRO = "run_mean_gyro"
        const val KEY_RUN_CADENCE = "run_cadence"
        const val KEY_WALK_HOLD_MS = "walk_hold_ms"
        const val KEY_RUN_HOLD_MS = "run_hold_ms"
        const val KEY_STATIC_HOLD_MS = "static_hold_ms"
    }
}

private data class ViewerSnapshot(
    val quaternion: Quaternion = Quaternion.Identity,
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val moving: Boolean = false,
    val biasReady: Boolean = false,
    val latestSample: ImuRawSample? = null,
    val sampleRateHz: Float = 0f,
    val activity: DogActivityClass = DogActivityClass.STATIC,
    val activityLabel: String = "Waiting",
    val activityConfidence: Int = 0,
    val activitySource: String = "--",
    val activityDebug: DogActivityDebugInfo = DogActivityDebugInfo(),
)
