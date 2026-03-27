package no.nordicsemi.android.blinky.ui.calibration

import androidx.hilt.navigation.compose.hiltViewModel
import no.nordicsemi.android.blinky.ui.calibration.view.ImuCalibrationScreen
import no.nordicsemi.android.blinky.ui.control.BlinkyDevice
import no.nordicsemi.android.common.navigation.createDestination
import no.nordicsemi.android.common.navigation.defineDestination
import no.nordicsemi.android.common.navigation.viewmodel.SimpleNavigationViewModel

val ImuCalibration = createDestination<BlinkyDevice, Unit>("imu_calibration")

val ImuCalibrationDestination = defineDestination(ImuCalibration) {
    val viewModel: SimpleNavigationViewModel = hiltViewModel()

    ImuCalibrationScreen(
        onNavigateUp = { viewModel.navigateUp() }
    )
}
