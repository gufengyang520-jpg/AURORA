# 音频模块（AURORA）· CONTEXT

一句话摘要：这是一个绕过系统均衡器、直接用 LSPosed 在 `system_server` 里以 AudioEffect 通道驱动高通/杜比 DAX3 `dap_hw` 效果器的 20 段 GEQ 叠加项目，v1.5.0 已在真机听感确认，旧路线 `aurora-pro`（改 Dolby XML + props）已归档。

元信息：本地静态阅读整理（只读 `C:\Users\lldf\XiaomiMiMoProjects\音频模块`），未连真机、未跑构建、未 ADB；结论全部来自仓库内文本，运行时行为以仓库既有记录为准。

## 1. 目标

把手机听感变成一个可编程的东西：不动原厂 `/odm` 配置、不重启 `audioserver`、不换系统文件，只在播放链路末端叠加一条自己可控的 20 段曲线（外加环绕/对白/虚拟低音等开关），关掉即回原厂。

- 目标设备与基线：Xiaomi 17 Pro（`pandora`），固件 `OS4.0.5.0.XBLCNXM`，音频平台 Qualcomm `sku_canoe`，调音引擎 Dolby DAX3 / DAP MOBILE v2.7（证据：README.md — "机型 | Xiaomi 17 Pro（codename `pandora`）"、"调音引擎 | Dolby DAX3 / DAP MOBILE v2.7"）。
- 关键能力来源：Dolby 以标准 AudioEffect 注册，名字 `dap_hw`、UUID `9d4921da-8225-4f29-aefa-39537a04bcaa`，**任何有音频权限的进程都能创建并下发厂商参数**（证据：docs/固件逆向报告-OS4.0.5.0.md — "Dolby 是以标准 AudioEffect 注册的，名字 `dap_hw`，UUID `9d4921da-8225-4f29-aefa-39537a04bcaa`。**任何有音频权限的进程都能创建它并下发厂商参数**"）。
- 频段与量纲：20 段，47 Hz – 19.7 kHz，1 单位 = 1/16 dB（证据：docs/固件逆向报告-OS4.0.5.0.md — "Dolby 内置 **20 段图形均衡器（GEQ）**，频点 47 Hz – 19.7 kHz，增益单位 **1/16 dB**"）。
- 明确不碰的东西：`/odm/etc/dolby/*.xml`、`persist.vendor.audio.*` 属性、`audioserver` 进程（证据：README.md — "**不碰的东西**：`/odm/etc/dolby/*.xml`、`persist.vendor.audio.*` 属性、`audioserver` 进程"）。

### 分层结构

| 层 | 目录/文件 | 职责 | 状态 |
| --- | --- | --- | --- |
| Java 注入层 | `aurora-lsp/`（5 个 Java 文件 + `build.sh`） | Hook `AudioService.systemReady`，在独立线程按播放会话挂 Dolby 效果器、写 20 段曲线，并给 UI 写回执 | 主线，v1.5.0 可用 |
| Magisk 层 | `aurora-pro/`（shell + WebUI） | 改 Dolby XML（bind mount）+ 写 4 个 `persist.*` 属性 + 必要时 `ctl.restart audioserver` | 旧路线，README 标注"已归档" |
| 配置层 | `aurora-lsp/.../Presets.java`、`aurora-pro/device/data/runtime/dax-default.xml`、`_work/assets/odm_etc_audio/audio_effects_config.json` | 曲线数据、9 profile / 7 tuning 定义、音量档位与 app→profile 映射 | 部分只读引用 |
| 调试归档 | `device/`（dumpsys、props、日志、db） | 真机抓取取证：效果器注册、spatializer 开关史、模块配置快照 | 只作证据 |

`officelite/` 是另一个独立项目（Office 复刻），与本项目无关，另有负责人。

## 2. 已完成

**注入链已跑通**：入口由 `xposed_init` 指定，模块只在 `pkg == "android"` 时动手，hook 的是 `com.android.server.audio.AudioService.systemReady()`（无参重载）的 after 钩子。

