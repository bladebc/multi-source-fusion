package com.example.multisensorlogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.multisensorlogger.CaptureMode
import com.example.multisensorlogger.RecorderUiState
import com.example.multisensorlogger.pdr.StepModel

/**
 * 实时轨迹页（期中演示投屏用）：状态一行 → 轨迹图（视觉重心）→ 四个读数 → 操作区。
 */
@Composable
fun TrajectoryScreen(
    recorder: RecorderUiState,
    preparationSeconds: Int,
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancelPreparation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pdr = recorder.pdr
    Column(modifier.fillMaxSize()) {
        StatusLine(recorder, preparationSeconds)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            val hint = when {
                preparationSeconds > 0 -> "准备好后保持静止"
                recorder.recording && pdr.enabled && pdr.aligning ->
                    "初始对准 ${fmt1(pdr.alignedSeconds)} / ${fmt1(pdr.config.staticS)} s，请保持静止"
                recorder.recording && pdr.enabled -> "对准完成，开始走吧"
                recorder.recording -> pdr.message
                else -> "开始后先静止 ${fmt1(pdr.config.staticS)} s 对准，再走"
            }
            TrajectoryCanvas(
                steps = pdr.steps,
                headingDeg = if (recorder.recording) pdr.headingDeg else null,
                emptyHint = hint,
                modifier = Modifier.fillMaxSize(),
            )
            if (preparationSeconds > 0) {
                Text(
                    "$preparationSeconds",
                    // 放在上方，不挡住画布中央的起点
                    modifier = Modifier.align(BiasAlignment(0f, -0.55f)),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Readouts(recorder)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Controls(recorder, preparationSeconds, mode, onModeChange, onStart, onStop, onCancelPreparation, onOpenSettings)
    }
}

@Composable
private fun StatusLine(recorder: RecorderUiState, preparationSeconds: Int) {
    val colors = MaterialTheme.colorScheme
    val (dotColor, title) = when {
        recorder.recording -> colors.error to "采集中 ${formatDuration(recorder.elapsedSeconds)}"
        preparationSeconds > 0 -> colors.primary to "$preparationSeconds 秒后开始"
        else -> colors.outline to "未采集"
    }
    val detail = when {
        recorder.recording && recorder.pdr.enabled -> recorder.pdr.message
        recorder.recording -> recorder.segment
        else -> recorder.message
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Readouts(recorder: RecorderUiState) {
    val pdr = recorder.pdr
    val heading = pdr.headingDeg?.takeIf { recorder.recording } ?: pdr.steps.lastOrNull()?.headingDeg
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        Readout("步数", "${pdr.steps.size}", "", Modifier.weight(1f))
        Readout("里程", fmt1(pdr.distanceM), "m", Modifier.weight(1f))
        Readout("航向", heading?.let { headingText(it) } ?: "—", "", Modifier.weight(1.3f))
        Readout("离起点", fmt1(closureDistance(pdr.steps)), "m", Modifier.weight(1f))
    }
    val c = pdr.config
    Text(
        "参数：${if (c.model == StepModel.WEINBERG) "Weinberg K ${fmt3(c.k)}" else c.model.title}" +
            " · α ${c.alpha}（τ ${if (c.tauS.isFinite()) fmt1(c.tauS) + " s" else "∞"}）" +
            " · 对准 ${fmt1(c.staticS)} s",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Readout(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Controls(
    recorder: RecorderUiState,
    preparationSeconds: Int,
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancelPreparation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            recorder.recording -> Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("停止并保存", style = MaterialTheme.typography.titleMedium) }

            preparationSeconds > 0 -> OutlinedButton(
                onClick = onCancelPreparation,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("取消", style = MaterialTheme.typography.titleMedium) }

            else -> {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CaptureMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = m == mode,
                            onClick = { onModeChange(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, CaptureMode.entries.size),
                            label = { Text(m.shortTitle, maxLines = 1) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStart, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("开始采集", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.height(52.dp)) { Text("设置") }
                }
            }
        }
    }
}
