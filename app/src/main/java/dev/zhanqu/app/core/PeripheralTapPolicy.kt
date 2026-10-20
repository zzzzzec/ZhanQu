package dev.zhanqu.app.core

/** A physical tap may replace a blocked semantic click only on a revalidated, visible anchor.
 * Never treats a package as trusted or searches for another clickable location around an obstruction.
 */
object PeripheralTapPolicy {
    data class Plan(val x: Float, val y: Float, val clearance: UiBounds)

    fun plan(action: UiBounds, anchor: UiBounds, viewport: UiBounds, marginPx: Int): Plan? {
        if (marginPx < 1 || !action.contains(anchor) || !viewport.contains(anchor)) return null
        val x = anchor.left + anchor.width / 2
        val y = anchor.top + anchor.height / 2
        val clearance = UiBounds(x - marginPx, y - marginPx, x + marginPx + 1, y + marginPx + 1)
        if (!anchor.contains(clearance) || !action.contains(clearance) || !viewport.contains(clearance)) return null
        return Plan(x.toFloat(), y.toFloat(), clearance)
    }

    fun permits(windows: List<WindowOcclusionPolicy.Window>, targetId: Int, action: UiBounds,
                viewport: UiBounds, plan: Plan, ownPackage: String): Boolean {
        if (plan.clearance.isEmpty || !action.contains(plan.clearance) || !plan.x.isFinite() || !plan.y.isFinite() ||
            plan.x < plan.clearance.left || plan.x >= plan.clearance.right ||
            plan.y < plan.clearance.top || plan.y >= plan.clearance.bottom) return false
        val target = windows.firstOrNull { it.id == targetId } ?: return false
        if (target.displayId != 0 || target.kind != WindowOcclusionPolicy.Kind.APPLICATION || (!target.active && !target.focused) ||
            !target.bounds.contains(plan.clearance) || !viewport.contains(plan.clearance)) return false
        // A second focused window is a hard stop, irrespective of its layer or geometry.
        val foreign = windows.filter { it.id != targetId && !WindowOcclusionPolicy.isOwned(it, ownPackage) }
        if (foreign.any { it.active || it.focused }) return false
        val upper = foreign.filter { it.layer >= target.layer }
        if (upper.any { it.displayId != target.displayId || it.bounds.isEmpty }) return false
        // Every intersecting window must be a known-package, nonfocused, narrow left/right strip.
        val blockers = upper.filter { w -> WindowOcclusionPolicy.regions(w).any { it.intersects(action) } }
        if (blockers.isEmpty()) return false // This path exists only as a bounded obstruction fallback.
        if (blockers.any { w ->
                w.rootPackage.isNullOrBlank() || w.kind !in setOf(WindowOcclusionPolicy.Kind.SYSTEM, WindowOcclusionPolicy.Kind.OTHER) ||
                    !isSideStrip(w.bounds, viewport)
            }) return false
        // Do not trust touchable-region holes: validate exact tap + clearance against the normal policy.
        return WindowOcclusionPolicy.blocker(windows, targetId, plan.clearance, ownPackage) == null
    }

    private fun isSideStrip(bounds: UiBounds, viewport: UiBounds): Boolean {
        val visible = bounds.intersection(viewport)
        if (visible.isEmpty || visible.width > viewport.width / 10 || bounds.width > viewport.width / 10) return false
        return visible.right <= viewport.left + viewport.width / 10 || visible.left >= viewport.right - viewport.width / 10
    }
}