- 入口类：`com.aurora.lsp.AuroraXposed`（证据：aurora-lsp/app/src/main/assets/xposed_init — "com.aurora.lsp.AuroraXposed"）。
- 模块声明：包名 `com.aurora.audio.lsp`，`xposedmodule=true`，`xposedminversion=93`，作用域走 `@array/xposed_scope`（证据：aurora-lsp/app/src/main/AndroidManifest.xml — "`<meta-data android:name="xposedmodule" android:value="true" />`"、"`<meta-data android:name="xposedminversion" android:value="93" />`"）。
- 只处理 system_server：`if (!"android".equals(params.packageName)) return;`（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/AuroraXposed.java — "if (!\"android\".equals(params.packageName)) return;"）。
- Hook 点：遍历 `AudioService` 声明方法找名字为 `systemReady` 且无参的那个，`XposedBridge.hookMethod` 后 `attach(param.thisObject)`；找不到会回退 `HOOKED=false` 并记日志（证据：同上 — "if (!\"systemReady\".equals(method.getName()) || method.getParameterTypes().length != 0)"、"AudioService hook failed: "）。
- 拿 Context：不是自己 new，而是从 `AudioService` 实例反射取 `mContext` 字段（证据：同上 — "Object value = XposedHelpers.getObjectField(audioService, \"mContext\");"）。

**线程模型已固化**：绝不在主 Looper 上做音频调用。

- 引擎线程：`new HandlerThread("aurora-engine", Process.THREAD_PRIORITY_BACKGROUND)`，所有 tick 都 post 到它（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/AuroraXposed.java — "HandlerThread thread = new HandlerThread(\"aurora-engine\", " + 换行 + "Process.THREAD_PRIORITY_BACKGROUND);"（源码实际分两行书写，此处以 + 表示换行））。
- 轮询：`TICK_MS = 2000L`，tick 完再 `postDelayed(this, TICK_MS)`（证据：同上 — "private static final long TICK_MS = 2000L;"）。
- 配置变更即时触发：对 15 个 `aurora_*` 键注册 `ContentObserver`，`onChange` 里 `removeCallbacks(TICK)` 后立即 `post`，等效一次即时 tick（证据：同上 — "target.removeCallbacks(TICK); target.post(TICK);"）。
- 双开关防重入：`HOOKED` 与 `STARTED` 两个 `AtomicBoolean` 各自 CAS，避免重复 hook / 重复起线程（证据：同上 — "if (!STARTED.compareAndSet(false, true)) return;"）。

**会话发现与挂载链路已打通**：`AuroraEngine.tick()` 是唯一入口，流程是「读配置 → 判 panic/enabled → 扫活跃会话 → 算曲线 → 按会话写入 → 回执」。

- 会话来源：反射 `AudioManager.getActivePlaybackConfigurations()`，再用 `AudioPlaybackConfiguration.getSessionId()` / `getPlayerState()` 取字段，只收 `state == 2`（STARTED）且 `0 < session <= 1_000_000` 的（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/AuroraEngine.java — "Class<?> managerClass = Class.forName(\"android.media.AudioManager\");"、"private static final int PLAYER_STATE_STARTED = 2;"、"if (session <= 0 || session > 1_000_000) continue;"）。
- 每会话一个效果器：`effects` 是 `Map<Integer, DolbyDap>`，上限 `MAX_SESSIONS = 8`（证据：同上 — "private static final int MAX_SESSIONS = 8;"、"private final Map<Integer, DolbyDap> effects = new HashMap<>();"）。
- 效果器创建：`AudioEffect(EFFECT_TYPE_NULL 的 UUID, dap_hw 的 UUID, 0, audioSession)`，用反射拿 4 参构造器并 `setAccessible(true)`（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/DolbyDap.java — "constructor = cls.getDeclaredConstructor(UUID.class, UUID.class, int.class, int.class);"、"effect = constructor.newInstance(UUID_TYPE_NULL, UUID_EFFECT, 0, audioSession);"）。
- 打开判定：创建后必须 `hasControl()` 为真才算 open 成功（证据：同上 — "return Boolean.TRUE.equals(hasControlMethod.invoke(effect));"）。
- 必须先显式开总闸：open 成功后先 `setEnabledFlag(true)`，下发 `CMD_ENABLE`=`0x00000000` 的 `{CMD_ENABLE, 1, 1}`，否则写入无声效（证据：同上 — "return write(new int[]{CMD_ENABLE, 1, enabled ? 1 : 0}) >= 0;"）。
- 写曲线：对 `profile 0..8` 每个都写 `106(geon)=[1]` 和 `110(gebg)=gains`，共 18 次 `setParameter`（证据：AuroraEngine.java — "for (int profile = 0; profile < PROFILE_COUNT; profile++) { ok &= dap.setParam(profile, DolbyDap.P_GEQ_ENABLE, new int[]{1}); ok &= dap.setParam(profile, DolbyDap.P_GEQ_GAINS, gains); }"）。
- profile 数量写死 9：读接口不可用，`PROFILE_COUNT` 是常量而非读值（证据：同上 — "Firmware defines nine profiles; reads are unsupported on this build, so this is fixed."）。
- 幂等：`desiredSignature(...)` 把曲线+场景拼成字符串，与上次 `markApplied` 的签名相同就跳过重写；回执本身也用 signature 去重（证据：同上 — "if (dap.isApplied() && desired.equals(dap.signature()))"、"if (signature.equals(lastSignature)) return;"）。
- 会话结束释放：`releaseStale(sessions)` 遍历 `effects` 找不在活跃集合里的 session，`close()` → `release()`，音频回原厂链路（证据：同上 — "releaseAll(); / dap.close();" 与 DolbyDap.java — "releaseMethod.invoke(effect);"）。

