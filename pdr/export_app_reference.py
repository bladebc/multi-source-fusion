"""导出 Android 端 PDR 引擎的对拍基准：用本目录的离线管线跑一条 App 记录，逐步写出结果。

    uv run python export_app_reference.py ../data/session_20261006_081737_496_fe4b5910.zip \
        ../android-app/app/src/test/resources/library_walk_reference.json

参数与 App 默认值一致（竖直分量步检、Weinberg K = 0.353、α = 0.998、开头 3 s 初始对准），
App 单元测试 PdrEngineParityTest 读同一个 ZIP 回放，逐步比较步时刻、步长、航向和坐标。
"""
import argparse
import json
from pathlib import Path

import numpy as np

import pdr
from loader import load_sensors

ap = argparse.ArgumentParser()
ap.add_argument("data", type=Path)
ap.add_argument("out", type=Path)
ap.add_argument("--K", type=float, default=0.353)
ap.add_argument("--alpha", type=float, default=0.998)
ap.add_argument("--static", type=float, default=3.0)
args = ap.parse_args()

d = load_sensors(args.data)
t, fs = d["t"], d["fs"]
steps = np.array(pdr.detect_steps(pdr.vertical_acc(d["acc"], fs), fs, abs_floor=0.5))
acc_norm_sm = pdr.smooth(np.linalg.norm(d["acc"], axis=1), fs)
pv = pdr.peak_valley(acc_norm_sm, fs, steps)
L = np.array([pdr.step_length(p, v, K=args.K) for p, v in pv])
mag_az = pdr.mag_azimuth(d["acc"], d["mag"])
psi0 = float(np.degrees(np.angle(np.exp(1j * np.radians(mag_az[t < args.static])).mean())))
gz = pdr.gyro_vertical(d["acc"], d["gyr_dps"], fs)
psi = pdr.heading_series(t, gz, mag_az, alpha=args.alpha, psi0=psi0)
x, y = pdr.run_pdr(steps, L, t, psi)
idx = np.round(steps * fs).astype(int)

ref = {
    "source": args.data.name,
    "params": {"fs": fs, "K": args.K, "alpha": args.alpha, "static_s": args.static},
    "t_offset_s": float(d["t_offset"]),
    "samples": len(t),
    "psi0_deg": psi0,
    "steps": [
        {"t": float(s), "length": float(l), "psi": float(psi[k]), "x": float(xx), "y": float(yy)}
        for s, l, k, xx, yy in zip(steps, L, idx, x[1:], y[1:])
    ],
}
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(ref, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(f"{len(steps)} 步，里程 {L.sum():.2f} m，终点 ({x[-1]:.2f}, {y[-1]:.2f})，ψ0 {psi0:.1f}° → {args.out}")
