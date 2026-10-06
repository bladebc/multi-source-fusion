"""实验 B：全链 PDR —— 步检 → 步长 → 航向 → 轨迹，可选与真值对质。

    uv run python run_pdr.py data/sim                      # 仿真数据（自动用 gt.csv 第一条边标定 K）
    uv run python run_pdr.py data/mine --calib 10 45 30    # 自己的数据：10–45 s 内走了已知的 30 m
    uv run python run_pdr.py data/mine --alpha 0.95 --model const

    uv run python run_pdr.py data/mine/xxx.zip --calib 10 45 30   # 队友 App 导出的 ZIP 也可以直接喂

数据可以是含 sensors.csv 的目录、队友 App 的 ZIP 或解压目录（格式见 loader.py）；目录里有 gt.csv（t,x,y）就会算误差。
"""
import argparse
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

import pdr
from loader import load_sensors

from plot_config import configure_fonts

configure_fonts()

ap = argparse.ArgumentParser()
ap.add_argument("data", type=Path)
ap.add_argument("--alpha", type=float, default=0.998,
                help="互补滤波系数，50 Hz 逐点更新；0.998 ≈ τ 10 s（图书馆实测：0.98 偏离直线 1.87 m，0.998 仅 0.49 m）")
ap.add_argument("--model", choices=["weinberg", "const"], default="weinberg")
ap.add_argument("--calib", type=float, nargs=3, metavar=("T0", "T1", "DIST"),
                help="标定 K：T0–T1 秒内走了已知的 DIST 米")
ap.add_argument("--static", type=float, default=3.0, help="开头静止秒数，用于初始对准")
ap.add_argument("--step-signal", choices=["vertical", "norm"], default="vertical",
                help="步检信号：vertical 竖直动态加速度（默认，慢走不重复计步）/ norm 课件的 |a−g|")
ap.add_argument("--trim", type=float, nargs=2, metavar=("T0", "T1"),
                help="只用 T0–T1 秒（裁掉首尾按按钮的动作；建议从静止段开始）")
args = ap.parse_args()

if not 0 <= args.alpha <= 1 or not np.isfinite(args.static) or args.static <= 0:
    ap.error("alpha 必须在 [0, 1] 内，static 必须为有限正数")
try:
    d = load_sensors(args.data)
except ValueError as error:
    ap.error(str(error))
base = args.data.with_suffix("") if args.data.suffix == ".zip" else args.data  # 输出图放在哪
t, fs = d["t"], d["fs"]
print(f"数据 {t[-1]:.0f} s，各传感器实测频率 {d['fs_real']} Hz（重采样到 {fs:.0f} Hz）")
if args.trim:  # 裁剪后时间轴仍用原始秒数，--calib 里的时间照原样填
    if not all(np.isfinite(v) for v in args.trim) or not 0 <= args.trim[0] < args.trim[1] <= t[-1]:
        ap.error("裁剪时间必须满足 0 ≤ T0 < T1 ≤ 记录时长")
    keep = (t >= args.trim[0]) & (t <= args.trim[1])
    if keep.sum() < fs:
        ap.error("裁剪后不足 1 秒")
    for k in ("acc", "gyr_dps", "mag", "lin_norm"):
        d[k] = d[k][keep]
    t = t[keep]
    print(f"   只用 {t[0]:.1f}–{t[-1]:.1f} s")
gt = pd.read_csv(base / "gt.csv") if (base / "gt.csv").exists() else None
if gt is not None:
    gt["t"] -= d["t_offset"]  # loader 把时间轴对齐到「最晚开始的那个传感器」，真值时间跟着平移

# ---- ① 步检
if args.step_signal == "vertical":
    steps = np.array(pdr.detect_steps(pdr.vertical_acc(d["acc"], fs), fs, abs_floor=0.5)) + t[0]
else:
    steps = np.array(pdr.detect_steps(d["lin_norm"], fs)) + t[0]
print(f"① 步检（{args.step_signal}）：{len(steps)} 步" + (f"（真值 {len(gt)-1} 步）" if gt is not None else ""))

# ---- ② 步长：峰谷差在平滑后的 |a| 上取
acc_norm_sm = pdr.smooth(np.linalg.norm(d["acc"], axis=1), fs)
pv = pdr.peak_valley(acc_norm_sm, fs, steps - t[0])
K = pdr.WEINBERG_K
calib = args.calib
if calib is None and gt is not None:  # 仿真：用第一条直边（真值）当「已知距离」
    leg_end = gt.t[np.argmax(gt.y.values >= gt.y.max() - 0.5)]
    calib = (0, leg_end, float(gt.y.max()))
if calib:
    t0, t1, D = calib
    if not all(np.isfinite(v) for v in calib) or not t[0] <= t0 < t1 <= t[-1]:
        ap.error("标定时间必须满足 0 ≤ T0 < T1 ≤ 记录时长")
    sel = [p for s, p in zip(steps, pv) if t0 <= s <= t1]
    try:
        K = pdr.calibrate_K(sel, D)
    except ValueError as error:
        ap.error(str(error))
    print(f"② 标定：{t0:.0f}–{t1:.0f} s 内 {len(sel)} 步走 {D:.1f} m → K = {K:.3f}")
