package no.nordicsemi.android.blinky.spec

data class ImuRawSample(
    val seq: Int,
    val ax: Int,
    val ay: Int,
    val az: Int,
    val gx: Int,
    val gy: Int,
    val gz: Int,
    val tempCenti: Int,
    val deviceTimestampMs: Long,
    val receivedAtMs: Long,
)
