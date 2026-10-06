package com.example.multisensorlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.multisensorlogger.pdr.PdrStep
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val GRID_STEPS = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0)

/**
 * PDR 轨迹图：ENU 平面，上北右东，等比例、自动缩放到装下全部轨迹（至少 8 m 见方）。
 * @param headingDeg 当前航向；为 null 时用最后一步的航向画箭头
 * @param emptyHint 还没有步时画在中间的提示
 */
@Composable
fun TrajectoryCanvas(
    steps: List<PdrStep>,
    headingDeg: Double?,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val marker = if (isSystemInDarkTheme()) MarkerDark else MarkerLight
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)
    val last = steps.lastOrNull()
    val description = if (last == null) emptyHint else
        "轨迹 ${steps.size} 步，当前位置东 ${fmt1(last.x)} 米、北 ${fmt1(last.y)} 米"

    Canvas(modifier.semantics { contentDescription = description }) {
        drawRect(colors.surfaceContainer)
        // ---- 视野：包含起点与全部步，留 12% 边距，至少 8 m
        var minX = 0.0; var maxX = 0.0; var minY = 0.0; var maxY = 0.0
        for (s in steps) {
            minX = min(minX, s.x); maxX = max(maxX, s.x)
            minY = min(minY, s.y); maxY = max(maxY, s.y)
        }
        val span = max(8.0, max(maxX - minX, maxY - minY) * 1.24)
        val scale = min(size.width, size.height) / span      // 像素 / 米
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        fun toScreen(x: Double, y: Double) = Offset(
            (size.width / 2 + (x - cx) * scale).toFloat(),
            (size.height / 2 - (y - cy) * scale).toFloat(),
        )

        // ---- 网格：1/2/5 系列，屏幕短边上 4–8 格
        val grid = GRID_STEPS.firstOrNull { span / it <= 8 } ?: GRID_STEPS.last()
        val halfW = size.width / 2 / scale
        val halfH = size.height / 2 / scale
        var gx = floor((cx - halfW) / grid) * grid
        while (gx <= cx + halfW) {
            val p = toScreen(gx, 0.0)
            drawLine(colors.outlineVariant, Offset(p.x, 0f), Offset(p.x, size.height), 1.dp.toPx())
            gx += grid
        }
        var gy = floor((cy - halfH) / grid) * grid
        while (gy <= cy + halfH) {
            val p = toScreen(0.0, gy)
            drawLine(colors.outlineVariant, Offset(0f, p.y), Offset(size.width, p.y), 1.dp.toPx())
            gy += grid
        }
        val gridLabel = "每格 ${if (grid < 1) fmt1(grid) else grid.toInt().toString()} m"
        drawText(measurer, gridLabel, Offset(8.dp.toPx(), size.height - 22.dp.toPx()), labelStyle)
        drawNorth(colors.onSurfaceVariant, measurer, labelStyle)

        // ---- 路线
        val start = toScreen(0.0, 0.0)
        if (steps.isNotEmpty()) {
            val path = Path().apply {
                moveTo(start.x, start.y)
                steps.forEach { val p = toScreen(it.x, it.y); lineTo(p.x, p.y) }
            }
            drawPath(path, colors.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // ---- 起点：空心圆 + 标注
        drawCircle(colors.surfaceContainer, 7.dp.toPx(), start)
        drawCircle(colors.tertiary, 7.dp.toPx(), start, style = Stroke(3.dp.toPx()))
        drawText(measurer, "起点", start + Offset(10.dp.toPx(), 2.dp.toPx()), labelStyle)

        // ---- 当前位置：实心点 + 航向箭头
        val heading = headingDeg ?: last?.headingDeg
        val here = last?.let { toScreen(it.x, it.y) } ?: start
        if (heading != null) {
            rotate(heading.toFloat(), pivot = here) {
                val tip = here + Offset(0f, -22.dp.toPx())
                val arrow = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(here.x - 7.dp.toPx(), here.y - 8.dp.toPx())
                    lineTo(here.x + 7.dp.toPx(), here.y - 8.dp.toPx())
                    close()
                }
                drawPath(arrow, marker)
            }
        }
        if (last != null || heading != null) {
            drawCircle(colors.surfaceContainer, 8.dp.toPx(), here)
            drawCircle(marker, 6.dp.toPx(), here)
        }

        if (steps.isEmpty()) {
            val layout = measurer.measure(emptyHint, TextStyle(color = colors.onSurfaceVariant, fontSize = 15.sp))
            drawText(
                layout,
                topLeft = Offset((size.width - layout.size.width) / 2, size.height * 0.68f),
            )
        }
    }
}

private fun DrawScope.drawNorth(
    color: androidx.compose.ui.graphics.Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
) {
    val c = Offset(size.width - 22.dp.toPx(), 34.dp.toPx())
    val arrow = Path().apply {
        moveTo(c.x, c.y - 12.dp.toPx())
        lineTo(c.x - 6.dp.toPx(), c.y + 6.dp.toPx())
        lineTo(c.x, c.y + 2.dp.toPx())
        lineTo(c.x + 6.dp.toPx(), c.y + 6.dp.toPx())
        close()
    }
    drawPath(arrow, color)
    val n = measurer.measure("N", style)
    drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - 12.dp.toPx() - n.size.height))
}

/** 起点到当前位置的直线距离（闭合路线就是闭合误差）。 */
fun closureDistance(steps: List<PdrStep>): Double = steps.lastOrNull()?.let { kotlin.math.hypot(it.x, it.y) } ?: 0.0
