# Multi-source Fusion

多源融合课程项目代码库。各子项目按独立目录组织。

## 子项目

- [`android-app/`](android-app/)：Android 多传感器采集 App，记录加速度计、陀螺仪、磁力计和 GNSS 位置点，导出 CSV 与会话 JSON；2.0 起实时推算并显示 PDR 轨迹（期中演示用）。
- [`pdr/`](pdr/)：实验② PDR 定位器（Python），读取 App 导出的 ZIP，完成步检、步长、航向和轨迹推算。

后续实验代码和分析工具可按主题添加为新的顶层子目录。
