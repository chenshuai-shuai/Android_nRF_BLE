package no.nordicsemi.android.blinky.ble

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class DataLogger(
    private val context: Context,
) {
    private companion object {
        private const val MAX_QUEUED_LINES = 4000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ConcurrentLinkedQueue<String>()
    private val queuedLines = AtomicInteger(0)
    private var writerJob: Job? = null
    private var onSaved: ((String) -> Unit)? = null
    @Volatile private var enabled: Boolean = false

    fun setOnSaved(cb: ((String) -> Unit)?) {
        onSaved = cb
    }

    fun start() {
        enabled = true
        if (writerJob?.isActive == true) return
        writerJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                flushMinute()
            }
        }
    }

    fun stop() {
        if (!enabled && writerJob == null) {
            queue.clear()
            queuedLines.set(0)
            return
        }
        enabled = false
        writerJob?.cancel()
        writerJob = null
        flushMinute()
    }

    fun append(line: String) {
        if (!enabled) return
        queue.add(line)
        val size = queuedLines.incrementAndGet()
        trimQueue(size)
    }

    fun appendSnapshot(line: String) {
        if (!enabled) return
        // Always include at least one line for each sensor in a file if available.
        queue.add(line)
        val size = queuedLines.incrementAndGet()
        trimQueue(size)
    }

    private fun flushMinute() {
        val lines = ArrayList<String>()
        while (true) {
            val s = queue.poll() ?: break
            queuedLines.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
            lines.add(s)
        }
        if (lines.isEmpty()) return
        val content = buildString {
            for (l in lines) {
                append(l)
                append('\n')
            }
        }
        writeToDocuments(content)
    }

    private fun writeToDocuments(content: String) {
        val ts = isoUtcNow()
        val fileName = "nrf52840_ble_$ts.txt"
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/nrf52840_ble_logs"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"),
                    values
                ) ?: return
                context.contentResolver.openOutputStream(uri, "w")?.use { os ->
                    os.write(content.toByteArray())
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "nrf52840_ble_logs"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeText(content)
            }
            val msg = "Saved ${content.lines().size} lines -> $relPath/$fileName"
            Timber.tag("DataLogger").i(msg)
            onSaved?.invoke(msg)
        } catch (t: Throwable) {
            Timber.tag("DataLogger").w("save failed: %s", t.message ?: "unknown")
        }
    }

    private fun trimQueue(currentSize: Int) {
        if (currentSize <= MAX_QUEUED_LINES) return
        while (queuedLines.get() > MAX_QUEUED_LINES) {
            queue.poll() ?: break
            queuedLines.updateAndGet { cur -> if (cur > 0) cur - 1 else 0 }
        }
    }

    private fun isoUtcNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
