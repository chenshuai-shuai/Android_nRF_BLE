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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.nordicsemi.android.blinky.spec.Blinky
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.ActivityAccuracyStats
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.DogActivityClass
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.DogActivityTuning
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.ImuCalibrationViewModel
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.ImuViewerUiState
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.Quaternion
import no.nordicsemi.android.blinky.ui.calibration.viewmodel.Vec3
import no.nordicsemi.android.common.ui.view.NordicAppBar
import no.nordicsemi.android.scanner.view.DeviceConnectingView
import no.nordicsemi.android.scanner.view.DeviceDisconnectedView
import no.nordicsemi.android.scanner.view.Reason
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
                            ImuDogCanvas(
                                quaternion = uiState.quaternion,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    RawDataCard(uiState = uiState)

                    ActivityDebugCard(uiState = uiState)

                    AccuracyCard(
                        stats = uiState.accuracyStats,
                        onSetReference = { viewModel.setReferenceActivity(it) },
                        onResetStats = { viewModel.resetAccuracyStats() },
                    )

                    ActivityTuningCard(
                        tuning = uiState.tuning,
                        onTuningChange = { viewModel.updateTuning(it) },
                        onReset = { viewModel.resetTuning() },
                    )

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
            Text(
                text = "动作识别：${uiState.activityLabel}  置信度=${uiState.activityConfidence}%  来源=${uiState.activitySource}",
                style = MaterialTheme.typography.bodyLarge,
            )
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
private fun ActivityDebugCard(
    uiState: ImuViewerUiState,
) {
    val debug = uiState.activityDebug
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
            Text(text = "动作调试信息", style = MaterialTheme.typography.titleMedium)
            Text(text = "原始判定=${debug.rawClass.labelZh()}  稳定输出=${debug.stableClass.labelZh()}  窗口样本=${debug.sampleCount}")
            Text(text = "平均动态加速度=${debug.meanAccG.format3()}g  标准差=${debug.stdAccG.format3()}g  峰值=${debug.peakAccG.format3()}g")
            Text(text = "平均角速度=${debug.meanGyroDps.format1()}dps  峰值角速度=${debug.peakGyroDps.format1()}dps")
            Text(text = "运动占比=${(debug.movingShare * 100f).roundToInt()}%  强运动占比=${(debug.strongShare * 100f).roundToInt()}%")
            Text(text = "步频估计=${debug.cadenceHz.format2()}Hz")
        }
    }
}

@Composable
private fun AccuracyCard(
    stats: ActivityAccuracyStats,
    onSetReference: (DogActivityClass?) -> Unit,
    onResetStats: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "准确率校验", style = MaterialTheme.typography.titleMedium)
            Text(text = "先点击狗狗此刻的真实动作，系统才会开始统计准确率。未标注时不会统计。")
            Text(
                text = "当前人工标注=${stats.activeReference?.labelZh() ?: "未标注"}  总体准确率=${stats.overallAccuracyPercent}%  已统计=${stats.totalEvaluations}次",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "静止=${stats.accuracyPercentFor(DogActivityClass.STATIC)}%/${stats.sampleCountFor(DogActivityClass.STATIC)}次  " +
                    "走路=${stats.accuracyPercentFor(DogActivityClass.WALK)}%/${stats.sampleCountFor(DogActivityClass.WALK)}次  " +
                    "跑步=${stats.accuracyPercentFor(DogActivityClass.RUN)}%/${stats.sampleCountFor(DogActivityClass.RUN)}次",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSetReference(DogActivityClass.STATIC) }) {
                    Text(text = "真实动作：静止")
                }
                Button(onClick = { onSetReference(DogActivityClass.WALK) }) {
                    Text(text = "真实动作：走路")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSetReference(DogActivityClass.RUN) }) {
                    Text(text = "真实动作：跑步")
                }
                OutlinedButton(onClick = { onSetReference(null) }) {
                    Text(text = "暂停统计")
                }
                OutlinedButton(onClick = onResetStats) {
                    Text(text = "清空统计")
                }
            }
        }
    }
}

