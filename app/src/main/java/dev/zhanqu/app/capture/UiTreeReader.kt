package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.zhanqu.app.core.UiBounds
import dev.zhanqu.app.core.UiNode
import dev.zhanqu.app.core.UiSnapshot

internal open class CaptureFailure(message: String) : Exception(message)
internal class CapturePausedException : Exception()
internal data class LiveScreen(val snapshot: UiSnapshot, val windowId: Int)

/** Native nodes never cross suspension points. A fresh immutable copy is returned for every read. */
internal class UiTreeReader(private val service: AccessibilityService, private val activityName: () -> String?) {
    private fun applicationRoot(): AccessibilityNodeInfo? {
        val windows = service.windows.orEmpty()
        for (window in windows) {
            if (!window.isActive && !window.isFocused) continue
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                if (WindowSafety.isOwnOverlay(window, service.packageName)) continue
            }
            // Never reach behind a focused system dialog, keyboard or another service's overlay.
            return null
        }
        val foregroundApps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
        if (foregroundApps.map { it.id }.distinct().size > 1) return null
        val active = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }
        for (window in active) {
            if (!window.isActive && !window.isFocused) continue
            val root = window.root ?: continue
            return root
        }
        return service.rootInActiveWindow
    }

    fun foregroundPackage(): String? {
        val root = applicationRoot() ?: return null
        return try { root.packageName?.toString() } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    fun read(expectedPackage: String): LiveScreen {
        if (!service.getSystemService(android.os.PowerManager::class.java).isInteractive ||
            service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            throw CaptureFailure("屏幕已锁定或关闭，请解锁并回到原页面后手动继续")
        val root = applicationRoot() ?: throw CaptureFailure("页面节点暂不可用，请保持目标页面打开")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg != expectedPackage) throw CaptureFailure("前台应用已变化，采集已暂停")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
            var count = 0
            fun copy(node: AccessibilityNodeInfo, path: String, depth: Int): UiNode {
                if (++count > 6000 || depth > 80) throw CaptureFailure("页面节点过多或树层级异常")
                val rect = Rect().also(node::getBoundsInScreen)
                val children = mutableListOf<UiNode>()
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { children += copy(child, "$path/$index", depth + 1) }
                    finally { @Suppress("DEPRECATION") child.recycle() }
                }
                return UiNode(path = path, viewId = node.viewIdResourceName, className = node.className?.toString(),
                    text = if (node.isPassword) null else node.text?.toString(), description = if (node.isPassword) null else node.contentDescription?.toString(),
                    bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom), clickable = node.isClickable,
                    scrollable = node.isScrollable, visible = node.isVisibleToUser, password = node.isPassword, children = children)
            }
            val copied = copy(root, "0", 0)
            if (copied.anyNode { it.password }) throw CaptureFailure("检测到密码输入区域，不采集此页面")
            val version = runCatching {
                @Suppress("DEPRECATION")
                service.packageManager.getPackageInfo(pkg, 0).versionName
            }.getOrNull()
            return LiveScreen(UiSnapshot(pkg, activityName(), metrics.widthPixels, metrics.heightPixels, listOf(copied), version), root.windowId)
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    fun perform(expectedPackage: String, expectedWindow: Int, expected: UiNode, action: Int): Boolean {
        val root = applicationRoot() ?: return false
        var node: AccessibilityNodeInfo = root
        try {
            if (root.packageName?.toString() != expectedPackage || root.windowId != expectedWindow) return false
            for (index in expected.path.split('/').drop(1).mapNotNull(String::toIntOrNull)) {
                val child = node.getChild(index) ?: return false
                if (node !== root) { @Suppress("DEPRECATION") node.recycle() }
                node = child
            }
            val b = Rect().also(node::getBoundsInScreen)
            if (!node.isVisibleToUser || node.isPassword || b.isEmpty || node.viewIdResourceName != expected.viewId || node.text?.toString() != expected.text) return false
            if (action == AccessibilityNodeInfo.ACTION_CLICK && !node.isClickable) return false
            val metrics = service.resources.displayMetrics
            if (!Rect.intersects(b, Rect(0, 0, metrics.widthPixels, metrics.heightPixels))) return false
            try { WindowSafety.check(service, WindowSafety.visibleArea(service, b), expectedWindow) }
            catch (e: WindowObstruction) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return false
                throw e
            }
            return node.performAction(action)
        } finally {
            if (node !== root) { @Suppress("DEPRECATION") node.recycle() }
            @Suppress("DEPRECATION") root.recycle()
        }
    }
}

internal fun UiNode.anyNode(test: (UiNode) -> Boolean): Boolean = test(this) || children.any { it.anyNode(test) }
internal fun UiSnapshot.allNodes(): List<UiNode> {
    val nodes = mutableListOf<UiNode>()
    fun walk(node: UiNode) { nodes += node; node.children.forEach(::walk) }
    roots.forEach(::walk)
    return nodes
}
internal fun UiBounds.androidRect() = Rect(left, top, right, bottom)
