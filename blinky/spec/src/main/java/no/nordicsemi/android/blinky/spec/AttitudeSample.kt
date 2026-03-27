package no.nordicsemi.android.blinky.spec

data class AttitudeSample(
    val seq: Int,
    val qwQ30: Int,
    val qxQ30: Int,
    val qyQ30: Int,
    val qzQ30: Int,
    val gravityXQ16: Int,
    val gravityYQ16: Int,
    val gravityZQ16: Int,
    val linearAccXQ16: Int,
    val linearAccYQ16: Int,
    val linearAccZQ16: Int,
    val accAccuracy: Int,
    val gyrAccuracy: Int,
    val magAccuracy: Int,
    val moving: Boolean,
    val biasReady: Boolean,
    val deviceTimestampMs: Long,
    val receivedAtMs: Long,
)
