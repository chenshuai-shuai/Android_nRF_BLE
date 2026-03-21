package no.nordicsemi.android.blinky.ble

class SpeexResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val channels: Int = 1,
    private val quality: Int = 4
) {
    private val handle: Long

    init {
        System.loadLibrary("speex_jni")
        handle = nativeCreate(inputRate, outputRate, channels, quality)
    }

    fun close() {
        nativeDestroy(handle)
    }

    fun reset() {
        nativeReset(handle)
    }

    fun resamplePcm16le(pcm: ByteArray): ByteArray {
        if (pcm.size < 2 || (pcm.size and 1) != 0 || handle == 0L) return pcm
        val inSamples = pcm.size / 2
        val input = ShortArray(inSamples)
        var inIdx = 0
        var off = 0
        while (off + 1 < pcm.size) {
            val lo = pcm[off].toInt() and 0xFF
            val hi = pcm[off + 1].toInt()
            input[inIdx++] = (((hi shl 8) or lo).toShort())
            off += 2
        }

        val estimate = ((inSamples.toLong() * outputRate + inputRate - 1L) / inputRate).toInt()
        val output = ShortArray((estimate + 32).coerceAtLeast(estimate))
        val produced = nativeProcess(handle, input, inSamples, output, output.size)
        if (produced <= 0) return ByteArray(0)

        val out = ByteArray(produced * 2)
        var outOff = 0
        for (i in 0 until produced) {
            val s = output[i].toInt()
            out[outOff++] = (s and 0xFF).toByte()
            out[outOff++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private external fun nativeCreate(inputRate: Int, outputRate: Int, channels: Int, quality: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeProcess(
        handle: Long,
        input: ShortArray,
        inputSamples: Int,
        output: ShortArray,
        outputCapacity: Int
    ): Int
}
