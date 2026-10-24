package dev.zhanqu.app.capture.apps.xiaohongshu

import dev.zhanqu.app.core.*
import org.junit.Assert.*
import org.junit.Test

class XiaohongshuPageGeometryTest {
    @Test fun recognizesVisibleEmptyCommentFooterOnlyInXhs() {
        val footer = UiNode("0", "com.xingin.xhs:id/loadMoreTV", text = "还没有评论哦", bounds = UiBounds(0,1800,1080,1900))
        val s = UiSnapshot("com.xingin.xhs", screenWidth=1080, screenHeight=2340, roots=listOf(footer))
        assertTrue(XiaohongshuPageGeometry.hasEmptyComments(s))
        assertFalse(XiaohongshuPageGeometry.hasEmptyComments(s.copy(packageName="tv.danmaku.bili")))
        assertFalse(XiaohongshuPageGeometry.hasEmptyComments(s.copy(roots=listOf(footer.copy(visible=false)))))
        assertFalse(XiaohongshuPageGeometry.hasEmptyComments(s.copy(roots=listOf(footer.copy(viewId="com.xingin.xhs:id/imageNoteTextView")))))
    }
    @Test fun gestureStartsAboveFixedInputBarEvenWhenListExtendsBehindIt() {
        val list = UiNode("0/0", "com.xingin.xhs:id/noteDetailRV", bounds = UiBounds(0,1559,1080,2340))
        val bar = UiNode("0/1", "com.xingin.xhs:id/engageBarLayout", bounds = UiBounds(0,2140,1080,2298))
        val snapshot = UiSnapshot("com.xingin.xhs",screenWidth=1080,screenHeight=2340,roots=listOf(list,bar))
        val region = XiaohongshuPageGeometry.commentScrollRegion(snapshot,list)!!
        assertEquals(2140,region.bottom)
        assertTrue(SwipePlan.create(region,false,true,12).startY < bar.bounds.top)
        assertTrue(SwipePlan.create(list.bounds,false,true,12).startY > bar.bounds.top)
    }
    @Test fun refusesSliverAboveInputBar() {
        val list=UiNode("0/0",bounds=UiBounds(0,2100,1080,2340))
        val bar=UiNode("0/1","com.xingin.xhs:id/engageBarLayout",bounds=UiBounds(0,2140,1080,2298))
        assertNull(XiaohongshuPageGeometry.commentScrollRegion(UiSnapshot("com.xingin.xhs",screenWidth=1080,screenHeight=2340,roots=listOf(list,bar)),list))
    }
}
