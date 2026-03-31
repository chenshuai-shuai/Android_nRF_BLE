package no.nordicsemi.android.blinky.spec

data class ImuMotionSample(
    val seq: Int,
    val action: Int,
    val confidence: Int,
    val receivedAtMs: Long,
)
