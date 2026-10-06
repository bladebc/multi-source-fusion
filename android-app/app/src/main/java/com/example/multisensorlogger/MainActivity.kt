package com.example.multisensorlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private var pendingSpec: SessionSpec? = null
    private var pendingExportId: String? = null
    private var savedSessions by mutableStateOf<List<SavedSession>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { requestNotificationThenStart() }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { launchPendingSession() }
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri -> if (uri != null) exportTo(uri) }

        if (!RecorderBus.state.value.recording) SessionStorage.recoverInterrupted(this)
        refreshSavedSessions()
        updateSensorAvailability()

        setContent {
            val recorder by RecorderBus.state.collectAsState()
            var label by rememberSaveable { mutableStateOf("自由采集") }
            var posture by rememberSaveable { mutableStateOf("手持固定姿态") }
            LaunchedEffect(recorder.lastSavedSessionId) { refreshSavedSessions() }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(
                        recorder = recorder,
                        sessions = savedSessions,
                        label = label,
                        onLabelChange = { label = it },
                        posture = posture,
                        onPostureChange = { posture = it },
                        onStart = { mode -> startCapture(SessionSpec(mode, label, posture)) },
                        onStop = { stopCapture() },
                        onExport = { beginExport(it) },
                        onRefresh = { refreshSavedSessions() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!RecorderBus.state.value.recording) SessionStorage.recoverInterrupted(this)
        refreshSavedSessions()
        updateSensorAvailability()
    }

    private fun updateSensorAvailability() {
        if (RecorderBus.state.value.recording) return
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        RecorderBus.update { previous ->
            previous.copy(streams = SensorStream.entries.associateWith { stream ->
                previous.streams[stream].orEmpty().copy(available = manager.getDefaultSensor(stream.type) != null)
            })
        }
    }

    private fun refreshSavedSessions() {
        savedSessions = SessionStorage.list(this)
    }

    private fun startCapture(spec: SessionSpec) {
        if (RecorderBus.state.value.recording || pendingSpec != null) return
        pendingSpec = spec
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        } else {
            requestNotificationThenStart()
        }
    }

    private fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchPendingSession()
        }
    }

    private fun launchPendingSession() {
        val spec = pendingSpec ?: return
        pendingSpec = null
        val intent = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_MODE, spec.mode.name)
            .putExtra(RecorderService.EXTRA_LABEL, spec.label)
            .putExtra(RecorderService.EXTRA_POSTURE, spec.posture)
        try {
            startForegroundService(intent)
        } catch (error: Exception) {
            RecorderBus.update { it.copy(message = "无法启动采集：${error.message}") }
        }
    }

    private fun stopCapture() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
    }

    private fun beginExport(session: SavedSession) {
        pendingExportId = session.id
        exportLauncher.launch("session_${session.id}.zip")
    }

    private fun exportTo(uri: Uri) {
        val sessionId = pendingExportId ?: return
        pendingExportId = null
        Thread {
            val result = runCatching { SessionStorage.exportZip(this, sessionId, uri) }
            runOnUiThread {
                RecorderBus.update {
                    it.copy(message = result.fold(
                        onSuccess = { "ZIP 已导出" },
                        onFailure = { error -> "导出失败：${error.message}" },
                    ))
                }
            }
        }.start()
    }
}

private fun StreamStatus?.orEmpty() = this ?: StreamStatus()

