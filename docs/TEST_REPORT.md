# 测试范围与复现

本文区分本轮实际执行结果与仍需验证的设备行为，不把旧构建记录或私人样本结果作为当前源码的验收结论。

## 0.3.0 交互打磨（2026-10-20 UTC）

预览包 `dev.zhanqu.app.preview`，versionName 0.3.0 / versionCode 9。最终代码在独立副本完成以下验证：

- `testDebugUnitTest`：115 项，114 通过、1 项既有可选私有输入用例跳过，0 失败/错误。
- `lintDebug`：0 错误、9 警告；`assembleDebug` 成功。
- 实际 Android API 35 Robolectric framework 回归：23/23 通过，无失败/错误/跳过。保留16项既有悬浮窗、安全点击、锚点与拖动回归；新增短标签选择、3项实际 Compose 渲染和3项导航/紧凑编辑测试。
- 导航覆盖：重复打开/关闭笔记，未保存时返回、继续编辑与放弃修改，保存后重新打开，复制与分享使用当前文字。新采集回调在数据库查询后重新核对编辑状态，避免覆盖用户修改。
- 布局覆盖：真实 Activity/Compose 渲染首页、首次授权页及编辑页；紧凑440dp高度、1.6倍字体与合成180px IME条件下，滚动后底部操作位于可用范围。公开截图使用最终源码和合成状态。
- 独立源码审查发现并修复了回调覆盖编辑的竞态及重复 IME 内边距问题；没有改动数据库、采集引擎或遮挡安全策略。

- 最终签名包：沿用既有证书，v3 签名与16KB ZIP 对齐通过；包名/版本正确、无 INTERNET 权限，最终构建输入与发布源码一致。APK SHA256：`0dceceda6a81decc2bf20daa923113d13390a974bce314b07edb913428d843f7`。

### 未完成的验证

Robolectric 中打开新导出下拉菜单时测试停滞，已隔离记录，未将该路径计为通过。23项通过结果不包含下拉选择、真实 SAF 文件写入或系统分享目的地完成操作。复制和 ACTION_SEND 载荷已核对，不能据此声称系统分享全流程验收。

尚未执行三星实体机/完整模拟器的真实采集、触摸、键盘、覆盖安装与数据保留验收。小高度测试使用合成 IME，不等同于物理键盘实测。当前仓库未配置 CI，本地和框架结果不称为 CI 通过。

## 0.2.3 本轮结果（2026-10-20 UTC）

本轮修复悬浮控件展开后收起落在中间的问题，预览包 `dev.zhanqu.app.preview`，versionName 0.2.3 / versionCode 8。只调整停靠边与拖动期间布局更新，不改变采集或遮挡安全检查。

