package dev.zhanqu.app.core

/** Target identity and focus only. Other windows' geometry is deliberately not a capture gate. */
object ForegroundWindowPolicy {
    data class Identity(val id: Int, val packageName: String?, val displayId: Int, val bounds: UiBounds)

    fun identity(windows: List<WindowOcclusionPolicy.Window>, targetId: Int, ownPackage: String): Identity? {
        val target = windows.firstOrNull { it.id == targetId } ?: return null
        if (target.kind != WindowOcclusionPolicy.Kind.APPLICATION || (!target.active && !target.focused)) return null
        if (windows.any { it.id != targetId && !WindowOcclusionPolicy.isOwned(it, ownPackage) && (it.active || it.focused) }) return null
        return Identity(target.id, target.rootPackage, target.displayId, target.bounds)
    }

    /** Keep switch-away-and-back history, without counting unrelated nonfocused floating-window events. */
    fun changesSnapshotTarget(eventPackage: String?, eventWindowId: Int, targetPackage: String,
                              targetWindowId: Int, foregroundPackage: String?, foregroundWindowId: Int?, ownPackage: String,
                              eventWindowIsForeground: Boolean? = null): Boolean {
        if (foregroundPackage != targetPackage || foregroundWindowId != targetWindowId) return true
        if (eventPackage == ownPackage) return false
        // A disappeared application window may deliver its event after the target has returned.
        // Only a known separate nonfocused float is irrelevant to target navigation.
        return eventPackage == targetPackage &&
            (eventWindowId == targetWindowId || eventWindowId < 0 || eventWindowIsForeground != false)
    }
}
