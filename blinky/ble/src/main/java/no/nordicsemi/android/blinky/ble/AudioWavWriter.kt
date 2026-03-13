package no.nordicsemi.android.blinky.ble

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AudioWavWriter {
    data class Result(
        val displayPath: String,
    )

    fun writePcm16leToWav(
        context: Context,
        pcm16le: ByteArray,
        sampleRate: Int,
        channels: Int,
        prefix: String = "nrf_audio",
    ): Result? {
        if (pcm16le.isEmpty() || sampleRate <= 0 || channels <= 0) return null

        val ts = isoUtcNow()
        val fileName = "${prefix}_${ts}.wav"
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/nrf52840_ble_audio"

        val wavBytes = buildWav(pcm16le, sampleRate, channels) ?: return null

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                ) ?: return null
                context.contentResolver.openOutputStream(uri, "w")?.use { os ->
                    os.write(wavBytes)
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "nrf52840_ble_audio"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeBytes(wavBytes)
            }
            Result("$relPath/$fileName")
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildWav(pcm16le: ByteArray, sampleRate: Int, channels: Int): ByteArray? {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val dataSize = pcm16le.size
        val riffSize = 36 + dataSize

        val header = ByteArrayOutputStream(44)
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(riffSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16) // PCM header size
        bb.putShort(1) // PCM format
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(16) // bits per sample
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        header.write(bb.array())

        val out = ByteArrayOutputStream(header.size() + dataSize)
        out.write(header.toByteArray())
        out.write(pcm16le)
        return out.toByteArray()
    }

    private fun isoUtcNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
