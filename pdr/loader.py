"""读取采集 CSV（实验① App 的输出格式），重采样到统一时间轴。

输入格式 sensors.csv（长表，一行一个读数，Android 原始单位）：
    t_ns,type,x,y,z
    123456789000,acc,0.12,0.03,9.79      # TYPE_ACCELEROMETER       m/s²（含重力）
    123456790000,gyr,0.001,-0.002,0.01   # TYPE_GYROSCOPE           rad/s
    123456791000,mag,-12.1,25.3,-40.2    # TYPE_MAGNETIC_FIELD      µT
    123456792000,lin,0.11,0.02,-0.01     # TYPE_LINEAR_ACCELERATION m/s²（可选）
t_ns 直接写 SensorEvent.timestamp（开机以来纳秒），不同传感器各写各的，不需要对齐。

也支持队友采集 App（bladebc/multi-source-fusion）导出的 ZIP 或其解压目录：
    accelerometer.csv / gyroscope.csv / magnetometer.csv
        timestamp_elapsed_ns,x,y,z,accuracy
    gnss.csv、session.json（读取时忽略，GNSS 另做）
"""
from __future__ import annotations

import io
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from pdr import moving_mean


TEAM_FILES = {"acc": "accelerometer.csv", "gyr": "gyroscope.csv", "mag": "magnetometer.csv"}


def read_raw(path):
    """把各种来源统一成长表 DataFrame(t_ns, type, x, y, z)。

    path 可以是：sensors.csv（长表）/ 含 sensors.csv 的目录 / 队友 App 的 ZIP / 解压后的目录。
    """
    path = Path(path)
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as z:
            names = {Path(n).name: n for n in z.namelist()}
            files = {k: io.BytesIO(z.read(names[f])) for k, f in TEAM_FILES.items() if f in names}
    elif path.is_dir() and (path / "accelerometer.csv").exists():
        files = {k: path / f for k, f in TEAM_FILES.items() if (path / f).exists()}
    else:
        return pd.read_csv(path / "sensors.csv" if path.is_dir() else path)
    parts = []
    for k, f in files.items():
        g = pd.read_csv(f).rename(columns={"timestamp_elapsed_ns": "t_ns"})
        g["type"] = k
        parts.append(g[["t_ns", "type", "x", "y", "z"]])
    return pd.concat(parts, ignore_index=True)


def load_sensors(path, fs=50.0):
    """返回 dict：t（秒，从 0 起）、acc/gyr/mag（N×3）、lin_norm（|a−g|）、fs_real（各传感器实测频率）。

    t_offset：统一时间轴的 0 点相对「最早一条读数」晚了多少秒（三路传感器都开始出数才算 0 点）。
    """
    df = read_raw(path)
    df["t"] = (df.t_ns - df.t_ns.min()) * 1e-9
    groups = {k: g.sort_values("t") for k, g in df.groupby("type")}
    missing = {"acc", "gyr", "mag"} - groups.keys()
    if missing:
        raise ValueError(f"缺少传感器：{missing}")

    # 实验报告要写「实测频率」而不是申请的档位（第 1 课传感器篇）
    fs_real = {k: round(1 / np.median(np.diff(g.t.values)), 1) for k, g in groups.items()}

    t0 = max(g.t.iloc[0] for g in groups.values())
    t1 = min(g.t.iloc[-1] for g in groups.values())
    t = np.arange(t0, t1, 1 / fs)

    def interp(k):
        g = groups[k]
        return np.column_stack([np.interp(t, g.t.values, g[c].values) for c in "xyz"])

    acc, gyr, mag = interp("acc"), interp("gyr"), interp("mag")
    if "lin" in groups:
        lin_norm = np.linalg.norm(interp("lin"), axis=1)
    else:
        # 没有系统的 linear acceleration：用 1 s 滑动均值当重力估计，减掉后求模
        w = int(fs)
        grav = moving_mean(acc, w)
        lin_norm = np.linalg.norm(acc - grav, axis=1)

    return {
        "t": t - t0,
        "acc": acc,
        "gyr_dps": np.degrees(gyr),  # 统一成 度/秒（签名约定）
        "mag": mag,
        "lin_norm": lin_norm,
        "fs": fs,
        "fs_real": fs_real,
        "t_offset": t0,
    }
