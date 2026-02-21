package no.nordicsemi.android.blinky.ui.conversation

import androidx.hilt.navigation.compose.hiltViewModel
import no.nordicsemi.android.blinky.ui.conversation.view.ConversationScreen
import no.nordicsemi.android.common.navigation.createSimpleDestination
import no.nordicsemi.android.common.navigation.defineDestination
import no.nordicsemi.android.common.navigation.viewmodel.SimpleNavigationViewModel

val Conversation = createSimpleDestination("conversation")

val ConversationDestination = defineDestination(Conversation) {
    val viewModel: SimpleNavigationViewModel = hiltViewModel()

    ConversationScreen(
        onNavigateUp = { viewModel.navigateUp() }
    )
}
