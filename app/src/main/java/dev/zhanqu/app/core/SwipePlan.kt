package dev.zhanqu.app.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Exact injected path and its clearance envelope, calculated together to prevent validation/dispatch drift. */
data class SwipePlan(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val clearance: UiBounds) {
    companion object {
        fun create(region: UiBounds, horizontal: Boolean, forward: Boolean, marginPx: Int): SwipePlan {
            require(region.width >= 40 && region.height >= 40 && marginPx >= 0)
            val sx = if (horizontal) region.left + region.width * (if (forward) .80f else .20f) else (region.left + region.right) / 2f
            val ex = if (horizontal) region.left + region.width * (if (forward) .20f else .80f) else sx
            val sy = if (horizontal) (region.top + region.bottom) / 2f else region.top + region.height * (if (forward) .80f else .25f)
            val ey = if (horizontal) sy else region.top + region.height * (if (forward) .25f else .80f)
            return SwipePlan(sx,sy,ex,ey,UiBounds(floor(min(sx,ex)).toInt()-marginPx,floor(min(sy,ey)).toInt()-marginPx,
                ceil(max(sx,ex)).toInt()+marginPx+1,ceil(max(sy,ey)).toInt()+marginPx+1))
        }
    }
}
