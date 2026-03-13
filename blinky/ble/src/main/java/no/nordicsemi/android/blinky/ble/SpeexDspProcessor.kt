package no.nordicsemi.android.blinky.ble

class SpeexDspProcessor(
    private val sampleRate: Int,
    private val frameSize: Int
) {
    private val handle: Long
    private val frameBuf = ShortArray(frameSize)
    private var pending = 0

    init {
        System.loadLibrary("speex_jni")
        handle = nativeCreate(sampleRate, frameSize)
    }

    fun close() {
        nativeDestroy(handle)
    }

    fun processPcm16le(pcm: ByteArray): ByteArray {
        if (pcm.isEmpty()) return pcm
        val out = ByteArray(pcm.size)
        var outIdx = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            var v = (hi shl 8) or lo
            if (v and 0x8000 != 0) v -= 0x10000
            frameBuf[pending++] = v.toShort()
            if (pending == frameSize) {
                nativeProcess(handle, frameBuf)
                var j = 0
                while (j < frameSize && outIdx + 1 < out.size) {
                    val s = frameBuf[j].toInt()
                    out[outIdx++] = (s and 0xFF).toByte()
                    out[outIdx++] = ((s shr 8) and 0xFF).toByte()
                    j++
                }
                pending = 0
            }
            i += 2
        }

        if (pending > 0) {
            // Pad remaining samples with zeros to avoid length mismatch.
            for (k in pending until frameSize) {
                frameBuf[k] = 0
            }
            nativeProcess(handle, frameBuf)
            var j = 0
            while (j < pending && outIdx + 1 < out.size) {
                val s = frameBuf[j].toInt()
                out[outIdx++] = (s and 0xFF).toByte()
                out[outIdx++] = ((s shr 8) and 0xFF).toByte()
                j++
            }
            pending = 0
        }

        return out
    }

    private external fun nativeCreate(sampleRate: Int, frameSize: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, frame: ShortArray)
}
