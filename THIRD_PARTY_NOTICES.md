# 第三方组件

净启使用下列主要开源组件。完整传递依赖请以 Gradle 锁定结果和构建产物为准。

## AndroidX / Jetpack Compose

- AndroidX Activity、Core、Lifecycle、WorkManager；
- Jetpack Compose Material、Material 3 和 UI；
- 许可证：Apache License 2.0；
- 来源：https://developer.android.com/jetpack/androidx

## Kotlin

- Kotlin 标准库和协程相关运行能力；
- 许可证：Apache License 2.0；
- 来源：https://github.com/JetBrains/kotlin

## Shizuku API

- `dev.rikka.shizuku:api`；
- `dev.rikka.shizuku:provider`；
- 当前仅用于 `0.8.x` 专家内测桥接；
- Shizuku API 许可证：MIT；
- Shizuku 项目代码许可证：Apache License 2.0，并保留名称、图标和包名限制；
- 来源：https://github.com/RikkaApps/Shizuku-API 和 https://github.com/RikkaApps/Shizuku

净启不使用 Shizuku 名称或图标作为自己的品牌，也不声明其保留 application ID 或权限名称。

## 许可证兼容

Apache-2.0 和 MIT 许可组件可以与 GPL-3.0-or-later 项目组合分发，但其原始版权和许可证通知仍须保留。正式 Release 发布前应从最终依赖图生成完整的第三方软件清单。
