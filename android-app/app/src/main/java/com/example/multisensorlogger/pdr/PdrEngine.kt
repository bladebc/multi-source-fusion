package com.example.multisensorlogger.pdr

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** PDR 参数；默认值与 pdr/run_pdr.py 的默认管线一致，K 取图书馆 22 m 直线标定值。 */
data class PdrConfig(
    val fs: Double = 50.0,
    val gravityWindowS: Double = 1.0,
    val smoothS: Double = 0.18,
    val threshRatio: Double = 0.9,
    val absFloor: Double = 0.5,
    val minGapS: Double = 0.3,
    val model: StepModel = StepModel.WEINBERG,
    val k: Double = 0.353,
    val alpha: Double = 0.998,
    val staticS: Double = 3.0,
) {
    /** 互补滤波时间常数 τ ≈ Δt·α/(1−α)（秒）；α = 1 时为无穷。 */
    val tauS: Double get() = if (alpha >= 1) Double.POSITIVE_INFINITY else alpha / (1 - alpha) / fs
}

data class PdrStep(
    val number: Int,
    /** 相对网格 0 点的秒数（与 run_pdr.py 的时间轴一致）。 */
    val timeS: Double,
    val timestampNs: Long,
    val peak: Double,
    val valley: Double,
    val length: Double,
    val headingDeg: Double,
    val x: Double,
    val y: Double,
)

/**
 * 在线 PDR 引擎：逐条喂入原始传感器事件，按步输出位置。算法与 pdr/pdr.py 的默认管线逐点一致：
 *
 *  ① 步检：竖直动态加速度（重力 = 1 s 居中滑动均值）→ 0.18 s 居中平滑 →
 *     峰高 ≥ max(0.9 × 均值, 0.5 m/s²) → 峰距 ≥ 0.3 s（同 scipy find_peaks 的 distance 规则：高峰优先）
 *  ② 步长：Weinberg，峰谷取在平滑后的 |a| 上（峰：步时刻 ±0.15 s 最大；谷：与上一步之间最小）
 *  ③ 航向：陀螺投影到竖直轴 + 倾角补偿磁航向，50 Hz 逐点互补滤波；开头 staticS 秒磁航向圆均值作初值
 *  ④ 位置：x += L·sinψ，y += L·cosψ
 *
 * 在线化的代价只是延迟：居中窗口要等后半窗数据（重力 0.5 s + 平滑 0.08 s），峰要等 0.3 s 确认
 * 没有更高的邻峰，合计约 0.9 s。唯一与离线不同之处：阈值里的「均值」用截至当时的累计均值
 * （竖直动态加速度均值≈0，阈值实际由 0.5 m/s² 下限决定，结果相同）。
 *
 * 非线程安全：所有调用须在同一线程。
 */
class PdrEngine(val config: PdrConfig = PdrConfig()) {
    private val gW = max(1, (config.gravityWindowS * config.fs).toInt())
    private val gLo = gW / 2
    private val gHi = gW - 1 - gW / 2
    private val sW = max(1, Math.round(config.smoothS * config.fs).toInt())
    private val sLo = sW / 2
    private val sHi = sW - 1 - sW / 2
    private val distance = max(1, (config.minGapS * config.fs).toInt())
    private val peakHalf = max(1, (0.15 * config.fs).toInt())
    private val firstValleyBack = (0.6 * config.fs).toInt()
    private val staticN = Math.ceil(config.staticS * config.fs - 1e-9).toInt().coerceAtLeast(1)

    private val store = Store()
    private val resampler = Resampler(config.fs) { store.addRaw(it) }

    private var nB = 0          // 已算出竖直分量 / 竖直角速度 / 磁航向的点数
    private var nS = 0          // 已平滑的点数
    private var nH = 0          // 已算出航向的点数
    private var smSum = 0.0
    private var scan = 1        // 峰扫描指针（scipy 从下标 1 起）
    private val cluster = ArrayList<Int>()      // 尚未定案的一簇候选峰（相邻间距 < distance）
    private val pending = ArrayDeque<Int>()     // 已定案、等航向的步下标
    private var lastStepIndex = -1
    private var finished = false
    private var lastTrim = 0

    var psi0: Double? = null
        private set
    val steps = ArrayList<PdrStep>()
    var x = 0.0
        private set
    var y = 0.0
        private set
    var distanceM = 0.0
        private set

    /** 每确认一步回调一次（在调用 addXxx 或 finish 的线程上）。 */
    var onStep: ((PdrStep) -> Unit)? = null

    val gridOriginNs: Long? get() = resampler.gridOriginNs
    val samples: Int get() = store.nRaw
    val aligning: Boolean get() = psi0 == null
    /** 初始对准已收集的秒数。 */
    val alignedSeconds: Double get() = min(nB, staticN) / config.fs
    /** 最新航向（度）；对准完成前为 null。约滞后 0.5 s。 */
    val headingDeg: Double? get() = if (nH > 0) store.psi(nH - 1) else null
    val droppedSamples: Long get() = resampler.droppedCount

