package com.example.multisensorlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.util.Locale

/* 配色取自纸质地图：亮色是米白底、墨蓝路线；暗色是夜航图的深海军蓝底。起点用绿色，当前位置用朱红。 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4E8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F7),
    onPrimaryContainer = Color(0xFF0B2547),
    secondary = Color(0xFF4D5B6B),
    secondaryContainer = Color(0xFFDCE3EC),
    onSecondaryContainer = Color(0xFF1B2733),
    tertiary = Color(0xFF2E7D4F),
    error = Color(0xFFB3261E),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1F24),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1B1F24),
    surfaceVariant = Color(0xFFE9E6DE),
    onSurfaceVariant = Color(0xFF4A4F57),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F0EA),
    surfaceContainer = Color(0xFFEFEDE6),
    surfaceContainerHigh = Color(0xFFE9E7E0),
    surfaceContainerHighest = Color(0xFFE4E1D9),
    outline = Color(0xFF8A8F98),
    outlineVariant = Color(0xFFD3D0C8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color(0xFF07254D),
    primaryContainer = Color(0xFF1D3A66),
    onPrimaryContainer = Color(0xFFD6E3F7),
    secondary = Color(0xFFB7C3D1),
    secondaryContainer = Color(0xFF2A3646),
    onSecondaryContainer = Color(0xFFD6DEE8),
    tertiary = Color(0xFF7FD3A0),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF0F1620),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF0F1620),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF1C2532),
    onSurfaceVariant = Color(0xFFB4BCC8),
    surfaceContainerLowest = Color(0xFF0A1018),
    surfaceContainerLow = Color(0xFF131A25),
    surfaceContainer = Color(0xFF161E2A),
    surfaceContainerHigh = Color(0xFF1A2330),
    surfaceContainerHighest = Color(0xFF1F2937),
    outline = Color(0xFF6F7887),
    outlineVariant = Color(0xFF2A3442),
)

/** 当前位置标记色（朱红），亮暗主题各一。 */
val MarkerLight = Color(0xFFD9480F)
val MarkerDark = Color(0xFFFF8A5B)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/* ---- 底部导航的三个小图标：直接画，免得为三个图标引一个图标库 ---- */

@Composable
fun TrackIcon(color: Color) = Canvas(Modifier.size(24.dp)) {
    val p = Path().apply {
        moveTo(size.width * 0.15f, size.height * 0.85f)
        lineTo(size.width * 0.35f, size.height * 0.45f)
        lineTo(size.width * 0.6f, size.height * 0.6f)
        lineTo(size.width * 0.85f, size.height * 0.18f)
    }
    drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color, 2.5.dp.toPx(), Offset(size.width * 0.85f, size.height * 0.18f))
}

@Composable
fun WaveIcon(color: Color) = Canvas(Modifier.size(24.dp)) {
    val p = Path()
    val n = 24
    for (i in 0..n) {
        val x = size.width * (0.1f + 0.8f * i / n)
        val y = size.height * (0.5f - 0.3f * kotlin.math.sin(i / n.toFloat() * 4 * Math.PI).toFloat())
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}

@Composable
fun ListIcon(color: Color) = Canvas(Modifier.size(24.dp)) {
    for (row in 0..2) {
        val y = size.height * (0.28f + 0.22f * row)
        drawCircle(color, 1.8.dp.toPx(), Offset(size.width * 0.18f, y))
        drawLine(color, Offset(size.width * 0.32f, y), Offset(size.width * 0.86f, y), 2.dp.toPx(), StrokeCap.Round)
    }
}

/* ---- 数字格式 ---- */

fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)
fun formatDuration(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

/** session.json 的 ISO 时间 → 「10-06 17:05:06」；解析不了就原样返回。 */
fun formatStartedAt(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}.getOrDefault(iso)

/** 航向 → 「16° 北」式读法：度数 + 八方位（顺时针从北量）。 */
fun headingText(deg: Double): String {
    val d = ((deg % 360) + 360) % 360
    val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    return "${Math.round(d)}° ${names[((d + 22.5) / 45).toInt() % 8]}"
}
