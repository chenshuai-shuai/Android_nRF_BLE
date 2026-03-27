package no.nordicsemi.android.blinky.ui.calibration.view

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.ImuCalibrationViewModel
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.ImuViewerUiState
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.Quaternion
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.Vec3
import no.nordicsemi.android.common.ui.view.NordicAppBar
import no.nordicsemi.android.scanner.view.DeviceConnectingView
import no.nordicsemi.android.scanner.view.DeviceDisconnectedView
import no.nordicsemi.android.scanner.view.Reason
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImuCalibrationScreen(
    onNavigateUp: () -> Unit,
) {
    val viewModel: ImuCalibrationViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column {
        NordicAppBar(
            title = { Text(text = "IMU 三维姿态") },
            onNavigationButtonClick = onNavigateUp,
        )

        when (state) {
            Blinky.State.LOADING -> {
                DeviceConnectingView(modifier = Modifier.padding(16.dp)) { }
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
                        Text(text = "重新连接")
                    }
                }
            }

            Blinky.State.READY -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ViewerStatusCard(
                        deviceName = viewModel.deviceName,
                        uiState = uiState,
                    )

                    OutlinedCard(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ImuCubeCanvas(
                                quaternion = uiState.quaternion,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    RawDataCard(uiState = uiState)

                    ControlCard(
                        paused = uiState.paused,
                        onResetReference = { viewModel.resetReference() },
                        onTogglePause = { viewModel.togglePause() },
                        onResetViewer = { viewModel.resetViewer() },
                        onExit = onNavigateUp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerStatusCard(
    deviceName: String,
    uiState: ImuViewerUiState,
) {
    val sample = uiState.latestSample
    val motionText = when {
        uiState.paused -> "已暂停"
        sample == null -> "等待 IMU 数据"
        uiState.moving -> "运动中"
        else -> "静止"
    }
    val filterText = when {
        uiState.paused -> "显示已冻结"
        uiState.biasReady -> "姿态解算稳定"
        sample != null -> "姿态解算预热中"
        else -> "尚未开始"
    }

    OutlinedCard(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "设备：$deviceName", style = MaterialTheme.typography.titleMedium)
            Text(text = "当前状态：$motionText")
            Text(text = "姿态解算：$filterText")
            Text(
                text = "采样频率：${uiState.sampleRateHz.format1()} Hz   Viewer FPS：${uiState.viewerFps.format1()}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "欧拉角：roll=${uiState.rollDeg.roundToInt()}°  pitch=${uiState.pitchDeg.roundToInt()}°  yaw=${uiState.yawDeg.roundToInt()}°",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "说明：这个页面只做 IMU 实时可视化，不影响音频、gRPC 上传和其他主功能。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RawDataCard(
    uiState: ImuViewerUiState,
) {
    val sample = uiState.latestSample
    OutlinedCard(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "IMU 原始数据", style = MaterialTheme.typography.titleMedium)
            if (sample == null) {
                Text(text = "尚未收到 IMU 原始样本。")
            } else {
                Text(text = "seq=${sample.seq}  temp=${sample.tempCenti / 100f}°C")
                Text(text = "加速度：ax=${sample.ax} ay=${sample.ay} az=${sample.az}")
                Text(text = "陀螺仪：gx=${sample.gx} gy=${sample.gy} gz=${sample.gz}")
            }
        }
    }
}

@Composable
private fun ControlCard(
    paused: Boolean,
    onResetReference: () -> Unit,
    onTogglePause: () -> Unit,
    onResetViewer: () -> Unit,
    onExit: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "操作", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResetReference) {
                    Text(text = "重置朝向")
                }
                Button(onClick = onTogglePause) {
                    Text(text = if (paused) "继续" else "暂停")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onResetViewer) {
                    Text(text = "重置解算器")
                }
                OutlinedButton(onClick = onExit) {
                    Text(text = "退出")
                }
            }
        }
    }
}

@Composable
private fun ImuCubeCanvas(
    quaternion: Quaternion,
    modifier: Modifier = Modifier,
) {
    val textPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val scale = size.minDimension * 0.18f

        val cubeVertices = listOf(
            Vec3(-1f, -1f, -1f), Vec3(1f, -1f, -1f), Vec3(1f, 1f, -1f), Vec3(-1f, 1f, -1f),
            Vec3(-1f, -1f, 1f), Vec3(1f, -1f, 1f), Vec3(1f, 1f, 1f), Vec3(-1f, 1f, 1f),
        )
        val cubeEdges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
        )

        val rotated = cubeVertices.map { quaternion.rotate(it) }
        val projected = rotated.map { project(it, center, scale) }

        cubeEdges.forEach { (a, b) ->
            drawLine(
                color = Color(0xFFE8EDF2),
                start = projected[a],
                end = projected[b],
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }

        val axisLength = 1.6f
        val axes = listOf(
            Triple(Vec3(0f, 0f, 0f), Vec3(axisLength, 0f, 0f), Color(0xFFFF5A5A) to "X"),
            Triple(Vec3(0f, 0f, 0f), Vec3(0f, axisLength, 0f), Color(0xFF47D16C) to "Y"),
            Triple(Vec3(0f, 0f, 0f), Vec3(0f, 0f, axisLength), Color(0xFF55A7FF) to "Z"),
        )

        axes.forEach { (origin, end, axisStyle) ->
            val start = project(quaternion.rotate(origin), center, scale * 1.2f)
            val finish = project(quaternion.rotate(end), center, scale * 1.2f)
            drawLine(
                color = axisStyle.first,
                start = start,
                end = finish,
                strokeWidth = 6f,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = axisStyle.first,
                radius = 8f,
                center = finish,
            )
            drawContext.canvas.nativeCanvas.drawText(
                axisStyle.second,
                finish.x + 10f,
                finish.y - 10f,
                textPaint,
            )
        }
    }
}

private fun project(
    point: Vec3,
    center: Offset,
    scale: Float,
): Offset {
    val distance = 4.6f
    val factor = distance / (distance - point.z)
    return Offset(
        x = center.x + point.x * scale * factor,
        y = center.y - point.y * scale * factor,
    )
}

private fun Float.format1(): String = String.format("%.1f", this)
