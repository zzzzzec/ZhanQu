package dev.zhanqu.app.core

import org.junit.Assert.*
import org.junit.Test

/** Synthetic rows only. Mixed IDs are supported profile candidates, not private page content. */
class CommentFieldFallbackTest {
    private val profile = BuiltInProfiles.xiaohongshu()
    private fun node(path: String, id: String, text: String? = null, children: List<UiNode> = emptyList()) =
        UiNode(path, id, text = text, bounds = UiBounds(0, 0, 800, 100), children = children)
    private fun parse(vararg rows: UiNode) = CommentParser.extract(
        UiSnapshot("com.xingin.xhs", screenWidth = 1080, screenHeight = 2340,
            roots = listOf(node("0", "noteDetailRV", children = rows.toList()))), profile)

    @Test fun parentAndReplyChooseFallbackIndependentlyForEveryField() {
        val parent = node("0/0", "parentCommentLayout", children = listOf(
            node("0/0/t", "tv_content", "合成主评论"),
            node("0/0/a", "tv_user_name", "合成甲"),
            node("0/0/d", "newTimePoiIpTransTv", "合成时间一"),
            node("0/0/r", "tv_location", "合成地区一"),
            node("0/0/l", "tv_like_num_new_engage", "1")))
        val reply = node("0/1", "subCommentLayout", children = listOf(
            node("0/1/t", "tv_content", "合成回复"),
            node("0/1/a", "tv_nickname", "合成乙"),
            node("0/1/d", "timeTV", "合成时间二"),
            node("0/1/r", "locationTV", "合成地区二"),
            node("0/1/l", "tv_like_count", "2")))
        val parsed = parse(parent, reply)
        assertEquals(2, parsed.size)
        assertEquals(listOf("合成甲", "合成乙"), parsed.map { it.author })
        assertEquals(listOf("合成时间一", "合成时间二"), parsed.map { it.timeText })
        assertEquals(listOf("合成地区一", "合成地区二"), parsed.map { it.regionText })
        assertEquals(listOf("1", "2"), parsed.map { it.likeText })
        assertEquals(parsed[0].id, parsed[1].parentId)
    }

    @Test fun emptyPreferredFieldDoesNotHideUsableFallback() {
        val parsed = parse(node("0/0", "parentCommentLayout", children = listOf(
            node("0/0/t", "tv_content", "合成内容"),
            node("0/0/a", "tv_user_name", "  "),
            node("0/0/b", "tv_nickname", "合成昵称"))))
        assertEquals("合成昵称", parsed.single().author)
    }

    @Test fun preferredCandidateStillWinsWithinTheSameComment() {
        val parsed = parse(node("0/0", "parentCommentLayout", children = listOf(
            node("0/0/t", "tv_content", "合成内容"),
            node("0/0/b", "tv_nickname", "次选昵称"),
            node("0/0/a", "tv_user_name", "首选昵称"))))
        assertEquals("首选昵称", parsed.single().author)
    }

    @Test fun missingParentTextNeverBorrowsNestedReplyTextOrCreatesAParent() {
        val reply = node("0/0/1", "subCommentLayout", children = listOf(
            node("0/0/1/t", "tv_content", "仅有合成回复"),
            node("0/0/1/a", "tv_nickname", "合成乙")))
        val parsed = parse(node("0/0", "parentCommentLayout", children = listOf(
            node("0/0/a", "tv_user_name", "合成甲"), reply)))
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().isReply)
        assertTrue(parsed.single().parentUnresolved)
        assertNull(parsed.single().parentId)
        assertEquals("合成乙", parsed.single().author)
    }
}
