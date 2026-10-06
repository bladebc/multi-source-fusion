# 多源传感器采集

这是一个离线 Android 采集 App，用于课程实验①。它记录加速度计、陀螺仪、磁力计和位置点，保留原始时间戳，以便后续做波形检查、步态分析和多源融合。位置点来自 Android `LocationManager`；`gps` 来源为 GNSS 定位，`network` 来源为粗略回退位置，并非原始卫星观测。

## 使用

1. 在 Android Studio 中打开此目录，等待 Gradle 同步，运行 `app` 到 Android 8 或更新版本的手机。
2. 在手机开启定位服务。首次开始采集时，按系统提示授权位置和通知。精确定位允许采集 GNSS；若仅授予大致位置，App 会标记网络位置回退。
3. 填写持机姿态。选择手动采集、40 秒预演（静坐 10 秒、常速踏步 20 秒、快速踏步 10 秒）或 10 分钟步行。预演建议全程保持同一种固定姿态。
4. 点击开始后有 5 秒准备倒计时，可以取消。采集开始后，做 PDR 数据时建议开头和结尾各静止 5 秒。40 秒预演的阶段切换会短振动提示，振动期间的原始读数也会照常记录。采集中可以锁屏。持续通知会显示当前动作，并提供“停止并保存”。
5. 在“本机采集记录”中点“导出 ZIP”，用系统文件选择器保存到所需位置。

若电脑看得到手机的 MTP 连接，但 Android Studio / `adb devices -l` 没有显示设备，请在手机开发者选项中开启 **USB 调试**，保持屏幕解锁并接受该电脑的调试授权提示。

## 数据格式

每个 ZIP 含四个 CSV 和一个 `session.json`：

| 文件 | 列 / 说明 |
| --- | --- |
| `accelerometer.csv` | `timestamp_elapsed_ns,x,y,z,accuracy`；三轴单位 m/s² |
| `gyroscope.csv` | 相同列；三轴单位 rad/s |
| `magnetometer.csv` | 相同列；三轴单位 µT |
| `gnss.csv` | `timestamp_elapsed_ns,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,bearing_deg,provider` |
| `session.json` | 会话、设备、采样请求、实测频率、长间隔次数、动作分段、姿态和异常信息 |

`timestamp_elapsed_ns` 是 Android 开机后单调时钟的纳秒值，可在同一手机、同一次开机期间对齐不同数据流。`session.json` 还提供 UTC 和本地开始时间。CSV 不对原始读数做滤波或重采样。

IMU 默认请求 50 Hz，位置更新默认请求 1 Hz。实际频率以文件中时间戳计算，系统不保证达到请求值；位置更新尤其受信号、权限和系统调度影响。IMU 间隔超过 100 ms、位置间隔超过 5 s 会计为长间隔。手机缺少某传感器或无定位时，相关 CSV 仍保留表头，原因写入 `session.json`。

记录存于 App 私有目录，卸载 App 会删除尚未导出的记录。App 不上传数据。

## 构建

项目使用 Kotlin、Jetpack Compose、Android Gradle Plugin 9.1.1 和 Gradle 9.3.1。Android Studio 自带 JDK 可用于构建。命令行构建：

```sh
./gradlew :app:assembleDebug
```

当前版本为 1.1（versionCode 2）。Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`，当前构建另复制到 `dist/multisensor-collector-v1.1-debug.apk`，方便直接取用。命令行安装时可使用 Android SDK 内的 `platform-tools/adb install -r`。

此工作目录的 `.local/debug.keystore` 用于后续 Debug 版本保持同一签名，避免更新安装时因签名变化而必须卸载 App。它被 `.gitignore` 排除；若移走该文件，新生成的 Debug APK 将使用另一把密钥，更新安装前需先导出手机内的记录。

本机已通过 `:app:assembleDebug`、`:app:testDebugUnitTest` 和 `:app:lintDebug`。真机安装、锁屏持续记录、GNSS 户外定位和 ZIP 实际导出仍需在 ADB 识别手机后验收。

## 采集可靠性与质检

采集服务持有带 10 分钟超时的 CPU 唤醒锁，活动会话每 5 分钟续期，停止或异常结束时释放。只在采集期间保持 CPU 运行，会增加耗电；设备厂商的进程管理仍可能中断服务，真机锁屏测试不能由构建结果替代。

会话 JSON 的 `available` 表示监听注册成功，新增 `sensor_present`、`listener_registered` 和 `wake_up_sensor` 字段。结束时记录零样本和长间隔警告；本机记录列表显示各传感器条数、实测频率、长间隔及位置条数。波形按原始时间戳绘制，并显示窗口秒数和当前幅值范围，幅值单位见对应卡片。

导出目标的会话 ID 会随 Activity 状态保存，文件选择期间旋转或系统重建页面后可继续导出。
