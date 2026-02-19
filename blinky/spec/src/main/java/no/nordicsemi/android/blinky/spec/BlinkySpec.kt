package no.nordicsemi.android.blinky.spec

import java.util.UUID

class BlinkySpec {

    companion object {
        val BLINKY_SERVICE_UUID: UUID = UUID.fromString("f0debc9a-7856-3412-7856-341234120000")
        val BLINKY_BUTTON_CHARACTERISTIC_UUID: UUID = UUID.fromString("f2debc9a-7856-3412-7856-341234120000")
        val BLINKY_LED_CHARACTERISTIC_UUID: UUID = UUID.fromString("f1debc9a-7856-3412-7856-341234120000")
    }

}
