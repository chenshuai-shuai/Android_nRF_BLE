package no.nordicsemi.android.blinky.ui.conversation.view

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.android.blinky.spec.ConversationState
import no.nordicsemi.android.blinky.ui.conversation.viewmodel.ConversationViewModel
import no.nordicsemi.android.common.ui.view.NordicAppBar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun ConversationScreen(
    onNavigateUp: () -> Unit,
) {
    val viewModel: ConversationViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sessionId by viewModel.sessionId.collectAsStateWithLifecycle()
    val waitingSeconds by viewModel.waitingSeconds.collectAsStateWithLifecycle()
    val sessionReady by viewModel.sessionReady.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startTalking()
        }
    }

    Column {
        NordicAppBar(
            title = { Text(text = "Realtime Conversation") },
            onNavigationButtonClick = onNavigateUp
        )

        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(16.dp)
        ) {
            val canTalk = state == ConversationState.READY || state == ConversationState.IDLE
            val waiting = state == ConversationState.WAITING_RESPONSE
            val isTalking = state == ConversationState.TALKING
            val buttonLabel = when {
                waiting -> "Waiting for reply..."
                isTalking -> "Release to send"
                else -> "Hold to talk"
            }

            Button(
                onClick = {},
                enabled = state != ConversationState.WAITING_RESPONSE,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .pointerInteropFilter { event ->
                        if (state == ConversationState.WAITING_RESPONSE) return@pointerInteropFilter true
                        when (event.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    viewModel.startTalking()
                                }
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                if (state == ConversationState.TALKING) {
                                    viewModel.stopTalking()
                                }
                            }
                        }
                        true
                    }
            ) {
                Text(text = buttonLabel)
            }

            Text(text = "Conversation: $state")
            if (sessionId != null) {
                Text(text = "Session: $sessionId")
            }
            Text(text = "Session Ready: ${if (sessionReady) "YES" else "NO"}")
            if (state == ConversationState.WAITING_RESPONSE) {
                Text(text = "Waiting reply: ${waitingSeconds}s")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.endConversation() },
                enabled = state != ConversationState.IDLE
            ) {
                Text(text = "End Session")
            }
        }
    }
}
