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
     * Latest raw IMU sample received from nRF.
     */
    val imuRawSample: StateFlow<ImuRawSample?>

    /**
     * Latest fused attitude sample received from nRF.
     */
    val attitudeSample: StateFlow<AttitudeSample?>

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
     * Whether sensor log saving to local storage is enabled.
     */
    val sensorLogging: StateFlow<Boolean>

    /**
     * Last sensor log save status.
     */
    val sensorLogStatus: StateFlow<String?>

    /**
     * IMU calibration state reported by nRF.
     */
    val imuCalibrationState: StateFlow<String>

    /**
     * Current IMU calibration step.
     */
    val imuCalibrationStep: StateFlow<String?>

    /**
     * Current IMU calibration hint.
     */
    val imuCalibrationHint: StateFlow<String?>

    /**
     * Latest IMU calibration result.
     */
    val imuCalibrationResult: StateFlow<String?>

    /**
     * IMU calibration blob status.
     */
    val imuCalibrationBlobStatus: StateFlow<String?>

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
     * Start manual sensor log saving.
     */
    suspend fun startSensorLogging()

    /**
     * Stop manual sensor log saving and flush buffered sensor lines.
     */
    suspend fun stopSensorLogging()

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

    /**
     * Enter exclusive IMU calibration mode.
     */
    suspend fun enterImuCalibration()

    /**
     * Exit IMU calibration mode.
     */
    suspend fun exitImuCalibration()

    /**
     * Start gyroscope bias calibration.
     */
    suspend fun startImuGyroCalibration()

    /**
     * Start accelerometer six-position calibration.
     */
    suspend fun startImuAccelCalibration()

    /**
     * Save IMU calibration to nRF flash.
     */
    suspend fun saveImuCalibration()

    /**
     * Abort the current IMU calibration session.
     */
    suspend fun abortImuCalibration()
}
