package dev.zhanqu.app.core

/** Platform-independent geometry/identity policy; never infers ownership from an app package alone. */
object WindowOcclusionPolicy {
    enum class CaptureMode { SCREEN_PIXELS, TARGET_WINDOW }
    enum class Kind { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, OTHER }
    data class Window(
        val id: Int, val kind: Kind, val layer: Int, val bounds: UiBounds,
        val systemRegions: List<UiBounds> = emptyList(),
        val rootPackage: String? = null, val title: String? = null,
        val registeredId: Boolean = false, val registeredTitle: Boolean = false,
        val idTitleMatches: Boolean = true,
        val verifiedPeripheralSystemBars: Boolean = false,
        val retiredRegistration: Boolean = false,
        val active: Boolean = false, val focused: Boolean = false, val platformType: Int = 0, val displayId: Int = 0
    )
    fun isOwned(window: Window, ownPackage: String): Boolean =
        window.kind == Kind.ACCESSIBILITY_OVERLAY &&
            (window.rootPackage == null || window.rootPackage == ownPackage) &&
            (window.registeredTitle || (window.registeredId && !window.retiredRegistration && window.idTitleMatches))

    fun blocker(windows: List<Window>, targetId: Int, area: UiBounds, ownPackage: String, mode: CaptureMode = CaptureMode.SCREEN_PIXELS): Window? {
        val target = windows.firstOrNull { it.id == targetId } ?: return null
        return windows.firstOrNull { w ->
            w.id != targetId && !isOwned(w, ownPackage) &&
                (w.active || w.focused || (w.layer >= target.layer && regions(w).any { it.intersects(area) } &&
                (mode == CaptureMode.SCREEN_PIXELS || w.active || w.focused ||
                    w.kind !in setOf(Kind.SYSTEM, Kind.OTHER) ||
                    regions(w).any { it.intersects(interior(area)) })))
        }
    }
    // Target-window capture excludes separate windows, but a covered central area still indicates an obstructed page.
    fun interior(b: UiBounds): UiBounds {
        val dx = (b.width / 10).coerceAtLeast(1); val dy = (b.height / 10).coerceAtLeast(1)
        return UiBounds(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy)
    }
    fun regions(w: Window): List<UiBounds> =
        if (w.kind == Kind.SYSTEM && w.verifiedPeripheralSystemBars && w.systemRegions.isNotEmpty()) w.systemRegions else listOf(w.bounds)
}
