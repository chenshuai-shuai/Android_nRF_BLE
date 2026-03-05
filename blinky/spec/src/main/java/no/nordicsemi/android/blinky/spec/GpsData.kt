package no.nordicsemi.android.blinky.spec

data class GpsData(
    val lat: Double,
    val lon: Double,
    val altM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    val provider: String?,
    val timestampMs: Long,
)