**`DolbyDap` 把厂商协议封装齐了**：参数通道 `setParameter(5, …)`，包体是小端 int32 数组 `[命令][数量][profile][参数 ID][值…]`。

- 命令码：`CMD_PARAM=0x01000000`、`CMD_ENABLE=0x00000000`、`CMD_PROFILE_COUNT=0x03000000`、`CMD_PROFILE=0x0A000000`、`CMD_CAPABILITY=0x0D000000`，通道固定 `CHANNEL = 5`（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/DolbyDap.java — "private static final int CMD_PARAM = 0x01000000;"、"private static final int CHANNEL = 5;"）。
- 参数 ID 与 4CC 一一对应，101–116 全套常量都在（证据：同上 — "public static final int P_GEQ_ENABLE = 106;"、"public static final int P_GEQ_GAINS = 110;"）。
- 编码：手写小端 `byte[]`，不依赖 ByteBuffer（证据：同上 — "out[offset] = (byte) value; out[offset + 1] = (byte) (value >>> 8);"）。
- 读能力保留但明确标记"尽力而为"：`readBandGains()` 注释为 best-effort，注释直接写"returns null when the firmware refuses"（证据：同上 — "/** Best-effort read of the live band gains; returns null when the firmware refuses. */"）。

**`Presets` 提供 9 套 20 段曲线**：`KEYS`/`NAMES`/`NOTES` 三组元数据 + `CURVES` 二维 float dB 数组，列序与 `DolbyDap.BAND_HZ` 严格对齐。

- 曲线定义位置与形状：`private static final float[][] CURVES`，每行 20 个 float，共 9 行（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/Presets.java — 表头注释 "// 47  141  234  328  469  656  844 1031 1313 1688 2250 3000 3750 4688 5813 7125 9000 11250 13875 19688"）。
- 已按听感结论重调：注释明说只在 300 Hz–6 kHz 内做文章（证据：同上 — "only roughly 300 Hz - 6 kHz is reproduced usefully"）；"低频推动"落在 328–844 Hz 而不是 47 Hz（证据：同上 — "在 328–844Hz 做推动，鼓点更实（不是堆 47Hz）"）。
- 归一化在引擎里做：曲线先乘强度再统一减算术均值，最后 `×16` 并限幅 ±12 dB（证据：AuroraEngine.java — "int value = Math.round((scaled[i] - mean) * DolbyDap.UNIT_PER_DB);"、"int limit = MAX_BAND_DB * DolbyDap.UNIT_PER_DB;"）。

