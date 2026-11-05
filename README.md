# AURORA

按播放会话挂载的多段均衡叠加。为小米 17 系列与澎湃 OS 4 而做。

官网与下载：**https://aurora.lingluandf.online**

---

## 它做什么

很多均衡器改的是全局混音。一旦系统开了空间音频、音频走了独立的输出线程，这些改动就完全失效——用户看到的是"我调了，但没变化"。

AURORA 换了个位置：它找到每一个**正在播放的音频会话**，逐个挂上去，在音轨末端叠加一条六段曲线。所以开不开空间音频都一样有效。

关掉开关，效果器立刻释放，声音回到出厂状态。不写 `/odm` 里的 Dolby 配置，不重启 `audioserver`，不替换任何系统文件。

## 音频链路

```
App 会话  ──▶  系统混音  ──▶  AURORA  ──▶  扬声器
 session        AudioFlinger   实时 EQ      最后一道还原
                                250 Hz – 6.3 kHz
```

只做 250 Hz 到 6.3 kHz 这六段，是因为实测手机扬声器只在这段里能有效还原。往 47 Hz 或 12 kHz 上堆增益，曲线看着好看，耳朵听不出来。

## 为什么不是 Dolby DAP

这台设备的固件里，Dolby 的 `dap_hw`（UUID `9d4921da-8225-4f29-aefa-39537a04bcaa`）接受厂商参数、写入返回 `rc=0`、效果器在链上、框架里 `Enabled=y`——但音乐完全没变化。它是控制面，不处理 PCM。

真正作用在 PCM 上的是 `android.media.audiofx.DynamicsProcessing`，一个 AOSP 软件均衡器。AURORA 驱动的是它。

## 安装

1. 设备已用 KernelSU 或 Magisk 取得 Root，并装好 LSPosed。
2. 安装 APK，先打开一次，让系统注册这个应用。
3. 在 LSPosed 里启用 AURORA，作用域勾选**系统**。
4. 重启设备，让模块进入 `system_server`。
5. 打开 App，开启「叠加调音」，播放任意音频。

第 3、4 步缺一不可。模块没生效时 App 会直接说"模块没有运行"，不会让你对着一个没反应的界面猜。

## 兼容性

**只在小米 17 Pro（`pandora` / `25098PN5AC`，澎湃 OS 4.0，Android 17）上验证过。其它品牌没有测试，不保证能用。**

模块启动时会在 `system_server` 里探一遍这台机器的链路：能不能拿到 `AudioManager`、有没有播放会话接口、`DynamicsProcessing` 能不能构造、全局效果器有哪些、输出设备有哪些。全通过才继续；任何一步不通，就把机型与完整链路档案发回，并弹窗说明卡在哪一步。

所以"用不了"不会被留成一个谜——它会变成一条带数据的记录。

## 工作原理

- 通过 LSPosed 注入 `system_server`，Hook `com.android.server.audio.AudioService#systemReady()`。
- 所有音频调用都在私有的 `aurora-engine` 线程上执行。**绝不在 `system_server` 主线程上碰音频 HAL**——那会让 watchdog 杀掉系统进程，表现为 SystemUI 连环重启、壁纸变黑。
- 每 2 秒扫一次活跃会话（`AudioManager#getActivePlaybackConfigurations`），给每个会话建一个 `DynamicsProcessing` 实例并写入曲线。
- 每个新会话要在**第二个 tick 重建一次效果器**：MiSound 会在音轨开始时重建效果链，第一拍建的效果器会被孤悬——链上还在、`Enabled=y`，但不出声。
- 曲线按最大绝对值等比缩放到 ±12 dB，不做减均值归一化。减均值会把预设的倾斜形状抹平，九套预设听起来就都一样了。

## 诊断与反馈

- **诊断页**显示引擎实际下发到系统的增益，可以放一段测试音自检（听到一强一弱说明链路通）。
- **反馈**在设置页，可以附运行日志一起提交。日志默认开启，因为**没有日志的问题基本无法定位**。
- 每日反馈汇总在服务器端自动生成。

## 构建

```bash
cd aurora-lsp
bash build.sh          # 产物：out/AURORA-1.2.0.apk
```

需要：JDK 17、Gradle 9.3.1、Android SDK `platforms/android-37` 与 `build-tools/37.0.0`，compileSdk 37 / minSdk 33 / targetSdk 36。

## 更新

App 内可检查更新，客户端从 `https://lingluandf.online/aurora/latest.json` 读取版本信息。没有新版本时完全不打扰；选「以后再说」会记住该版本，同一版本不再提示。

## 许可

界面组件移植自 [KernelSU](https://github.com/tiann/KernelSU)（GPL-3.0），液态玻璃折射效果来自 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）。本项目沿用 **GPL-3.0**。

第三方组件详见 [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md)。

## 作者

凌乱的风 · https://lingluandf.online