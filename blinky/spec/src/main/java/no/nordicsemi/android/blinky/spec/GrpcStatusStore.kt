package no.nordicsemi.android.blinky.spec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GrpcStatusStore {
    private val _state = MutableStateFlow("DISCONNECTED")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private val _droppedPackets = MutableStateFlow(0)
    val droppedPackets: StateFlow<Int> = _droppedPackets.asStateFlow()

    fun setState(value: String) {
        _state.value = value
    }

    fun setLastMessage(value: String?) {
        _lastMessage.value = value
    }

    fun incrementDropped() {
        _droppedPackets.value = _droppedPackets.value + 1
    }
}