**UI 与自检/急停可用**：`MainActivity` 四页「音效 / 均衡 / 场景 / 诊断」（证据：aurora-lsp/app/src/main/java/com/aurora/lsp/MainActivity.java — "tabTone = navItem(\"音效\", v -> showTab(TAB_TONE));"、"tabDiag = navItem(\"诊断\", v -> showTab(TAB_DIAG));"）。

- App 写配置走 root：`ProcessBuilder("su", "-c", command)`，即 `settings put global …` 由 su 执行（证据：同上 — "process = new ProcessBuilder(\"su\", \"-c\", command).redirectErrorStream(true).start();"）。
- 自检：写 `aurora_selftest` 递增 token → 引擎全频 −12 dB 持续 6 s 后自动回原曲线（证据：AuroraEngine.java — "selftestUntil = android.os.SystemClock.elapsedRealtime() + 6000L;"、Presets/Main 侧注释 "自检中：全频段 -12 dB，6 秒后自动恢复。"）。
- 急停：`aurora_panic=1` → `releaseAll()` 并回执 "panic"（证据：AuroraEngine.java — "if (config.panic) { releaseAll(); report(\"panic\""）。

**旧路线 `aurora-pro` 也留有完整实现**（虽已归档）：`module.prop` v5.7.3、Magisk/KernelSU 四段脚本 + WebUI + 运行时 XML 补丁。

- 三段时序分工：`post-fs-data.sh` 与 `post-mount.sh` 都调 `aurora_bind_early` 做"尽早绑定"，`service.sh` 等 `sys.boot_completed=1` 再 `sleep 3` 后调 `aurora_apply_all` 做完整应用（证据：aurora-pro/device/service.sh — "until [ \"$(getprop sys.boot_completed 2>/dev/null)\" = \"1\" ]; do sleep 2; done"、"aurora_apply_all \"$MODDIR/aurora.conf\" service"）。
- 并发用 mkdir 锁：`mkdir "$LOCK"` 成功才算拿到锁，失败则查 `pid` 判活并回收陈旧锁（证据：aurora-pro/device/post-fs-data.sh — "if mkdir \"$LOCK\" 2>/dev/null; then"、service.sh — "service: recovered stale apply lock"）。
- XML 来源：不打包静态 XML，首启复制设备自己的 `/odm` 再补丁（证据：aurora-pro/device/customize.sh — "The package contains no static Dolby XML: the runtime copies the device's own /odm XML on first boot and patches that copy."）。
- props 注入 4 个：`persist.vendor.audio.effectimplenter=dolby`、`persist.vendor.audio.misound.disable=true`、`persist.audio.effect.device_map=speaker:dolby,earpiece:dolby`、`persist.audio.dolby.profile=<id>`，写前快照、可回滚（证据：aurora-pro/device/aurora_core.sh — "aurora_set_prop persist.vendor.audio.effectimplenter dolby"、"aurora_set_prop persist.vendor.audio.misound.disable true"）。
- `system.prop` 故意留空：属性全部运行时写，先快照 OEM 值便于安全模式/卸载恢复（证据：aurora-pro/device/system.prop — "keeps audio properties runtime-only so their OEM values can be snapshotted before activation"）。

**取证归档已成型**：`device/` 里能对上关键运行时事实。

- `dap_hw` 确实注册在系统：flinger dump 里能看到该 UUID 与 implementor（证据：device/dumpsys-audio-flinger.txt — "- UUID: 9d4921da-8225-4f29-aefa-39537a04bcaa"、"- implementor: Dolby Laboratories"）。
- dolby 效果器是当前活动效果，且有 device→effect 映射（证据：device/dumpsys-audio.txt — "Current active effect: dolby"、"Device -> Effect map: {speaker=dolby,headset}"）。
- spatializer 被反复开关，印证"空间音频开着时走独立输出链"的判断（证据：device/dumpsys-audio.txt — "onSpatializerEnabledChanged: enabled=true, current effect type=0" 多行）。
- 系统 blur 默认开启，这也是后来"模糊丢失"现象显眼的原因（证据：device/system-props.txt — "persist.sys.background_blur_setting]: [1]"、"persist.sys.background_blur_supported]: [true]"）。

## 3. 关键决策

