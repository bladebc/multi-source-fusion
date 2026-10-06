package com.example.multisensorlogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionSpec(
    val mode: CaptureMode,
    val label: String,
    val posture: String,
)

data class SavedSession(
    val id: String,
    val title: String,
    val startedAt: String,
    val status: String,
    val durationSeconds: Long,
    val qualitySummary: String,
)

class StreamMetrics(private val longGapThresholdNs: Long) {
    var count: Long = 0
        private set
    var maxGapNs: Long = 0
        private set
    var longGapCount: Long = 0
        private set
    var nonMonotonicCount: Long = 0
        private set
    private var lastTimestampNs: Long? = null
    private var totalValidIntervalNs: Long = 0
    private var validIntervalCount: Long = 0
    private val recentTimestampsNs = ArrayDeque<Long>()

    val maxGapMs: Double get() = maxGapNs / 1_000_000.0
    val measuredHz: Double?
        get() = if (totalValidIntervalNs > 0) {
            validIntervalCount * 1_000_000_000.0 / totalValidIntervalNs
        } else null
    val liveHz: Double?
        get() {
            if (recentTimestampsNs.size < 2) return null
            val span = recentTimestampsNs.last() - recentTimestampsNs.first()
            return if (span > 0) (recentTimestampsNs.size - 1) * 1_000_000_000.0 / span else null
        }

