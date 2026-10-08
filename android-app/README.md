# 多源传感器采集

这是一个离线 Android 采集 App，用于课程实验①和实验②。它记录加速度计、陀螺仪、磁力计和位置点，保留原始时间戳；2.0 起在采集的同时**实时推算 PDR 轨迹**，满足期中验收「走廊实走 60 s，实时轨迹投屏」。位置点来自 Android `LocationManager`；`gps` 来源为 GNSS 定位，`network` 来源为粗略回退位置，并非原始卫星观测。

## 使用

1. 在 Android Studio 中打开此目录，等待 Gradle 同步，运行 `app` 到 Android 8 或更新版本的手机。
2. 在手机开启定位服务。首次开始采集时，按系统提示授权位置和通知。精确定位允许采集 GNSS；若仅授予大致位置，App 会标记网络位置回退。
3. 底部三个页面：**轨迹**（实时 PDR，演示投屏用）、**传感器**（三路 IMU 实时波形和位置流状态）、**记录**（本机记录、轨迹回放、标定 K、导出、删除）。
4. 在「轨迹」页选择手动采集、40 秒预演（静坐 10 秒、常速踏步 20 秒、快速踏步 10 秒）或 10 分钟步行；「设置」里填标签、持机姿态和 PDR 参数。
5. 点击开始后有 5 秒准备倒计时，可以取消。采集开始后**先静止 3 秒做初始对准**（时长可在设置里改），状态栏提示「对准完成」后再走；结尾也静止几秒。40 秒预演的阶段切换会短振动提示，振动期间的原始读数也会照常记录。采集中屏幕保持常亮，也可以锁屏。持续通知会显示当前动作，并提供“停止并保存”。
6. 在「记录」页点一条记录展开操作，「导出 ZIP」用系统文件选择器保存到所需位置。

### 实时轨迹与演示

- PDR 持机方式：手机**平端在胸前，屏幕朝上，顶部朝前进方向，全程不换握法**。
- 轨迹图上北右东、等比例，自动缩放到装下整条轨迹（至少 8 m 见方），左下角标每格米数；绿圈是起点，朱红点和箭头是当前位置与航向。下方读数：步数、里程、航向、离起点（闭合路线即闭合误差）。
- 实时结果约滞后 0.9 s：重力和平滑用的是居中窗口，峰值要等 0.3 s 确认没有更高的邻峰。
- 投屏：电脑上 `scrcpy`（USB 调试已开）即可把手机画面投到教室屏幕。

### 标定 K

1. 量一段已知长度的直线（例如 22 m），按上面的持机方式「静止 → 走完 → 静止」录一条。
2. 在「记录」页展开这条记录 →「轨迹 / 标定」→ 填实际距离，App 按 `K = D / Σ⁴√(峰−谷)` 算出新 K，点「保存为 K」。之后的采集和回放都用新 K。
3. 回放用的是当前参数，所以改了 K、α 之后可以对旧记录重新看轨迹。

若电脑看得到手机的 MTP 连接，但 Android Studio / `adb devices -l` 没有显示设备，请在手机开发者选项中开启 **USB 调试**，保持屏幕解锁并接受该电脑的调试授权提示。

## 数据格式

每个 ZIP 含以下 CSV 和一个 `session.json`（`pdr.csv` 为 2.0 新增，1.x 的旧记录没有）：

| 文件 | 列 / 说明 |
| --- | --- |
| `accelerometer.csv` | `timestamp_elapsed_ns,x,y,z,accuracy`；三轴单位 m/s² |
| `gyroscope.csv` | 相同列；三轴单位 rad/s |
| `magnetometer.csv` | 相同列；三轴单位 µT |
| `gnss.csv` | `timestamp_elapsed_ns,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,bearing_deg,provider` |
| `pdr.csv` | `step,timestamp_elapsed_ns,time_s,peak_mps2,valley_mps2,length_m,heading_deg,x_m,y_m`；实时 PDR 每步一行，x 东、y 北（米），航向从磁北顺时针（度），`time_s` 相对 PDR 网格 0 点（三路传感器都出数的时刻） |
| `session.json` | 会话、设备、采样请求、实测频率、长间隔次数、动作分段、姿态和异常信息；`schema_version` 2 起含 `pdr` 块（全部参数、初始航向、步数、里程、终点） |

`timestamp_elapsed_ns` 是 Android 开机后单调时钟的纳秒值，可在同一手机、同一次开机期间对齐不同数据流。`session.json` 还提供 UTC 和本地开始时间。CSV 不对原始读数做滤波或重采样。

IMU 默认请求 50 Hz，位置更新默认请求 1 Hz。实际频率以文件中时间戳计算，系统不保证达到请求值；位置更新尤其受信号、权限和系统调度影响。IMU 间隔超过 100 ms、位置间隔超过 5 s 会计为长间隔。手机缺少某传感器或无定位时，相关 CSV 仍保留表头，原因写入 `session.json`。

