package com.example.multisensorlogger.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.multisensorlogger.CaptureMode
import com.example.multisensorlogger.RecorderUiState
import com.example.multisensorlogger.SavedSession
import com.example.multisensorlogger.pdr.PdrConfig

enum class Tab(val title: String) { TRACK("轨迹"), SENSORS("传感器"), SESSIONS("记录") }

/** 一次性提示；id 自增，同样的文字连续出现也会再弹一次。 */
data class Toast(val id: Long, val text: String)

/** 由 MainActivity 实现的动作；界面只管展示与转发。 */
interface AppActions {
    fun start(mode: CaptureMode)
    fun stop()
    fun cancelPreparation()
    fun export(session: SavedSession)
    fun delete(session: SavedSession)
    fun refreshSessions()
    fun saveSettings(label: String, posture: String, config: PdrConfig)
    fun saveK(k: Double)
}

@Composable
fun AppScreen(
    recorder: RecorderUiState,
    sessions: List<SavedSession>,
    preparationSeconds: Int,
    label: String,
    posture: String,
    config: PdrConfig,
    toast: Toast?,
    actions: AppActions,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.TRACK) }
    var mode by rememberSaveable { mutableStateOf(CaptureMode.MANUAL) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast?.id) { toast?.let { snackbar.showSnackbar(it.text) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = {
                            val c = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            when (t) {
                                Tab.TRACK -> TrackIcon(c)
                                Tab.SENSORS -> WaveIcon(c)
                                Tab.SESSIONS -> ListIcon(c)
                            }
                        },
                        label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.TRACK -> TrajectoryScreen(
                recorder = recorder,
                preparationSeconds = preparationSeconds,
                mode = mode,
                onModeChange = { mode = it },
                onStart = { actions.start(mode) },
                onStop = actions::stop,
                onCancelPreparation = actions::cancelPreparation,
                onOpenSettings = { settingsOpen = true },
                modifier = m,
            )
            Tab.SENSORS -> SensorsScreen(recorder, m)
            Tab.SESSIONS -> SessionsScreen(
                sessions = sessions,
                recording = recorder.recording,
                config = config,
                onExport = actions::export,
                onDelete = actions::delete,
                onSaveK = actions::saveK,
                onRefresh = actions::refreshSessions,
                modifier = m,
            )
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            label = label,
            posture = posture,
            config = config,
            onSave = { l, p, c -> actions.saveSettings(l, p, c); settingsOpen = false },
            onDismiss = { settingsOpen = false },
        )
    }
}