@Composable
private fun AppScreen(
    recorder: RecorderUiState,
    sessions: List<SavedSession>,
    label: String,
    onLabelChange: (String) -> Unit,
    posture: String,
    onPostureChange: (String) -> Unit,
    onStart: (CaptureMode) -> Unit,
    onStop: () -> Unit,
    onExport: (SavedSession) -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("多源传感器采集", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("本地原始数据 · 加速度计 / 陀螺仪 / 磁力计 / GNSS", style = MaterialTheme.typography.bodyMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (recorder.recording) "● 正在采集" else "○ 未采集", fontWeight = FontWeight.Bold)
                    Text(recorder.message)
                    if (recorder.recording) {
                        Text("${recorder.mode?.title ?: "采集"} · ${formatDuration(recorder.elapsedSeconds)} · ${recorder.segment}")
                        Button(onClick = onStop) { Text("停止并保存") }
                    }
                }
            }
            if (!recorder.recording) {
                OutlinedTextField(
                    value = label,
                    onValueChange = onLabelChange,
                    label = { Text("手动采集标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = posture,
                    onValueChange = onPostureChange,
                    label = { Text("持机姿态备注") },
                    supportingText = { Text("预演建议全程保持同一种固定姿态") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onStart(CaptureMode.MANUAL) }, modifier = Modifier.fillMaxWidth()) {
                    Text("开始手动采集")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onStart(CaptureMode.PREVIEW) }, modifier = Modifier.weight(1f)) {
                        Text("40 秒预演")
                    }
                    OutlinedButton(onClick = { onStart(CaptureMode.WALK) }, modifier = Modifier.weight(1f)) {
                        Text("10 分钟步行")
                    }
                }
            }

            Text("实时波形", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SensorStream.entries.forEach { stream ->
                SensorCard(stream, recorder.streams[stream].orEmpty())
            }
            LocationCard(recorder.location)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("本机采集记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
            if (sessions.isEmpty()) Text("还没有采集记录。")
            sessions.forEach { session ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(session.title, fontWeight = FontWeight.SemiBold)
                            Text(session.startedAt, style = MaterialTheme.typography.bodySmall)
                            Text("${session.durationSeconds} 秒 · ${statusText(session.status)}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { onExport(session) }, enabled = session.status != "recording") {
                            Text("导出 ZIP")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SensorCard(stream: SensorStream, status: StreamStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${stream.title} · ${if (status.available) "可用" else "不可用"}", fontWeight = FontWeight.SemiBold)
            Text("${status.count} 条 · 请求 50 Hz · 实测 ${formatRate(status.measuredHz)} · 最大间隔 ${formatDecimal(status.maxGapMs)} ms · 长间隔 ${status.longGapCount} 次",
                style = MaterialTheme.typography.bodySmall)
            val sample = status.latest
            if (sample != null) {
                Text("X ${formatDecimal(sample.x.toDouble())}  Y ${formatDecimal(sample.y.toDouble())}  Z ${formatDecimal(sample.z.toDouble())} ${stream.unit}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Waveform(status.waveform)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("● X", color = AxisX, style = MaterialTheme.typography.bodySmall)
                Text("● Y", color = AxisY, style = MaterialTheme.typography.bodySmall)
                Text("● Z", color = AxisZ, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LocationCard(location: LocationStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("GNSS / 位置", fontWeight = FontWeight.SemiBold)
            Text("${location.permission} · 来源 ${location.provider} · ${location.count} 条 · 请求 1 Hz · 实测 ${formatRate(location.measuredHz)}",
                style = MaterialTheme.typography.bodySmall)
            Text("最大间隔 ${formatDecimal(location.maxGapMs)} ms · 长间隔 ${location.longGapCount} 次",
                style = MaterialTheme.typography.bodySmall)
            Text(location.message, style = MaterialTheme.typography.bodySmall)
            if (location.latitude != null && location.longitude != null) {
                Text("${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)} · 精度 ${location.accuracyMeters?.let { formatDecimal(it.toDouble()) + " m" } ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Waveform(points: List<SamplePoint>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(Color(0xFFF1F4F8)),
    ) {
        val middle = size.height / 2f
        drawLine(Color(0xFFCCD5DF), Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
        if (points.size < 2) return@Canvas
        val maxAbsolute = points.maxOf { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y), kotlin.math.abs(it.z)) }
            .coerceAtLeast(0.01f)
        val scale = size.height * 0.43f / maxAbsolute
        listOf(
            AxisX to { p: SamplePoint -> p.x },
            AxisY to { p: SamplePoint -> p.y },
            AxisZ to { p: SamplePoint -> p.z },
        ).forEach { (color, value) ->
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = index.toFloat() * size.width / (points.size - 1)
                val y = middle - value(point) * scale
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private val AxisX = Color(0xFFD43F3A)
private val AxisY = Color(0xFF18864A)
private val AxisZ = Color(0xFF2966CC)

private fun formatRate(value: Double?): String = value?.let { "${formatDecimal(it)} Hz" } ?: "—"
private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
private fun formatDuration(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)
private fun statusText(status: String): String = when (status) {
    "completed" -> "已完成"
    "interrupted" -> "中断"
    "stopped" -> "手动停止"
    "recording" -> "进行中"
    else -> status
}
