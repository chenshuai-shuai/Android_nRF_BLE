package no.nordicsemi.android.blinky.ui.control.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.ui.R
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
                    val rxMessages by viewModel.rxMessages.collectAsStateWithLifecycle()
                    val audioStats by viewModel.audioStats.collectAsStateWithLifecycle()
                    val recording by viewModel.recording.collectAsStateWithLifecycle()
                    val lastSavedPath by viewModel.lastSavedPath.collectAsStateWithLifecycle()
                    var message by remember { mutableStateOf("") }

                    Column(
                        modifier = Modifier
                            .widthIn(max = 460.dp)
                            .padding(16.dp)
                    ) {
                        Text(text = "Audio Stats")
                        Text(text = "Packets: ${audioStats.packets}  Bytes: ${audioStats.bytes}")
                        Text(text = "Frames: ${audioStats.frames}  Dropped: ${audioStats.droppedFrames}")
                        Text(text = "Last Seq: ${audioStats.lastSeq}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Recording: ${if (recording) "ON" else "OFF"} (auto)")
                        if (lastSavedPath != null) {
                            Text(text = "Saved: $lastSavedPath")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.stopRecording() },
                            enabled = recording
                        ) { Text(text = "Stop & Save") }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "NRF Messages")
                        if (rxMessages.isEmpty()) {
                            Text(text = "(no messages)")
                        } else {
                            rxMessages.takeLast(6).forEach { msg ->
                                Text(text = msg)
                            }
                        }
                    }

                    TextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text(text = "Send to device") },
                        modifier = Modifier
                            .widthIn(max = 460.dp)
                            .padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.sendMessage(message)
                            message = ""
                        },
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(text = "Send")
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
