package no.nordicsemi.android.blinky.ui.calibration.viewmodel

import no.nordicsemi.android.blinky.spec.AttitudeSample
import no.nordicsemi.android.blinky.spec.ImuRawSample
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class DogActivityClass {
    STATIC,
    WALK,
    RUN,
}

internal data class DogActivityEstimate(
    val activity: DogActivityClass,
    val confidence: Int,
    val source: String,
) {
    val label: String
        get() = when (activity) {
            DogActivityClass.STATIC -> "静止"
            DogActivityClass.WALK -> "走路"
            DogActivityClass.RUN -> "跑步"
        }
}

internal data class DogActivityTuning(
    val windowMs: Long = 2600L,
    val staticMeanAccG: Float = 0.070f,
    val staticStdAccG: Float = 0.040f,
    val staticMovingShare: Float = 0.18f,
    val staticMeanGyroDps: Float = 18f,
    val staticCadenceHz: Float = 0.90f,
    val walkMeanAccG: Float = 0.115f,
    val walkMovingShare: Float = 0.26f,
    val walkMeanGyroDps: Float = 28f,
    val walkCadenceHz: Float = 1.10f,
    val runMeanAccG: Float = 0.210f,
    val runPeakAccG: Float = 0.38f,
    val runMovingShare: Float = 0.45f,
    val runStrongShare: Float = 0.22f,
    val runMeanGyroDps: Float = 65f,
    val runCadenceHz: Float = 1.80f,
    val walkHoldMs: Long = 900L,
    val runHoldMs: Long = 700L,
    val staticHoldMs: Long = 1200L,
)

internal data class DogActivityDebugInfo(
    val meanAccG: Float = 0f,
    val stdAccG: Float = 0f,
    val peakAccG: Float = 0f,
    val meanGyroDps: Float = 0f,
    val peakGyroDps: Float = 0f,
    val movingShare: Float = 0f,
    val strongShare: Float = 0f,
    val cadenceHz: Float = 0f,
    val rawClass: DogActivityClass = DogActivityClass.STATIC,
    val stableClass: DogActivityClass = DogActivityClass.STATIC,
    val sampleCount: Int = 0,
)

internal data class DogActivityDecision(
    val estimate: DogActivityEstimate,
    val debug: DogActivityDebugInfo,
)