    fun addAccel(tNs: Long, x: Double, y: Double, z: Double) = add(Resampler.ACC, tNs, x, y, z)
    fun addGyro(tNs: Long, x: Double, y: Double, z: Double) = add(Resampler.GYR, tNs, x, y, z)
    fun addMag(tNs: Long, x: Double, y: Double, z: Double) = add(Resampler.MAG, tNs, x, y, z)

    private fun add(kind: Int, tNs: Long, x: Double, y: Double, z: Double) {
        if (finished) return
        resampler.add(kind, tNs, x, y, z)
        advance()
    }

    /** 记录结束：末端按「延续端点值」补齐（同 moving_mean），把剩下的步全部定案。 */
    fun finish() {
        if (finished) return
        finished = true
        advance()
    }

    private fun advance() {
        val n = store.nRaw
        while (nB < n && (finished || nB + gHi < n)) computeVertical(nB++)
        while (nS < nB && (finished || nS + sHi < nB)) computeSmooth(nS++)
        if (psi0 == null && (nB >= staticN || (finished && nB > 0))) {
            psi0 = circularMeanDeg((0 until min(nB, staticN)).map { store.magAz(it) }) ?: 0.0
        }
        psi0?.let { p0 ->
            while (nH < nB) {
                val k = nH++
                store.setPsi(
                    k,
                    if (k == 0) p0 else headingUpdate(
                        store.psi(k - 1), store.gzv(k), store.magAz(k),
                        (store.tNs(k) - store.tNs(k - 1)) / 1e9, config.alpha,
                    ),
                )
            }
        }
        scanPeaks()
        commitSteps()
        if (store.nRaw - lastTrim > 500) trim()
    }

    private fun computeVertical(i: Int) {
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        val last = store.nRaw - 1
        for (j in i - gLo..i + gHi) {
            val c = j.coerceIn(0, last)
            sx += store.ax(c); sy += store.ay(c); sz += store.az(c)
        }
        sx /= gW; sy /= gW; sz /= gW
        val gn = sqrt(sx * sx + sy * sy + sz * sz)
        val ax = store.ax(i); val ay = store.ay(i); val az = store.az(i)
        val vert: Double
        val gzv: Double
        if (gn > 1e-9) {
            vert = (ax * sx + ay * sy + az * sz) / gn - gn
            gzv = (store.gx(i) * sx + store.gy(i) * sy + store.gz(i) * sz) / gn
        } else {
            vert = 0.0; gzv = store.gz(i)
        }
        store.setB(
            i, vert, gzv,
            magAzimuth(ax, ay, az, store.mx(i), store.my(i), store.mz(i)),
            sqrt(ax * ax + ay * ay + az * az),
        )
    }

    private fun computeSmooth(i: Int) {
        var a = 0.0; var b = 0.0
        val last = nB - 1
        for (j in i - sLo..i + sHi) {
            val c = j.coerceIn(0, last)
            a += store.vert(c); b += store.accNorm(c)
        }
        a /= sW; b /= sW
        store.setS(i, a, b)
        smSum += a
    }

    private fun threshold(): Double = max(config.threshRatio * smSum / max(nS, 1), config.absFloor)

    /** scipy.signal._peak_finding_utils._local_maxima_1d 的在线版：严格上升后持平再下降，取平台中点。 */
    private fun scanPeaks() {
        val iMax = nS - 1
        while (scan < iMax) {
            val i = scan
            if (store.sm(i - 1) < store.sm(i)) {
                var ahead = i + 1
                while (ahead < iMax && store.sm(ahead) == store.sm(i)) ahead++
                if (!finished && ahead >= iMax && store.sm(ahead) == store.sm(i)) return  // 平台还没走完
                if (store.sm(ahead) < store.sm(i)) {
                    val mid = (i + ahead - 1) / 2
                    if (store.sm(mid) >= threshold()) {
                        if (cluster.isNotEmpty() && mid - cluster.last() >= distance) closeCluster()
                        cluster.add(mid)
                    }
                    scan = ahead + 1
                    continue
                }
            }
            scan++
        }
        // 下一个候选峰至少在 scan 处；与簇尾相距已够远，簇不会再变
        if (cluster.isNotEmpty() && (finished || scan - cluster.last() >= distance)) closeCluster()
    }

    /** 簇内按 find_peaks(distance=) 的规则取舍：从高到低，删掉距已保留峰不足 distance 的峰。 */
    private fun closeCluster() {
        val peaks = cluster.toIntArray()
        cluster.clear()
        val keep = BooleanArray(peaks.size) { true }
        val order = peaks.indices.sortedBy { store.sm(peaks[it]) }.reversed()
        for (j in order) {
            if (!keep[j]) continue
            var k = j - 1
            while (k >= 0 && peaks[j] - peaks[k] < distance) keep[k--] = false
            k = j + 1
            while (k < peaks.size && peaks[k] - peaks[j] < distance) keep[k++] = false
        }
        peaks.indices.filter { keep[it] }.forEach { pending.addLast(peaks[it]) }
    }

