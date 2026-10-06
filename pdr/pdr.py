"""PDR 定位器核心：严格按第 6 次课「接口发放」页的三个函数签名实现。

    detect_steps   —— 步态检测（第 5 次课 ①：平滑 · 阈值 · 峰距）
    step_length    —— 步长估计（第 5 次课 ②：恒定 0.75 m / Weinberg）
    heading_update —— 航向互补滤波（第 6 次课 ③：高频信陀螺，低频信磁力计）
    run_pdr        —— 主循环四行：psi → x += L·sinψ → y += L·cosψ → 记点

单位约定（签名即合同）：角度用「度」，长度用「米」，时间用「秒」。
坐标系：ENU，x = 东，y = 北；航向 ψ 从正北顺时针量（北 0°、东 90°）。
"""
from __future__ import annotations

import numpy as np
from scipy.signal import find_peaks

G = 9.80665

# ---- 默认参数：全部来自课件锚数据的取值，你的数据要自己重新标定 ----
SMOOTH_S = 0.18        # ① 平滑窗 0.18 s：太窄留毛刺，太宽压扁峰
THRESH_RATIO = 0.9     # ② 阈值 = 0.9 × 平滑后信号均值：太高漏步，太低碎步
ABS_FLOOR = 1.2        # 绝对下限 1.2 m/s²：「坐车不计步」第一道防线
MIN_GAP_S = 0.3        # ③ 最小峰距 0.3 s：人最快约 3 步/秒，防一步数两次
STEP_CONST = 0.75      # 恒定步长模型（死尺子）
WEINBERG_K = 0.52      # Weinberg 系数（活尺子），必须走已知距离标定


def wrap180(a):
    """把角度卷绕到 (-180, 180]：−66° 和 294° 是同一个方向。"""
    return (np.asarray(a) + 180.0) % 360.0 - 180.0


def smooth(x, fs, win_s=SMOOTH_S):
    """滑动均值（第 2 课滑窗的复用）。"""
    w = max(1, int(round(win_s * fs)))
    return moving_mean(x, w)