1. **走 AudioEffect 通道，不做 XML 替换**。原因是 XML 路线每次切换要重启音频服务，且与 MiSound 抢同一份文件（证据：docs/固件逆向报告-OS4.0.5.0.md — "能改到 20 段，但每次切换要重启音频服务，且和 MiSound 抢同一份文件"）。这直接导致 `aurora-lsp` 成为主线、`aurora-pro` 归档。
2. **必须按播放会话挂，不挂 session 0**。本机开空间音频时音乐走 `AudioOut_5D`（SPATIALIZER）带 per-session 效果链，session 0 的链被完全绕过（证据：docs/固件逆向报告-OS4.0.5.0.md — "开启空间音频时，音乐在 AudioOut_5D（type 7 SPATIALIZER）上播放，携带 per-session 效果链；AudioOut_D 的 session 0 链根本没有音频流经过"）。代码层面体现为 `open(audioSession)` 直接拒绝 `<=0`（证据：DolbyDap.java — "if (audioSession <= 0) { lastError = \"invalid session \" + audioSession; return false; }"）。
3. **所有音频 HAL 调用下沉到私有线程**。这是用系统崩溃换来的教训，代码里三处注释反复强调（证据：AuroraXposed.java — "Running them on the system_server main looper makes the watchdog kill the system process, so nothing here ever touches the main thread."）。
4. **profile 数量与顺序写死为 9，读值只做校验**。因为 `getParameter` 全线返回 12 且不回填（证据：docs/固件逆向报告-OS4.0.5.0.md — "getParameter 对  x03000005、 x04000005、 x0A000005、 x01000005、 x0D000005 全部返回 12，且缓冲区原样返回命令字"）。
5. **默认写全部 9 个 profile**。与官方 MiSound 行为一致，这样 app 触发的 profile 自动切换不会把曲线顶掉（证据：docs/固件逆向报告-OS4.0.5.0.md — "**默认写全部 profile**：与官方 MiSound 行为一致，这样 app 触发的 profile 自动切换不会把曲线顶掉"）。
6. **只做叠加、零均值归一化**。原厂 GEQ 全为 0，所以零均值曲线是纯叠加、不改整体响度、不引入削顶（证据：docs/固件逆向报告-OS4.0.5.0.md — "原厂 GEQ 全为 0，所以写一条零均值曲线是纯叠加，不会削顶"）。
7. **保留 AusioEffect 的通用 byte 通道而非找厂商 AIDL**。即所有厂商命令统一走 `setParameter(5, …)`，避免依赖隐藏 AIDL 接口（证据：DolbyDap.java — "Its vendor parameter space is a 32-bit command packet delivered through the generic AudioEffect byte-array channel."）。
8. **配置与回执都放 `Settings.Global`，用 ContentObserver 解耦**。App 与 system_server 内模块不共享进程，`settings` 全局键是唯一低成本通道（证据：AuroraXposed.java — "resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer);"）。
9. **保留紧急停机开关**。`aurora_panic` 一条命令释放全部效果器，是踩坑后加的保险（证据：README.md — "紧急停机：`adb shell settings put global aurora_panic 1`"）。
10. **听感优先于对称性**：扬声器有效区间只有约 300 Hz–6 kHz，预设据此重写（证据：README.md — "47–328 Hz 和 9 k 以上的调整幅度再大也几乎听不出来"）。

## 4. 踩过的坑

