# 测试范围与复现

本文区分本轮实际执行结果与仍需验证的设备行为，不把旧构建记录或私人样本结果作为当前源码的验收结论。

## 0.2.0 本轮结果（2026-10-20 UTC）

默认包 `dev.zhanqu.app` 与独立预览包 `dev.zhanqu.app.preview`，versionName 0.2.0 / versionCode 5，最终源码验证：

- 两种构建的 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 均成功。预览构建的单元测试任务复用同一冻结源码的最新结果（UP-TO-DATE），并非额外重新执行了另一套 90 项测试；预览 lint、manifest 与 APK 构建重新执行。
- JUnit：90 项，89 项通过，0 失败、0 错误、1 项按预期跳过。跳过的是未提供本地私有输入的可选 ZIP 回归，不计为通过。
- Android lint：两种包均为 0 错误、17 警告。包括 8 项依赖更新建议、3 项 UseKtx，以及 UnusedAttribute、SwitchIntDef、ChromeOsAbiSupport、KaptUsageInsteadOfKsp、SetTextI18n、RtlHardcoded 各 1 项；未将警告说成已全部修复。
- 隔离的 Robolectric 4.14.1 / Android API 35 native graphics 渲染检查：3 项通过。实际 MainActivity 源码分别在默认 393×852dp / 440dpi、320×640dp / mdpi / 2 倍字体配置下渲染；实际 CaptureOverlay 源码覆盖展开待机、紧凑采集、展开采集三种状态。使用空白合成笔记库和合成状态，已人工检查图像；小屏文字换行、内容可滚动。该检查不是手机或模拟器运行，不证明真实采集、手势、授权、触摸或窗口身份行为。渲染测试工具位于公开工程之外。

预览 APK 已验证 v2 签名、独立包名、应用与服务名称、独立 provider authorities，合并 manifest 无 INTERNET 权限。该检查不等于安装或真实设备运行，也不能推断其他 ABI 的行为。原始日志与机器生成报告不随公开源码发布。

## JVM 回归

```sh
./gradlew testDebugUnitTest
```

测试覆盖：
- 页码解析、轮播回首页、跳页、总数变化、重复图像校验。
- 评论父子层级、相邻行组、未确认父级、屏外节点和去重边界。
- OCR 坐标排版、原始文字保留、旧模型和导出兼容性。
- 窗口遮挡、目标窗口截图与整屏截图差异、实际手势路径。
- ZIP 路径穿越、多个快照、截断 JSON、重复 ID、循环、密码字段剔除。

`xhs-192-synthetic.json` 使用合成自由文本；公共资源 ID 和结构几何用于可重复回归。它不是可供识别准确率评测的真实内容集。

有一个可选 ZIP 回归通过 `ZHANQU_REAL_SNAPSHOT_ZIP` 或 `zhanqu.realSnapshotZip` 读取本机输入。未指定时用 JUnit assumption 跳过，不能计为真实输入测试通过。它依赖特定基线结构，不是任意 ZIP 的通用合格检查。无需私人输入即可执行其余合成回归。不要上传输入、截图或含私人路径的测试输出。

## 构建与静态检查

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

本机输出位置：
- APK：`app/build/outputs/apk/debug/`
- JUnit XML：`app/build/test-results/testDebugUnitTest/`
- 测试与 lint 报告：`app/build/reports/`

这些是生成物，不属于公开源码白名单。发布 APK 前另行核对合并 manifest、签名、ABI、ZIP/原生库对齐与 SHA-256；debug 签名不等于正式发布签名。

## 不能由 JVM 测试证明的事项

- 安装启动、无障碍授权、悬浮窗交互和屏幕边缘布局。
- 真机截图、自动翻页、评论滚动及 OEM 窗口行为。
- 首装断网 OCR、字符错误率、漏行率、每页耗时和内存峰值。
- Room 迁移、权限撤销、强杀恢复和实际导出接收端行为。

请按 [TEST_PLAN.md](TEST_PLAN.md) 在目标设备执行，并区分“未测”“失败”“通过”。合成测试不代表特定应用版本获得完整支持，视觉设计修改也不自动提高 OCR 识别率。