@Composable
private fun ActivityTuningCard(
    tuning: DogActivityTuning,
    onTuningChange: (DogActivityTuning) -> Unit,
    onReset: () -> Unit,
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
            Text(text = "动作识别调参", style = MaterialTheme.typography.titleMedium)
            Text(text = "这些参数修改后会立刻生效，并自动保存到 app。本次调好的值，下次打开 app 仍会继续使用。")

            TuningSlider(
                label = "静止平均动态加速度",
                helper = "狗狗静止时仍误判走路，就调高一点；静止稍微一动就识别不出来，就调低一点。",
                value = tuning.staticMeanAccG,
                range = 0.03f..0.20f,
                valueText = "${tuning.staticMeanAccG.format3()} g",
                onValueChange = { onTuningChange(tuning.copy(staticMeanAccG = it)) },
            )
            TuningSlider(
                label = "静止动态加速度标准差",
                helper = "狗狗原地轻微摆头、调整姿势仍应算静止时，把它调高一点。",
                value = tuning.staticStdAccG,
                range = 0.01f..0.10f,
                valueText = "${tuning.staticStdAccG.format3()} g",
                onValueChange = { onTuningChange(tuning.copy(staticStdAccG = it)) },
            )
            TuningSlider(
                label = "静止平均角速度",
                helper = "原地摇头也想算静止时，优先调高这个值；如果静止总被误判走路，也可以适当调高。",
                value = tuning.staticMeanGyroDps,
                range = 5f..60f,
                valueText = "${tuning.staticMeanGyroDps.format1()} dps",
                onValueChange = { onTuningChange(tuning.copy(staticMeanGyroDps = it)) },
            )
            TuningSlider(
                label = "走路平均动态加速度",
                helper = "正常走路识别不出来就调低；静止常被误判走路就调高。",
                value = tuning.walkMeanAccG,
                range = 0.05f..0.25f,
                valueText = "${tuning.walkMeanAccG.format3()} g",
                onValueChange = { onTuningChange(tuning.copy(walkMeanAccG = it)) },
            )
            TuningSlider(
                label = "走路平均角速度",
                helper = "走路时头部持续摆动较明显但算法没跟上，就调低；原地摇头误判走路多，就调高。",
                value = tuning.walkMeanGyroDps,
                range = 10f..90f,
                valueText = "${tuning.walkMeanGyroDps.format1()} dps",
                onValueChange = { onTuningChange(tuning.copy(walkMeanGyroDps = it)) },
            )
            TuningSlider(
                label = "走路步频阈值",
                helper = "正常走路有节奏但没识别出来就调低；原地摆头或短时抖动误判走路时就调高。",
                value = tuning.walkCadenceHz,
                range = 0.6f..2.2f,
                valueText = "${tuning.walkCadenceHz.format2()} Hz",
                onValueChange = { onTuningChange(tuning.copy(walkCadenceHz = it)) },
            )
            TuningSlider(
                label = "跑步平均动态加速度",
                helper = "跑步常被判成走路就调低；快走经常被误判跑步就调高。",
                value = tuning.runMeanAccG,
                range = 0.10f..0.40f,
                valueText = "${tuning.runMeanAccG.format3()} g",
                onValueChange = { onTuningChange(tuning.copy(runMeanAccG = it)) },
            )
            TuningSlider(
                label = "跑步动态加速度峰值",
                helper = "只有出现更强冲击才进跑步。摇头或偶发猛抖一下就进跑步时，把它调高。",
                value = tuning.runPeakAccG,
                range = 0.20f..0.90f,
                valueText = "${tuning.runPeakAccG.format3()} g",
                onValueChange = { onTuningChange(tuning.copy(runPeakAccG = it)) },
            )
            TuningSlider(
                label = "跑步平均角速度",
                helper = "持续快速运动时头部转动能量更高。跑步识别太迟钝就调低，快走误判跑步就调高。",
                value = tuning.runMeanGyroDps,
                range = 30f..180f,
                valueText = "${tuning.runMeanGyroDps.format1()} dps",
                onValueChange = { onTuningChange(tuning.copy(runMeanGyroDps = it)) },
            )
            TuningSlider(
                label = "切到走路保持时间",
                helper = "原始判定连续保持多久才真正切到走路。类别跳来跳去就调高，切换太慢就调低。",
                value = tuning.walkHoldMs.toFloat(),
                range = 300f..1800f,
                valueText = "${tuning.walkHoldMs} ms",
                onValueChange = { onTuningChange(tuning.copy(walkHoldMs = it.roundToInt().toLong())) },
            )
            TuningSlider(
                label = "切到跑步保持时间",
                helper = "偶发大动作就误进跑步时调高；真正跑起来切换太慢时调低。",
                value = tuning.runHoldMs.toFloat(),
                range = 300f..1500f,
                valueText = "${tuning.runHoldMs} ms",
                onValueChange = { onTuningChange(tuning.copy(runHoldMs = it.roundToInt().toLong())) },
            )
            TuningSlider(
                label = "回到静止保持时间",
                helper = "狗狗刚停下就想马上回到静止可调低；如果刚停一下又继续走，分类闪烁就调高。",
                value = tuning.staticHoldMs.toFloat(),
                range = 500f..2500f,
                valueText = "${tuning.staticHoldMs} ms",
                onValueChange = { onTuningChange(tuning.copy(staticHoldMs = it.roundToInt().toLong())) },
            )

            OutlinedButton(onClick = onReset) {
                Text(text = "恢复默认参数")
            }
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    helper: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "$label: $valueText", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
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
private fun ImuDogCanvas(
    quaternion: Quaternion,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            isAntiAlias = true
        }
    }
    val mesh by produceState<ObjMesh?>(initialValue = null, context) {
        value = ObjMeshLoader.load(context, DOG_MODEL_ASSET)
    }

    Canvas(modifier = modifier) {
        val center = Offset(
            x = size.width / 2f,
            y = size.height / 2f - size.minDimension * DOG_MODEL_CENTER_Y_OFFSET_FACTOR,
        )
        val meshScale = size.minDimension * DOG_MODEL_VIEW_SCALE
        val axisScale = size.minDimension * 0.18f

        drawRect(color = Color(0xFF11161C))

        mesh?.let { objMesh ->
            drawMesh(
                mesh = objMesh,
                quaternion = quaternion,
                center = center,
                scale = meshScale,
            )
        } ?: run {
            drawContext.canvas.nativeCanvas.drawText(
                "Loading dog model...",
                center.x - 120f,
                center.y,
                textPaint,
            )
        }

        val axisLength = 1.6f
        val axes = listOf(
            Triple(Vec3(0f, 0f, 0f), Vec3(axisLength, 0f, 0f), Color(0xFFFF5A5A) to "X"),
            Triple(Vec3(0f, 0f, 0f), Vec3(0f, axisLength, 0f), Color(0xFF47D16C) to "Y"),
            Triple(Vec3(0f, 0f, 0f), Vec3(0f, 0f, axisLength), Color(0xFF55A7FF) to "Z"),
        )

        axes.forEach { (origin, end, axisStyle) ->
            val start = project(quaternion.rotate(origin), center, axisScale * 1.2f)
            val finish = project(quaternion.rotate(end), center, axisScale * 1.2f)
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMesh(
    mesh: ObjMesh,
    quaternion: Quaternion,
    center: Offset,
    scale: Float,
) {
    val rotatedVertices = ArrayList<Vec3>(mesh.vertices.size)
    val projectedVertices = ArrayList<Offset>(mesh.vertices.size)
    mesh.vertices.forEach { vertex ->
        val rotated = quaternion.rotate(DOG_MODEL_BASE_ROTATION.rotate(vertex))
        rotatedVertices += rotated
        projectedVertices += project(rotated, center, scale)
    }
    val lightDirection = Vec3(0.28f, 0.36f, 1f).normalized()

    val visibleTriangles = ArrayList<RenderTriangle>(mesh.triangles.size)
    mesh.triangles.forEach { triangle ->
        val p0 = rotatedVertices[triangle.a]
        val p1 = rotatedVertices[triangle.b]
        val p2 = rotatedVertices[triangle.c]
        val normal = (p1 - p0).cross(p2 - p0).normalized()
        if (normal.z <= 0f) {
            return@forEach
        }
        visibleTriangles += RenderTriangle(
            a = projectedVertices[triangle.a],
            b = projectedVertices[triangle.b],
            c = projectedVertices[triangle.c],
            depth = (p0.z + p1.z + p2.z) / 3f,
            light = normal.dot(lightDirection).coerceIn(0f, 1f),
            rim = (1f - abs(normal.z)).coerceIn(0f, 1f),
        )
    }
    visibleTriangles.sortBy { it.depth }

    visibleTriangles.forEach { triangle ->
        val shade = 0.24f + triangle.light * 0.58f + triangle.rim * 0.18f
        val fillColor = Color(
            red = (0.72f * shade + 0.10f).coerceIn(0f, 1f),
            green = (0.63f * shade + 0.08f).coerceIn(0f, 1f),
            blue = (0.56f * shade + 0.06f).coerceIn(0f, 1f),
            alpha = 1f,
        )
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(triangle.a.x, triangle.a.y)
                lineTo(triangle.b.x, triangle.b.y)
                lineTo(triangle.c.x, triangle.c.y)
                close()
            },
            color = fillColor,
        )
    }

    visibleTriangles.forEach { triangle ->
        if (triangle.rim < DOG_MODEL_EDGE_RIM_THRESHOLD) {
            return@forEach
        }
        val edgeAlpha = 0.18f + triangle.rim * 0.42f
        drawLine(
            color = Color(0xFFF6F2EA).copy(alpha = edgeAlpha),
            start = triangle.a,
            end = triangle.b,
            strokeWidth = DOG_MODEL_EDGE_WIDTH,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFFF6F2EA).copy(alpha = edgeAlpha),
            start = triangle.b,
            end = triangle.c,
            strokeWidth = DOG_MODEL_EDGE_WIDTH,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFFF6F2EA).copy(alpha = edgeAlpha),
            start = triangle.c,
            end = triangle.a,
            strokeWidth = DOG_MODEL_EDGE_WIDTH,
            cap = StrokeCap.Round,
        )
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

