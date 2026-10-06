package com.example.multisensorlogger.pdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 手机平端、顶部朝前的合成步行：竖直加速度每步一个正弦峰，陀螺 z 轴给转弯，磁场指向固定的北。 */
class PdrEngineSyntheticTest {
    private val g = 9.80665

    /**
     * @param turns 每段 (时长 s, 转弯角速度 度/s，正为右转)
     * @return 引擎
     */
    private fun walk(stepHz: Double, segments: List<Pair<Double, Double>>, staticS: Double = 5.0, magNoise: Boolean = false): PdrEngine {
        val engine = PdrEngine(PdrConfig())
        val fs = 100.0
        var t = 0.0
        var heading = 0.0           // 真航向（度，北起顺时针）
        val total = staticS + segments.sumOf { it.first } + staticS
        var n = 0L
        while (t < total) {
            val walkT = t - staticS
            var seg = -1
            var acc0 = 0.0
            for ((i, s) in segments.withIndex()) {
                if (walkT >= acc0 && walkT < acc0 + s.first) { seg = i; break }
                acc0 += s.first
            }
            val rate = if (seg >= 0) segments[seg].second else 0.0
            val vert = if (seg >= 0) 2.0 * sin(2 * PI * stepHz * walkT) else 0.0
            heading += rate / fs
            val tNs = 1_000_000_000L + Math.round(t * 1e9)
            engine.addAccel(tNs, 0.0, 0.0, g + vert)
            // 航向顺时针为正、陀螺逆时针为正
            engine.addGyro(tNs + 3_000_000, 0.0, 0.0, Math.toRadians(-rate))
            // 地磁场 ENU = (0, Bh, −Bv)；机体 x 右 = (cos h, −sin h, 0)，y 前 = (sin h, cos h, 0)
            val h = Math.toRadians(heading) + if (magNoise) 0.05 * sin(n * 0.37) else 0.0
            engine.addMag(tNs + 7_000_000, -20 * sin(h), 20 * cos(h), -40.0)
            t += 1 / fs
            n++
        }
        engine.finish()
        return engine
    }

    @Test
    fun straightWalkCountsEveryStep() {
        val e = walk(1.8, listOf(60.0 to 0.0))
        val expected = (60 * 1.8).toInt()
        assertTrue("步数 ${e.steps.size} 应接近 $expected", abs(e.steps.size - expected) <= 1)
        // 向北走：x ≈ 0
        assertTrue("横向偏差 ${e.x}", abs(e.x) < 0.5)
        assertTrue(e.y > 0)
    }

    @Test
    fun rightTurnGoesEast() {
        // 先北走 20 s，原地右转 90°（10 s × 9°/s 边走边转），再走 20 s：最后一段应朝东
        val e = walk(1.8, listOf(20.0 to 0.0, 10.0 to 9.0, 20.0 to 0.0))
        val last = e.steps.last()
        assertTrue("末段航向 ${last.headingDeg} 应接近 90°", abs(wrap180(last.headingDeg - 90.0)) < 5)
        assertTrue("应向东拐：x=${e.x}", e.x > 5)
    }

    @Test
    fun longRecordingKeepsWorkingAfterTrim() {
        // 10 分钟：覆盖多次内存裁剪
        val e = walk(1.6, listOf(600.0 to 0.0), magNoise = true)
        val expected = 1.6 * 600
        assertTrue("步数 ${e.steps.size} 应接近 $expected", abs(e.steps.size - expected) <= 3)
        assertTrue(e.steps.zipWithNext().all { (a, b) -> b.timeS > a.timeS })
        assertEquals(e.distanceM, e.steps.sumOf { it.length }, 1e-6)
        assertTrue(hypot(e.x, e.y) > 0)
    }
}
