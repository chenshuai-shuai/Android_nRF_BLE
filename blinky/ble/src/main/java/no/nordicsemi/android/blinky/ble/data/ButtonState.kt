package no.nordicsemi.android.blinky.ble.data

import android.bluetooth.BluetoothDevice
import no.nordicsemi.android.ble.data.Data

class ButtonState: ButtonCallback() {
    var text: String = ""

    override fun onMessageReceived(device: BluetoothDevice, data: Data) {
        text = data.getStringValue(0) ?: ""
    }
}