    private fun commitSteps() {
        while (pending.isNotEmpty()) {
            val k = pending.first()
            if (k >= nH || (!finished && k + peakHalf >= nS)) return
            pending.removeFirst()
            var peak = Double.NEGATIVE_INFINITY
            for (j in max(0, k - peakHalf)..min(nS - 1, k + peakHalf)) peak = max(peak, store.accSm(j))
            val lo = if (lastStepIndex >= 0) lastStepIndex else max(0, k - firstValleyBack)
            var valley = Double.POSITIVE_INFINITY
            for (j in lo..k) valley = min(valley, store.accSm(j))
            val length = stepLength(peak, valley, config.model, config.k)
            val psi = store.psi(k)
            val r = Math.toRadians(psi)
            x += length * sin(r)
            y += length * cos(r)
            distanceM += length
            lastStepIndex = k
            val step = PdrStep(
                number = steps.size + 1,
                timeS = (store.tNs(k) - gridOriginNs!!) / 1e9,
                timestampNs = store.tNs(k),
                peak = peak,
                valley = valley,
                length = length,
                headingDeg = psi,
                x = x,
                y = y,
            )
            steps.add(step)
            onStep?.invoke(step)
        }
    }

    /** 丢掉以后再也用不到的旧点，长时间采集内存不随时长增长（站着不动时保留到上一步为止）。 */
    private fun trim() {
        lastTrim = store.nRaw
        var keep = minOf(nB - gLo - 1, nS - sLo - 1, scan - 1, nH - 1)
        if (cluster.isNotEmpty()) keep = min(keep, cluster.first() - firstValleyBack - peakHalf)
        if (pending.isNotEmpty()) keep = min(keep, pending.first() - firstValleyBack - peakHalf)
        if (lastStepIndex >= 0) keep = min(keep, lastStepIndex)
        if (psi0 == null) keep = 0
        store.trimBefore(keep)
    }

    /**
     * 按网格下标存数据的环形区：下标只增不减，trimBefore 之后旧下标不可再访问。
     * 原始 9 轴 + 时间戳，竖直分量 / 竖直角速度 / 磁航向 / |a|，两条平滑序列，航向。
     */
    private class Store {
        private val cols = 17
        private var buf = DoubleArray(cols * 1024)
        private var times = LongArray(1024)
        private var base = 0
        var nRaw = 0
            private set

        private fun at(i: Int, c: Int): Int {
            check(i >= base) { "下标 $i 已被裁掉（base=$base）" }
            return (i - base) * cols + c
        }

        fun addRaw(s: GridSample) {
            val rel = nRaw - base
            if ((rel + 1) * cols > buf.size) {
                buf = buf.copyOf(buf.size * 2)
                times = times.copyOf(times.size * 2)
            }
            val o = rel * cols
            buf[o] = s.ax; buf[o + 1] = s.ay; buf[o + 2] = s.az
            buf[o + 3] = s.gx; buf[o + 4] = s.gy; buf[o + 5] = s.gz
            buf[o + 6] = s.mx; buf[o + 7] = s.my; buf[o + 8] = s.mz
            times[rel] = s.tNs
            nRaw++
        }

        fun trimBefore(index: Int) {
            val drop = index - base
            if (drop < 256) return
            System.arraycopy(buf, drop * cols, buf, 0, (nRaw - index) * cols)
            System.arraycopy(times, drop, times, 0, nRaw - index)
            base = index
        }

        fun tNs(i: Int) = times[i - base].also { check(i >= base) }
        fun ax(i: Int) = buf[at(i, 0)]
        fun ay(i: Int) = buf[at(i, 1)]
        fun az(i: Int) = buf[at(i, 2)]
        fun gx(i: Int) = buf[at(i, 3)]
        fun gy(i: Int) = buf[at(i, 4)]
        fun gz(i: Int) = buf[at(i, 5)]
        fun mx(i: Int) = buf[at(i, 6)]
        fun my(i: Int) = buf[at(i, 7)]
        fun mz(i: Int) = buf[at(i, 8)]
        fun vert(i: Int) = buf[at(i, 9)]
        fun gzv(i: Int) = buf[at(i, 10)]
        fun magAz(i: Int) = buf[at(i, 11)]
        fun accNorm(i: Int) = buf[at(i, 12)]
        fun sm(i: Int) = buf[at(i, 13)]
        fun accSm(i: Int) = buf[at(i, 14)]
        fun psi(i: Int) = buf[at(i, 15)]

        fun setB(i: Int, vert: Double, gzv: Double, magAz: Double, accNorm: Double) {
            val o = at(i, 0)
            buf[o + 9] = vert; buf[o + 10] = gzv; buf[o + 11] = magAz; buf[o + 12] = accNorm
        }

        fun setS(i: Int, sm: Double, accSm: Double) {
            val o = at(i, 0)
            buf[o + 13] = sm; buf[o + 14] = accSm
        }

        fun setPsi(i: Int, psi: Double) {
            buf[at(i, 15)] = psi
        }
    }
}