def moving_mean(x, w):
    """长度为 w 点的滑动均值，输出与输入等长。

    边缘用「延续端点值」补齐，而不是 np.convolve(mode="same") 默认的补 0——补 0 会让开头结尾的
    重力估计只剩一半，凭空造出约 4 m/s² 的假冲击和假步（图书馆数据首尾各多数 1 步就是它）。
    支持一维或按列处理的二维数组。
    """
    x = np.asarray(x, float)
    if x.size == 0:
        return x.copy()
    if x.ndim == 2:
        return np.column_stack([moving_mean(x[:, i], w) for i in range(x.shape[1])])
    w = max(1, int(w))
    xp = np.pad(x, (w // 2, w - 1 - w // 2), mode="edge")
    return np.convolve(xp, np.ones(w) / w, mode="valid")


# ============================================================ ① 步态检测
def detect_steps(acc_mag, fs=100, *, smooth_s=SMOOTH_S, thresh_ratio=THRESH_RATIO,
                 abs_floor=ABS_FLOOR, min_gap_s=MIN_GAP_S) -> list[float]:
    """|a−g| 平滑 + 峰检测，返回每个步时刻（秒，相对输入序列起点）。

    acc_mag 是去掉重力后的加速度信号：课件用模长 |a−g|（快走时一步一峰）；
    手持慢走推荐用 vertical_acc 的竖直分量（|a−g| 会一步两峰），此时 abs_floor 取 0.5 左右。
    """
    sm = smooth(np.asarray(acc_mag, float), fs, smooth_s)
    height = max(thresh_ratio * sm.mean(), abs_floor)
    peaks, _ = find_peaks(sm, height=height, distance=max(1, int(min_gap_s * fs)))
    return (peaks / fs).tolist()


def peak_valley(acc_mag, fs, step_times):
    """给每一步配一对 (峰值, 谷值)：峰取步时刻附近最大值，谷取与上一步之间的最小值。
    Weinberg 要的就是这一步的「峰谷差」。"""
    a = np.asarray(acc_mag, float)
    idx = np.round(np.asarray(step_times) * fs).astype(int)
    half = max(1, int(0.15 * fs))
    out = []
    for i, k in enumerate(idx):
        lo = idx[i - 1] if i > 0 else max(0, k - int(0.6 * fs))
        peak = a[max(0, k - half): k + half + 1].max()
        valley = a[lo: k + 1].min()
        out.append((peak, valley))
    return out


# ============================================================ ② 步长估计
def step_length(peak_val, valley_val, *, model="weinberg", K=WEINBERG_K) -> float:
    """返回本步步长 L（米）。

    model="const"    ：恒定 0.75 m —— 零参数零标定，「三不认」（不认人/状态/路况）
    model="weinberg" ：L = K · ⁴√(amax − amin) —— 迈大步起伏就大，K 要标定
    """
    if model == "const":
        return STEP_CONST
    return float(K * max(peak_val - valley_val, 0.0) ** 0.25)


def calibrate_K(peak_valleys, known_distance_m):
    """走一段已知距离 D：Σ K·⁴√Δ = D  ⇒  K = D / Σ ⁴√Δ。"""
    if not np.isfinite(known_distance_m) or known_distance_m <= 0:
        raise ValueError("标定距离必须为有限正数")
    if not all(np.isfinite(p) and np.isfinite(v) for p, v in peak_valleys):
        raise ValueError("标定峰谷包含无效值")
    s = sum(max(p - v, 0.0) ** 0.25 for p, v in peak_valleys)
    if s <= 0:
        raise ValueError("标定区间没有有效步，请检查时间区间、动作和检测阈值")
    return known_distance_m / s


# ============================================================ ③ 航向估计
def mag_azimuth(acc, mag):
    """磁力计 + 加速度计 → 倾角补偿后的磁航向（度）。

    与 Android SensorManager.getRotationMatrix + getOrientation 同一套几何：
    H = m × g 指东，M = g × H 指北，航向 = atan2(H_y, M_y)。
    acc/mag 都是机体系三轴（N×3），acc 要用含重力的原始加速度（它负责告诉我们哪边是天）。
    """
    acc = np.atleast_2d(acc).astype(float)
    mag = np.atleast_2d(mag).astype(float)
    H = np.cross(mag, acc)
    H /= np.linalg.norm(H, axis=1, keepdims=True)
    A = acc / np.linalg.norm(acc, axis=1, keepdims=True)
    M = np.cross(A, H)
    return np.degrees(np.arctan2(H[:, 1], M[:, 1]))


def vertical_acc(acc, fs, win_s=1.0):
    """竖直方向的动态加速度（去掉重力后、沿「天」方向的分量，m/s²，向上为正）。

    为什么用它数步：|a−g| 是总晃动量，手持慢走时手臂前后晃、蹬地都会贡献，一步常出现两个峰
    （图书馆 22 m 实测：|a−g| 主频 3.0 Hz、数出 64 步；竖直分量主频 1.5 Hz、39 步，与 27 s 慢走吻合）。
    竖直分量只看身体上下起伏，一步一峰。
    """
    w = max(1, int(win_s * fs))
    g = moving_mean(acc, w)
    gn = np.linalg.norm(g, axis=1)
    return np.einsum("ij,ij->i", acc, g / gn[:, None]) - gn


def gyro_vertical(acc, gyr, fs, win_s=1.0):
    """把陀螺读数投影到「竖直向上」方向，得到绕竖直轴的角速度（与 gyr 同单位）。

    为什么需要：手机斜着拿时，机体 z 轴不再指天，直接用 gyro z 会把转弯算小。
    重力方向从加速度计来：1 s 滑动均值把走路的抖动平均掉，剩下的就是重力（静止时读 +g，指向上）。
    手机平放时 up = (0,0,1)，结果就等于 gyro z。
    """
    w = max(1, int(win_s * fs))
    up = moving_mean(acc, w)
    up /= np.linalg.norm(up, axis=1, keepdims=True)
    return np.einsum("ij,ij->i", gyr, up)


def heading_update(psi, gyro_z, mag_az, dt, alpha=0.98) -> float:
    """互补滤波更新航向，返回新 psi（度）。

        ψ̂ ← α · (ψ̂ + ω·Δt) + (1 − α) · ψ_mag

    gyro_z：绕竖直轴的角速度（度/秒）。手机平放时就是 Android 陀螺 z 轴；斜拿时先用 gyro_vertical 投影。
    坑：Android 的 z 轴朝屏幕外、逆时针为正，而航向是顺时针为正 —— 所以航向变化率 = −gyro_z。
    角度卷绕：不能直接对 350° 和 10° 做加权平均（会得到 180°），
    所以写成「预测 + (1−α)×卷绕后的差值」的等价形式。
    mag_az 传 None 表示这一拍没有磁力计（退化成纯陀螺积分）。
    """
    pred = psi + (-gyro_z) * dt                       # 陀螺掌舵：高频
    if mag_az is None or np.isnan(mag_az):
        return float(wrap180(pred))
    return float(wrap180(pred + (1 - alpha) * wrap180(mag_az - pred)))  # 磁力计拽回：低频


def heading_series(t, gyro_z, mag_az, alpha=0.98, psi0=None):
    """在整条时间轴上逐拍跑 heading_update，返回每拍航向。"""
    psi = mag_az[0] if psi0 is None else psi0
    out = np.empty(len(t))
    out[0] = psi
    for k in range(1, len(t)):
        psi = heading_update(psi, gyro_z[k], mag_az[k], t[k] - t[k - 1], alpha)
        out[k] = psi
    return out


# ============================================================ ④ 位置更新
def run_pdr(step_times, lengths, t_heading, psi_series, x0=0.0, y0=0.0):
    """主循环：每一步取当时的航向，x += L·sinψ（东），y += L·cosψ（北）。"""
    xs, ys = [x0], [y0]
    for ts, L in zip(step_times, lengths):
        psi = np.radians(np.interp(ts, t_heading, np.unwrap(np.radians(psi_series)) * 180 / np.pi))
        xs.append(xs[-1] + L * np.sin(psi))
        ys.append(ys[-1] + L * np.cos(psi))
    return np.array(xs), np.array(ys)


def trajectory_error(x, y, step_times, gt_t, gt_x, gt_y, start_time=0.0):
    """在同一时间比较位置，仅评估真值覆盖的时刻。"""
    times = np.r_[start_time, step_times]
    gt_t, gt_x, gt_y = [np.asarray(v, dtype=float) for v in (gt_t, gt_x, gt_y)]
    if len(gt_t) < 2 or np.any(np.diff(gt_t) <= 0):
        raise ValueError("真值至少需要两个严格递增时间戳")
    valid = (times >= gt_t[0]) & (times <= gt_t[-1])
    if not valid.any():
        raise ValueError("轨迹与真值没有共同时间范围")
    errors = np.hypot(np.asarray(x)[valid] - np.interp(times[valid], gt_t, gt_x),
                      np.asarray(y)[valid] - np.interp(times[valid], gt_t, gt_y))
    return float(errors[-1]), float(errors.mean())
