"""仿真一段「手机平端、绕矩形走一圈」的数据，带真值，用来在采数据之前把全链跑通。

故意埋了三个真实世界的坑：
  1. 陀螺零偏 0.5°/s（课件里锚数据实测量级）—— 纯陀螺积分会卷
  2. 磁力计噪声 + 手持摆动 —— 纯磁航向会毛
  3. 第三条边经过一段「钢筋区」，磁场被拉偏几十度 —— 见铁就歪
输出格式与 loader.py 规定的采集格式完全一致。

    uv run python sim.py            # 生成 data/sim/sensors.csv 与 gt.csv
"""
from pathlib import Path

import numpy as np

rng = np.random.default_rng(2026)
FS = 100.0
G = 9.80665
STEP_HZ = 1.9
K_TRUE = 0.5  # 仿真世界里「真实」的 Weinberg 系数

# 路线：先静止 5 s（初始对准），然后 北 40 m → 东 20 m → 南 40 m（钢筋区）→ 西 20 m，再静止 5 s
LEGS = [(0, 40), (90, 20), (180, 40), (270, 20)]  # (航向°, 长度 m)

# ---- 1. 按步生成真值：每步步长在 0.6–0.8 之间呼吸
steps = []  # (航向, 步长)
for psi, length in LEGS:
    d = 0.0
    while d < length - 0.3:
        L = float(np.clip(rng.normal(0.70, 0.04), 0.60, 0.80))
        steps.append((psi, L))
        d += L
n = len(steps)
t_start = 5.0
step_t = t_start + np.arange(n) / STEP_HZ + rng.normal(0, 0.015, n)
T = step_t[-1] + 5.0
t = np.arange(0, T, 1 / FS)

# 真值航向：拐弯处用 1.5 s 平滑过渡（人不会瞬间转 90°）
psi_steps = np.unwrap(np.radians([s[0] for s in steps])) * 180 / np.pi
psi_true = np.interp(t, step_t, psi_steps)
w = int(1.5 * FS)
psi_true = np.convolve(np.pad(psi_true, w, mode="edge"), np.ones(w) / w, "same")[w:-w]

# 真值轨迹（每步一个点）
xs, ys = [0.0], [0.0]
for (psi, L) in steps:
    xs.append(xs[-1] + L * np.sin(np.radians(psi)))
    ys.append(ys[-1] + L * np.cos(np.radians(psi)))

# ---- 2. 加速度：每步一个脚跟冲击尖峰，幅度 ∝ (L/K)^4（Weinberg 世界观）
#    波形 = 窄正峰（落地冲击）− 同面积的宽负瓣（腾空），一步内积分为 0，基线回到 0 附近
acc_z_dyn = np.zeros_like(t)
for ts, (_, L) in zip(step_t, steps):
    amp = (L / K_TRUE) ** 4 * 1.2
    acc_z_dyn += amp * (np.exp(-0.5 * ((t - ts) / 0.07) ** 2)
                        - 0.35 * np.exp(-0.5 * ((t - ts - 0.24) / 0.2) ** 2))
walking = (t > t_start - 0.3) & (t < step_t[-1] + 0.3)
acc_y_dyn = 0.8 * np.sin(2 * np.pi * STEP_HZ * t) * walking
acc = np.column_stack([
    rng.normal(0, 0.15, len(t)),
    acc_y_dyn + rng.normal(0, 0.15, len(t)),
    G + acc_z_dyn + rng.normal(0, 0.15, len(t)),
])
lin = acc - np.array([0, 0, G])

# ---- 3. 陀螺：航向变化率取负（Android z 轴逆时针为正）+ 手持摆动 + 零偏 + 噪声
sway = 4.0 * np.sin(2 * np.pi * STEP_HZ / 2 * t) * walking   # 手臂摆动 ±4°，不改变真实行进方向
psi_dev = psi_true + sway
gyro_z = -np.gradient(psi_dev, 1 / FS) + 0.5 + rng.normal(0, 0.8, len(t))  # 度/秒
gyr = np.column_stack([rng.normal(0, .5, len(t)), rng.normal(0, .5, len(t)), gyro_z])
gyr = np.radians(gyr)

# ---- 4. 磁力计：地磁（北京：水平 30 µT，垂直向下 45 µT）转到机体系 + 噪声 + 钢筋区干扰
r = np.radians(psi_dev)
mag = np.column_stack([-30 * np.sin(r), 30 * np.cos(r), -45 * np.ones_like(t)])
y_now = np.interp(t, np.r_[t_start, step_t], ys[: n + 1])
x_now = np.interp(t, np.r_[t_start, step_t], xs[: n + 1])
steel = (x_now > 15) & (y_now > 8) & (y_now < 32)            # 第三条边中段
mag[steel, 0] += 18 * np.sin(y_now[steel] / 6)                 # 慢变的局部畸变
mag[steel, 1] += 10
mag += rng.normal(0, 2.0, mag.shape)

# ---- 5. 写成采集格式（各传感器时间戳错开一点，模拟真实的不同步）
out = Path("data/sim"); out.mkdir(parents=True, exist_ok=True)
rows = []
for name, arr, jitter in [("acc", acc, 0), ("gyr", gyr, 1.3e-3), ("mag", mag, 2.1e-3), ("lin", lin, 0.7e-3)]:
    sub = slice(None, None, 2) if name == "mag" else slice(None)  # 磁力计只有 50 Hz
    tn = ((t[sub] + jitter) * 1e9 + 1_000_000_000).astype(np.int64)
    for k, v in zip(tn, arr[sub]):
        rows.append(f"{k},{name},{v[0]:.5f},{v[1]:.5f},{v[2]:.5f}")
rows.sort(key=lambda s: int(s.split(",", 1)[0]))
(out / "sensors.csv").write_text("t_ns,type,x,y,z\n" + "\n".join(rows) + "\n")
with open(out / "gt.csv", "w") as f:
    f.write("t,x,y\n")
    for ts, x, y in zip(np.r_[t_start, step_t], xs, ys):
        f.write(f"{ts:.3f},{x:.3f},{y:.3f}\n")
print(f"生成 {n} 步，真值总里程 {sum(s[1] for s in steps):.1f} m，时长 {T:.0f} s → {out}/")
