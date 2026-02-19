package no.nordicsemi.android.blinky.spec

data class AudioStats(
    val packets: Long = 0,
    val bytes: Long = 0,
    val frames: Long = 0,
    val droppedFrames: Long = 0,
    val lastSeq: Int = -1,
)
