package dev.zhanqu.app.core

/** Pure positioning policy for the movable capture controls. Coordinates are screen pixels. */
internal object OverlayPlacement {
    data class Position(val x: Int, val y: Int)
    fun constrain(x: Int, y: Int, width: Int, height: Int,
                  left: Int, top: Int, right: Int, bottom: Int, margin: Int,
                  snapToEdge: Boolean = false): Position {
        val minX = left + margin
        val maxX = (right - width - margin).coerceAtLeast(minX)
        val minY = top + margin
        val maxY = (bottom - height - margin).coerceAtLeast(minY)
        val boundedX = x.coerceIn(minX, maxX)
        return Position(if (!snapToEdge) boundedX else if (boundedX < minX + (maxX - minX) / 2) minX else maxX,
            y.coerceIn(minY, maxY))
    }
}
