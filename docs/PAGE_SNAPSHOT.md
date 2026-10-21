# 当前页面快照

页面快照用于分析新应用的页面结构。它只读取当前可访问节点和当前画面，不翻页、不滚动，也不启动持续采集。

## ZIP 格式

归档中只有一个 JSON 与一个 PNG，使用同一捕获时间戳命名。节点以屏幕坐标保存，包含父子关系、文字、控件类型、资源 ID 与交互属性。截图与节点坐标对齐；未截取的屏幕区域留空，不补画内容。

这是独立实现的 GKD 格式兼容导出，不包含 GKD 实现代码，也不冒充 GKD 生成版本。实际字段参考：

- [ComplexSnapshot](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/commonMain/kotlin/li/gkd/app/model/ComplexSnapshot.kt)
- [AttrInfo](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/commonMain/kotlin/li/gkd/app/model/AttrInfo.kt)
- [SnapshotFileLayout](https://github.com/gkd-kit/gkd/blob/fe6736fb083ba90d7bace124e30b61ff6e5701f2/gkd-app/src/commonMain/kotlin/li/gkd/app/snapshot/SnapshotFileLayout.kt)
- [官方审查器导入逻辑](https://github.com/gkd-kit/inspect/blob/db5d161840513982f6a3de6720e053186641f485/gkd-inspect/src/features/snapshot-management/import.ts)

## 内容与隐私

页面中的账号、聊天、订单等文字和图像可能进入快照。只在准备分享的页面使用，发送前核对内容。应用不自动上传，也不把真实页面快照加入公开代码仓库。密码页、锁屏或系统保护的窗口不会绕过限制采集。

0.3.2 不再因非聚焦侧栏或浮窗的几何遮挡停止采集；真实前台或焦点变化仍会停止。Android 11–13 使用屏幕截图，画面可能包含可见浮层；Android 14+ 使用目标窗口截图。

快照不等于整个应用的数据：离屏且未加载的内容、未暴露的节点和受系统保护的画面不在范围内。无法确认的 Activity 元数据标记为空，不沿用上一个应用的信息。

节点读取与截图并非系统级原子操作。导出会核对截图前后的节点树、前台和窗口身份，但不能保证捕捉到两次检查之间出现又消失的瞬时画面。生成中的快照设置节点、文本、像素、体积和超时上限；失败时不生成伪装完整的 ZIP。

本机缓存位于专用快照目录，超过7天的旧缓存会在下一次生成时清理，目录达到容量限制时提示清理缓存。已由你另存的 ZIP 不受该缓存清理影响。不要通过“清除应用数据”处理缓存，因为那会同时删除笔记。

当前已按上述官方源码定义构建格式，并使用合成数据验证 ZIP 与本项目解析器往返；官方 GKD 网页审查器实际导入仍需单独验证，不能把格式参考等同于该工具的端到端验收。
