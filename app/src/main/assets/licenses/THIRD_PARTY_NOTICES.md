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

## Kadb

- 组件：`com.flyfishxu:kadb:1.2.1`；
- 用途：Android ADB TLS 配对、认证和 Shell 传输；
- 许可证：Apache License 2.0；
- Copyright (c) 2024 Flyfish-Xu；
- 来源：https://github.com/flyfishxu/Kadb

## SPAKE2 Java

- 组件：`com.github.flyfishxu.spake2-java:spake2:0.0.5`（Kadb 传递依赖）；
- 用途：Android 无线调试配对的 SPAKE2 握手；
- 许可证：GNU Lesser General Public License v3.0；
- Copyright 2021 Muntashir Al-Islam；
- 来源：https://github.com/flyfishxu/spake2-java 和 https://github.com/MuntashirAkon/spake2-java

本项目整体以 GPL-3.0-or-later 提供，与 LGPL-3.0 组件兼容。SPAKE2 Java 未被修改；其独立源码、构建脚本和许可证可从上述仓库取得。分发 APK 时必须同时保留本通知和 `third_party_licenses/LGPL-3.0.txt`。

## Conscrypt

- 组件：`org.conscrypt:conscrypt-android:2.5.3`；
- 用途：TLS 1.3 和 TLS Exporter，避免访问 Android 隐藏 API；
- 许可证：Apache License 2.0；
- Copyright 2015 The Android Open Source Project；
- 来源：https://github.com/google/conscrypt

## Bouncy Castle 与 Okio

- Bouncy Castle `bcprov` / `bcpkix` 由 Kadb 引入，用于 X.509 身份材料，采用 Bouncy Castle 许可（按 MIT 许可理解），来源：https://www.bouncycastle.org/；
- Okio 由 Kadb 引入，用于 ADB 字节流，采用 Apache License 2.0，来源：https://github.com/square/okio。

## 许可证兼容

Apache-2.0、MIT 类许可和 LGPL-3.0 组件可以与 GPL-3.0-or-later 项目组合分发，但其原始版权、许可证文本和替换/再链接权利仍须保留。正式 Release 发布前应从最终依赖图生成完整 SBOM 和第三方软件清单。

