package dev.zhanqu.app.capture.apps.xiaohongshu

import dev.zhanqu.app.core.UiBounds
import dev.zhanqu.app.core.UiNode
import dev.zhanqu.app.core.UiSnapshot

/** The native comment list extends behind XHS's fixed input/engagement bar on real devices. */
internal object XiaohongshuPageGeometry {
    fun hasEmptyComments(snapshot: UiSnapshot): Boolean = snapshot.packageName == "com.xingin.xhs" &&
        snapshot.allNodes().any { it.shortViewId == "loadMoreTV" && it.text?.trim() == "还没有评论哦" &&
            it.visible && it.bounds.intersects(snapshot.displayBounds) }

    fun commentScrollRegion(snapshot: UiSnapshot, list: UiNode): UiBounds? {
        val bottom = snapshot.allNodes().filter { it.visible && it.shortViewId == "engageBarLayout" &&
            it.bounds.intersects(snapshot.displayBounds) }.minOfOrNull { it.bounds.top } ?: snapshot.screenHeight
        val region = list.bounds.intersection(UiBounds(0, 0, snapshot.screenWidth, bottom))
        return region.takeIf { it.width >= 80 && it.height >= 120 }
    }
}
