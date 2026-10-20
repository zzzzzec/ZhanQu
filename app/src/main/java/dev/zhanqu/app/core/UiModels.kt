package dev.zhanqu.app.core

/** Immutable copies only: never retains AccessibilityNodeInfo or Bitmap instances. */
data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right.toLong() - left).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val height: Int get() = (bottom.toLong() - top).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val area: Long get() = width.toLong() * height
    val isEmpty: Boolean get() = right <= left || bottom <= top
    fun intersection(other: UiBounds): UiBounds = UiBounds(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom)).let { if (it.isEmpty) EMPTY else it }
    fun intersects(other: UiBounds): Boolean = !intersection(other).isEmpty
    fun contains(other: UiBounds): Boolean = !other.isEmpty && left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    companion object { val EMPTY = UiBounds(0, 0, 0, 0) }
}

data class UiNode(
    val path: String,
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val description: String? = null,
    val bounds: UiBounds = UiBounds.EMPTY,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val visible: Boolean = true,
    val password: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val focusable: Boolean = false, val checkable: Boolean = false, val checked: Boolean? = null,
    val editable: Boolean = false, val longClickable: Boolean = false
) {
    val shortViewId: String? get() = viewId?.substringAfterLast('/')
    fun descendants(includeSelf: Boolean = true): Sequence<UiNode> = sequence {
        val queue = java.util.ArrayDeque<UiNode>()
        if (includeSelf) queue.add(this@UiNode) else children.forEach(queue::add)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            yield(node)
            node.children.forEach(queue::add)
        }
    }
    companion object { const val MAX_NODES = 20_000 }
}

data class UiSnapshot(
    val packageName: String,
    val activityName: String? = null,
    val screenWidth: Int,
    val screenHeight: Int,
    val roots: List<UiNode>,
    val appVersion: String? = null
) {
    val displayBounds: UiBounds get() = UiBounds(0, 0, screenWidth, screenHeight)
    fun allNodes(): Sequence<UiNode> = roots.asSequence().flatMap { it.descendants() }.take(UiNode.MAX_NODES)
    fun ancestorsOf(path: String): List<UiNode> {
        val result = mutableListOf<UiNode>()
        fun walk(node: UiNode, parents: List<UiNode>): Boolean {
            if (node.path == path) { result.addAll(parents); return true }
            if (parents.size > 128) return false
            return node.children.any { walk(it, parents + node) }
        }
        roots.any { walk(it, emptyList()) }
        return result
    }
    fun hasSensitiveFields(): Boolean = allNodes().any { it.password && it.visible && it.bounds.intersects(displayBounds) }
}

data class PageCounter(val current: Int, val total: Int) {
    init { require(total > 0 && current in 1..total) { "Invalid page counter" } }
}
