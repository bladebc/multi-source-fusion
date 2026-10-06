package com.example.multisensorlogger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

class RecorderService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var recordingThread: HandlerThread
    private lateinit var worker: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private var writer: SessionWriter? = null
    private var sensors: Map<SensorStream, Sensor?> = emptyMap()
    private var locationSelection = LocationSelection(null, "未授权", emptyList())
    private var locationMessage = "尚未请求定位"
    private var lastSegment = ""
    private var lastCheckpointSecond = -1L
    private var startRequested = false
    private val registeredStreams = mutableSetOf<SensorStream>()
    private val waveforms = SensorStream.entries.associateWith { ArrayDeque<SamplePoint>() }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val session = writer ?: return
            try {
                session.recordLocation(location)
                locationMessage = if (location.provider == LocationManager.GPS_PROVIDER) {
                    "GNSS 已定位"
                } else {
                    "粗略位置；并非 GNSS 定位"
                }
            } catch (error: Exception) {
                failRecording("位置数据写入失败：${error.message}")
            }
        }

        override fun onProviderDisabled(provider: String) {
            writer?.addWarning("定位提供者 $provider 已关闭")
            locationMessage = "定位服务已关闭"
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        recordingThread = HandlerThread("MultiSensorRecorder").apply { start() }
        worker = Handler(recordingThread.looper)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.post { finishRecording("stopped") }
            ACTION_START -> {
                if (startRequested || writer != null) return START_NOT_STICKY
                startRequested = true
                val mode = CaptureMode.entries.firstOrNull {
                    it.name == intent.getStringExtra(EXTRA_MODE)
                } ?: CaptureMode.MANUAL
                val spec = SessionSpec(
                    mode = mode,
                    label = intent.getStringExtra(EXTRA_LABEL).orEmpty().trim().ifBlank { "自由采集" },
                    posture = intent.getStringExtra(EXTRA_POSTURE).orEmpty().trim().ifBlank { "手持固定姿态" },
                )
                locationSelection = chooseLocation()
                try {
                    beginForeground(locationSelection.provider != null, "正在准备采集")
                } catch (error: Exception) {
                    startRequested = false
                    RecorderBus.update { it.copy(message = "无法启动前台采集：${error.message}") }
                    stopSelf()
                    return START_NOT_STICKY
                }
                worker.post { beginRecording(spec) }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(spec: SessionSpec) {
        try {
            sensors = SensorStream.entries.associateWith { sensorManager.getDefaultSensor(it.type) }
            val session = SessionWriter(
                context = this,
                spec = spec,
                sensors = sensors,
                locationProvider = locationSelection.provider,
                locationPermission = locationSelection.permission,
                initialWarnings = locationSelection.warnings,
            )
            writer = session
            waveforms.values.forEach { it.clear() }
            registeredStreams.clear()
            lastCheckpointSecond = -1L
            lastSegment = ""

            sensors.forEach { (stream, sensor) ->
                if (sensor != null) {
                    if (sensorManager.registerListener(this, sensor, 20_000, 0, worker)) {
                        registeredStreams.add(stream)
                    } else {
                        session.addWarning("${stream.title}监听器注册失败")
                    }
                }
            }
            registerLocation(session)
            RecorderBus.update {
                it.copy(
                    recording = true,
                    mode = spec.mode,
                    elapsedSeconds = 0,
                    segment = segmentFor(spec.mode, 0, spec.label),
                    message = "正在采集，锁屏后会继续记录",
                )
            }
            worker.post(ticker)
        } catch (error: Exception) {
            failRecording("启动采集失败：${error.message}")
        }
    }

    private fun registerLocation(session: SessionWriter) {
        val provider = locationSelection.provider
        if (provider == null) {
            locationMessage = locationSelection.warnings.firstOrNull() ?: "本次未启用位置采集"
            return
        }
        try {
            // Permission is checked immediately before starting the service.
            locationManager.requestLocationUpdates(provider, 1_000L, 0f, locationListener, recordingThread.looper)
            locationMessage = if (provider == LocationManager.GPS_PROVIDER) {
                "等待 GNSS 定位；室外开阔处更容易获得位置点"
            } else {
                "等待粗略位置；本次没有 GNSS 权限"
            }
        } catch (error: SecurityException) {
            session.addWarning("定位权限在采集中不可用：${error.message}")
            locationMessage = "定位权限不可用"
        } catch (error: IllegalArgumentException) {
            session.addWarning("定位提供者不可用：${error.message}")
            locationMessage = "定位提供者不可用"
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val session = writer ?: return
        val stream = SensorStream.entries.firstOrNull { it.type == event.sensor.type } ?: return
        try {
            session.recordSensor(stream, event)
            if (event.values.size >= 3) {
                val wave = waveforms.getValue(stream)
                wave.addLast(SamplePoint(event.values[0], event.values[1], event.values[2]))
                while (wave.size > 180) wave.removeFirst()
            }
        } catch (error: Exception) {
            failRecording("${stream.title}数据写入失败：${error.message}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private val ticker = object : Runnable {
        override fun run() {
            val session = writer ?: return
            val elapsedNs = SystemClock.elapsedRealtimeNanos() - session.startedElapsedNs
            val elapsedSeconds = elapsedNs / 1_000_000_000L
            val limit = session.spec.mode.durationSeconds
            if (limit != null && elapsedNs >= limit * 1_000_000_000L) {
                finishRecording("completed")
                return
            }
            val segment = segmentFor(session.spec.mode, elapsedSeconds, session.spec.label)
            if (segment != lastSegment) {
                lastSegment = segment
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("正在采集：$segment"))
            }
            if (elapsedSeconds >= lastCheckpointSecond + 5) {
                try {
                    session.checkpoint()
                    lastCheckpointSecond = elapsedSeconds
                } catch (error: Exception) {
                    failRecording("保存采集数据失败：${error.message}")
                    return
                }
            }
            publishState(session, elapsedSeconds, segment)
            worker.postDelayed(this, 250L)
        }
    }

    private fun publishState(session: SessionWriter, elapsedSeconds: Long, segment: String) {
        val streamStatus = SensorStream.entries.associateWith { stream ->
            val metrics = session.metrics.getValue(stream)
            val points = waveforms.getValue(stream).toList()
            StreamStatus(
                available = stream in registeredStreams,
                count = metrics.count,
                measuredHz = metrics.liveHz,
                maxGapMs = metrics.maxGapMs,
                longGapCount = metrics.longGapCount,
                latest = points.lastOrNull(),
                waveform = points,
            )
        }
        val fix = session.lastLocation
        val location = LocationStatus(
            provider = locationSelection.provider ?: "未启用",
            permission = locationSelection.permission,
            count = session.locationMetrics.count,
            measuredHz = session.locationMetrics.liveHz,
            maxGapMs = session.locationMetrics.maxGapMs,
            longGapCount = session.locationMetrics.longGapCount,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracyMeters = if (fix?.hasAccuracy() == true) fix.accuracy else null,
            message = locationMessage,
        )
        RecorderBus.update {
            it.copy(
                recording = true,
                elapsedSeconds = elapsedSeconds,
                segment = segment,
                streams = streamStatus,
                location = location,
            )
        }
    }

    private fun failRecording(message: String) {
        writer?.addWarning(message)
        RecorderBus.update { it.copy(message = message) }
        finishRecording("interrupted")
    }

    private fun finishRecording(status: String) {
        worker.removeCallbacks(ticker)
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(locationListener) }
        val session = writer
        writer = null
        startRequested = false
        if (session != null) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - session.startedElapsedNs) / 1_000_000_000L
            val segment = segmentFor(session.spec.mode, elapsed, session.spec.label)
            publishState(session, elapsed, segment)
            try {
                session.close(status)
                RecorderBus.update {
                    it.copy(
                        recording = false,
                        message = if (status == "interrupted") "采集中断，已保留现有数据" else "采集已保存",
                        lastSavedSessionId = session.id,
                    )
                }
            } catch (error: Exception) {
                RecorderBus.update {
                    it.copy(recording = false, message = "关闭采集文件失败：${error.message}")
                }
            }
        } else {
            RecorderBus.update { it.copy(recording = false) }
        }
        mainHandler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun segmentFor(mode: CaptureMode, elapsedSeconds: Long, label: String): String = when (mode) {
        CaptureMode.MANUAL -> label
        CaptureMode.WALK -> "匀速步行"
        CaptureMode.PREVIEW -> when {
            elapsedSeconds < 10 -> "静坐（0–10 秒）"
            elapsedSeconds < 30 -> "常速踏步（10–30 秒）"
            else -> "快速踏步（30–40 秒）"
        }
    }

    private fun chooseLocation(): LocationSelection {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val permission = when {
            fine -> "精确定位"
            coarse -> "大致位置"
            else -> "未授权"
        }
        val gpsOn = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkOn = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        val provider = when {
            fine && gpsOn -> LocationManager.GPS_PROVIDER
            (fine || coarse) && networkOn -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        val warnings = buildList {
            if (!fine) add("未授予精确定位，无法取得 GNSS 位置点")
            if (!fine && !coarse) add("未授予位置权限，位置流为空")
            if (fine && !gpsOn) add("GPS 提供者已关闭，无法取得 GNSS 位置点")
            if (provider == null && (fine || coarse)) add("定位服务不可用，位置流为空")
            if (provider == LocationManager.NETWORK_PROVIDER) add("使用网络位置作粗略回退，记录并非 GNSS 位置点")
        }
        return LocationSelection(provider, permission, warnings)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "传感器采集",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "显示正在进行的传感器采集" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("多源传感器采集中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止并保存", stopIntent).build())
            .build()
    }

    private fun beginForeground(hasLocation: Boolean, text: String) {
        val flags = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        } else if (Build.VERSION.SDK_INT >= 29 && hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(text), flags)
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
    }

    override fun onDestroy() {
        if (writer != null) {
            worker.post { finishRecording("interrupted") }
        }
        recordingThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.multisensorlogger.START"
        const val ACTION_STOP = "com.example.multisensorlogger.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LABEL = "label"
        const val EXTRA_POSTURE = "posture"
        private const val CHANNEL_ID = "sensor_recording"
        private const val NOTIFICATION_ID = 1001
    }
}

private data class LocationSelection(
    val provider: String?,
    val permission: String,
    val warnings: List<String>,
)