- 冻结源码在独立副本执行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`，均成功。JUnit **112 项中 111 项通过、1 项既有可选私有输入测试跳过**，0 失败、0 错误。
- Android lint：**0 错误、10 警告**。纯逻辑 `OverlayPlacementTest` **10/10 通过**，含本轮新增 5 项停靠边、尺寸切换、屏幕变化与小范围边界回归。
- 隔离 Robolectric Android API 35 framework 回归 **16/16 通过**，包括原有 4 项紧凑控件、5 项实际 `UiTreeReader` / `GestureController` 安全路径，以及新增 7 项停靠位置/拖动回归。
- 新增回归读取实际附着窗口 `root.layoutParams` 的 `WindowManager.LayoutParams.x`，不是仅断言宽度：右侧与左侧 216dp 展开 → 56dp 收起仍贴原边；20 次循环不漂移且保留同一个 root/title/window；可用屏幕实际变宽后重新停靠；拖动期间运行状态变化不替换触摸中的 handle、不抢位置，UP 或 CANCEL 后完成待处理布局；隐藏后再显示仍记住左边。
- 测试数据与渲染均为纯合成；隔离 framework 源码与运行配置保留在验证工具中，不增加产品依赖。不使用用户截图、生产数据或真实上游 API。
- 构建源码与发布工作树核对一致。签名 APK 已核对包名、版本、arm64-v8a、最低 Android 30 / target 35，无 INTERNET 权限，16KB ZIP 对齐通过；沿用 0.2.1 / 0.2.2 的既有证书，v3 签名验证通过，未生成替代密钥。

这些结果不是三星实体机或完整模拟器验收。实际 OEM 窗口位置、手势与截图、覆盖安装及笔记保留仍待目标设备验证；不声称任意极小 viewport 均可完整显示控件。0.2.3 的同包名、同证书与递增版本满足从 0.2.1 / 0.2.2 覆盖升级的必要条件，不需要为更新主动卸载。

## 0.2.2 本轮结果（2026-10-20 UTC）

本轮修复边缘窄条遮挡下的安全点击回退，预览包 `dev.zhanqu.app.preview`，versionName 0.2.2 / versionCode 7。未针对三星包名设置白名单，也未放宽原有整节点点击的遮挡检查。

- 冻结源码在独立副本执行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`，均成功。JUnit **107 项中 106 项通过、1 项既有可选私有输入测试跳过**，0 失败、0 错误。
- Android lint：**0 错误、10 警告**，未把警告描述成已全部修复。
- 新增纯合成 `PeripheralTapPolicyTest` **11/11 通过**，包含边缘窄条、未知/中央遮挡、点击净空、窗口身份与非默认显示屏拒绝等边界。
- 隔离 Robolectric Android framework 回归 **9/9 通过**：4 项既有紧凑悬浮控件检查，以及 5 项实际 `UiTreeReader` / `GestureController` 调用路径检查。覆盖整宽节点仍拒绝语义点击、标签/描述/类型/坐标/可用性/窗口/前台变化拒绝、非默认显示屏拒绝、节点验证失败不派发、验证后新增中央遮挡不派发、完成回调才返回成功、取消或拒绝派发返回失败、临时隐藏悬浮控件后恢复。框架对象与窗口输入为合成数据，framework 工具位于公开工程之外。
- 构建输入与发布工作树核对一致；签名 APK 的包名、版本与 Android 30 最低版本已核验，无 INTERNET 权限，16KB ZIP 对齐检查通过。使用与 0.2.1 相同的既有证书签名，签名验证通过；不是重新生成密钥。

以上不是三星手机实测，也不是完整 Android 模拟器验收。真实 OEM 窗口身份、无障碍手势实际落点、连续翻页、截图结果、覆盖安装及数据保留仍需目标设备验证。`0.2.2` 与 `0.2.1` 的同包名、同证书和递增版本支持覆盖升级条件，但尚未实际安装验证；不要为升级主动卸载 `0.2.1`。`0.2.0` 使用另一旧证书，其安装限制不适用于从 `0.2.1` 升级。

## 0.2.1 本轮结果（2026-10-20 UTC）

本轮改动为紧凑悬浮控制与纯展示策略，独立预览包 `dev.zhanqu.app.preview`，versionName 0.2.1 / versionCode 6。

- 最终源码 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 均完成；JUnit 96 项中 **95 项通过、1 项旧有可选私有输入测试跳过**，不将跳过计为通过。
- Android lint：**0 错误、13 警告**。未将警告描述成全部修复。
- 隔离 Android framework 渲染与交互检查：**4/4 通过**，覆盖入口、展开图标盘、运行/异常状态、按需诊断、极小屏大字体及触摸相关检查。实绘发现图标在宽按钮中被拉伸后，已增加正方形 intrinsic viewport 并重新验证。
- 图标盘默认隐藏原始诊断详情，48dp 触摸目标不因图标缩小而缩小；新增 JVM 用例检查尺寸、短状态、保存中禁用、选项联动和空选择。

上述为离线构建、JVM 与 framework 层验证，**不是物理手机或完整 Android 模拟器验收**。未证明真实截图/手势、系统无障碍授权、OEM 窗口身份、连续采集或应用覆盖安装成功。实际设备仍需验证。原始日志与构建副本不随公开源码发布。

本次预览签名与此前 0.2.0 预览版不同，不能承诺覆盖安装保留数据；安装前请阅读 README 的导出与重装说明。应用尚无 JSON 笔记恢复导入功能。

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