private data class RenderTriangle(
    val a: Offset,
    val b: Offset,
    val c: Offset,
    val depth: Float,
    val light: Float,
    val rim: Float,
)

private operator fun Vec3.minus(other: Vec3): Vec3 = Vec3(
    x = x - other.x,
    y = y - other.y,
    z = z - other.z,
)

private fun Vec3.cross(other: Vec3): Vec3 = Vec3(
    x = y * other.z - z * other.y,
    y = z * other.x - x * other.z,
    z = x * other.y - y * other.x,
)

private fun Vec3.dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

private fun rotationAroundX(deg: Float): Quaternion {
    val halfRad = Math.toRadians(deg.toDouble()).toFloat() * 0.5f
    return Quaternion(
        w = cos(halfRad),
        x = sin(halfRad),
        y = 0f,
        z = 0f,
    )
}

private fun rotationAroundY(deg: Float): Quaternion {
    val halfRad = Math.toRadians(deg.toDouble()).toFloat() * 0.5f
    return Quaternion(
        w = cos(halfRad),
        x = 0f,
        y = sin(halfRad),
        z = 0f,
    )
}

private const val DOG_MODEL_ASSET = "models/dog_head_lite.obj"
private const val DOG_MODEL_VIEW_SCALE = 0.70f
private const val DOG_MODEL_CENTER_Y_OFFSET_FACTOR = 0.035f
private const val DOG_MODEL_EDGE_WIDTH = 0.8f
private const val DOG_MODEL_EDGE_RIM_THRESHOLD = 0.58f
private val DOG_MODEL_BASE_ROTATION = (rotationAroundY(0f) * rotationAroundX(6f)).normalized()
private fun no.nordicsemi.android.blinky.ui.calibration.viewmodel.DogActivityClass.labelZh(): String = when (this) {
    no.nordicsemi.android.blinky.ui.calibration.viewmodel.DogActivityClass.STATIC -> "静止"
    no.nordicsemi.android.blinky.ui.calibration.viewmodel.DogActivityClass.WALK -> "走路"
    no.nordicsemi.android.blinky.ui.calibration.viewmodel.DogActivityClass.RUN -> "跑步"
}
private fun Float.format1(): String = String.format("%.1f", this)
private fun Float.format2(): String = String.format("%.2f", this)
private fun Float.format3(): String = String.format("%.3f", this)
