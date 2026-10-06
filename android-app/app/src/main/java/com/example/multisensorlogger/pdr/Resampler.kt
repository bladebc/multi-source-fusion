package com.example.multisensorlogger.pdr

/**
 * 三路传感器（加速度 / 陀螺 / 磁力计）各自不等间隔到达，这里线性插值到统一的 fs 网格，
 * 与 pdr/loader.py 的 load_sensors 同一口径：
 *   0 点 = 三路都已出数的时刻（各路首条时间戳的最大值）；
 *   第 n 个网格点 = 0 点 + n / fs，只有三路都已有不早于它的读数时才输出。
 * 陀螺读数在这里从 rad/s 换成 度/秒（签名约定）。
 */
class Resampler(private val fs: Double, private val onSample: (GridSample) -> Unit) {
    private class Raw(val tNs: Long, val x: Double, val y: Double, val z: Double)

    private val queues = Array(3) { ArrayDeque<Raw>() }
    private var originNs: Long? = null
    private var next = 0L

    /** 网格 0 点对应的开机时间戳（ns）；三路都到齐前为 null。 */
    val gridOriginNs: Long? get() = originNs

    /** 时间戳倒退或重复的读数个数（与 loader.py 不同，在线时丢弃而不报错）。 */
    var droppedCount = 0L
        private set

    fun add(kind: Int, tNs: Long, x: Double, y: Double, z: Double) {
        val q = queues[kind]
        val last = q.lastOrNull()
        if (last != null && tNs <= last.tNs) {
            droppedCount++
            return
        }
        q.addLast(Raw(tNs, x, y, z))
        if (originNs == null) {
            if (queues.any { it.isEmpty() }) return
            originNs = queues.maxOf { it.first().tNs }
        }
        drain()
    }

    private fun gridNs(n: Long): Long = originNs!! + Math.round(n * 1e9 / fs)

    private fun drain() {
        while (true) {
            val t = gridNs(next)
            if (queues.any { it.last().tNs < t }) return
            val v = DoubleArray(9)
            for (k in 0 until 3) {
                val q = queues[k]
                while (q.size >= 2 && q[1].tNs <= t) q.removeFirst()
                val a = q[0]
                if (q.size == 1 || a.tNs >= t) {
                    v[3 * k] = a.x; v[3 * k + 1] = a.y; v[3 * k + 2] = a.z
                } else {
                    val b = q[1]
                    val w = (t - a.tNs).toDouble() / (b.tNs - a.tNs)
                    v[3 * k] = a.x + w * (b.x - a.x)
                    v[3 * k + 1] = a.y + w * (b.y - a.y)
                    v[3 * k + 2] = a.z + w * (b.z - a.z)
                }
            }
            onSample(
                GridSample(
                    index = next,
                    tNs = t,
                    ax = v[0], ay = v[1], az = v[2],
                    gx = Math.toDegrees(v[3]), gy = Math.toDegrees(v[4]), gz = Math.toDegrees(v[5]),
                    mx = v[6], my = v[7], mz = v[8],
                ),
            )
            next++
        }
    }

    companion object {
        const val ACC = 0
        const val GYR = 1
        const val MAG = 2
    }
}

data class GridSample(
    val index: Long,
    val tNs: Long,
    val ax: Double, val ay: Double, val az: Double,
    val gx: Double, val gy: Double, val gz: Double,
    val mx: Double, val my: Double, val mz: Double,
)