记录存于 App 私有目录，卸载 App 会删除尚未导出的记录。App 不上传数据。

## 构建

项目使用 Kotlin、Jetpack Compose、Android Gradle Plugin 9.1.1 和 Gradle 9.3.1。Android Studio 自带 JDK 可用于构建。命令行构建：

```sh
./gradlew :app:assembleDebug
```

当前版本为 2.0（versionCode 3）。Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。命令行安装时可使用 Android SDK 内的 `platform-tools/adb install -r`。

此工作目录的 `.local/debug.keystore` 用于后续 Debug 版本保持同一签名，避免更新安装时因签名变化而必须卸载 App。它被 `.gitignore` 排除；若移走该文件，新生成的 Debug APK 将使用另一把密钥，更新安装前需先导出手机内的记录。

```sh
./gradlew :app:testDebugUnitTest   # 含 PDR 对拍：回放 ../data/ 的两条真实记录，逐步对比 Python 管线
```


## 采集可靠性与质检

采集服务持有带 10 分钟超时的 CPU 唤醒锁，活动会话每 5 分钟续期，停止或异常结束时释放。只在采集期间保持 CPU 运行，会增加耗电；设备厂商的进程管理仍可能中断服务，真机锁屏测试不能由构建结果替代。

会话 JSON 的 `available` 表示监听注册成功，新增 `sensor_present`、`listener_registered` 和 `wake_up_sensor` 字段。结束时记录零样本和长间隔警告；本机记录列表显示各传感器条数、实测频率、长间隔及位置条数。波形按原始时间戳绘制，并显示窗口秒数和当前幅值范围，幅值单位见对应卡片。

导出目标的会话 ID 会随 Activity 状态保存，文件选择期间旋转或系统重建页面后可继续导出。

## 代码结构

| 路径 | 内容 |
| --- | --- |
| `MainActivity.kt` | 系统交互：权限链、5 秒倒计时、启动/停止采集服务、导出、设置持久化 |
| `RecorderService.kt` | 前台采集服务：注册传感器与定位、写 CSV、喂给 PDR 引擎、发布界面状态 |
| `SessionStorage.kt` | 会话目录读写、`session.json`、`pdr.csv`、ZIP 导出、回放、删除 |
| `RecorderModel.kt` | 界面状态与采集模式定义 |
| `pdr/PdrMath.kt` | 课件三函数签名的 Kotlin 版：`stepLength`、`headingUpdate`，以及 `magAzimuth`、`calibrateK` |
| `pdr/Resampler.kt` | 三路不等间隔事件 → 50 Hz 统一网格（同 `pdr/loader.py`） |
| `pdr/PdrEngine.kt` | 在线 PDR：竖直分量步检、Weinberg 步长、互补滤波航向、位置更新 |
| `pdr/PdrReplay.kt` | 把一条记录的原始 CSV 回放给引擎（记录页回放、标定、单元测试共用） |
| `pdr/PdrSettings.kt` | 可调参数的持久化与合法范围 |
| `ui/` | Compose 界面：轨迹页、传感器页、记录页、设置弹窗、主题 |

### PDR 引擎与 Python 的关系

`pdr/PdrEngine.kt` 逐点复现仓库 `pdr/pdr.py` 的默认管线（`run_pdr.py` 默认参数，K 取图书馆标定值 0.353），只是把居中窗口改成「等后半窗到齐再算」的在线形式，结果与离线一致。
`PdrEngineParityTest` 回放 `data/` 里的两条真实记录，与 `pdr/export_app_reference.py` 导出的 Python 结果逐步比较（步数一致，坐标差 < 2 cm）。改了 `pdr.py` 的默认管线后，要重新导出基准：

```sh
cd ../pdr
uv run python export_app_reference.py ../data/session_20261006_081737_496_fe4b5910.zip ../android-app/app/src/test/resources/library_walk_reference.json
uv run python export_app_reference.py ../data/session_20261006_075107_474_1c743e9b.zip ../android-app/app/src/test/resources/sample_session_reference.json
```

唯一的口径差异：步检阈值 `max(0.9 × 均值, 下限)` 里的均值，离线用全程均值，在线用截至当时的累计均值。竖直动态加速度均值约为 0，阈值实际由下限 0.5 m/s² 决定，两者结果相同。

### 断流与内存边界

三路 IMU 任一路相邻读数间隔超过 100 ms、包含非有限值，或同步等待超过 5 秒时，停止 PDR 并提示原因，原始 CSV 继续采集。与 Python loader 的断流上限一致，不跨断流插值；回放遇到这些数据会提示失败。时间戳重复或倒退仍按原有在线规则丢弃。

网格缓存会定期裁剪；走完后长时间静止只累计谷值，不保留整个静止段。轨迹步列表仍随步数增长。PDR 中途停止时保存已有结果、停用状态及错误原因；停止采集时尾部处理失败也写入警告。
