# AURORA 1.3.1

这一版修掉了两个存在已久的隐形故障，并让机型适配不再依赖发版。

## 一、限幅器从未生效——破音/炸音的根因

`AudioEq.enableLimiter()` 反射查找的构造器签名是 `(boolean, int, float×5)`，而系统 API 里 `DynamicsProcessing$Limiter` 的真实构造器是 **`(inUse, enabled, linkGroup, attack, release, ratio, threshold, postGain)` 8 个参数**。签名不匹配抛 NoSuchMethodException，被 `catch (Throwable ignored)` 静默吞掉，`limiterCtor` 一直是 null——**从第一个版本起，限幅器就没有写进去过一个参数**。

而 AOSP 默认配置里 limiter 阶段是 in-use + enabled 的：所有设备实际运行的是**系统默认的 −2 dBFS、10:1、attack 1 ms 硬限幅**。1.2.2/1.3.0 声称的「限幅器收紧到 −3 dBFS」从未落地。对照用户反馈：

- 「刷视频刷到广告会炸音」——广告母带响度大，砖墙限幅猛压 → 抽吸与炸音；
- 「酷狗一卡一卡的炸音」——增益随节目起伏反复起控释放；
- 「信息提示音声音不对」——提示音母带接近满刻度，过 10:1 限幅直接发劈；
- 17 Ultra 上 EQ 写入全失败，效果器带着默认 limiter 留在链上——**写入失败却破音**。

修复：按真实签名构造（用 android-37 的 android.jar javap 验证过），默认参数 −6 dBFS、6:1、attack 5 ms、release 80 ms——比默认砖墙早 4 dB 起控但斜率软一倍，保护 DAC 同时不再把大声内容压变形。

## 二、写入失败时不再留半配置效果器

`open()` 建完效果器立即 `setEnabled(false)`，等曲线与限幅器都落地才在 `apply()` 末尾启用。任何一段写入失败会先把效果器下线再报错——**写入被拒的设备不再带着一个只有默认限幅器活着的效果器混音**。逐段错误恢复（audio-quality-v2 合并时丢失）：报错指明「band N @ X Hz, gain Y dB」，适配有据可依。

## 三、设备适配包

新增服务器下发的按机型适配，**不装新 APK 也能调机型**：

```
服务器 /aurora/devices.json ──App 按 product code 匹配──▶ aurora_device_pack（Settings.Global）
                                                              │ 每 tick 消费，逐字段范围校验
                                                              ▼
                    引擎：bandHz 频点表 / maxBoost 分段上限 / limiter 形态
```

- App 在模块开启时自动检查（此时 root 已授权，不会突袭 su 弹窗），诊断页也有「检查适配包」。
- 引擎解析失败的字段静默退回默认值，恶意/损坏的包到不了音频链。
- `aurora_device_pack` 挂进了 ContentObserver，写入后约 150 ms 生效，另有 2 秒周期 tick 兜底。

首发的三个包（17 Ultra / 17 Pro Max / 17）：`maxBoost {4,4,6,6,6,4}` + 上述温和限幅器——治两端过推与炸音。

## 四、频点被拒自动回退

AOSP 默认 EQ 频段从 220 Hz 起排，80 Hz / 10 kHz 端点超出部分机型 DSP 的接受范围（17 Ultra 的 bad parameter value 合理怀疑对象）。现在主频点表被拒时，**同一 tick 内自动用 250 Hz–6.3 kHz 的老频点表重写整条曲线**（1.2.0 时代的已知可行表），并回执 `aurora_backend_adapt=fallback`。回退状态随引擎存活，重启后重新学习。UI 的频点标签跟随引擎实际生效的频点表，回退机型不再把 250 Hz 标成 80 Hz。

## 五、反馈通道补盲

设备档案新增 `backend` / `backendMessage` / `bandHz` 三个字段——之前 probe 通过 ≠ 写入成功（17 Ultra 正是这种「链路可访问但全段被拒」），档案里却完全看不见。现在装机即可远程看见写入结果。

## 升级须知

**改了 system_server 侧引擎，装完必须重启设备。** 17 Ultra 用户重启后播放任意音频，若诊断页显示「频点已回退到 250 Hz–6.3 kHz」即适配生效。

版本：`versionCode 1301` / `versionName 1.3.1`
产物：`out/AURORA-1.3.1.apk`

凌乱的风＆
SHA-256：`bb47de3a7073bd3ba604ae637bdbbf07ad4367494a1f7c70492246ecd604ac26`
