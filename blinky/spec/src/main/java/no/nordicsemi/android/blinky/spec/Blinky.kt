package no.nordicsemi.android.blinky.spec

import kotlinx.coroutines.flow.StateFlow

interface Blinky {

    enum class State {
        LOADING,
        READY,
        NOT_AVAILABLE
    }

    /**
     * Connects to the device.
     */
    suspend fun connect()

    /**
     * Disconnects from the device.
     */
    fun release()

    /**
     * The current state of the blinky.
     */
    val state: StateFlow<State>

    /**
     * The current state of the LED.
     */
    val ledState: StateFlow<Boolean>

    /**
     * The current state of the button.
     */
    val buttonState: StateFlow<Boolean>

    /**
     * Received messages from device.
     */
    val rxMessages: StateFlow<List<String>>

    /**
     * Audio stream statistics.
     */
    val audioStats: StateFlow<AudioStats>

    /**
     * Recording state.
     */
    val recording: StateFlow<Boolean>

    /**
     * Last saved recording path.
     */
    val lastSavedPath: StateFlow<String?>

    /**
     * Controls the LED state.
     *
     * @param state the new state of the LED.
     */
    suspend fun turnLed(state: Boolean)

    /**
     * Sends a UTF-8 message to the device.
     *
     * @param text message to send.
     */
    suspend fun sendMessage(text: String)

    /**
     * Start audio recording.
     */
    suspend fun startRecording()

    /**
     * Stop audio recording and save to WAV file.
     */
    suspend fun stopRecording()
}
