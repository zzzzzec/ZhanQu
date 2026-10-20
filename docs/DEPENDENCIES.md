# 构建依赖与来源
构建选用经本工程实际验证的固定组合，而非声称所有依赖均为最新：Gradle 8.11.1、AGP 8.9.2、Kotlin/Compose compiler 2.1.20、compile/target SDK35、min SDK30、JDK21运行 Gradle/Java17字节码、Compose BOM2025.04.00、Room2.7.1、Coroutines1.10.1。

- Android AccessibilityService API：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- 服务开发说明：https://developer.android.com/guide/topics/ui/accessibility/service
- ML Kit 中文 bundled 16.0.1：https://developers.google.com/ml-kit/vision/text-recognition/v2/android
  文档列明模型静态链接、立即可用，本项目不使用 Play Services 动态模型依赖。
- Android Gradle Plugin8.9：https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes
- AndroidX（Apache2.0）与发行说明：https://developer.android.com/jetpack/androidx/versions
- Kotlin（Apache2.0）：https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
- kotlinx.coroutines（Apache2.0）：https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt
- Google ML Kit 条款：https://developers.google.com/ml-kit/terms
- Android SDK 条款：https://developer.android.com/studio/terms

未复制 GKD、OpenRing、AutoX 或 PaddleOCR 源码。GKD 仅用于解析用户快照文件格式。ML Kit 为 Google SDK，分发受其条款及内含第三方许可约束；正式发布前应完成依赖许可证清单和商店政策复核。
