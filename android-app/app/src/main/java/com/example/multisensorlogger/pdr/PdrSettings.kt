package com.example.multisensorlogger.pdr

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** 用户可调的 PDR 参数，存在 SharedPreferences；每次采集开始时读一次，采集中不变。 */
object PdrSettings {
    private const val PREFS = "pdr_settings"

    /** 可调范围：超出范围的值不保存，避免把步检或滤波调成无意义的状态。 */
    val K_RANGE = 0.1..1.0
    val ALPHA_RANGE = 0.0..1.0
    val STATIC_RANGE = 1.0..10.0
    val FLOOR_RANGE = 0.1..3.0

    fun load(context: Context): PdrConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = PdrConfig()
        return d.copy(
            k = p.number("k", K_RANGE) ?: d.k,
            alpha = p.number("alpha", ALPHA_RANGE) ?: d.alpha,
            staticS = p.number("static_s", STATIC_RANGE) ?: d.staticS,
            absFloor = p.number("abs_floor", FLOOR_RANGE) ?: d.absFloor,
            model = StepModel.entries.firstOrNull { it.name == p.getString("model", null) } ?: d.model,
        )
    }

    private fun android.content.SharedPreferences.number(key: String, range: ClosedFloatingPointRange<Double>): Double? =
        getString(key, null)?.toDoubleOrNull()?.takeIf { it in range }

    fun save(context: Context, config: PdrConfig) {
        require(config.k in K_RANGE && config.alpha in ALPHA_RANGE &&
            config.staticS in STATIC_RANGE && config.absFloor in FLOOR_RANGE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            // 存十进制字符串而不是 Float：0.353 存成 Float 读回来会变成 0.35299998…
            putString("k", config.k.toString())
            putString("alpha", config.alpha.toString())
            putString("static_s", config.staticS.toString())
            putString("abs_floor", config.absFloor.toString())
            putString("model", config.model.name)
        }
    }
}

/** 写进 session.json 的参数块，离线复现时照此填 run_pdr.py 的参数。 */
fun PdrConfig.toJson(): JSONObject = JSONObject()
    .put("resample_hz", fs)
    .put("step_signal", "vertical")
    .put("gravity_window_s", gravityWindowS)
    .put("smooth_s", smoothS)
    .put("thresh_ratio", threshRatio)
    .put("abs_floor_mps2", absFloor)
    .put("min_gap_s", minGapS)
    .put("step_model", model.name.lowercase())
    .put("weinberg_k", k)
    .put("alpha", alpha)
    .put("tau_s", if (tauS.isFinite()) tauS else JSONObject.NULL)
    .put("static_s", staticS)