- **在 system_server 主线程做音频调用 → watchdog 直接杀系统进程**，表现是 SystemUI 连环重启、壁纸黑屏、背景模糊消失。（证据：docs/固件逆向报告-OS4.0.5.0.md — "**system_server 主线程碰不得。** 在 AudioService 的主 Looper 上做上述调用，一旦阻塞就是 watchdog 杀系统进程；实测导致 SystemUI 连环重启、壁纸黑屏"；README.md — "放在主线程会被 watchdog 杀掉整个系统进程（壁纸变黑、模糊丢失、SystemUI 连环重启就是这么来的）"）修复方式：独立 `HandlerThread` + `THREAD_PRIORITY_BACKGROUND`，见第 3 节第 3 条。
- **挂 session 0 完全没声音变化**。曾以为全局输出混音就是唯一入口，实测被 spatializer 的 per-session 链绕过（证据：docs/固件逆向报告-OS4.0.5.0.md — "写 session 0 完全无效"）。
- **新建效果器默认 `enabled=false`**，只写曲线不开总闸等于什么都没做（证据：docs/固件逆向报告-OS4.0.5.0.md — "**新建实例默认 enabled=false。** 必须显式下发 CMD_ENABLE=[1]，否则写入没有任何效果"）。
- **读接口是幻觉**：六类 get 命令 rc 恒为 12、缓冲区原样返回，任何用读值当循环上界或"当前 profile"的做法都是错误来源（证据：docs/固件逆向报告-OS4.0.5.0.md 10.2 第 1 条）。
- **老方案的 5 段均衡器根本不是这条链路**：`new Equalizer(0,0)` 命中的是 `libbundleaidl.so` 的 5 段 AOSP 实现，UI 上画的 10 段是插值假象（证据：docs/固件逆向报告-OS4.0.5.0.md — "0.4.0 UI 画 10 段曲线 | 上位机只有 5 个真实频段，曲线是插值出来的假象"）。
- **`persist.vendor.audio.effectimplenter=dolby` 会把控制权搞丢**：与 Dolby 效果器实例冲突（证据：docs/固件逆向报告-OS4.0.5.0.md — "与 Dolby 效果器实例冲突，导致效果器失去控制权"）。注意这条与 `aurora-pro` 仍在写同名的属性**方向相反**，见待确认。
- **增益堆在两头听不出来**：47–328 Hz 与 9 kHz 以上几乎不可闻，同一套 ±12 dB 曲线堆中频是"明显变亮"、堆两端是"一丝丝变化"（证据：docs/固件逆向报告-OS4.0.5.0.md — "同一套 ±12 dB 曲线，堆在两端时\"一丝丝变化\"，做在中频时\"明显变亮\""）。
- **会话切换有约 2 秒空窗**（track 重建时旧会话已释放、新会话尚未被 2 s 轮询捡到）（证据：README.md — "会话切换瞬间（track 重建）有约 2 秒空窗"）。
- **旧 XML 路线的半状态问题**：改属性成功但 XML 未生效会留下不一致，v5.7.0 才做到"任一步失败都会回退"（证据：aurora-pro/releases/AURORA-pro_v5.7.0_修复说明.md — "运行时先生成并绑定 XML，再写音频属性；任一步失败都会回退，不留下\"属性已改但 XML 未生效\"的半状态"）。
- **soft-reboot / 模块更新后旧 XML 绑定残留**，导致状态降级，需要先解绑再重新生成快照（证据：aurora-pro/releases/AURORA-pro_v5.7.1_修复说明.md — "修复 soft-reboot 或模块更新后旧 XML 绑定残留导致的降级问题"）。
- **ADB 本身不稳定**：曾出现 USB 授权后立即掉线（证据：aurora-pro/releases/AURORA-pro_v5.7.1_修复说明.md — "当前设备上的 adb 曾出现过 USB 授权后立即掉线的情况"）。这是仓库内唯一一条 ADB 调试异常记录。
- **`AURORA-marked XML` 自检保护会挡住快照**：`aurora.log` 里反复出现拒绝对已被 AURORA 标记的 XML 做快照，并直接导致一次 `prepare failed`（证据：aurora-pro/device/aurora.log — "refusing to snapshot AURORA-marked XML: /odm/etc/dolby/dax-default.xml"、"prepare failed; no runtime properties written"）。

## 5. 当前待办

用户口径是"下一步先补齐现状文档"，据此判断当前缺口主要在**文档自身与代码/现实的漂移**，而不是功能。

