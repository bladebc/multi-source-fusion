package com.example.multisensorlogger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.multisensorlogger.SavedSession
import com.example.multisensorlogger.SessionStorage
import com.example.multisensorlogger.pdr.PdrConfig
import com.example.multisensorlogger.pdr.PdrStep
import com.example.multisensorlogger.pdr.StepModel
import com.example.multisensorlogger.pdr.calibrateK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 记录页：本机采集记录列表；点一条展开操作（轨迹回放 / 导出 / 删除）。 */
@Composable
fun SessionsScreen(
    sessions: List<SavedSession>,
    recording: Boolean,
    config: PdrConfig,
    onExport: (SavedSession) -> Unit,
    onDelete: (SavedSession) -> Unit,
    onSaveK: (Double) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var replaying by remember { mutableStateOf<SavedSession?>(null) }
    var deleting by remember { mutableStateOf<SavedSession?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "共 ${sessions.size} 条，记录只存在本机，卸载 App 会一并删除",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
        if (sessions.isEmpty()) {
            Text("还没有采集记录。", Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    expanded = expanded == session.id,
                    onToggle = { expanded = if (expanded == session.id) null else session.id },
                    busy = session.status == "recording",
                    onReplay = { replaying = session },
                    onExport = { onExport(session) },
                    onDelete = { deleting = session },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    replaying?.let { session ->
        ReplayDialog(session, config, onSaveK, onDismiss = { replaying = null })
    }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记录？") },
            text = { Text("${session.title}\n${session.startedAt}\n\n删除后无法恢复；需要保留的请先导出 ZIP。") },
            confirmButton = {
                TextButton(onClick = { onDelete(session); deleting = null }, enabled = !recording || session.status != "recording") {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(
    session: SavedSession,
    expanded: Boolean,
    onToggle: () -> Unit,
    busy: Boolean,
    onReplay: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(session.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "${formatDuration(session.durationSeconds)} · ${statusText(session.status)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(session.startedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        session.pdrSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (expanded) {
            Text(session.qualitySummary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReplay, enabled = !busy) { Text("轨迹 / 标定") }
                OutlinedButton(onClick = onExport, enabled = !busy) { Text("导出 ZIP") }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text("删除", color = if (busy) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private sealed interface ReplayResult {
    data object Loading : ReplayResult
    data class Done(val steps: List<PdrStep>, val distance: Double) : ReplayResult
    data class Failed(val message: String) : ReplayResult
}

/**
 * 用当前参数把一条记录重算一遍并画轨迹；输入已知距离可按 K = D / Σ⁴√(峰−谷) 标定。
 * 峰谷与 K 无关，所以一次回放就能算出新 K，保存后自动按新 K 重算。
 */
@Composable
private fun ReplayDialog(session: SavedSession, config: PdrConfig, onSaveK: (Double) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<ReplayResult>(ReplayResult.Loading) }
    var distanceText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(session.id, config) {
        result = ReplayResult.Loading
        result = withContext(Dispatchers.Default) {
            runCatching { SessionStorage.replay(context, session.id, config) }.fold(
                onSuccess = { ReplayResult.Done(it.steps.toList(), it.distanceM) },
                onFailure = { ReplayResult.Failed(it.message ?: "回放失败") },
            )
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("轨迹回放", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(session.startedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                when (val r = result) {
                    ReplayResult.Loading -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                    }
                    is ReplayResult.Failed -> Text("回放失败：${r.message}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    is ReplayResult.Done -> {
                        TrajectoryCanvas(r.steps, null, "这条记录没有检测到步", Modifier.weight(1f).fillMaxWidth())
                        Text(
                            "${r.steps.size} 步 · 里程 ${fmt1(r.distance)} m · 离起点 ${fmt1(closureDistance(r.steps))} m" +
                                " · 用当前参数（K ${fmt3(config.k)}，α ${config.alpha}）重算",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Calibration(r.steps, config, distanceText, { distanceText = it }, onSaveK)
                    }
                }
            }
        }
    }
}

@Composable
private fun Calibration(
    steps: List<PdrStep>,
    config: PdrConfig,
    distanceText: String,
    onDistanceChange: (String) -> Unit,
    onSaveK: (Double) -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("标定 K", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (config.model != StepModel.WEINBERG) {
        Text("当前是恒定步长模型，不需要标定。", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        "这条记录应是一段已知长度的路（例如量好的 22 m 直线，首尾静止不影响）。填实际距离，按全部检出步算 K。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val d = distanceText.toDoubleOrNull()
    val newK = d?.let { calibrateK(steps.map { it.peak to it.valley }, it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = distanceText,
            onValueChange = onDistanceChange,
            label = { Text("实际距离 m") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { newK?.let(onSaveK) },
            enabled = newK != null && newK in com.example.multisensorlogger.pdr.PdrSettings.K_RANGE,
            modifier = Modifier.fillMaxHeight(),
        ) { Text("保存为 K") }
    }
    when {
        distanceText.isNotEmpty() && (d == null || d <= 0) -> Text("请输入正数距离", color = MaterialTheme.colorScheme.error)
        distanceText.isNotEmpty() && newK == null -> Text("这条记录没有有效步，无法标定", color = MaterialTheme.colorScheme.error)
        newK != null -> Text(
            "K ${fmt3(config.k)} → ${fmt3(newK)}" +
                if (newK !in com.example.multisensorlogger.pdr.PdrSettings.K_RANGE) "（超出 0.1–1.0，请检查距离或步检）" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun statusText(status: String): String = when (status) {
    "completed" -> "已完成"
    "interrupted" -> "中断"
    "stopped" -> "手动停止"
    "recording" -> "进行中"
    else -> status
}
