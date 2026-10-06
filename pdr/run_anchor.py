"""实验 A：在老师的锚数据上复现第 5 次课的数字（步数、步长、里程）。

锚数据来自课程网站 D08 演示（20 分钟，50 Hz，只有 |a|、|a−g|、|ω| 三路模长 + 状态标注），
所以只能做「步检 + 步长 + 一维里程」，没法算航向——航向和轨迹去跑 run_pdr.py data/sim。

    uv run python run_anchor.py     # 第一次运行会从校内课程网站下载锚数据（需校园网），不提交进仓库
"""
import json
import urllib.request
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

import pdr

plt.rcParams["font.sans-serif"] = ["Noto Sans CJK SC", "WenQuanYi Micro Hei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

FS = 50
ANCHOR_URL = "http://10.112.165.210:9000/multi_source_fusion/lesson03_pdr/demos/d08_anchor_data.js"


def fetch_anchor():
    """把课程网站 D08 演示里的 window.ANCHOR_DATA 转成 CSV（t 对齐 0.02 s 网格，三路模长）。"""
    print(f"下载锚数据：{ANCHOR_URL}")
    s = urllib.request.urlopen(ANCHOR_URL, timeout=30).read().decode("utf-8")
    d = json.loads(s[s.index("{"): s.rstrip().rstrip(";").rindex("}") + 1])
    ch = {k: dict(zip(v[0::2], v[1::2])) for k, v in d["ch"].items()}
    ts = sorted(set(ch["acc"]) | set(ch["lin"]) | set(ch["gyr"]))
    Path("data").mkdir(exist_ok=True)
    with open("data/anchor_50hz.csv", "w") as f:
        f.write("t,acc_norm,lin_norm,gyr_norm\n")
        for t in ts:
            f.write(f"{t:.2f}," + ",".join("" if ch[k].get(t) is None else str(ch[k][t]) for k in ("acc", "lin", "gyr")) + "\n")
    json.dump({"states": d["states"], "events": d["events"]},
              open("data/anchor_states.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if not Path("data/anchor_50hz.csv").exists():
    fetch_anchor()
df = pd.read_csv("data/anchor_50hz.csv").interpolate().bfill()
states = json.load(open("data/anchor_states.json", encoding="utf-8"))["states"]


def seg(a, b):
    s = df[(df.t >= a) & (df.t <= b)]
    return s.t.values, s.lin_norm.values, s.acc_norm.values


print("== 各状态段：|a−g| 均值 / 标准差 / 峰检测步数（同一套参数）")
for name, a, b in states:
    t, lin, _ = seg(a, b)
    n = len(pdr.detect_steps(lin, FS))
    print(f"  {name:6s} {a:4d}–{b:4d}s  mean={lin.mean():5.2f}  σ={lin.std():5.2f}  steps={n}")

# ---- 考场：下车快走段 658–905 s
t, lin, acc = seg(658, 905)
steps = pdr.detect_steps(lin, FS)
print(f"\n== 快走段 658–905 s：检测到 {len(steps)} 步（课件 377 步，原始 100 Hz；这里是降采样到 50 Hz 的版本）")
gaps = np.diff(steps)
print(f"   步间隔中位 {np.median(gaps):.2f}s ≈ {1/np.median(gaps):.2f} 步/秒")

# 第 5 次课「坐车为什么不计步」：只靠幅度门限 1.2 时，车行段能数出多少假步
tc, linc, _ = seg(0, 300)
fake = pdr.detect_steps(linc, FS, thresh_ratio=0, abs_floor=1.2)
print(f"== 车行段 0–300 s，只用门限 1.2 m/s²：{len(fake)} 个假步（课件 131）——所以还需要静/动判别和峰距规律")

# ---- 步长：Weinberg 峰谷差取「平滑后」的 |a|（峰谷差与重力无关；平滑窗与步检一致）
pv = pdr.peak_valley(pdr.smooth(acc, FS), FS, steps)  # 和步检同一把平滑窗，否则毛刺会把峰谷差撑大
diffs = np.array([p - v for p, v in pv])
K = pdr.calibrate_K(pv, 0.75 * len(steps))  # 课件口径：以 0.75 m 平均步长对齐
L_w = np.array([pdr.step_length(p, v, K=K) for p, v in pv])
L_c = np.array([pdr.step_length(p, v, model="const") for p, v in pv])
print(f"== 峰谷差中位 {np.median(diffs):.1f} m/s²（课件 4.2）；标出 K = {K:.2f}（课件 0.52）")
print(f"   Weinberg 步长范围 {np.percentile(L_w,2):.2f}–{np.percentile(L_w,98):.2f} m（课件 0.57–0.93）")
print(f"== 一维里程：恒定 {L_c.sum():.0f} m · Weinberg {L_w.sum():.0f} m（课件 283 m）")

# ---- 出图
fig, ax = plt.subplots(3, 1, figsize=(12, 9))
sm = pdr.smooth(lin, FS)
ax[0].plot(t, lin, lw=.5, c="#bbb", label="|a−g| 原始")
ax[0].plot(t, sm, lw=.8, c="#2563eb", label="平滑 0.18 s")
k = np.round(np.array(steps) * FS).astype(int)
ax[0].plot(t[k], sm[k], "v", c="#dc2626", ms=4, label=f"检测到的步 ({len(steps)})")
ax[0].axhline(max(.9 * sm.mean(), 1.2), ls="--", c="#f59e0b", label="阈值")
ax[0].set_xlim(700, 730); ax[0].legend(loc="upper right"); ax[0].set_title("① 步态检测（局部放大 700–730 s）")
ax[1].plot(t[k], L_w, ".-", lw=.6, c="#16a34a", label=f"Weinberg K={K:.2f}")
ax[1].axhline(.75, c="#2563eb", label="恒定 0.75 m"); ax[1].legend(); ax[1].set_ylabel("m")
ax[1].set_title("② 每步步长：活尺子在呼吸，死尺子一动不动")
ax[2].step(t[k], np.cumsum(L_c), c="#2563eb", label="恒定")
ax[2].step(t[k], np.cumsum(L_w), c="#16a34a", label="Weinberg")
ax[2].set_ylabel("累计里程 m"); ax[2].set_xlabel("t (s)"); ax[2].legend(); ax[2].set_title("④ 一维里程")
fig.tight_layout(); fig.savefig("out_anchor.png", dpi=110)
print("\n图已保存：out_anchor.png")