1. **文档口径统一**（最直接缺口）：README、逆向报告、代码三处的配置键与回执键不一致，见待确认 2–4。
2. **预设重调**：按 300 Hz–6 kHz 重新设计 9 套曲线，"低频冲击"要改成中低频推动（证据：README.md — "**预设重调**：按 300 Hz–6 kHz 有效区间重新设计 9 套曲线"）。注：`Presets.java` 注释显示曲线已按此结论写过一轮，是否已完成听感复核需确认(推断) 代码注释与 README 待办同时存在，说明"实现已改、待办未销号"。
3. **场景项量纲校准**：对白增强/声场宽度/残响/音量均衡的强度范围仍是保守猜测值（证据：README.md — "**场景项量纲校准**：对白增强 / 声场宽度 / 残响 / 音量均衡的强度范围还是保守猜测值"）。
4. **曲线确认间隙**：会话切换约 2 s 空窗，可缩短轮询或改监听会话变化事件（证据：README.md — "**曲线确认间隙**：会话切换瞬间（track 重建）有约 2 秒空窗"）。
5. **App 端体验**：20 段目前只能拖单点，缺整体升降；诊断页可补"当前挂载会话"实时刷新（证据：README.md — "**App 端体验**：20 段曲线目前只能拖单点，缺整体升降"）。
6. **旧路线归属**：`aurora-pro/` 确认不要后移出工程（证据：README.md — "**旧路线归档**：`aurora-pro/` 确认不要后移出工程"）。
7. **补运行时证据链**：`device/` 里没有 AURORA 自己的 logcat（`lsposed-modules.log` 3.3 MB 中含 "aurora" 的匹配数为 0，全是别的模块），诊断只能靠 `settings get global aurora_backend_*`，缺一份可复现的失败样本。
8. **设备基线固化**：`_work/` 只留脚本与 `payload_manifest.json`(250 KB)，OTA 解包产物在 `_work/fs`（体积大），建议明确"哪些是可再生的"。

## 6. 相关文件路径

> 相对根目录 `C:\Users\lldf\XiaomiMiMoProjects\音频模块`。仅列实际读过或用于定位的文件。

Java 注入层（主线）：
- `aurora-lsp/app/src/main/assets/xposed_init` — 入口类声明（28 B）
- `aurora-lsp/app/src/main/AndroidManifest.xml` — 模块元数据、包名 `com.aurora.audio.lsp`、1.5.0
- `aurora-lsp/app/src/main/java/com/aurora/lsp/AuroraXposed.java` — hook 与线程启动（5.9 KB）
- `aurora-lsp/app/src/main/java/com/aurora/lsp/AuroraEngine.java` — 会话发现、曲线计算、写入、回执（16 KB）
- `aurora-lsp/app/src/main/java/com/aurora/lsp/DolbyDap.java` — `dap_hw` 反射封装、命令协议、参数常量（10 KB）
- `aurora-lsp/app/src/main/java/com/aurora/lsp/Presets.java` — 9×20 段 dB 曲线（2.9 KB）
- `aurora-lsp/app/src/main/java/com/aurora/lsp/MainActivity.java` — 四页 UI + 测试音（47 KB，仅抽读）
- `aurora-lsp/build.sh` — javac + d8 + aapt + apksigner，无 Gradle，`VERSION="1.5.0"`
- `aurora-lsp/out/AURORA-LSP-1.5.0.apk` — 当前产物（41 KB）

Magisk/KernelSU 旧路线：
- `aurora-pro/device/module.prop` — id `AURORA-pro`，v5.7.3
- `aurora-pro/device/customize.sh`、`post-fs-data.sh`、`post-mount.sh`、`service.sh`、`uninstall.sh`、`system.prop`、`aurora.conf`
- `aurora-pro/device/aurora_core.sh` — 运行时核心（25 KB，函数表已抽读）
- `aurora-pro/device/aurora_xml_patch.sh` — awk 打补丁（6.7 KB，仅读前 40 行）
- `aurora-pro/device/aurora.log` — 真机运行日志（2.8 KB，全读）
- `aurora-pro/device/module_status` — 状态键值快照
- `aurora-pro/device/data/runtime/dax-default.xml` — Dolby 配置副本（120 KB，未读正文）
- `aurora-pro/releases/AURORA-pro_v5.7.0_修复说明.md`、`..._v5.7.1_修复说明.md`

配置与逆向中间产物：
- `docs/固件逆向报告-OS4.0.5.0.md` — 唯一逆向报告（16 KB，全读，含第 10 节真机验证）
- `_work/assets/odm_etc_audio/audio_effects_config.json` — 音量档位与 app→profile 映射（3 KB，全读）
- `_work/probe/AuroraProbe.java` — 独立进程探针（10 KB，未读）
- `_work/payload_manifest.json` — OTA manifest（250 KB，未读正文）

