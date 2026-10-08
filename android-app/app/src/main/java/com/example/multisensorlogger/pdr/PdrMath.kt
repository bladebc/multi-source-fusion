package com.example.multisensorlogger.pdr

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * PDR 的纯函数部分，与仓库 pdr/pdr.py 一一对应（第 6 次课「接口发放」页的签名）：
 *
 *     step_length(peak_val, valley_val)                 → stepLength
 *     heading_update(psi, gyro_z, mag_az, dt, alpha)    → headingUpdate
 *     detect_steps(acc_mag, fs)                         → StepDetector（在线版，见 PdrEngine）
 *
 * 单位约定：角度用度，长度用米，时间用秒。坐标系 ENU：x 东、y 北，航向 ψ 从正北顺时针量。
 */

const val STEP_CONST_M = 0.75

enum class StepModel(val title: String) {
    WEINBERG("Weinberg"),
    CONST("恒定 0.75 m"),
}

/** 把角度卷绕到 [-180, 180)：−66° 和 294° 是同一个方向。 */
fun wrap180(deg: Double): Double {
    val r = (deg + 180.0) % 360.0
    return (if (r < 0) r + 360.0 else r) - 180.0
}

/**
 * 本步步长 L（米）。
 * WEINBERG：L = K · ⁴√(amax − amin)，迈大步起伏就大，K 要走已知距离标定。
 * CONST：恒定 0.75 m，零参数零标定。
 */
fun stepLength(peakVal: Double, valleyVal: Double, model: StepModel = StepModel.WEINBERG, k: Double): Double =
    when (model) {
        StepModel.CONST -> STEP_CONST_M
        StepModel.WEINBERG -> k * max(peakVal - valleyVal, 0.0).pow(0.25)
    }

/** 走一段已知距离 D：Σ K·⁴√Δ = D ⇒ K = D / Σ ⁴√Δ。没有有效步时返回 null。 */
fun calibrateK(peakValleys: List<Pair<Double, Double>>, knownDistanceM: Double): Double? {
    if (!knownDistanceM.isFinite() || knownDistanceM <= 0) return null
    val sum = peakValleys.sumOf { (p, v) -> max(p - v, 0.0).pow(0.25) }
    return if (sum > 0 && sum.isFinite()) knownDistanceM / sum else null
}

/**
 * 互补滤波更新航向，返回新 ψ（度）：ψ̂ ← α(ψ̂ + ω·Δt) + (1 − α)ψ_mag。
 *
 * gyroZ 是绕竖直轴的角速度（度/秒，逆时针为正，与 Android 陀螺 z 轴同向），航向顺时针为正，
 * 所以航向变化率 = −gyroZ。写成「预测 + (1−α)×卷绕后的差值」，避免 350° 与 10° 平均成 180°。
 * magAz 为 null 或 NaN 时退化为纯陀螺积分。
 */
fun headingUpdate(psi: Double, gyroZ: Double, magAz: Double?, dt: Double, alpha: Double = 0.98): Double {
    val pred = psi - gyroZ * dt
    if (magAz == null || magAz.isNaN()) return wrap180(pred)
    return wrap180(pred + (1 - alpha) * wrap180(magAz - pred))
}

/**
 * 磁力计 + 加速度计 → 倾角补偿后的磁航向（度），与 SensorManager.getRotationMatrix 同一套几何：
 * H = m × g 指东，M = g × H 指北，航向 = atan2(H_y, M_y)。acc 用含重力的原始读数。
 * 两向量近乎平行（磁场竖直或自由落体）时返回 NaN。
 */
fun magAzimuth(ax: Double, ay: Double, az: Double, mx: Double, my: Double, mz: Double): Double {
    var hx = my * az - mz * ay
    var hy = mz * ax - mx * az
    var hz = mx * ay - my * ax
    val hn = sqrt(hx * hx + hy * hy + hz * hz)
    val an = sqrt(ax * ax + ay * ay + az * az)
    if (hn < 1e-9 || an < 1e-9) return Double.NaN
    hx /= hn; hy /= hn; hz /= hn
    val gx = ax / an
    val gz = az / an
    // M = A × H，只用到 y 分量
    val my2 = gz * hx - gx * hz
    return Math.toDegrees(atan2(hy, my2))
}

/** 复数平均求角度的圆均值（度），用于静止段初始对准；没有有效值时返回 null。 */
fun circularMeanDeg(values: Iterable<Double>): Double? {
    var s = 0.0
    var c = 0.0
    var n = 0
    for (v in values) {
        if (v.isNaN()) continue
        val r = Math.toRadians(v)
        s += kotlin.math.sin(r)
        c += kotlin.math.cos(r)
        n++
    }
    return if (n == 0) null else Math.toDegrees(atan2(s, c))
}
