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
     * gRPC connection state.
     */
    val grpcState: StateFlow<String>

    /**
     * Last gRPC message (summary).
     */
    val grpcLastMessage: StateFlow<String?>

    /**
     * Latest phone GPS sample.
     */
    val gpsData: StateFlow<GpsData?>

    /**
     * Phone GPS state.
     */
    val gpsState: StateFlow<GpsState>

    /**
     * Re-check GPS permission/settings and restart location updates if possible.
     */
    fun refreshGps()

    /**
     * Conversation state for phone microphone gRPC sessions.
     */
    val conversationState: StateFlow<ConversationState>

    /**
     * Current session ID (null when idle).
     */
    val conversationSessionId: StateFlow<String?>

    /**
     * Remaining seconds while waiting for response (0 when not waiting).
     */
    val waitingResponseSeconds: StateFlow<Long>

    /**
     * Session ready signal after server accepts first audio.
     */
    val conversationSessionReady: StateFlow<Boolean>

    /**
     * Whether the automatic realtime conversation service is enabled.
     */
    val realtimeServiceEnabled: StateFlow<Boolean>

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

    /**
     * Start a new conversation session (generates a new session ID).
     */
    suspend fun startConversation()

    /**
     * Start automatic realtime conversation service.
     */
    suspend fun startRealtimeService()

    /**
     * Begin sending microphone audio to the active session.
     */
    suspend fun startTalking()

    /**
     * Stop sending microphone audio and wait for server response.
     */
    suspend fun stopTalking()

    /**
     * End the current conversation session and close gRPC.
     */
    suspend fun endConversation()

    /**
     * Stop automatic realtime conversation service.
     */
    suspend fun stopRealtimeService()
}
