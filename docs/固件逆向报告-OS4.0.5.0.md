# AURORA 固件逆向报告 · OS4.0.5.0.XBLCNXM

> 目标固件：`pandora-ota_full-OS4.0.5.0.XBLCNXM-user-17.0-a4da788249.zip`（8.54 GB）
> 机型：Xiaomi 17 Pro（codename `pandora`）
> 分析方法：Android OTA payload 解析 → EROFS 解包 → 配置与二进制静态分析 → 与设备侧 MiSound 客户端交叉验证

## 一、结论摘要

1. 这台设备的音效**不是**"系统均衡器 + 厂商 XML"两层，而是 **Qualcomm AIDL 效果框架 + Dolby DAX3（DAP）+ 小米 MiSound 代理**三层。
2. Dolby 是以标准 AudioEffect 注册的，名字 `dap_hw`，UUID `9d4921da-8225-4f29-aefa-39537a04bcaa`。**任何有音频权限的进程都能创建它并下发厂商参数**，官方 MiSound 自己就是这么做的。
3. Dolby 内置 **20 段图形均衡器（GEQ）**，频点 47 Hz – 19.7 kHz，增益单位 **1/16 dB**。原厂 GEQ 全为 0，所以写一条零均值曲线是纯叠加，不会削顶。
4. 固件定义了 **9 个 profile** 和 **7 套 tuning**，tuning 按音量档位（small / medium / large）区分，配合 `/odm/etc/audio/audio_effects_config.json` 里的 0–70 / 71–130 / 131–150 音量分段。
5. 旧模块（v5.7.x 改 XML、0.4.0 用 AOSP 5 段均衡器）**都没碰这一层**。旧 UI 画的 10 段曲线在这台机器上根本不存在对应的处理级。

## 二、固件提取链

```
pandora-ota_full-OS4.0.5.0.zip (8.54 GB)
└─ payload.bin                     Android A/B OTA，version=2，manifest 335,287 B
   ├─ 58 个分区，全部 REPLACE_XZ / REPLACE_BZ / REPLACE 三种 op
   └─ 关键分区
      ├─ odm      3,714 MB  → Dolby 配置、音效资源、SKU mixer paths
      ├─ vendor     562 MB  → soundfx 库、audio HAL、DSP 参数
      ├─ system     932 MB  → framework / audio_effects.xml
      └─ mi_ext     164 MB  → 小米扩展
```

分区文件系统全部是 **EROFS**（magic `e2e1f5e0`），用 `erofs-utils` 解包。

数据区起点算法：`data_start = 24 + manifest_size + metadata_signature_size`，
每条 op 的 `data_offset` 相对该起点，可随机寻址，因此能只解需要的分区。

## 三、音频架构

```svg
<svg viewBox="0 0 680 300" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M1 1 L9 5 L1 9" fill="none" stroke="#69707B" stroke-width="1.2"/>
    </marker>
  </defs>
  <text x="40" y="22" font-family="-apple-system,'PingFang SC',sans-serif" font-size="14" font-weight="500" fill="#1D2025">固件里的音效链路</text>

  <rect x="40" y="44" width="150" height="52" rx="8" fill="#F4F7FE" stroke="#426AC7" stroke-width="0.5"/>
  <text x="115" y="66" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">应用音频</text>
  <text x="115" y="83" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="11" fill="#69707B">music / video / game</text>

  <rect x="230" y="44" width="160" height="52" rx="8" fill="#F4F7FE" stroke="#426AC7" stroke-width="0.5"/>
  <text x="310" y="66" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">audio_effects_config.xml</text>
  <text x="310" y="83" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="11" fill="#69707B">AIDL 效果注册表</text>

  <rect x="430" y="44" width="210" height="52" rx="8" fill="#F4F7FE" stroke="#426AC7" stroke-width="0.5"/>
  <text x="535" y="66" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">dap_hw · libhwdapaidl.so</text>
  <text x="535" y="83" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="11" fill="#69707B">Dolby DAX3 · 20 段 GEQ</text>

  <rect x="40" y="140" width="150" height="52" rx="8" fill="#FDF8EF" stroke="#C08A2E" stroke-width="0.5"/>
  <text x="115" y="162" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">MiSound</text>
  <text x="115" y="179" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="11" fill="#69707B">com.miui.misound</text>

  <rect x="230" y="140" width="160" height="52" rx="8" fill="#FDF8EF" stroke="#C08A2E" stroke-width="0.5"/>
  <text x="310" y="162" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">DolbyAudioEffect(0,0)</text>
  <text x="310" y="179" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="11" fill="#69707B">session 0 · 全局输出</text>

  <rect x="430" y="140" width="210" height="52" rx="8" fill="#FDF8EF" stroke="#C08A2E" stroke-width="0.5"/>
  <text x="535" y="162" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">dax-default.xml</text>
  <text x="535" y="179" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="11" fill="#69707B">9 profile · 7 tuning</text>

  <rect x="40" y="232" width="600" height="48" rx="8" fill="#F1F3F6" stroke="#D6DAE2" stroke-width="0.5"/>
  <text x="340" y="260" text-anchor="middle" font-family="-apple-system,'PingFang SC',sans-serif" font-size="13" fill="#1D2025">DSP / 扬声器 · 原厂音量分级保护不变</text>

  <line x1="190" y1="70" x2="228" y2="70" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
  <line x1="390" y1="70" x2="428" y2="70" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
  <line x1="190" y1="166" x2="228" y2="166" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
  <line x1="390" y1="166" x2="428" y2="166" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
  <path d="M535 96 V138" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
  <path d="M115 192 V230" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
  <path d="M535 192 V230" stroke="#69707B" stroke-width="1" marker-end="url(#a)"/>
</svg>
```

