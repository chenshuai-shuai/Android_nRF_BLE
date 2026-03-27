package no.nordicsemi.android.blinky.ui.calibration.viewmodel

import no.nordicsemi.android.blinky.spec.ImuRawSample
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

internal data class Quaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun normalized(): Quaternion {
        val norm = sqrt((w * w + x * x + y * y + z * z).toDouble()).toFloat()
        if (norm <= 0f) return Identity
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    fun dot(other: Quaternion): Float =
        w * other.w + x * other.x + y * other.y + z * other.z

    fun slerpTo(target: Quaternion, alpha: Float): Quaternion {
        val t = alpha.coerceIn(0f, 1f)
        var end = target
        var cosTheta = dot(target)

        if (cosTheta < 0f) {
            end = Quaternion(-target.w, -target.x, -target.y, -target.z)
            cosTheta = -cosTheta
        }

        if (cosTheta > 0.9995f) {
            return Quaternion(
                w = w + t * (end.w - w),
                x = x + t * (end.x - x),
                y = y + t * (end.y - y),
                z = z + t * (end.z - z),
            ).normalized()
        }

        val theta = kotlin.math.acos(cosTheta.toDouble()).toFloat()
        val sinTheta = sin(theta)
        if (sinTheta <= 1e-6f) {
            return end.normalized()
        }

        val weightA = sin((1f - t) * theta) / sinTheta
        val weightB = sin(t * theta) / sinTheta
        return Quaternion(
            w = weightA * w + weightB * end.w,
            x = weightA * x + weightB * end.x,
            y = weightA * y + weightB * end.y,
            z = weightA * z + weightB * end.z,
        ).normalized()
    }

    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun rotate(v: Vec3): Vec3 {
        val qv = Quaternion(0f, v.x, v.y, v.z)
        val qr = this * qv * conjugate()
        return Vec3(qr.x, qr.y, qr.z)
    }

    fun toEulerDegrees(): Triple<Float, Float, Float> {
        val roll = atan2(
            2f * (w * x + y * z),
            1f - 2f * (x * x + y * y),
        ) * RAD_TO_DEG

        val pitch = asin((2f * (w * y - z * x)).coerceIn(-1f, 1f)) * RAD_TO_DEG

        val yaw = atan2(
            2f * (w * z + x * y),
            1f - 2f * (y * y + z * z),
        ) * RAD_TO_DEG

        return Triple(roll, pitch, yaw)
    }

    companion object {
        val Identity = Quaternion(1f, 0f, 0f, 0f)
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    }
}

internal data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun normalized(): Vec3 {
        val norm = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        if (norm <= 0f) return Vec3(0f, 0f, 1f)
        return Vec3(x / norm, y / norm, z / norm)
    }
}

internal data class OrientationSnapshot(
    val displayQuaternion: Quaternion,
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val moving: Boolean,
    val biasReady: Boolean,
)

internal class ImuOrientationEstimator {
    private var orientation = Quaternion.Identity
    private var reference = Quaternion.Identity
    private var lastTimestampMs: Long? = null

    private val gyroBiasAccum = FloatArray(3)
    private var gyroBiasCount = 0
    private val gyroBiasDps = FloatArray(3)
    private var biasReady = false

    fun resetReference() {
        reference = orientation
    }

    fun resetAll() {
        orientation = Quaternion.Identity
        reference = Quaternion.Identity
        lastTimestampMs = null
        gyroBiasAccum.fill(0f)
        gyroBiasDps.fill(0f)
        gyroBiasCount = 0
        biasReady = false
    }

