package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.view.accessibility.AccessibilityWindowInfo
import dev.zhanqu.app.core.UiBounds
import dev.zhanqu.app.core.WindowOcclusionPolicy

/** Own windows are tracked by a per-view random title plus native window ID, including removal transitions. */
internal class WindowObstruction(message: String) : CaptureFailure(message)

internal object WindowSafety {
    private data class Owned(val title: String, val ids: MutableSet<Int> = mutableSetOf(), var retired: Boolean = false)
    private val owned = linkedMapOf<String, Owned>()
    fun registerOwnOverlay(title: String, id: Int = -1) {
        val entry = owned.getOrPut(title) { Owned(title) }
        if (id >= 0) entry.ids += id
    }
    fun clearOwnOverlays() { owned.clear() }
    fun retireOwnOverlay(title: String) { owned[title]?.retired = true }
    private fun descriptor(window: AccessibilityWindowInfo, content: Rect? = null): WindowOcclusionPolicy.Window {
        val bounds = Rect().also(window::getBoundsInScreen)
        val root = window.root
        val pkg = try { root?.packageName?.toString() } finally { @Suppress("DEPRECATION") root?.recycle() }
        val title = window.title?.toString()
        val identity = owned.values.firstOrNull { window.id in it.ids }
        val kind = when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowOcclusionPolicy.Kind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowOcclusionPolicy.Kind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowOcclusionPolicy.Kind.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowOcclusionPolicy.Kind.ACCESSIBILITY_OVERLAY
            else -> WindowOcclusionPolicy.Kind.OTHER
        }
        val regions = mutableListOf<UiBounds>()
        run {
            val region = Region(); window.getRegionInScreen(region)
            val iterator = RegionIterator(region); val rect = Rect()
            while (iterator.next(rect)) regions += UiBounds(rect.left, rect.top, rect.right, rect.bottom)
        }
        return WindowOcclusionPolicy.Window(window.id, kind, window.layer,
            UiBounds(bounds.left,bounds.top,bounds.right,bounds.bottom), regions, pkg, title,
            identity != null, title != null && title in owned,
            title.isNullOrEmpty() || identity?.title == title,
            pkg == "com.android.systemui" && !window.isActive && !window.isFocused &&
                content != null && regions.isNotEmpty() && regions.none {
                    it.intersects(UiBounds(content.left, content.top, content.right, content.bottom))
                }, identity?.retired == true, window.isActive, window.isFocused, window.type, window.displayId)
    }
    fun isOwnOverlay(window: AccessibilityWindowInfo, ownPackage: String): Boolean =
        WindowOcclusionPolicy.isOwned(descriptor(window), ownPackage)

    fun contentViewport(service: AccessibilityService): Rect {
        val metrics = service.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
        val b = Rect(metrics.bounds)
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        b.left += insets.left; b.top += insets.top; b.right -= insets.right; b.bottom -= insets.bottom
        return b
    }
    fun visibleArea(service: AccessibilityService, requested: Rect): Rect = Rect(requested).also {
        if (!it.intersect(contentViewport(service)) || it.isEmpty) throw CaptureFailure("目标区域不在可见内容范围内")
    }
    fun check(service: AccessibilityService, region: Rect, targetWindowId: Int? = null, isolatedTargetCapture: Boolean = false): String {
        val windows = service.windows.orEmpty()
        val target = if (targetWindowId != null) windows.firstOrNull { it.id == targetWindowId }
            else windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
        target ?: throw CaptureFailure("无法确认目标窗口，请保持原页面可见")
        if (target.type != AccessibilityWindowInfo.TYPE_APPLICATION || (!target.isActive && !target.isFocused))
            throw CaptureFailure("目标页面不再处于前台，请返回原页面后继续")
        val content = contentViewport(service)
        val descriptors = windows.map { descriptor(it, content) }
        val area = UiBounds(region.left,region.top,region.right,region.bottom)
        val obstruction = WindowOcclusionPolicy.blocker(descriptors, target.id, area, service.packageName, if (isolatedTargetCapture) WindowOcclusionPolicy.CaptureMode.TARGET_WINDOW else WindowOcclusionPolicy.CaptureMode.SCREEN_PIXELS)
        if (obstruction != null) {
            // Structural diagnostics only: no page text, account data or raw window titles are exported.
            if (obstruction.active || obstruction.focused)
                throw CaptureFailure("其他窗口已取得焦点，请关闭弹窗或键盘并返回原页面后继续")
            val titleHash = obstruction.title?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(4).joinToString("") { b -> "%02x".format(b) } } ?: "null"
            throw WindowObstruction("窗口遮挡: ${obstruction.kind}/${obstruction.platformType} id=${obstruction.id} a=${obstruction.active} f=${obstruction.focused} pkg=${obstruction.rootPackage ?: "null"} titleHash=$titleHash b=${obstruction.bounds} r=${obstruction.systemRegions.take(2)}")
        }
        val presentIds = windows.map { it.id }.toSet()
        val presentTitles = descriptors.mapNotNull { it.title }.toSet()
        owned.entries.removeAll { (_, v) -> v.retired && v.ids.none { it in presentIds } && v.title !in presentTitles }
        return descriptors.filterNot { WindowOcclusionPolicy.isOwned(it,service.packageName) }
            .sortedBy { it.id }.joinToString("|") { "${it.id}:${it.kind}/${it.platformType}:${it.displayId}:${it.layer}:${it.active}:${it.focused}:${it.rootPackage}:${WindowOcclusionPolicy.regions(it)}" }
    }
}
