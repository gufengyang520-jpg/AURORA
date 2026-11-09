# 第三方代码声明

本项目 UI 层的液态玻璃底栏移植自以下 Apache-2.0 项目，保留了原作者署名：

## KernelSU 管理器
- 来源：https://github.com/tiann/KernelSU
- 许可：GPL-3.0（kernel 目录 GPL-2.0）
- 移植文件：
  - `ui/component/FloatingBottomBar.kt`
  - `ui/component/bottombar/BottomBarMiuix.kt`（改写为本项目的 `AuroraBottomBar.kt`）
  - `ui/component/liquid/{Lens,CombinedBackdrop,InnerShadow,Vibrancy}.kt`
  - `ui/component/miuix/animation/{DampedDragAnimation,InteractiveHighlight}.kt`
  - `ui/component/miuix/modifier/DragGestureInspector.kt`
- 移植范围：仅 UI 组件，未包含 KernelSU 的 root 逻辑、模块管理、导航栈等业务代码。

## AndroidLiquidGlass
- 来源：https://github.com/Kyant0/AndroidLiquidGlass
- 许可：Apache-2.0
- 用途：`Lens.kt` 中的圆角矩形折射 shader（KernelSU 亦由此改编）。

## miuix（Compose Multiplatform）
- 来源：https://github.com/compose-miuix-ui/miuix
- 许可：Apache-2.0
- 用途：组件库与 `miuix-blur` 的 Backdrop / runtimeShaderEffect 能力。

凌乱的风＆

### 升级 miuix 时要人工复核的一处

- FloatingBottomBar.kt 里的高光方向照抄了 miuix-blur 内部的 LIGHT_REF（0.5 / 0.7），源码注释已标注“keep in sync”。
- 这是对库内部约定的依赖，不是公开 API：升级 miuix 后底栏高光方向可能静默改变，没有测试能发现。
- 复核方法：升级后对照 miuix-blur 源码里的 LIGHT_REF 常量，不一致就同步 LIGHT_REF_X/Y。
