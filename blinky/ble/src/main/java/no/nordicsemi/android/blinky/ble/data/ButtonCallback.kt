package no.nordicsemi.android.blinky.ble.data

import android.bluetooth.BluetoothDevice
import no.nordicsemi.android.ble.callback.profile.ProfileReadResponse
import no.nordicsemi.android.ble.data.Data

abstract class ButtonCallback: ProfileReadResponse() {

    override fun onDataReceived(device: BluetoothDevice, data: Data) {
        if (data.size() > 0) {
            onMessageReceived(device, data)
        } else {
            onInvalidDataReceived(device, data)
        }
    }

    abstract fun onMessageReceived(device: BluetoothDevice, data: Data)
}
