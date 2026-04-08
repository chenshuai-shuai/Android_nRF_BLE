package no.nordicsemi.android.blinky.ui.control.view

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.spec.ConversationState
import no.nordicsemi.android.blinky.spec.GpsData
import no.nordicsemi.android.blinky.spec.GpsState
import no.nordicsemi.android.blinky.ui.R
import no.nordicsemi.android.blinky.ui.control.BlinkyDevice
import no.nordicsemi.android.blinky.ui.control.viewmodel.BlinkyViewModel
import no.nordicsemi.android.common.logger.view.LoggerAppBarIcon
import no.nordicsemi.android.common.permissions.ble.RequireBluetooth
import no.nordicsemi.android.common.ui.view.NordicAppBar
import no.nordicsemi.android.scanner.view.DeviceConnectingView
import no.nordicsemi.android.scanner.view.DeviceDisconnectedView
import no.nordicsemi.android.scanner.view.Reason

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlinkyScreen(
    onNavigateUp: () -> Unit,
    onOpenRuntimeLog: () -> Unit,
    onOpenImuCalibration: (BlinkyDevice) -> Unit,
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
                    val sensorLogging by viewModel.sensorLogging.collectAsStateWithLifecycle()
                    val sensorLogStatus by viewModel.sensorLogStatus.collectAsStateWithLifecycle()
                    val grpcState by viewModel.grpcState.collectAsStateWithLifecycle()
                    val grpcLastMessage by viewModel.grpcLastMessage.collectAsStateWithLifecycle()
                    val gpsData by viewModel.gpsData.collectAsStateWithLifecycle()
                    val gpsState by viewModel.gpsState.collectAsStateWithLifecycle()
                    val conversationState by viewModel.conversationState.collectAsStateWithLifecycle()
                    val sessionId by viewModel.conversationSessionId.collectAsStateWithLifecycle()
                    val waitingSeconds by viewModel.waitingResponseSeconds.collectAsStateWithLifecycle()
                    val sessionReady by viewModel.conversationSessionReady.collectAsStateWithLifecycle()
                    val realtimeServiceEnabled by viewModel.realtimeServiceEnabled.collectAsStateWithLifecycle()
                    val ppgMessages = rxMessages.filter { it.startsWith("PPG ") }
                    val imuMessages = rxMessages.filter {
                        it.startsWith("IMU ") || it.startsWith("IMU_RAW ")
                    }
                    val tempMessages = rxMessages.filter { it.startsWith("TEMP ") }
                    val gpsMessages = rxMessages.filter { it.startsWith("GPS ") }
                    val lastPpg = ppgMessages.lastOrNull()
                    val lastImu = imuMessages.lastOrNull()
                    val lastTemp = tempMessages.lastOrNull()
                    val lastGps = gpsMessages.lastOrNull()
                    val sensorMessages = rxMessages.filter {
                        it.startsWith("PPG ") ||
                            it.startsWith("IMU ") ||
                            it.startsWith("IMU_RAW ") ||
                            it.startsWith("TEMP ") ||
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = "Conversation: $conversationState")
                                if (sessionId != null) {
                                    Text(text = "Session: $sessionId")
                                }
                                Text(text = "Session Ready: ${if (sessionReady) "YES" else "NO"}")
                                Text(text = "Realtime Service: ${if (realtimeServiceEnabled) "ON" else "OFF"}")
                                if (conversationState == ConversationState.WAITING_RESPONSE) {
                                    Text(text = "Waiting reply: ${waitingSeconds}s")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                                    Button(
                                        onClick = {
                                            if (realtimeServiceEnabled) {
                                                viewModel.stopRealtimeService()
                                            } else {
                                                viewModel.startRealtimeService()
                                            }
                                        },
                                        enabled = conversationState != ConversationState.CONNECTING
                                    ) {
                                        Text(
                                            text = if (realtimeServiceEnabled) {
                                                "Stop Realtime Service"
                                            } else {
                                                "Start Realtime Service"
                                            }
                                        )
                                    }
                                }
                                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                                    Button(onClick = onOpenRuntimeLog) {
                                        Text(text = "Open Runtime Log")
                                    }
                                }
                                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                                    Button(
                                        onClick = {
                                            onOpenImuCalibration(BlinkyDevice(viewModel.device, viewModel.deviceName))
                                        }
                                    ) {
                                        Text(text = "Open IMU 3D Viewer")
                                    }
                                }
                            }

                            SensorSummaryCard(
                                modifier = Modifier.weight(1f),
                                lastPpg = lastPpg,
                                gpsState = gpsState,
                                gpsData = gpsData,
                            ) {
                                viewModel.refreshGps()
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
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
                    Text(text = "Sensor Log Saving: ${if (sensorLogging) "ON" else "OFF"} (manual)")

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Sensor Save Status")
                    Text(text = sensorLogStatus ?: "(manual/off)")
                        Button(
                            onClick = {
                                if (sensorLogging) {
                                    viewModel.stopSensorLogging()
                                } else {
                                    viewModel.startSensorLogging()
                                }
                            },
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(text = if (sensorLogging) "Stop Sensor Save" else "Start Sensor Save")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Audio Recording: ${if (recording) "ON" else "OFF"} (manual)")
                        if (lastSavedPath != null) {
                            Text(text = "Audio Saved: $lastSavedPath")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (recording) viewModel.stopRecording() else viewModel.startRecording()
                            },
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(text = if (recording) "Stop Recording" else "Start Recording")
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "PPG Data")
                        Text(text = lastPpg ?: "(no ppg data)")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "IMU Data")
                        Text(text = lastImu ?: "(no imu data)")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Temp Data")
                        Text(text = lastTemp ?: "(no temp data)")
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

