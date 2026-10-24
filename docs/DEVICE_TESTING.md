# USB 真机调试与自动测试

## 默认使用模拟器

官方模拟器可用于粘取自身的界面回归。当前小红书在 Android 16 实例 `ZhanQu_API36` 和 Android 11 实例 `ZhanQu_API30` 中均启动失败，不能将它们描述为已经可登录或可执行小红书提取。两个实例使用同一个测试序列号 `emulator-5580`，每次只启动一个；账户和应用数据独立于真机。

本机完成初始化后，打开可见窗口：

```powershell
.\scripts\start-emulator.ps1
# 较旧系统对照实例；先关闭当前模拟器窗口再启动
.\scripts\start-emulator.ps1 -AndroidApi 30
.\scripts\device-test.ps1 -Serial emulator-5580
.\scripts\device-ui.ps1 -Serial emulator-5580 -Mode dump
```

`device-test.ps1` 根据目标设备选择 ARM64 或 x86_64 构建。明确指定模拟器序列号，避免连接多台设备时误操作手机。`-SkipBuild` 还需要调用者确认 APK 的 ABI 与目标匹配；无需清空模拟器或退出账号来执行日常测试。

Android 11 实例使用软件渲染以避免宿主图形后端问题；启动脚本禁用诊断上报提示。测试截图和节点树写入各自调试包的私有目录，通过 `run-as` 导出，避免不同 Android 版本对外部存储的访问差异，无需 Root。小红书后续测试环境选择以实际兼容性为准，登录由用户完成。

以下保留真机连接方法，供必要时复验。

手机无需 Root。用支持数据传输的 USB 线连接电脑，开启开发者选项和 USB 调试，解锁手机并允许这台电脑的调试授权。Windows 如果无法识别设备，按手机厂商说明安装 ADB USB 驱动。

## 本机命令

在项目根目录使用 PowerShell：

```powershell
# 查看连接状态：device 才能开始；unauthorized 需要在手机确认授权
.\scripts\device-test.ps1 -Action devices

# 构建、安装独立调试包并启动
.\scripts\device-test.ps1 -Action install

# 构建、安装应用和测试 APK，运行四项真机 UI 自动测试并提取截图
.\scripts\device-test.ps1

# 多设备时指定目标；APK 已构建且确认是调试包时可用 -SkipBuild
.\scripts\device-test.ps1 -Serial YOUR_DEVICE_SERIAL -SkipBuild
```

脚本优先使用本机 `.gradle/local-tools/env.ps1` 配置的工具链；其他电脑需要配置 Java 与 `ANDROID_HOME`。构建入口仍使用仓库的 Gradle Wrapper。

## 数据和安装隔离

`-PzhanquDeviceTest=true` 生成 `dev.zhanqu.app.devtest`（粘取调试），无障碍服务名称为“粘取调试页面采集”。默认包与预览包的构建方式保持原样。测试代码只接受这个独立包名；测试脚本核对构建元数据后才安装，签名不匹配时失败，不通过卸载或清除数据解决。

四项测试使用带随机 ID 的合成笔记，结束后仅删除各自的样本，并恢复测试前的首次提示状态；不清空整个笔记库。测试期间保持手机解锁，暂时不要同时操作手机。

## 自动验证范围

1. 首次提示、同意按钮、使用帮助打开和关闭。
2. 在真实界面编辑并保存笔记，核对 Room 数据，关闭并重新打开 Activity 后核对正文。
3. 未保存修改的返回提示、继续编辑、放弃修改及原文保留。
4. 导出菜单的三种格式、打开系统文件选择器并取消返回。此项不实际写文件或向分享接收端发送内容。

测试通过必须在 Android instrumentation 输出中得到 `OK (4 tests)`。结果与截图保存在被 Git 忽略的 `reports/device-test/<run-id>/`；只有本次实际执行通过才可以记录为真机通过。截图仅在调试应用前台时生成，不截取系统文件列表。

## 跨应用采集验证

四项 UI 测试不证明无障碍授权、真实截图/OCR、悬浮窗、跨应用翻页或评论覆盖率。连接后在系统设置中手动启用“粘取调试页面采集”，使用你有权保存的非敏感样本，按 `TEST_PLAN.md` 验证，并区分实际通过、失败和未执行。首次系统授权、锁屏 PIN 和账号登录由用户在手机上完成。

`tools/phone-driver/` 是独立测试应用，不打入产品。它通过 UI Automator 观察和操作页面，使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 保持采集服务运行，避免对产品本身运行 instrumentation 时重启采集进程。工具范围限定为小红书、知乎、B站、独立调试包、设置、系统权限提示和系统文件选择器。各平台分别编写测试步骤，不建立通用任务配置语言。

本机先加载工具链并构建，然后按各目录 `output-metadata.json` 中的 `elements[0].outputFile` 找到 APK，用 `adb install -r -t` 安装应用及测试 APK：

```powershell
. .\.gradle\local-tools\env.ps1
.\gradlew.bat -p tools/phone-driver assembleDebug assembleDebugAndroidTest --console=plain

# 驱动应用与测试 APK 的元数据目录：
# tools/phone-driver/build/outputs/apk/debug/
# tools/phone-driver/build/outputs/apk/androidTest/debug/

.\scripts\device-ui.ps1 -Serial YOUR_DEVICE_SERIAL -Mode dump
# 先检查 tree.xml，再使用包名和唯一内容描述操作已确认的控件
.\scripts\device-ui.ps1 -Serial YOUR_DEVICE_SERIAL -Mode tapDesc `
    -Target dev.zhanqu.app.devtest -Value '查看状态'
```

每步截图、节点树和驱动日志保存在 `reports/phone-ui/`。`device-records.ps1` 仅通过 `run-as` 读取独立调试包的数据库副本，由 `tools/analyze_device_records.py` 生成本地统计；请在采集停止后使用。`device-copy.ps1` 仅复制调试包生成的页面快照 ZIP。这些文件可能含页面内容，不随源码提交。

本轮实测结果见 [TEST_REPORT.md](TEST_REPORT.md)。图片数量、评论数量、父子关系、全文展开、OCR 字符和阅读顺序分别验收；数量吻合不代表文字完整准确。

参考：[Android 真机连接](https://developer.android.com/studio/run/device)、[UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)。
