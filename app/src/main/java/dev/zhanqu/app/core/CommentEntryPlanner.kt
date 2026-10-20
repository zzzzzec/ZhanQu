package dev.zhanqu.app.core

/** Only declared note regions or the recognized carousel may be used; never an arbitrary screen swipe. */
object CommentEntryPlanner {
    sealed interface Plan {
        data object Ready : Plan
        data class Scroll(val node: UiNode, val region: UiBounds) : Plan
        data class Swipe(val region: UiBounds) : Plan
        data class Click(val node: UiNode) : Plan
        data object Missing : Plan
    }
    fun plan(snapshot: UiSnapshot, profile: CaptureProfile, preferCommentButton: Boolean = false): Plan {
        if (snapshot.packageName != profile.packageName || snapshot.hasSensitiveFields()) return Plan.Missing
        if (profile.find(snapshot, profile.commentFlow.scrollContainer).isNotEmpty()) return Plan.Ready
        val commentButton = profile.find(snapshot,"commentEntry").firstOrNull { it.clickable }
        if (preferCommentButton && commentButton != null) return Plan.Click(commentButton)
        for (node in profile.find(snapshot,"noteScroll")) {
            val region = node.bounds.intersection(snapshot.displayBounds)
            if (region.width >= 120 && region.height >= 200) return Plan.Scroll(node,region)
        }
        // A verified carousel supplies a safe content region even if the parent scroll ID changed.
        if (profile.matches(snapshot)) {
            val region = profile.find(snapshot,profile.imageFlow.gestureRegion).firstOrNull()?.bounds?.intersection(snapshot.displayBounds)
            if (region != null && region.width >= 120 && region.height >= 200) return Plan.Swipe(region)
        }
        val entry = commentButton
        return if (entry != null) Plan.Click(entry) else Plan.Missing
    }
}
