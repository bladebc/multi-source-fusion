package com.example.multisensorlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.example.multisensorlogger.pdr.PdrConfig
import com.example.multisensorlogger.pdr.PdrSettings
import com.example.multisensorlogger.ui.AppActions
import com.example.multisensorlogger.ui.AppScreen
import com.example.multisensorlogger.ui.AppTheme
import com.example.multisensorlogger.ui.Toast
import com.example.multisensorlogger.ui.fmt3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 只负责系统交互：权限、启动/停止采集服务、文件导出、设置持久化；界面在 ui/ 包。 */
class MainActivity : ComponentActivity(), AppActions {
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private var pendingSpec: SessionSpec? = null
    private var pendingExportId: String? = null
    private var preparationJob: Job? = null
    private var preparationSeconds by mutableIntStateOf(0)
    private var savedSessions by mutableStateOf<List<SavedSession>>(emptyList())
    private var pdrConfig by mutableStateOf(PdrConfig())
    private var label by mutableStateOf("自由采集")
    private var posture by mutableStateOf("手持固定姿态")
    private var toast by mutableStateOf<Toast?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingExportId = savedInstanceState?.getString("pending_export_id")
        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { requestNotificationThenStart() }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { launchPendingSession() }
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri -> if (uri != null) exportTo(uri) }

        pdrConfig = PdrSettings.load(this)
        getSharedPreferences(CAPTURE_PREFS, MODE_PRIVATE).let {
            label = it.getString("label", null) ?: label
            posture = it.getString("posture", null) ?: posture
        }
        if (!RecorderBus.state.value.recording) {
            SessionStorage.recoverInterrupted(this)
            RecorderBus.update { it.copy(pdr = it.pdr.copy(config = pdrConfig)) }
        }
        refreshSavedSessions()
        updateSensorAvailability()

        setContent {
            val recorder by RecorderBus.state.collectAsState()
            LaunchedEffect(recorder.lastSavedSessionId) { refreshSavedSessions() }
            // 采集时保持亮屏：投屏演示不黑屏；停止后恢复系统默认
            LaunchedEffect(recorder.recording) {
                if (recorder.recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            AppTheme {
                AppScreen(
                    recorder = recorder,
                    sessions = savedSessions,
                    preparationSeconds = preparationSeconds,
                    label = label,
                    posture = posture,
                    config = pdrConfig,
                    toast = toast,
                    actions = this,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export_id", pendingExportId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!RecorderBus.state.value.recording) SessionStorage.recoverInterrupted(this)
        refreshSavedSessions()
        updateSensorAvailability()
    }

    private fun showToast(text: String) {
        toast = Toast((toast?.id ?: 0) + 1, text)
    }

    // ---------------------------------------------------------------- AppActions

    override fun start(mode: CaptureMode) {
        prepareCapture(SessionSpec(mode, label, posture))
    }

    override fun stop() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
    }

    override fun cancelPreparation() {
        preparationJob?.cancel()
        preparationJob = null
        preparationSeconds = 0
    }

    override fun export(session: SavedSession) {
        pendingExportId = session.id
        exportLauncher.launch("session_${session.id}.zip")
    }

    override fun delete(session: SavedSession) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SessionStorage.delete(this@MainActivity, session.id) } }
            showToast(result.fold({ "已删除" }, { "删除失败：${it.message}" }))
            refreshSavedSessions()
        }
    }

    override fun refreshSessions() = refreshSavedSessions()

    override fun saveSettings(label: String, posture: String, config: PdrConfig) {
        this.label = label
        this.posture = posture
        getSharedPreferences(CAPTURE_PREFS, MODE_PRIVATE).edit {
            putString("label", label)
            putString("posture", posture)
        }
        applyConfig(config)
        showToast(if (RecorderBus.state.value.recording) "已保存，下次采集生效" else "已保存")
    }

    override fun saveK(k: Double) {
        applyConfig(pdrConfig.copy(k = k))
        showToast("K 已保存为 ${fmt3(k)}，下次采集生效")
    }

    private fun applyConfig(config: PdrConfig) {
        PdrSettings.save(this, config)
        pdrConfig = config
        if (!RecorderBus.state.value.recording) RecorderBus.update { it.copy(pdr = it.pdr.copy(config = config)) }
    }

    // ---------------------------------------------------------------- 采集启动链：倒计时 → 定位权限 → 通知权限 → 前台服务

    private fun prepareCapture(spec: SessionSpec) {
        if (preparationJob?.isActive == true || pendingSpec != null || RecorderBus.state.value.recording) return
        preparationJob = lifecycleScope.launch {
            try {
                for (seconds in 5 downTo 1) {
                    preparationSeconds = seconds
                    delay(1_000)
                }
                startCapture(spec)
            } finally {
                preparationSeconds = 0
            }
        }
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

    // ---------------------------------------------------------------- 记录与状态

    private fun refreshSavedSessions() {
        savedSessions = SessionStorage.list(this)
    }

    private fun exportTo(uri: Uri) {
        val sessionId = pendingExportId ?: return
        pendingExportId = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SessionStorage.exportZip(this@MainActivity, sessionId, uri) } }
            showToast(result.fold({ "ZIP 已导出" }, { "导出失败：${it.message}" }))
        }
    }

    private fun updateSensorAvailability() {
        if (RecorderBus.state.value.recording) return
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val gpsOn = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkOn = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val permission = when {
            fine -> "精确定位"
            coarse -> "大致位置"
            else -> "未授权"
        }
        val provider = when {
            fine && gpsOn -> "gps"
            (fine || coarse) && networkOn -> "network"
            else -> "未启用"
        }
        val locationMessage = when {
            !fine && !coarse -> "尚未授予位置权限；开始采集时会请求"
            !fine -> "仅有大致位置权限，无法采集 GNSS 位置点"
            !gpsOn -> "精确定位已授权，但手机的定位总开关或 GPS 未开启"
            else -> "GNSS 权限和 GPS 已开启；采集时仍需等待卫星定位"
        }
        RecorderBus.update { previous ->
            previous.copy(
                streams = SensorStream.entries.associateWith { stream ->
                    (previous.streams[stream] ?: StreamStatus()).copy(available = manager.getDefaultSensor(stream.type) != null)
                },
                location = previous.location.copy(
                    permission = permission,
                    provider = provider,
                    message = locationMessage,
                ),
            )
        }
    }

    companion object {
        private const val CAPTURE_PREFS = "capture"
    }
}
