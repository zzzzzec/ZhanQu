package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test

class CommentEndMarkerTest {
    private val profile = BuiltInProfiles.xiaohongshu()
    private fun snapshot(text: String, inCommentList: Boolean = true): UiSnapshot {
        val marker = UiNode("0/0", text = text, bounds = UiBounds(0, 100, 600, 160))
        val root = UiNode("0", viewId = if (inCommentList) "com.xingin.xhs:id/noteDetailRV" else "com.xingin.xhs:id/imageNoteTextView",
            bounds = UiBounds(0, 0, 600, 900), children = listOf(marker))
        return UiSnapshot("com.xingin.xhs", screenWidth = 600, screenHeight = 900, roots = listOf(root))
    }

    @Test fun acceptsPlainAndDecoratedListEndMarkers() {
        for (text in listOf("到底了", "- 到底了 -", "  — 到底了 —  ", "· 没有更多评论了 ·", "没有更多了", "已显示全部评论")) {
            assertEquals(text, 1, profile.find(snapshot(text), "commentEnd").size)
        }
    }

    @Test fun rejectsSimilarTextAndMarkersOutsideCommentList() {
        for (text in listOf("还没到底了", "到底了？", "到底了 还有更多", "没有更多了，稍后重试")) {
            assertTrue(text, profile.find(snapshot(text), "commentEnd").isEmpty())
        }
        assertTrue(profile.find(snapshot("- 到底了 -", inCommentList = false), "commentEnd").isEmpty())
    }
}
