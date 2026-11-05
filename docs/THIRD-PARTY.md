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