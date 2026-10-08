package com.example.multisensorlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.multisensorlogger.pdr.PdrConfig
import com.example.multisensorlogger.pdr.PdrSettings
import com.example.multisensorlogger.pdr.StepModel

/**
 * 采集设置：记录元数据（标签、持机姿态）+ PDR 参数。PDR 参数只影响之后开始的采集。
 */
@Composable
fun SettingsDialog(
    label: String,
    posture: String,
    config: PdrConfig,
    onSave: (label: String, posture: String, config: PdrConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var labelText by rememberSaveable { mutableStateOf(label) }
    var postureText by rememberSaveable { mutableStateOf(posture) }
    var model by rememberSaveable { mutableStateOf(config.model) }
    var k by rememberSaveable { mutableStateOf(config.k.toString()) }
    var alpha by rememberSaveable { mutableStateOf(config.alpha.toString()) }
    var static by rememberSaveable { mutableStateOf(config.staticS.toString()) }
    var floor by rememberSaveable { mutableStateOf(config.absFloor.toString()) }

    val kV = k.toDoubleOrNull()?.takeIf { it in PdrSettings.K_RANGE }
    val alphaV = alpha.toDoubleOrNull()?.takeIf { it in PdrSettings.ALPHA_RANGE }
    val staticV = static.toDoubleOrNull()?.takeIf { it in PdrSettings.STATIC_RANGE }
    val floorV = floor.toDoubleOrNull()?.takeIf { it in PdrSettings.FLOOR_RANGE }
    val valid = kV != null && alphaV != null && staticV != null && floorV != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("采集设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("记录信息", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    labelText, { labelText = it }, Modifier.fillMaxWidth(),
                    label = { Text("标签（手动采集时写入记录）") }, singleLine = true,
                )
                OutlinedTextField(
                    postureText, { postureText = it }, Modifier.fillMaxWidth(),
                    label = { Text("持机姿态") },
                    supportingText = { Text("PDR 建议平端胸前、屏幕朝上、顶部朝前，全程不换握法") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("PDR 参数", fontWeight = FontWeight.SemiBold)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StepModel.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = model == m,
                            onClick = { model = m },
                            shape = SegmentedButtonDefaults.itemShape(i, StepModel.entries.size),
                            label = { Text(m.title) },
                        )
                    }
                }
                NumberField(
                    k, { k = it }, "Weinberg K", kV != null,
                    "0.1–1.0；图书馆 22 m 标定 0.353，课件默认 0.52。在「记录」里回放已知距离可直接标定",
                    enabled = model == StepModel.WEINBERG,
                )
                NumberField(
                    alpha, { alpha = it }, "互补滤波 α", alphaV != null,
                    alphaV?.let {
                        val tau = PdrConfig(alpha = it).tauS
                        "50 Hz 逐点更新，τ ≈ ${if (tau.isFinite()) fmt1(tau) + " s" else "∞（纯陀螺）"}；0 = 纯磁力计，1 = 纯陀螺"
                    } ?: "0–1",
                )
                NumberField(static, { static = it }, "初始对准 秒", staticV != null, "1–10；开始后静止这么久，用磁航向圆均值定初始方向")
                NumberField(floor, { floor = it }, "步检阈值下限 m/s²", floorV != null, "0.1–3；漏步调低，碎步调高")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        labelText.trim().ifBlank { "自由采集" },
                        postureText.trim().ifBlank { "手持固定姿态" },
                        config.copy(model = model, k = kV!!, alpha = alphaV!!, staticS = staticV!!, absFloor = floorV!!),
                    )
                },
                enabled = valid,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                val d = PdrConfig()
                model = d.model; k = d.k.toString(); alpha = d.alpha.toString()
                static = d.staticS.toString(); floor = d.absFloor.toString()
            }) { Text("恢复默认参数") }
        },
    )
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    valid: Boolean,
    help: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = enabled && !valid,
        supportingText = { Text(help) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
