package com.example.multisensorlogger

import android.hardware.Sensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SensorStream(
    val type: Int,
    val title: String,
    val unit: String,
    val fileName: String,
) {
    ACCELEROMETER(Sensor.TYPE_ACCELEROMETER, "加速度计", "m/s²", "accelerometer.csv"),
    GYROSCOPE(Sensor.TYPE_GYROSCOPE, "陀螺仪", "rad/s", "gyroscope.csv"),
    MAGNETOMETER(Sensor.TYPE_MAGNETIC_FIELD, "磁力计", "µT", "magnetometer.csv"),
}

enum class CaptureMode(val title: String, val durationSeconds: Int?) {
    MANUAL("手动采集", null),
    PREVIEW("40 秒课程预演", 40),
    WALK("10 分钟步行", 600),
}

data class SamplePoint(val x: Float, val y: Float, val z: Float, val timestampNs: Long = 0)

data class StreamStatus(
    val available: Boolean = false,
    val count: Long = 0,
    val measuredHz: Double? = null,
    val maxGapMs: Double = 0.0,
    val longGapCount: Long = 0,
    val latest: SamplePoint? = null,
    val waveform: List<SamplePoint> = emptyList(),
)

data class LocationStatus(
    val provider: String = "未启用",
    val permission: String = "未授权",
    val count: Long = 0,
    val measuredHz: Double? = null,
    val maxGapMs: Double = 0.0,
    val longGapCount: Long = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val message: String = "尚未开始采集",
)

data class RecorderUiState(
    val recording: Boolean = false,
    val mode: CaptureMode? = null,
    val elapsedSeconds: Long = 0,
    val segment: String = "",
    val streams: Map<SensorStream, StreamStatus> = SensorStream.entries.associateWith { StreamStatus() },
    val location: LocationStatus = LocationStatus(),
    val message: String = "准备就绪",
    val lastSavedSessionId: String? = null,
)

object RecorderBus {
    private val mutable = MutableStateFlow(RecorderUiState())
    val state = mutable.asStateFlow()

    fun set(value: RecorderUiState) {
        mutable.value = value
    }

    fun update(change: (RecorderUiState) -> RecorderUiState) {
        mutable.update(change)
    }
}
