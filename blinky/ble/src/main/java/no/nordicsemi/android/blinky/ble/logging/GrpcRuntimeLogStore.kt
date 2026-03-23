package no.nordicsemi.android.blinky.ble.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class GrpcRuntimeLogLine(
    val timestamp: String,
    val priority: Int,
    val tag: String,
    val message: String,
)

object GrpcRuntimeLogStore {
    private const val MAX_LINES = 400

    private val lock = Any()
    private val buffer = ArrayDeque<GrpcRuntimeLogLine>(MAX_LINES)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<GrpcRuntimeLogLine>>(emptyList())

    val lines: StateFlow<List<GrpcRuntimeLogLine>> = _lines.asStateFlow()

    fun append(priority: Int, tag: String, message: String) {
        val line = GrpcRuntimeLogLine(
            timestamp = dateFormat.format(Date()),
            priority = priority,
            tag = tag,
            message = message,
        )
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }
}