    fun onSample(sample: ImuRawSample): OrientationSnapshot {
        val nowMs = if (sample.deviceTimestampMs > 0L) sample.deviceTimestampMs else sample.receivedAtMs
        val dt = ((nowMs - (lastTimestampMs ?: nowMs)).coerceIn(0L, 100L)).toFloat() / 1000f
        lastTimestampMs = nowMs

        val acc = Vec3(
            x = sample.ax / ACC_LSB_PER_G,
            y = sample.ay / ACC_LSB_PER_G,
            z = sample.az / ACC_LSB_PER_G,
        )
        val gyroDps = floatArrayOf(
            sample.gx / GYRO_LSB_PER_DPS,
            sample.gy / GYRO_LSB_PER_DPS,
            sample.gz / GYRO_LSB_PER_DPS,
        )

        val moving = isMoving(acc, gyroDps)
        if (!biasReady && !moving) {
            gyroBiasAccum[0] += gyroDps[0]
            gyroBiasAccum[1] += gyroDps[1]
            gyroBiasAccum[2] += gyroDps[2]
            gyroBiasCount += 1
            if (gyroBiasCount >= BIAS_SAMPLES_REQUIRED) {
                gyroBiasDps[0] = gyroBiasAccum[0] / gyroBiasCount
                gyroBiasDps[1] = gyroBiasAccum[1] / gyroBiasCount
                gyroBiasDps[2] = gyroBiasAccum[2] / gyroBiasCount
                biasReady = true
            }
        }

        if (dt > 0f) {
            updateOrientation(acc, gyroDps, dt)
        }

        val display = (reference.conjugate() * orientation).normalized()
        val (roll, pitch, yaw) = display.toEulerDegrees()
        return OrientationSnapshot(
            displayQuaternion = display,
            rollDeg = roll,
            pitchDeg = pitch,
            yawDeg = yaw,
            moving = moving,
            biasReady = biasReady,
        )
    }

    private fun updateOrientation(acc: Vec3, gyroDps: FloatArray, dt: Float) {
        var q0 = orientation.w
        var q1 = orientation.x
        var q2 = orientation.y
        var q3 = orientation.z

        var gx = (gyroDps[0] - gyroBiasDps[0]) * DEG_TO_RAD
        var gy = (gyroDps[1] - gyroBiasDps[1]) * DEG_TO_RAD
        var gz = (gyroDps[2] - gyroBiasDps[2]) * DEG_TO_RAD

        var ax = acc.x
        var ay = acc.y
        var az = acc.z

        val accNorm = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        if (accNorm > 0f) {
            ax /= accNorm
            ay /= accNorm
            az /= accNorm

            val twoQ0 = 2f * q0
            val twoQ1 = 2f * q1
            val twoQ2 = 2f * q2
            val twoQ3 = 2f * q3
            val fourQ0 = 4f * q0
            val fourQ1 = 4f * q1
            val fourQ2 = 4f * q2
            val eightQ1 = 8f * q1
            val eightQ2 = 8f * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            var s0 = fourQ0 * q2q2 + twoQ2 * ax + fourQ0 * q1q1 - twoQ1 * ay
            var s1 = fourQ1 * q3q3 - twoQ3 * ax + 4f * q0q0 * q1 - twoQ0 * ay - fourQ1 +
                eightQ1 * q1q1 + eightQ1 * q2q2 + fourQ1 * az
            var s2 = 4f * q0q0 * q2 + twoQ0 * ax + fourQ2 * q3q3 - twoQ3 * ay - fourQ2 +
                eightQ2 * q1q1 + eightQ2 * q2q2 + fourQ2 * az
            var s3 = 4f * q1q1 * q3 - twoQ1 * ax + 4f * q2q2 * q3 - twoQ2 * ay

            val sNorm = sqrt((s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3).toDouble()).toFloat()
            if (sNorm > 0f) {
                s0 /= sNorm
                s1 /= sNorm
                s2 /= sNorm
                s3 /= sNorm

                val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - BETA * s0
                val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - BETA * s1
                val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - BETA * s2
                val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - BETA * s3

                q0 += qDot0 * dt
                q1 += qDot1 * dt
                q2 += qDot2 * dt
                q3 += qDot3 * dt
                orientation = Quaternion(q0, q1, q2, q3).normalized()
                return
            }
        }

        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        val qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
        val qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
        val qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

        orientation = Quaternion(
            w = q0 + qDot0 * dt,
            x = q1 + qDot1 * dt,
            y = q2 + qDot2 * dt,
            z = q3 + qDot3 * dt,
        ).normalized()
    }

    private fun isMoving(acc: Vec3, gyroDps: FloatArray): Boolean {
        val gyroMagnitude = abs(gyroDps[0]) + abs(gyroDps[1]) + abs(gyroDps[2])
        val accNorm = sqrt((acc.x * acc.x + acc.y * acc.y + acc.z * acc.z).toDouble()).toFloat()
        return gyroMagnitude > 4.5f || abs(accNorm - 1f) > 0.12f
    }

    private companion object {
        const val ACC_LSB_PER_G = 2048f
        const val GYRO_LSB_PER_DPS = 16.4f
        const val BIAS_SAMPLES_REQUIRED = 80
        const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
        const val BETA = 0.12f
    }
}
