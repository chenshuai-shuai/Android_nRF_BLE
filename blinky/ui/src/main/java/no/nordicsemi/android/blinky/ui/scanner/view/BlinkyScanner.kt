package no.nordicsemi.android.blinky.ui.scanner.view

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.android.blinky.spec.BlinkySpec
import no.nordicsemi.android.blinky.spec.GrpcStatusStore
import no.nordicsemi.android.blinky.ui.R
import no.nordicsemi.android.scanner.DeviceSelected
import no.nordicsemi.android.scanner.ScannerScreen

@Composable
fun BlinkyScanner(
    onDeviceSelected: (BluetoothDevice, String?) -> Unit,
) {
    ScannerScreen(
        title = { Text(stringResource(id = R.string.scanner_title)) },
        uuid = ParcelUuid(BlinkySpec.BLINKY_SERVICE_UUID),
        cancellable = false,
        header = {
            val grpcState by GrpcStatusStore.state.collectAsStateWithLifecycle()
            val grpcLast by GrpcStatusStore.lastMessage.collectAsStateWithLifecycle()
            val grpcDropped by GrpcStatusStore.droppedPackets.collectAsStateWithLifecycle()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = "gRPC: $grpcState")
                if (grpcLast != null) {
                    Text(text = "gRPC Last: $grpcLast")
                }
                Text(text = "gRPC Dropped: $grpcDropped")
            }
        },
        onResult = { result ->
            when (result) {
                is DeviceSelected -> with(result.device) {
                    onDeviceSelected(device, name)
                }
                else -> {}
            }
        }
    )
}
