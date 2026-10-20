package dev.zhanqu.app.core

/** Pure positioning policy for the movable capture controls. Coordinates are screen pixels. */
internal object OverlayPlacement {
    enum class Edge { LEFT, RIGHT }
    data class Position(val x: Int, val y: Int)
    fun constrain(x: Int, y: Int, width: Int, height: Int,
                  left: Int, top: Int, right: Int, bottom: Int, margin: Int,
                  snapToEdge: Boolean = false, edge: Edge? = null): Position {
        val minX = left + margin
        val maxX = (right - width - margin).coerceAtLeast(minX)
        val minY = top + margin
        val maxY = (bottom - height - margin).coerceAtLeast(minY)
        val boundedX = x.coerceIn(minX, maxX)
        val targetEdge = edge ?: if (snapToEdge) nearestEdge(x, width, left, right, margin) else null
        return Position(when (targetEdge) { Edge.LEFT -> minX; Edge.RIGHT -> maxX; null -> boundedX },
            y.coerceIn(minY, maxY))
    }
    /** Choose only at drag release; never infer the edge again after the window changes width. */
    fun nearestEdge(x: Int, width: Int, left: Int, right: Int, margin: Int): Edge {
        val minX = left + margin
        val maxX = (right - width - margin).coerceAtLeast(minX)
        return if (x.coerceIn(minX, maxX) < minX + (maxX - minX) / 2) Edge.LEFT else Edge.RIGHT
    }
}
