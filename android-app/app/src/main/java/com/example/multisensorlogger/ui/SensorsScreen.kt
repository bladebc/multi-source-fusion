package com.example.multisensorlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.multisensorlogger.LocationStatus
import com.example.multisensorlogger.RecorderUiState
import com.example.multisensorlogger.SamplePoint
import com.example.multisensorlogger.SensorStream
import com.example.multisensorlogger.StreamStatus
import java.util.Locale

/** 传感器页：三路 IMU 实时波形 + 位置流状态（实验①的检查界面）。 */
@Composable
fun SensorsScreen(recorder: RecorderUiState, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (recorder.recording) "${recorder.mode?.title ?: "采集"} · ${recorder.segment}" else "开始采集后显示实时波形",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SensorStream.entries.forEach { stream ->
            SensorCard(stream, recorder.streams[stream] ?: StreamStatus())
        }
        LocationCard(recorder.location)
    }
}

private data class AxisColors(val x: Color, val y: Color, val z: Color)

@Composable
private fun axisColors() = if (isSystemInDarkTheme()) {
    AxisColors(Color(0xFFFF8A80), Color(0xFF7FD3A0), Color(0xFF8AB4F8))
} else {
    AxisColors(Color(0xFFD43F3A), Color(0xFF18864A), Color(0xFF2966CC))
}

@Composable
private fun SensorCard(stream: SensorStream, status: StreamStatus) {
    val axes = axisColors()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${stream.title} · ${if (status.available) "可用" else "不可用"}", fontWeight = FontWeight.SemiBold)
            Text(
                "${status.count} 条 · 请求 50 Hz · 实测 ${formatRate(status.measuredHz)} · 最大间隔 ${fmt1(status.maxGapMs)} ms · 长间隔 ${status.longGapCount} 次",
                style = MaterialTheme.typography.bodySmall,
            )
            status.latest?.let { s ->
                Text(
                    "X ${fmt1(s.x.toDouble())}  Y ${fmt1(s.y.toDouble())}  Z ${fmt1(s.z.toDouble())} ${stream.unit}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Waveform(status.waveform, axes)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("● X", color = axes.x, style = MaterialTheme.typography.bodySmall)
                Text("● Y", color = axes.y, style = MaterialTheme.typography.bodySmall)
                Text("● Z", color = axes.z, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LocationCard(location: LocationStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("GNSS / 位置", fontWeight = FontWeight.SemiBold)
            Text(
                "${location.permission} · 来源 ${location.provider} · ${location.count} 条 · 请求 1 Hz · 实测 ${formatRate(location.measuredHz)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "最大间隔 ${fmt1(location.maxGapMs)} ms · 长间隔 ${location.longGapCount} 次",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(location.message, style = MaterialTheme.typography.bodySmall)
            if (location.latitude != null && location.longitude != null) {
                Text(
                    "${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)} · 精度 ${location.accuracyMeters?.let { fmt1(it.toDouble()) + " m" } ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 按原始时间戳画三轴波形，纵轴对称自动缩放，左上角标幅值、底部标时间窗。 */
@Composable
private fun Waveform(points: List<SamplePoint>, axes: AxisColors) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    Canvas(Modifier.fillMaxWidth().height(104.dp)) {
        drawRect(colors.surfaceVariant)
        val middle = size.height / 2f
        drawLine(colors.outlineVariant, Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
        if (points.size < 2) return@Canvas
        val maxAbsolute = points.maxOf { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y), kotlin.math.abs(it.z)) }
            .coerceAtLeast(0.01f)
        val scale = size.height * 0.36f / maxAbsolute
        val startNs = points.first().timestampNs
        val spanNs = (points.last().timestampNs - startNs).coerceAtLeast(1L)
        drawText(measurer, "±${fmt1(maxAbsolute.toDouble())}", Offset(4.dp.toPx(), 2.dp.toPx()), label)
        drawText(measurer, "0 s", Offset(4.dp.toPx(), size.height - 14.dp.toPx()), label)
        val duration = measurer.measure("${fmt1(spanNs / 1_000_000_000.0)} s", label)
        drawText(duration, topLeft = Offset(size.width - duration.size.width - 4.dp.toPx(), size.height - 14.dp.toPx()))
        listOf(
            axes.x to { p: SamplePoint -> p.x },
            axes.y to { p: SamplePoint -> p.y },
            axes.z to { p: SamplePoint -> p.z },
        ).forEach { (color, value) ->
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = (point.timestampNs - startNs).toFloat() * size.width / spanNs
                val y = middle - value(point) * scale
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private fun formatRate(value: Double?): String = value?.let { "${fmt1(it)} Hz" } ?: "—"
private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