`/vendor/etc/audio/sku_canoe/audio_effects_config.xml` 注册的效果器（节选）：

| 名称 | 库 | UUID |
| --- | --- | --- |
| `dap_hw` | `libhwdapaidl.so` | `9d4921da-8225-4f29-aefa-39537a04bcaa` |
| `misoundfx` | `libmisoundfx_aosp_aidl.so` | `5b8e36a5-144a-4c38-b1d7-0002a5d5c51b` |
| `spatializer` | `libswspatializeraidl.so` | `ccd4cf09-a79d-46c2-9aae-06a1698d6c8f` |
| `gamedap` | `libswgamedapaidl.so` | `3783c334-d3a0-4d13-874f-0032e5fb80e2` |
| `dlb_music_listener` | `libdlbvolaidl.so` | `40f66c8b-5aa5-4345-8919-53ec431aaa98` |
| `equalizer`（sw） | `libbundleaidl.so` | 5 段 AOSP 实现 |

固件按 SKU 分成 `alor` / `canoe` / `whale` 三套音频配置，本机走 `canoe`。

## 四、Dolby DAP 控制协议（逆向核心）

### 4.1 创建

```java
// 与官方 MiSound 完全一致（e/a.java）
AudioEffect effect = new AudioEffect(
        AudioEffect.EFFECT_TYPE_NULL,          // ec7178ec-e5e1-4432-a3f4-4657e6795210
        UUID.fromString("9d4921da-...bcaa"),   // dap_hw
        0, 0);                                  // priority, session 0 = 全局输出混音
```

`AudioEffect(UUID, UUID, int, int)` 在 framework 里是 `public`，但 `setParameter(int, byte[])` /
`getParameter(int, byte[])` / `EFFECT_TYPE_NULL` 都被 public SDK 剥离，所以模块用反射调用。
在 `system_server` 内不存在隐藏 API 限制。

### 4.2 参数通道

所有厂商命令走 `setParameter(5, byte[])`，缓冲区是小端 int32 数组：

```
set 类：[命令][数量][profile][参数 ID][值...]
```

| 命令 | 值 | 含义 |
| --- | --- | --- |
| `CMD_PARAM` | `0x01000000` | 设置某 profile 的厂商参数 |
| `CMD_ENABLE` | `0x00000000` | Dolby 处理总开关 |
| `CMD_PROFILE` | `0x0A000000` | 选择 profile |

读操作走 `getParameter(命令 + 5, buf)`，buf 首 int 为命令。例：读 profile 数量 `getParameter(0x03000005)`。

### 4.3 参数表（`e.b` 枚举 ↔ 4CC）

| ID | 4CC | 含义 |
| --- | --- | --- |
| 101 | `vdhe` | 耳机虚拟化 |
| 102 | `vspe` | 扬声器虚拟化（环绕） |
| 103 | `dvle` | 音量均衡开关 |
| 104 | `ieid` | IEQ 预设 ID |
| 105 | `deon` | 对白增强开关 |
| **106** | **`geon`** | **图形均衡器开关** |
| 107 | `iea` | IEQ 强度 |
| 108 | `dea` | 对白增强强度 |
| 109 | `ded` | 对白增强 ducking |
| **110** | **`gebg`** | **图形均衡器 20 段增益** |
| 111 | `beon` | 低音增强开关 |
| 112 | `vbon` | 虚拟低音开关 |
| 113 | `vssd` | 声场宽度 |
| 114 | `rvse` | 残响抑制开关 |
| 115 | `rvsa` | 残响抑制强度 |
| 116 | `dvla` | 音量均衡强度 |

