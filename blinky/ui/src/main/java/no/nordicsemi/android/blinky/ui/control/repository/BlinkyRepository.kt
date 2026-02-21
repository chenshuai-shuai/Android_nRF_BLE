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

    override val audioStats: StateFlow<no.nordicsemi.android.blinky.spec.AudioStats>
        get() = blinky.audioStats

    override val recording: StateFlow<Boolean>
        get() = blinky.recording

    override val lastSavedPath: StateFlow<String?>
        get() = blinky.lastSavedPath

    override val grpcState: StateFlow<String>
        get() = blinky.grpcState

    override val grpcLastMessage: StateFlow<String?>
        get() = blinky.grpcLastMessage

    override val conversationState: StateFlow<no.nordicsemi.android.blinky.spec.ConversationState>
        get() = blinky.conversationState

    override val conversationSessionId: StateFlow<String?>
        get() = blinky.conversationSessionId

    override val waitingResponseSeconds: StateFlow<Long>
        get() = blinky.waitingResponseSeconds

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
}