    fun add(timestampNs: Long) {
        count++
        val previous = lastTimestampNs
        if (previous != null && timestampNs <= previous) {
            nonMonotonicCount++
            return
        }
        if (previous != null) {
            val gap = timestampNs - previous
            validIntervalCount++
            totalValidIntervalNs += gap
            maxGapNs = maxOf(maxGapNs, gap)
            if (gap > longGapThresholdNs) longGapCount++
        }
        lastTimestampNs = timestampNs
        recentTimestampsNs.addLast(timestampNs)
        while (recentTimestampsNs.size > 250 ||
            (recentTimestampsNs.size > 2 && timestampNs - recentTimestampsNs.first() > 5_000_000_000L)
        ) {
            recentTimestampsNs.removeFirst()
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("count", count)
        .put("measured_hz", measuredHz ?: JSONObject.NULL)
        .put("max_gap_ms", maxGapMs)
        .put("long_gap_count", longGapCount)
        .put("long_gap_threshold_ms", longGapThresholdNs / 1_000_000.0)
        .put("non_monotonic_timestamp_count", nonMonotonicCount)
}

class SessionWriter(
    context: Context,
    val spec: SessionSpec,
    private val sensors: Map<SensorStream, Sensor?>,
    val locationProvider: String?,
    private val locationPermission: String,
    initialWarnings: List<String>,
) {
    val id: String = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        .withZone(java.time.ZoneOffset.UTC)
        .format(Instant.now()) + "_" + UUID.randomUUID().toString().take(8)
    private val directory = File(File(context.filesDir, "sessions"), id).apply { mkdirs() }
    val startedAtUtc: Instant = Instant.now()
    val startedElapsedNs: Long = SystemClock.elapsedRealtimeNanos()
    private var endedAtUtc: Instant? = null
    private var endedElapsedNs: Long? = null
    private val writers = SensorStream.entries.associateWith { stream ->
        newWriter(File(directory, stream.fileName), "timestamp_elapsed_ns,x,y,z,accuracy")
    }
    private val locationWriter = newWriter(
        File(directory, "gnss.csv"),
        "timestamp_elapsed_ns,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,bearing_deg,provider",
    )
    val metrics = SensorStream.entries.associateWith { StreamMetrics(100_000_000L) }
    val locationMetrics = StreamMetrics(5_000_000_000L)
    private val warnings = initialWarnings.toMutableList()
    private var registeredStreams: Set<SensorStream> = emptySet()
    private var closed = false
    var lastLocation: Location? = null
        private set

    init {
        sensors.forEach { (stream, sensor) ->
            if (sensor == null) warnings.add("${stream.title}不可用")
        }
        saveManifest("recording")
    }

    fun setRegisteredStreams(streams: Set<SensorStream>) {
        registeredStreams = streams.toSet()
        checkpoint()
    }

    fun recordSensor(stream: SensorStream, event: SensorEvent) {
        if (closed) return
        val values = event.values
        if (values.size < 3) return
        val writer = writers.getValue(stream)
        writer.write("${event.timestamp},${values[0]},${values[1]},${values[2]},${event.accuracy}\n")
        metrics.getValue(stream).add(event.timestamp)
    }

    fun recordLocation(location: Location) {
        if (closed) return
        locationWriter.write(
            listOf(
                location.elapsedRealtimeNanos.toString(),
                location.latitude.toString(),
                location.longitude.toString(),
                if (location.hasAltitude()) location.altitude.toString() else "",
                if (location.hasAccuracy()) location.accuracy.toString() else "",
                if (location.hasSpeed()) location.speed.toString() else "",
                if (location.hasBearing()) location.bearing.toString() else "",
                location.provider ?: "",
            ).joinToString(",") + "\n",
        )
        locationMetrics.add(location.elapsedRealtimeNanos)
        lastLocation = location
    }

    fun addWarning(warning: String) {
        if (warning !in warnings) warnings.add(warning)
    }

    fun checkpoint() {
        if (closed) return
        writers.values.forEach { it.flush() }
        locationWriter.flush()
        saveManifest("recording")
    }

    fun close(status: String) {
        if (closed) return
        endedAtUtc = Instant.now()
        endedElapsedNs = SystemClock.elapsedRealtimeNanos()
        SensorStream.entries.forEach { stream ->
            val data = metrics.getValue(stream)
            if (data.count == 0L) addWarning("${stream.title}没有样本")
            if (data.longGapCount > 0) addWarning("${stream.title}存在 ${data.longGapCount} 次长间隔")
        }
        if (locationMetrics.count == 0L) addWarning("本次采集没有取得位置点")
        writers.values.forEach { it.close() }
        locationWriter.close()
        closed = true
        saveManifest(status)
    }

    private fun saveManifest(status: String) {
        val durationNs = (endedElapsedNs ?: SystemClock.elapsedRealtimeNanos()) - startedElapsedNs
        val streamsJson = JSONObject()
        SensorStream.entries.forEach { stream ->
            val sensor = sensors[stream]
            val data = metrics.getValue(stream).toJson()
                .put("file", stream.fileName)
                .put("available", stream in registeredStreams)
                .put("sensor_present", sensor != null)
                .put("listener_registered", stream in registeredStreams)
                .put("requested_hz", 50)
                .put("unit", stream.unit)
            if (sensor != null) {
                data.put("sensor_name", sensor.name)
                    .put("vendor", sensor.vendor)
                    .put("version", sensor.version)
                    .put("resolution", sensor.resolution)
                    .put("max_range", sensor.maximumRange)
                    .put("min_delay_us", sensor.minDelay)
                    .put("wake_up_sensor", sensor.isWakeUpSensor)
            }
            streamsJson.put(stream.name.lowercase(), data)
        }
        val locationJson = locationMetrics.toJson()
            .put("file", "gnss.csv")
            .put("requested_hz", 1)
            .put("provider", locationProvider ?: JSONObject.NULL)
            .put("permission", locationPermission)
            .put("gps_provider_used", locationProvider == "gps")
        val json = JSONObject()
            .put("schema_version", 1)
            .put("id", id)
            .put("status", status)
            .put("mode", spec.mode.name.lowercase())
            .put("mode_title", spec.mode.title)
            .put("label", spec.label)
            .put("posture_note", spec.posture)
            .put("started_at_utc", startedAtUtc.toString())
            .put("started_at_local", startedAtUtc.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("ended_at_utc", endedAtUtc?.toString() ?: JSONObject.NULL)
            .put("start_elapsed_ns", startedElapsedNs)
            .put("end_elapsed_ns", endedElapsedNs ?: JSONObject.NULL)
            .put("duration_ms", durationNs / 1_000_000)
            .put("timestamp_clock", "SystemClock.elapsedRealtimeNanos, nanoseconds since boot")
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_api", Build.VERSION.SDK_INT))
            .put("streams", streamsJson)
            .put("location", locationJson)
            .put("segments", segments(durationNs))
            .put("warnings", JSONArray(warnings))

        val temp = File(directory, "session.json.tmp")
        temp.writeText(json.toString(2), StandardCharsets.UTF_8)
        val target = File(directory, "session.json")
        if (!temp.renameTo(target)) {
            target.writeText(json.toString(2), StandardCharsets.UTF_8)
            temp.delete()
        }
    }

    private fun segments(durationNs: Long): JSONArray {
        val result = JSONArray()
        val templates = when (spec.mode) {
            CaptureMode.MANUAL -> listOf(Triple(0L, Long.MAX_VALUE, spec.label.ifBlank { "自由采集" }))
            CaptureMode.WALK -> listOf(Triple(0L, 600_000_000_000L, "匀速步行"))
            CaptureMode.PREVIEW -> listOf(
                Triple(0L, 10_000_000_000L, "静坐"),
                Triple(10_000_000_000L, 30_000_000_000L, "常速踏步"),
                Triple(30_000_000_000L, 40_000_000_000L, "快速踏步"),
            )
        }
        templates.forEach { (start, end, label) ->
            if (durationNs > start) {
                result.put(JSONObject()
                    .put("label", label)
                    .put("start_elapsed_ns", startedElapsedNs + start)
                    .put("end_elapsed_ns", startedElapsedNs + minOf(durationNs, end)))
            }
        }
        return result
    }

    private fun newWriter(file: File, header: String): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8), 64 * 1024)
            .also { it.write(header + "\n") }
}

object SessionStorage {
    private val exportedFiles = SensorStream.entries.map { it.fileName } + "gnss.csv" + "session.json"

