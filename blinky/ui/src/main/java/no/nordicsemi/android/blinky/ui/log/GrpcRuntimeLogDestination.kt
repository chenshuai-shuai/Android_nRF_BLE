package no.nordicsemi.android.blinky.ui.log

import androidx.hilt.navigation.compose.hiltViewModel
import no.nordicsemi.android.blinky.ui.log.view.GrpcRuntimeLogScreen
import no.nordicsemi.android.common.navigation.createSimpleDestination
import no.nordicsemi.android.common.navigation.defineDestination
import no.nordicsemi.android.common.navigation.viewmodel.SimpleNavigationViewModel

val GrpcRuntimeLog = createSimpleDestination("grpc_runtime_log")

val GrpcRuntimeLogDestination = defineDestination(GrpcRuntimeLog) {
    val viewModel: SimpleNavigationViewModel = hiltViewModel()

    GrpcRuntimeLogScreen(
        onNavigateUp = { viewModel.navigateUp() }
    )
}