设备取证：
- `device/check-module.sh` — 模块 ZIP 校验（路径/可执行位/CRLF/shellcheck）
- `device/dumpsys-audio.txt`（175 KB）、`dumpsys-audio-flinger.txt`（600 KB）、`system-props.txt`(44 KB) — 仅 Select-String 抽样
- `device/lsposed-modules.log`（3.3 MB）— 仅抽样，无 AURORA 记录
- `device/misound-package.txt`(62 KB)、两份 `lsposed-config-*.db`、`zygisk*_module.prop` — 未读

不读的二进制（仅记名/大小）：`reference/framework.jar`（51 MB）、`reference/MiSound.apk`（19 MB）、`*.apk`/`*.aar`/`*.jar`/`*.db` 正文。

另有 `officelite/`（独立项目，含 `src/`、`docs/`、`tools/`、`run.bat`），不属于本项目范围。

## 待确认

1. **壁纸/模糊异常无原始日志**。仓库里只有两处结论性表述（README 三条铁律第 1 条、逆向报告 10.2 第 5 条），没有任何带时间戳的 logcat、无 `dumpsys` 对照、无 ANR/watchdog 堆栈；`device/lsposed-modules.log` 中 "aurora" 匹配数为 0。因此"ADB 调试记录"只落到了结论，没落到证据。
2. **`aurora_scope` 键疑似不存在**。逆向报告 §7 的配置键清单里列了 `aurora_scope`，但 `AuroraEngine` 的常量表（K_*）里没有它，`AuroraXposed.observe()` 注册的 15 个键里也没有。是文档残留还是代码漏实现？
3. **回执键两份文档互相矛盾**。README 写 `aurora_backend_state/_message/_bands/_sessions/_applied/_updated`；报告 §7 写 `_profile/_profiles/_capability`。代码实际只写 state/message/bands/sessions/applied/updated 六个，没有 `_profile`/`_profiles`/`_capability`。以代码为准的话，报告那段已过时。
4. **构建产物版本号注释不一致**。`build.sh` 里 `VERSION="1.5.0"`，但 README「构建与安装」代码块的注释写的是 "产物：out/AURORA-LSP-1.1.0.apk"。另外 `out/` 里没有 `AURORA-LSP-1.0.0.apk` 本体（只有 `.idsig`）。
5. **两代方案对同一个 prop 态度相反**。报告判定 `persist.vendor.audio.effectimplenter=dolby` 会导致效果器失去控制权，而 `aurora-pro/aurora_core.sh` 仍在主动写这个属性。现在 `aurora-pro` 归档、`aurora-lsp` 生效，二者是否需要共存/互斥未见记录。
6. **`aurora-pro` 最后一次真机状态是 STOCK 而非 ACTIVE**。`device/module_status` 为 `STATUS=STOCK`、`XML_MODE=stock`，而 v5.7.1 修复说明称设备复核为 `STATUS=ACTIVE`。仓库内两个状态快照谁更新、归档前是否已回退到原厂，无法判定。
7. **预设待办是否已销号**。README 待办 1 要求重调预设，`Presets.java` 注释显示已按 300 Hz–6 kHz 结论写过；但没人记录"听感复核通过"。属实现已改、验收未留痕。
8. **`dax-default.xml` 是否与设备逐字节一致**。报告称 sha256 与设备一致（`0b9d1a2b…cd9437`），仓库内 `aurora-pro/device/data/runtime/dax-default.xml` 与 `stock-dax-default.xml` 同为 119916 B。本次未做哈希比对（禁止跑脚本），一致性只能引用报告。
9. **`_work/probe/AuroraProbe.java` 未读**。它是独立进程探针（README 提到"probe/（独立进程探针）"），可能是理解"如何在不进 system_server 的情况下验证效果器"的关键，本次未展开。
10. **`device/` 两份 `lsposed-config-*.db` 未读**（413 KB/417 KB 二进制），其中可能记录了模块作用域勾选的历史状态，本次只按文件名推断用途。