L = np.array([pdr.step_length(p, v, model=args.model, K=K) for p, v in pv])
print(f"② 步长：{args.model}，均值 {L.mean():.2f} m，总里程 {L.sum():.1f} m"
      + (f"（真值 {np.hypot(np.diff(gt.x), np.diff(gt.y)).sum():.1f} m）" if gt is not None else ""))

# ---- ③ 航向三源
mag_az = pdr.mag_azimuth(d["acc"], d["mag"])
psi0 = np.degrees(np.angle(np.exp(1j * np.radians(mag_az[t < t[0] + args.static])).mean()))  # 初始对准：静止段磁航向的圆均值
print(f"③ 初始对准：开头 {args.static:.0f} s 静止段磁航向 = {psi0:.1f}°")
gz = pdr.gyro_vertical(d["acc"], d["gyr_dps"], fs)  # 绕竖直轴的角速度：手机斜拿也不怕
# α 的「性格」取决于更新频率：时间常数 τ ≈ dt·α/(1−α)。课件「α=0.98，50 拍约 1 分钟」是按每步（~1 Hz）
# 更新算的；这里每个采样点（50 Hz）都更新，同样的 α=0.98 只等于 1 s——磁力计几乎全盘接管。
tau = lambda a: np.inf if a >= 1 else (1 / fs) * a / (1 - a)
print(f"③ α={args.alpha} 在 {fs:.0f} Hz 更新下的时间常数 τ ≈ {tau(args.alpha):.1f} s")
sources = {
    "纯陀螺 α=1": pdr.heading_series(t, gz, mag_az, alpha=1.0, psi0=psi0),
    "纯磁力计 α=0": pdr.heading_series(t, gz, mag_az, alpha=0.0, psi0=psi0),
    f"互补 α={args.alpha}": pdr.heading_series(t, gz, mag_az, alpha=args.alpha, psi0=psi0),
}


# ---- ④ 轨迹 + 误差
def err_vs_gt(x, y):
    return pdr.trajectory_error(x, y, steps, gt.t.values, gt.x.values, gt.y.values, start_time=t[0])


def unwrap_deg(a):
    """画图用：去掉 ±180° 跳变，让南向那段不变成一堆竖线。"""
    return np.degrees(np.unwrap(np.radians(a)))


fig, ax = plt.subplots(1, 2, figsize=(14, 6.5))
if gt is not None:
    ax[0].plot(gt.x, gt.y, "k-", lw=2.5, label="真值")
for (name, psi), c in zip(sources.items(), ["#dc2626", "#f59e0b", "#2563eb"]):
    x, y = pdr.run_pdr(steps, L, t, psi)
    tag = ""
    if gt is not None:
        e_end, e_mean = err_vs_gt(x, y)
        tag = f"  末次共同时间误差 {e_end:.1f} m · 平均误差 {e_mean:.1f} m"
    else:  # 没有真值：给出起终点距离与偏离起终点连线的程度（走直线时就是「直线度」）
        P = np.c_[x, y]
        chord = P[-1] - P[0]
        D = float(np.hypot(*chord))
        n = np.array([-chord[1], chord[0]]) / D if D > 0 else np.zeros(2)
        dev = np.abs((P - P[0]) @ n)
        tag = f"  起终点 {D:.1f} m · 偏离直线 RMS {np.sqrt((dev**2).mean()):.2f} m / 最大 {dev.max():.2f} m"
    print(f"④ {name:12s}{tag}")
    ax[0].plot(x, y, "-", c=c, lw=1.3, label=name + tag)
    ax[1].plot(t, unwrap_deg(psi), c=c, lw=.8, label=name)
ax[0].plot(0, 0, "g*", ms=15)
ax[0].set_aspect("equal"); ax[0].grid(alpha=.3)
ax[0].legend(fontsize=8, loc="upper center", bbox_to_anchor=(0.5, -0.1))  # 图例放图下方，免得盖住轨迹
ax[0].set_xlabel("东 E (m)"); ax[0].set_ylabel("北 N (m)"); ax[0].set_title("PDR 轨迹：只换航向源")
ax[1].plot(t, unwrap_deg(mag_az), c="#999", lw=.3, label="磁航向（原始）", zorder=0)
ax[1].legend(fontsize=8); ax[1].set_xlabel("t (s)"); ax[1].set_ylabel("航向 ψ (°)")
ax[1].set_title("航向：陀螺会卷，磁力计会毛，互补又平又不漂")
fig.tight_layout()
out_dir = Path("out") / base.name if args.data.suffix == ".zip" else base  # ZIP 的结果放 out/，不弄脏数据目录
out_dir.mkdir(parents=True, exist_ok=True)
out = out_dir / "out_pdr.png"
fig.savefig(out, dpi=110)
print(f"图已保存：{out}")

# ---- α 扫描：没有正确答案，只有「输入质量决定的妥协」
if gt is not None:
    alphas = [0, .5, .8, .9, .95, .97, .98, .99, .995, .998, .999, 1]
    res = [err_vs_gt(*pdr.run_pdr(steps, L, t, pdr.heading_series(t, gz, mag_az, a, psi0))) for a in alphas]
    print("α 扫描：")
    for a, (e, m) in zip(alphas, res):
        print(f"   α={a:<6} τ≈{tau(a):6.1f} s   末次共同时间 {e:5.1f} m   平均 {m:5.1f} m")
