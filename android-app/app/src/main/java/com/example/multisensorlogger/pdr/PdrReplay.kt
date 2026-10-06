package com.example.multisensorlogger.pdr

import java.io.InputStream

/**
 * 把一次记录的三路原始 CSV（timestamp_elapsed_ns,x,y,z,accuracy）按时间戳归并后回放给引擎。
 * 用于：记录列表里用当前参数重算轨迹、按已知距离标定 K，以及单元测试对拍。
 *
 * @param open 按文件名打开 CSV；缺文件时返回 null
 */
fun replayRecording(config: PdrConfig, open: (String) -> InputStream?): PdrEngine {
    class Ev(val kind: Int, val t: Long, val x: Double, val y: Double, val z: Double)

    val events = ArrayList<Ev>()
    listOf("accelerometer.csv" to Resampler.ACC, "gyroscope.csv" to Resampler.GYR, "magnetometer.csv" to Resampler.MAG)
        .forEach { (name, kind) ->
            val stream = open(name) ?: throw IllegalArgumentException("缺少 $name")
            stream.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    // 中断的记录最后一行可能只写了一半，解析不了的行直接跳过
                    val c = line.split(',')
                    val t = c.getOrNull(0)?.toLongOrNull()
                    val x = c.getOrNull(1)?.toDoubleOrNull()
                    val y = c.getOrNull(2)?.toDoubleOrNull()
                    val z = c.getOrNull(3)?.toDoubleOrNull()
                    if (t != null && x != null && y != null && z != null) events.add(Ev(kind, t, x, y, z))
                }
            }
        }
    events.sortWith(compareBy({ it.t }, { it.kind }))
    val engine = PdrEngine(config)
    for (e in events) {
        when (e.kind) {
            Resampler.ACC -> engine.addAccel(e.t, e.x, e.y, e.z)
            Resampler.GYR -> engine.addGyro(e.t, e.x, e.y, e.z)
            else -> engine.addMag(e.t, e.x, e.y, e.z)
        }
    }
    engine.finish()
    return engine
}
