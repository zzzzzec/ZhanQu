package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test

class CompactOverlayPolicyTest {
    @Test fun `entry and menu retain accessible touch targets`() {
        assertTrue(CompactOverlayPolicy.ENTRY_WIDTH_DP >= CompactOverlayPolicy.TOUCH_TARGET_DP + 8)
        assertTrue(CompactOverlayPolicy.PANEL_WIDTH_DP >= 3 * CompactOverlayPolicy.TOUCH_TARGET_DP + 28)
        assertTrue(CompactOverlayPolicy.PANEL_WIDTH_DP < 240)
    }
    @Test fun `raw diagnostic identifiers never become compact status`() {
        val raw = "窗口遮挡: OTHER/-1 pkg=synthetic.private.app b=UiBounds(left=10)"
        assertEquals("有遮挡", CompactOverlayPolicy.status(false, false, "finished", raw))
        assertEquals("需处理", CompactOverlayPolicy.status(false, false, "finished", "synthetic failure\nprivate detail"))
    }
    @Test fun `saving cannot be mistaken for restartable idle state`() {
        assertEquals("保存中", CompactOverlayPolicy.status(true, true, "stopping", "failure"))
        assertFalse(CaptureControlPolicy.canControl(true, "stopping"))
        assertFalse(CaptureControlPolicy.canStart(true, true, false))
    }
    @Test fun `reply toggle enables comments and switching comments off clears replies`() {
        val replies = CompactOverlayPolicy.Selection().toggleReplies()
        assertTrue(replies.replies); assertTrue(replies.comments)
        val cleared = replies.toggleComments()
        assertFalse(cleared.comments); assertFalse(cleared.replies)
    }
    @Test fun `turning replies off retains comments and empty selection disables start`() {
        val comments = CompactOverlayPolicy.Selection().toggleReplies().toggleReplies()
        assertTrue(comments.comments); assertFalse(comments.replies)
        val empty = CompactOverlayPolicy.Selection().toggleImages()
        assertFalse(CaptureControlPolicy.canStart(false, empty.images, empty.comments))
    }
    @Test fun `compact status distinguishes paused completed and ready`() {
        assertEquals("已暂停", CompactOverlayPolicy.status(true, true, "images", null))
        assertEquals("已保存", CompactOverlayPolicy.status(false, true, "finished", null))
        assertEquals("就绪", CompactOverlayPolicy.status(false, false, "idle", null))
    }
}
