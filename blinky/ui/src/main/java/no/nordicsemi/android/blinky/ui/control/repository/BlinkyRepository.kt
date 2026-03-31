package no.nordicsemi.android.blinky.ui.control.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.log.ILogSession
import no.nordicsemi.android.log.LogContract
import no.nordicsemi.android.log.timber.nRFLoggerTree
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named

/**
 *
 * @param context The application context.
 * @param deviceId The device ID.
 * @param deviceName The name of the Blinky device, as advertised.
 * @property blinky The Blinky implementation.
 */
class BlinkyRepository @Inject constructor(
    @ApplicationContext context: Context,
    @Named("deviceId") deviceId: String,
    @Named("deviceName") deviceName: String,
    private val blinky: Blinky,
): Blinky by blinky {
    /** Timber tree that logs to nRF Logger. */
    private val tree: Timber.Tree

    /** If the nRF Logger is installed, this will allow to open the session. */
    internal val logSession: ILogSession?

    init {
        // Plant a new Tree that logs to nRF Logger.
        tree = nRFLoggerTree(context, null, deviceId, deviceName)
            .also { Timber.plant(it) }
            .also { logSession = it.session }
    }

    val loggedLedState: Flow<Boolean>
        get() = blinky.ledState.onEach {
            // Although Timber log levels are the same as LogCat's, nRF Logger has its own.
            // All standard log levels are mapped to the corresponding nRF Logger's levels:
            // https://github.com/NordicSemiconductor/nRF-Logger-API/blob/f90d5834c46cc2057b6a9f39dcbb8f2f2dd45d56/log-timber/src/main/java/no/nordicsemi/android/log/timber/nRFLoggerTree.java#L104
            // However, in order to log in nRF Logger on APPLICATION level, we need to use
            // that level explicitly.
            when(it) {
                true -> Timber.log(LogContract.Log.Level.APPLICATION, "LED turned ON")
                false -> Timber.log(LogContract.Log.Level.APPLICATION, "LED turned OFF")
            }
        }

    val loggedButtonState: Flow<Boolean>
        get() = blinky.buttonState.onEach {
            when(it) {
                true -> Timber.log(LogContract.Log.Level.APPLICATION, "Button pressed")
                false -> Timber.log(LogContract.Log.Level.APPLICATION, "Button released")
            }
        }

    override val rxMessages: StateFlow<List<String>>
        get() = blinky.rxMessages

    override val imuRawSample: StateFlow<no.nordicsemi.android.blinky.spec.ImuRawSample?>
        get() = blinky.imuRawSample

    override val attitudeSample: StateFlow<no.nordicsemi.android.blinky.spec.AttitudeSample?>
        get() = blinky.attitudeSample

    override val imuMotionSample: StateFlow<no.nordicsemi.android.blinky.spec.ImuMotionSample?>
        get() = blinky.imuMotionSample

    override val audioStats: StateFlow<no.nordicsemi.android.blinky.spec.AudioStats>
        get() = blinky.audioStats

    override val recording: StateFlow<Boolean>
        get() = blinky.recording

    override val lastSavedPath: StateFlow<String?>
        get() = blinky.lastSavedPath

    override val sensorLogging: StateFlow<Boolean>
        get() = blinky.sensorLogging

    override val sensorLogStatus: StateFlow<String?>
        get() = blinky.sensorLogStatus

    override val imuCalibrationState: StateFlow<String>
        get() = blinky.imuCalibrationState

    override val imuCalibrationStep: StateFlow<String?>
        get() = blinky.imuCalibrationStep

    override val imuCalibrationHint: StateFlow<String?>
        get() = blinky.imuCalibrationHint

    override val imuCalibrationResult: StateFlow<String?>
        get() = blinky.imuCalibrationResult

    override val imuCalibrationBlobStatus: StateFlow<String?>
        get() = blinky.imuCalibrationBlobStatus

    override val grpcState: StateFlow<String>
        get() = blinky.grpcState

    override val grpcLastMessage: StateFlow<String?>
        get() = blinky.grpcLastMessage

    override val gpsData: StateFlow<no.nordicsemi.android.blinky.spec.GpsData?>
        get() = blinky.gpsData

    override val gpsState: StateFlow<no.nordicsemi.android.blinky.spec.GpsState>
        get() = blinky.gpsState

    override val conversationState: StateFlow<no.nordicsemi.android.blinky.spec.ConversationState>
        get() = blinky.conversationState

    override val conversationSessionId: StateFlow<String?>
        get() = blinky.conversationSessionId

    override val waitingResponseSeconds: StateFlow<Long>
        get() = blinky.waitingResponseSeconds

    override val conversationSessionReady: StateFlow<Boolean>
        get() = blinky.conversationSessionReady

    override val realtimeServiceEnabled: StateFlow<Boolean>
        get() = blinky.realtimeServiceEnabled

    override fun release() {
        Timber.uproot(tree)
        blinky.release()
    }

    override suspend fun startConversation() {
        blinky.startConversation()
    }

    override suspend fun startTalking() {
        blinky.startTalking()
    }

    override suspend fun stopTalking() {
        blinky.stopTalking()
    }

    override suspend fun endConversation() {
        blinky.endConversation()
    }

    override suspend fun startRealtimeService() {
        blinky.startRealtimeService()
    }

    override suspend fun stopRealtimeService() {
        blinky.stopRealtimeService()
    }

    override suspend fun startSensorLogging() {
        blinky.startSensorLogging()
    }

    override suspend fun stopSensorLogging() {
        blinky.stopSensorLogging()
    }

    override suspend fun enterImuCalibration() {
        blinky.enterImuCalibration()
    }

    override suspend fun exitImuCalibration() {
        blinky.exitImuCalibration()
    }

    override suspend fun startImuGyroCalibration() {
        blinky.startImuGyroCalibration()
    }

    override suspend fun startImuAccelCalibration() {
        blinky.startImuAccelCalibration()
    }

    override suspend fun saveImuCalibration() {
        blinky.saveImuCalibration()
    }

    override suspend fun abortImuCalibration() {
        blinky.abortImuCalibration()
    }
}