internal class DogActivityClassifier(
    initialTuning: DogActivityTuning = DogActivityTuning(),
) {
    private val window = ArrayDeque<ActivityWindowSample>()
    private var tuning = initialTuning
    private var stableClass = DogActivityClass.STATIC
    private var candidateClass: DogActivityClass? = null
    private var candidateSinceMs = 0L

    fun reset() {
        window.clear()
        stableClass = DogActivityClass.STATIC
        candidateClass = null
        candidateSinceMs = 0L
    }

    fun setTuning(newTuning: DogActivityTuning) {
        tuning = newTuning
        trimWindow(window.lastOrNull()?.tsMs ?: 0L)
    }

    fun onRawSample(
        sample: ImuRawSample,
        latestAttitude: AttitudeSample?,
    ): DogActivityDecision {
        val nowMs = if (sample.deviceTimestampMs > 0L) sample.deviceTimestampMs else sample.receivedAtMs
        val dynamicAccG = latestAttitude
            ?.takeIf { abs(it.receivedAtMs - sample.receivedAtMs) <= ATTITUDE_FRESH_MS }
            ?.let { attitude ->
                magnitude(
                    attitude.linearAccXQ16 / Q16_PER_G,
                    attitude.linearAccYQ16 / Q16_PER_G,
                    attitude.linearAccZQ16 / Q16_PER_G,
                )
            }
            ?: abs(magnitude(sample.ax / ACC_LSB_PER_G, sample.ay / ACC_LSB_PER_G, sample.az / ACC_LSB_PER_G) - 1f)

        val gyroDps = magnitude(sample.gx / GYRO_LSB_PER_DPS, sample.gy / GYRO_LSB_PER_DPS, sample.gz / GYRO_LSB_PER_DPS)
        window.addLast(
            ActivityWindowSample(
                tsMs = nowMs,
                dynamicAccG = dynamicAccG,
                gyroDps = gyroDps,
            ),
        )
        trimWindow(nowMs)

        if (window.size < MIN_WINDOW_SAMPLES) {
            val debug = DogActivityDebugInfo(sampleCount = window.size)
            return DogActivityDecision(
                estimate = DogActivityEstimate(
                    activity = stableClass,
                    confidence = 0,
                    source = "app",
                ),
                debug = debug,
            )
        }

        val features = extractFeatures()
        val rawClass = classifyFromFeatures(features)
        stableClass = updateStableClass(rawClass, nowMs)
        val confidence = confidenceForClass(rawClass, features)

        return DogActivityDecision(
            estimate = DogActivityEstimate(
                activity = stableClass,
                confidence = confidence,
                source = "app",
            ),
            debug = DogActivityDebugInfo(
                meanAccG = features.meanAccG,
                stdAccG = features.stdAccG,
                peakAccG = features.peakAccG,
                meanGyroDps = features.meanGyroDps,
                peakGyroDps = features.peakGyroDps,
                movingShare = features.movingShare,
                strongShare = features.strongShare,
                cadenceHz = features.cadenceHz,
                rawClass = rawClass,
                stableClass = stableClass,
                sampleCount = window.size,
            ),
        )
    }

    private fun extractFeatures(): WindowFeatures {
        val values = window.toList()
        var sumAcc = 0f
        var sumAccSq = 0f
        var peakAcc = 0f
        var sumGyro = 0f
        var peakGyro = 0f
        var movingCount = 0
        var strongCount = 0
        var cadencePeaks = 0
        var lastPeakTs = Long.MIN_VALUE

        values.forEachIndexed { index, sample ->
            val acc = sample.dynamicAccG
            val gyro = sample.gyroDps
            sumAcc += acc
            sumAccSq += acc * acc
            peakAcc = maxOf(peakAcc, acc)
            sumGyro += gyro
            peakGyro = maxOf(peakGyro, gyro)
            if (acc >= tuning.walkMeanAccG) {
                movingCount += 1
            }
            if (acc >= tuning.runPeakAccG * 0.55f) {
                strongCount += 1
            }

            if (index in 1 until values.lastIndex) {
                val prev = values[index - 1]
                val next = values[index + 1]
                if (acc > prev.dynamicAccG &&
                    acc >= next.dynamicAccG &&
                    acc >= tuning.walkMeanAccG &&
                    (sample.tsMs - lastPeakTs) >= PEAK_MIN_SEPARATION_MS
                ) {
                    cadencePeaks += 1
                    lastPeakTs = sample.tsMs
                }
            }
        }

        val size = values.size.toFloat()
        val meanAcc = sumAcc / size
        val variance = (sumAccSq / size) - meanAcc * meanAcc
        val stdAcc = sqrt(maxOf(0f, variance))
        val meanGyro = sumGyro / size
        val durationMs = (values.last().tsMs - values.first().tsMs).coerceAtLeast(1L)
        val cadenceHz = cadencePeaks * 1000f / durationMs.toFloat()

        return WindowFeatures(
            meanAccG = meanAcc,
            stdAccG = stdAcc,
            peakAccG = peakAcc,
            meanGyroDps = meanGyro,
            peakGyroDps = peakGyro,
            movingShare = movingCount / size,
            strongShare = strongCount / size,
            cadenceHz = cadenceHz,
        )
    }

    private fun classifyFromFeatures(features: WindowFeatures): DogActivityClass {
        val staticLike = features.meanAccG <= tuning.staticMeanAccG &&
            features.stdAccG <= tuning.staticStdAccG &&
            features.movingShare <= tuning.staticMovingShare &&
            features.meanGyroDps <= tuning.staticMeanGyroDps &&
            features.cadenceHz <= tuning.staticCadenceHz

        if (staticLike) {
            return DogActivityClass.STATIC
        }

        val runLike = features.meanAccG >= tuning.runMeanAccG ||
            features.peakAccG >= tuning.runPeakAccG ||
            (features.meanGyroDps >= tuning.runMeanGyroDps &&
                features.movingShare >= tuning.runMovingShare) ||
            (features.cadenceHz >= tuning.runCadenceHz &&
                features.strongShare >= tuning.runStrongShare)

        if (runLike) {
            return DogActivityClass.RUN
        }

        val walkLike = features.meanAccG >= tuning.walkMeanAccG ||
            features.movingShare >= tuning.walkMovingShare ||
            features.meanGyroDps >= tuning.walkMeanGyroDps ||
            features.cadenceHz >= tuning.walkCadenceHz

        return if (walkLike) DogActivityClass.WALK else DogActivityClass.STATIC
    }

    private fun updateStableClass(
        rawClass: DogActivityClass,
        nowMs: Long,
    ): DogActivityClass {
        if (rawClass == stableClass) {
            candidateClass = null
            candidateSinceMs = 0L
            return stableClass
        }

        if (candidateClass != rawClass) {
            candidateClass = rawClass
            candidateSinceMs = nowMs
            return stableClass
        }

        if ((nowMs - candidateSinceMs) >= holdMsFor(rawClass)) {
            stableClass = rawClass
            candidateClass = null
            candidateSinceMs = 0L
        }
        return stableClass
    }

    private fun holdMsFor(activity: DogActivityClass): Long = when (activity) {
        DogActivityClass.STATIC -> tuning.staticHoldMs
        DogActivityClass.WALK -> tuning.walkHoldMs
        DogActivityClass.RUN -> tuning.runHoldMs
    }

    private fun confidenceForClass(
        activity: DogActivityClass,
        features: WindowFeatures,
    ): Int {
        val score = when (activity) {
            DogActivityClass.STATIC -> minOf(
                1f,
                (
                    (tuning.staticMeanAccG - features.meanAccG).coerceAtLeast(0f) / tuning.staticMeanAccG +
                        (tuning.staticStdAccG - features.stdAccG).coerceAtLeast(0f) / tuning.staticStdAccG +
                        (tuning.staticMeanGyroDps - features.meanGyroDps).coerceAtLeast(0f) / tuning.staticMeanGyroDps
                    ) / 3f,
            )

            DogActivityClass.WALK -> minOf(
                1f,
                maxOf(
                    features.meanAccG / tuning.walkMeanAccG,
                    features.movingShare / tuning.walkMovingShare,
                    features.cadenceHz / tuning.walkCadenceHz,
                ) / 1.6f,
            )

            DogActivityClass.RUN -> minOf(
                1f,
                maxOf(
                    features.meanAccG / tuning.runMeanAccG,
                    features.peakAccG / tuning.runPeakAccG,
                    features.cadenceHz / tuning.runCadenceHz,
                    features.meanGyroDps / tuning.runMeanGyroDps,
                ) / 1.8f,
            )
        }
        return (score * 100f).roundToInt().coerceIn(5, 99)
    }

    private fun trimWindow(nowMs: Long) {
        val minTs = nowMs - tuning.windowMs
        while (window.size > 1 && window.first().tsMs < minTs) {
            window.removeFirst()
        }
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float =
        sqrt(x * x + y * y + z * z)

    private data class ActivityWindowSample(
        val tsMs: Long,
        val dynamicAccG: Float,
        val gyroDps: Float,
    )

    private data class WindowFeatures(
        val meanAccG: Float,
        val stdAccG: Float,
        val peakAccG: Float,
        val meanGyroDps: Float,
        val peakGyroDps: Float,
        val movingShare: Float,
        val strongShare: Float,
        val cadenceHz: Float,
    )

    private companion object {
        const val ACC_LSB_PER_G = 2048f
        const val GYRO_LSB_PER_DPS = 16.4f
        const val Q16_PER_G = 65536f
        const val ATTITUDE_FRESH_MS = 250L
        const val MIN_WINDOW_SAMPLES = 10
        const val PEAK_MIN_SEPARATION_MS = 260L
    }
}
