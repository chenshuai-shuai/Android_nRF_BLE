package no.nordicsemi.android.blinky.ui.control.view

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.spec.ConversationState
import no.nordicsemi.android.blinky.ui.R
import no.nordicsemi.android.blinky.ui.control.viewmodel.BlinkyViewModel
import no.nordicsemi.android.common.logger.view.LoggerAppBarIcon
import no.nordicsemi.android.common.permissions.ble.RequireBluetooth
import no.nordicsemi.android.common.ui.view.NordicAppBar
import no.nordicsemi.android.scanner.view.DeviceConnectingView
import no.nordicsemi.android.scanner.view.DeviceDisconnectedView
import no.nordicsemi.android.scanner.view.Reason

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BlinkyScreen(
    onNavigateUp: () -> Unit,
) {
    val viewModel: BlinkyViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NordicAppBar(
            title = { Text(text = viewModel.deviceName) },
            onNavigationButtonClick = onNavigateUp,
            actions = {
                LoggerAppBarIcon(onClick = { viewModel.openLogger() })
            }
        )
        RequireBluetooth {
            when (state) {
                Blinky.State.LOADING -> {
                    DeviceConnectingView(
                        modifier = Modifier.padding(16.dp),
                    ) { padding ->
                        Button(
                            onClick = onNavigateUp,
                            modifier = Modifier.padding(padding),
                        ) {
                            Text(text = stringResource(id = R.string.action_cancel))
                        }
                    }
                }
                Blinky.State.READY -> {
                    val requestLocationPermissions = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        viewModel.refreshGps()
                    }
                    var gpsPermissionAsked by remember { mutableStateOf(false) }
                    val rxMessages by viewModel.rxMessages.collectAsStateWithLifecycle()
                    val audioStats by viewModel.audioStats.collectAsStateWithLifecycle()
                    val recording by viewModel.recording.collectAsStateWithLifecycle()
                    val lastSavedPath by viewModel.lastSavedPath.collectAsStateWithLifecycle()
                    val grpcState by viewModel.grpcState.collectAsStateWithLifecycle()
                    val grpcLastMessage by viewModel.grpcLastMessage.collectAsStateWithLifecycle()
                    val gpsData by viewModel.gpsData.collectAsStateWithLifecycle()
                    val gpsState by viewModel.gpsState.collectAsStateWithLifecycle()
                    val conversationState by viewModel.conversationState.collectAsStateWithLifecycle()
                    val sessionId by viewModel.conversationSessionId.collectAsStateWithLifecycle()
                    val waitingSeconds by viewModel.waitingResponseSeconds.collectAsStateWithLifecycle()
                    val sessionReady by viewModel.conversationSessionReady.collectAsStateWithLifecycle()
                    val ppgMessages = rxMessages.filter { it.startsWith("PPG ") }
                    val imuMessages = rxMessages.filter {
                        it.startsWith("IMU ") || it.startsWith("IMU_RAW ")
                    }
                    val gpsMessages = rxMessages.filter { it.startsWith("GPS ") }
                    val lastPpg = ppgMessages.lastOrNull()
                    val lastImu = imuMessages.lastOrNull()
                    val lastGps = gpsMessages.lastOrNull()
                    val saveMessages = rxMessages.filter { it.startsWith("SAVE ") }
                    val lastSave = saveMessages.lastOrNull()
                    val sensorMessages = rxMessages.filter {
                        it.startsWith("PPG ") ||
                            it.startsWith("IMU ") ||
                            it.startsWith("IMU_RAW ") ||
                            it.startsWith("GPS ")
                    }

                    LaunchedEffect(gpsState) {
                        if (gpsState == no.nordicsemi.android.blinky.spec.GpsState.PERMISSION_DENIED &&
                            !gpsPermissionAsked
                        ) {
                            gpsPermissionAsked = true
                            requestLocationPermissions.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .widthIn(max = 460.dp)
                            .padding(16.dp)
                    ) {
                        Text(text = "Conversation: $conversationState")
                        if (sessionId != null) {
                            Text(text = "Session: $sessionId")
                        }
                        Text(text = "Session Ready: ${if (sessionReady) "YES" else "NO"}")
                        if (conversationState == ConversationState.WAITING_RESPONSE) {
                            Text(text = "Waiting reply: ${waitingSeconds}s")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val canTalk = conversationState == ConversationState.READY ||
                            conversationState == ConversationState.IDLE ||
                            conversationState == ConversationState.CONNECTING
                        val waiting = conversationState == ConversationState.WAITING_RESPONSE
                        val isTalking = conversationState == ConversationState.TALKING
                        val buttonLabel = when {
                            waiting -> "Waiting for reply..."
                            isTalking -> "Release to send"
                            conversationState == ConversationState.CONNECTING -> "Starting session..."
                            else -> "Hold to talk"
                        }
                        Button(
                            onClick = {},
                            enabled = canTalk,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .pointerInteropFilter { event ->
                                    if (!canTalk) return@pointerInteropFilter true
                                    when (event.action) {
                                        android.view.MotionEvent.ACTION_DOWN -> {
                                            viewModel.startConversation()
                                            viewModel.startTalking()
                                        }
                                        android.view.MotionEvent.ACTION_UP,
                                        android.view.MotionEvent.ACTION_CANCEL -> {
                                            if (conversationState == ConversationState.TALKING) {
                                                viewModel.stopTalking()
                                            }
                                        }
                                    }
                                    true
                                }
                        ) {
                            Text(text = buttonLabel)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.endConversation() },
                            enabled = conversationState != ConversationState.IDLE,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(text = "End Session")
                        }

                        Text(text = "gRPC Status: $grpcState")
                        if (grpcLastMessage != null) {
                            Text(text = "gRPC Last: $grpcLastMessage")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Audio Stats")
                        Text(text = "Packets: ${audioStats.packets}  Bytes: ${audioStats.bytes}")
                        Text(text = "Frames: ${audioStats.frames}  Dropped: ${audioStats.droppedFrames}")
                        Text(text = "Last Seq: ${audioStats.lastSeq}")
                        Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Recording: ${if (recording) "ON" else "OFF"} (auto)")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Save Status")
                    Text(text = lastSave ?: "(no saves yet)")
                        if (lastSavedPath != null) {
                            Text(text = "Saved: $lastSavedPath")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.stopRecording() },
                            enabled = recording
                        ) { Text(text = "Stop & Save") }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "PPG Data")
                        Text(text = lastPpg ?: "(no ppg data)")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "IMU Data")
                        Text(text = lastImu ?: "(no imu data)")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "GPS Data")
                        when (gpsState) {
                            no.nordicsemi.android.blinky.spec.GpsState.READY -> {
                                if (gpsData != null) {
                                    val g = gpsData!!
                                    Text(text = "lat=${"%.6f".format(g.lat)} lon=${"%.6f".format(g.lon)}")
                                    Text(
                                        text = "acc=${"%.1f".format(g.accuracyM ?: -1f)}m " +
                                            "spd=${"%.2f".format(g.speedMps ?: 0f)}m/s " +
                                            "alt=${"%.1f".format(g.altM ?: 0.0)}m"
                                    )
                                } else {
                                    Text(text = lastGps ?: "(gps ready, no sample)")
                                }
                            }
                            no.nordicsemi.android.blinky.spec.GpsState.PERMISSION_DENIED -> {
                                Text(text = "permission denied (please enable Location permission in system settings)")
                            }
                            no.nordicsemi.android.blinky.spec.GpsState.LOCATION_OFF -> {
                                Text(text = "location is off")
                                Button(
                                    onClick = { viewModel.refreshGps() },
                                    modifier = Modifier.padding(top = 6.dp)
                                ) {
                                    Text(text = "Retry GPS")
                                }
                            }
                            no.nordicsemi.android.blinky.spec.GpsState.SEARCHING -> {
                                Text(text = "searching...")
                            }
                            no.nordicsemi.android.blinky.spec.GpsState.UNAVAILABLE -> {
                                Text(text = "unavailable")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "Sensor Messages")
                        if (sensorMessages.isEmpty()) {
                            Text(text = "(no messages)")
                        } else {
                            sensorMessages.takeLast(8).forEach { msg ->
                                Text(text = msg)
                            }
                        }
                    }
                }
                Blinky.State.NOT_AVAILABLE -> {
                    DeviceDisconnectedView(
                        reason = Reason.LINK_LOSS,
                        modifier = Modifier.padding(16.dp),
                    ) { padding ->
                        Button(
                            onClick = { viewModel.connect() },
                            modifier = Modifier.padding(padding),
                        ) {
                            Text(text = stringResource(id = R.string.action_retry))
                        }
                    }
                }
            }
        }
    }
}