@Composable
private fun SensorSummaryCard(
    modifier: Modifier = Modifier,
    lastPpg: String?,
    gpsState: GpsState,
    gpsData: GpsData?,
    onRetryGps: () -> Unit,
) {
    val spo2Hb = lastPpg.metricIntValue("spo2_hb")
    val hr = if (spo2Hb != null && spo2Hb > 0) {
        spo2Hb.toString()
    } else {
        lastPpg.metricValue("hr")
    }
    val hrv = lastPpg.metricValue("hrv")
    val spo2 = lastPpg.metricValue("spo2")
    val gpsPrimary = when (gpsState) {
        GpsState.READY -> gpsData?.let { "${"%.6f".format(it.lat)}, ${"%.6f".format(it.lon)}" } ?: "--"
        GpsState.SEARCHING -> "searching..."
        GpsState.LOCATION_OFF -> "location off"
        GpsState.PERMISSION_DENIED -> "permission denied"
        GpsState.UNAVAILABLE -> "unavailable"
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = "Realtime Sensors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            SensorMetric(label = "Heart Rate", value = hr ?: "--", suffix = "bpm")
            Spacer(modifier = Modifier.height(10.dp))
            SensorMetric(label = "HRV", value = hrv ?: "--", suffix = "ms")
            Spacer(modifier = Modifier.height(10.dp))
            SensorMetric(label = "SpO2", value = spo2 ?: "--", suffix = "%")
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "GPS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = gpsPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (gpsState == GpsState.LOCATION_OFF) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetryGps) {
                    Text(text = "Retry GPS")
                }
            }
        }
    }
}

@Composable
private fun SensorMetric(
    label: String,
    value: String,
    suffix: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (value == "--") value else "$value $suffix",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun String?.metricValue(key: String): String? {
    if (this == null) return null
    val match = Regex("""\b$key=([^\s]+)""").find(this) ?: return null
    return match.groupValues.getOrNull(1)
}

private fun String?.metricIntValue(key: String): Int? {
    return this.metricValue(key)?.toIntOrNull()
}