### 4.4 数值量纲

官方 UI 是 10 个滑块（float dB），写入前会展开成 20 段 int：

```java
iArr[i] = (int) (dB * 16.0f);   // z/f.java
```

即 **1 单位 = 1/16 dB**。写入前再减去所有段的算术平均（`z/d.java`），保证曲线零均值、不改变整体响度、不引入削顶。

## 五、Dolby 数据模型（`/odm/etc/dolby/dax-default.xml`）

格式版本 3.9.1，工具 3.18，`DAX3 Android / DAP MOBILE v2.7`。文件 sha256
`0b9d1a2b…cd9437`，与设备上现存文件逐字节一致。

| 项 | 内容 |
| --- | --- |
| profile | 9 个：0 Dynamic、1 Movie、2 Music、3 Custom、4 long_video、5 short_video、6 navigation、7 Mobility_travel、8 Voice |
| endpoint | 每个 profile 有 speaker / headphone / other 三套，各 28 个参数 |
| tuning | 7 套：speaker_landscape、_medium、_small、_large、speaker_portrait、headphone、unknown |
| GEQ | 20 段，47 141 234 328 469 656 844 1031 1313 1688 2250 3000 3750 4688 5813 7125 9000 11250 13875 19688 Hz |
| preset | 3 个 IEQ（balanced / detailed / warm，取值为 target）+ 9 个 GEQ（按 profile ID，全零） |
| 安全 | `peak-value`、`high-pass-filter`、`dynamic-speaker-optimization`、`regulator-*` 全部独立于 GEQ |

音量分级与 tuning 的绑定在 `/odm/etc/audio/audio_effects_config.json`：

| 音量区间 | device_id |
| --- | --- |
| 0–70 | small_mode_speaker |
| 71–130 | medium_mode_speaker |
| 131–150 | large_mode_speaker |

这说明原厂按音量切换整套 tuning（含保护阈值）。任何“只改一条曲线就套用到所有音量”的做法都会绕过这层匹配，旧方案的风险就在这里。

## 六、旧方案的问题定位

| 旧实现 | 实测/逆向结论 |
| --- | --- |
| 0.4.0 用 `new Equalizer(0,0)` | 命中的是 `libbundleaidl.so` 的 **5 段 AOSP 均衡器**（60/230/910/3600/14000 Hz），与 Dolby 无关 |
| 0.4.0 UI 画 10 段曲线 | 上位机只有 5 个真实频段，曲线是插值出来的假象 |
| v5.7.x 补丁 `dax-default.xml` + `ctl.restart audioserver` | 能改到 20 段，但每次切换要重启音频服务，且和 MiSound 抢同一份文件 |
| `persist.vendor.audio.effectimplenter=dolby` | 与 Dolby 效果器实例冲突，导致效果器失去控制权 |
| 没有读取原厂 profile / tuning | profile 由 app 分类自动切换（长视频=4、短视频=5、导航=6），旧模块完全没考虑 |

## 七、新模块设计（已实现，v1.0.0）

```
LSPosed 注入 system_server
└─ AudioService.systemReady 之后
   ├─ AuroraEngine.apply()
   │   ├─ 读 Settings.Global 的 aurora_* 配置
   │   ├─ 曲线 = 预设或用户曲线 × 强度 → 减均值 → ×16 → 限幅 ±12 dB
   │   ├─ DolbyDap.setParam(每个 profile, 106, [1])     打开 GEQ
   │   ├─ DolbyDap.setParam(每个 profile, 110, gains)   写 20 段
   │   ├─ 场景项：102 环绕 / 105+108 对白 / 112 虚拟低音 / 103 音量均衡 / 113 宽度 / 114 残响
   │   └─ 回执写入 aurora_backend_*
   └─ ContentObserver → 配置变更 220 ms 防抖后重新应用
```

关键取舍：

- **只做叠加**：原厂 profile、tuning、DRC、扬声器保护一律不动；关闭时把 GEQ 写回全零。
- **不重启 audioserver**：所有改动经由效果器通道即时生效。
- **不替换任何系统文件**：不 bind mount，不写 `/odm`。
- **默认写全部 profile**：与官方 MiSound 行为一致，这样 app 触发的 profile 自动切换不会把曲线顶掉。

### 配置键（Settings.Global）