    fun recoverInterrupted(context: Context) {
        val root = File(context.filesDir, "sessions")
        root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val manifest = File(directory, "session.json")
            if (!manifest.isFile) return@forEach
            runCatching {
                val json = JSONObject(manifest.readText(StandardCharsets.UTF_8))
                if (json.optString("status") == "recording") {
                    json.put("status", "interrupted")
                    val warnings = json.optJSONArray("warnings") ?: JSONArray()
                    warnings.put("采集进程意外中止；文件可能缺少最后几秒数据")
                    json.put("warnings", warnings)
                    manifest.writeText(json.toString(2), StandardCharsets.UTF_8)
                }
            }
        }
    }

    fun list(context: Context): List<SavedSession> {
        val root = File(context.filesDir, "sessions")
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, "session.json").isFile }
            ?.mapNotNull { directory ->
                runCatching {
                    val json = JSONObject(File(directory, "session.json").readText(StandardCharsets.UTF_8))
                    SavedSession(
                        id = directory.name,
                        title = json.optString("mode_title", "采集记录"),
                        startedAt = json.optString("started_at_local", ""),
                        status = json.optString("status", "unknown"),
                        durationSeconds = json.optLong("duration_ms") / 1000,
                        qualitySummary = qualitySummary(json),
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.id }
            ?: emptyList()
    }

    private fun qualitySummary(json: JSONObject): String {
        val streams = json.optJSONObject("streams")
        val imu = SensorStream.entries.joinToString("；") { stream ->
            val data = streams?.optJSONObject(stream.name.lowercase())
            val hz = if (data != null && !data.isNull("measured_hz")) {
                String.format(java.util.Locale.US, "%.1f Hz", data.optDouble("measured_hz"))
            } else "频率未知"
            "${stream.title} ${data?.optLong("count") ?: 0} 条 / $hz / 长间隔 ${data?.optLong("long_gap_count") ?: 0} 次"
        }
        val warnings = json.optJSONArray("warnings")
        val warningText = (0 until (warnings?.length() ?: 0)).joinToString("；") { warnings!!.optString(it) }
        return "$imu\n位置 ${json.optJSONObject("location")?.optLong("count") ?: 0} 条" +
            if (warningText.isBlank()) "" else "\n注意：$warningText"
    }

    fun exportZip(context: Context, sessionId: String, uri: Uri) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(File(context.filesDir, "sessions"), sessionId)
        require(directory.isDirectory) { "采集记录不存在" }
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("无法打开导出位置")
        output.use { raw ->
            ZipOutputStream(raw).use { zip ->
                exportedFiles.forEach { name ->
                    val file = File(directory, name)
                    require(file.isFile) { "缺少文件：$name" }
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
