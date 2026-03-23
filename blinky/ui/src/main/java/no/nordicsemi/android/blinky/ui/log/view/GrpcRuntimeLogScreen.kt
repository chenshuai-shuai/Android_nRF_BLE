package no.nordicsemi.android.blinky.ui.log.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.android.blinky.ble.logging.GrpcRuntimeLogStore
import no.nordicsemi.android.common.ui.view.NordicAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GrpcRuntimeLogScreen(
    onNavigateUp: () -> Unit,
) {
    val lines by GrpcRuntimeLogStore.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(lines.size, autoScroll) {
        if (autoScroll && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        NordicAppBar(
            title = { Text(text = "gRPC Runtime Log") },
            onNavigationButtonClick = onNavigateUp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { autoScroll = !autoScroll }) {
                Text(text = if (autoScroll) "Pause Scroll" else "Resume Scroll")
            }
            Button(onClick = { GrpcRuntimeLogStore.clear() }) {
                Text(text = "Clear")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = "${line.timestamp} ${line.tag}: ${line.message}",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