`aurora_enabled`、`aurora_preset`、`aurora_strength`、`aurora_geq`（20 个 1/16 dB 整数，逗号分隔）、`aurora_profile`（-1 跟随系统）、`aurora_scope`、`aurora_surround`、`aurora_dialog`、`aurora_dialog_amount`、`aurora_vbass`、`aurora_leveler`、`aurora_leveler_amount`、`aurora_width`、`aurora_reverb`、`aurora_reverb_amount`

回执键：`aurora_backend_state`、`_message`、`_bands`、`_profile`、`_profiles`、`_capability`、`_updated`

## 八、尚未验证的部分

1. **Dolby 效果器能否在 system_server 里成功创建**。官方是 app 进程创建，我们换到了系统进程；权限只会更宽，但 `dap_hw` 是否允许多客户端、是否要求独占，必须上机确认。
2. **`getParameter` 读带宽目标**。协议里只确认了 MiSound 会写入 GEQ，没有读取 GEQ 的调用，所以“原值快照”是尽力而为；读不到时回退为写零（等于原厂状态）。
3. **场景类强度的数值范围**（108/112/113/116）没有官方 UI 对应，当前按 0–10 的小档位下发，属于保守取值，需要听感校准。
4. **`profile` 数量与顺序**按 XML 推断为 9 个，实际以 `getParameter(0x03000005)` 的回报为准。

## 九、上机验证步骤

```bash
# 1. 安装
adb install -r out/AURORA-LSP-1.0.0.apk
# 2. 在 LSPosed 里给 com.aurora.audio.lsp 勾选「系统框架」，重启
# 3. 打开 App，看诊断页
#    期望：Profile 共 9 个；状态「叠加生效中」；实际写入显示峰值与均值
# 4. 若状态为 unavailable：说明效果器创建失败
adb shell dumpsys media.audio_flinger | grep -i dolby
adb logcat -s AURORA-LSP
```

凌乱的风＆
## 十、运行时验证（2026-10-26 实机）

静态结论在真机上做了逐项验证，有几条与原先的推断不同，以本节为准。

### 10.1 已验证可用的部分

| 操作 | 结果 |
| --- | --- |
| 创建 dap_hw 效果器 | 12–16 ms，hasControl=true |
| setParameter(5, …) 写 GEQ 开关（106） | rc=0，5 ms |
| setParameter(5, …) 写 20 段增益（110） | rc=0，0–1 ms |
| 切 profile（ x0A000000） | rc=0 |
| 全频段 −12 dB | 听感确认：音量明显下降 |

### 10.2 与推断不符、必须修正的部分

1. **读接口不可用。** getParameter 对  x03000005、 x04000005、 x0A000005、 x01000005、
    x0D000005 全部返回 12，且缓冲区原样返回命令字。因此 profile 数量、当前 profile 都读不到，
   必须按固件写死（9 个），任何基于读值的循环上界都是错误来源。
2. **session 0 不是播放路径。** 开启空间音频时，音乐在 AudioOut_5D（type 7 SPATIALIZER）上播放，
   携带 per-session 效果链；AudioOut_D 的 session 0 链根本没有音频流经过。写 session 0 完全无效。
3. **必须按播放会话挂载。** 把同一个效果器挂到正在播放的 session 上，写入立即生效（听感确认）。
4. **新建实例默认 enabled=false。** 必须显式下发 CMD_ENABLE=[1]，否则写入没有任何效果。
5. **system_server 主线程碰不得。** 在 AudioService 的主 Looper 上做上述调用，一旦阻塞就是 watchdog
   杀系统进程；实测导致 SystemUI 连环重启、壁纸黑屏。所有工作必须放在独立后台线程。

### 10.3 扬声器有效区间

20 个频点里，听感上真正有效的是 **656 Hz – 4.7 kHz**：

- 47–328 Hz 与 9 kHz 以上的调整几乎不可闻（受扬声器物理限制）；
- 同一套 ±12 dB 曲线，堆在两端时"一丝丝变化"，做在中频时"明显变亮"。

因此预设设计必须围绕中频，低频推动要靠 300–700 Hz 段而不是 47 Hz。

### 10.4 最终架构

`
system_server
└─ aurora-engine 线程（绝不使用主线程）
   ├─ 每 2s 读取 getActivePlaybackConfigurations()
   ├─ 取 state=STARTED 且 sessionId>0 的会话
   ├─ 每个会话：AudioEffect(NULL_TYPE, dap_uuid, 0, session)
   │             → CMD_ENABLE=[1]
   │             → 9 × (106=[1], 110=20 段曲线)
   └─ 会话结束 → release()，音频回原厂链路
`

凌乱的风＆