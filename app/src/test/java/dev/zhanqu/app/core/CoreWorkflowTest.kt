package dev.zhanqu.app.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreWorkflowTest {
    private val profile = BuiltInProfiles.xiaohongshu()
    private fun n(path: String, id: String? = null, text: String? = null, desc: String? = null, children: List<UiNode> = emptyList(), visible: Boolean = true, bounds: UiBounds = UiBounds(0, 0, 1080, 2000)) =
        UiNode(path, id, "android.widget.TextView", text, desc, bounds, visible = visible, children = children)
    private fun snapshot(vararg nodes: UiNode) = UiSnapshot("com.xingin.xhs", "com.xingin.matrix.notedetail.NoteDetailActivity", 1080, 2340, nodes.toList(), "9.49.2")
    private fun comment(path: String, reply: Boolean, author: String, body: String, time: String = "合成时间", children: List<UiNode> = emptyList(), visible: Boolean = true) =
        n(path, if (reply) "subCommentLayout" else "parentCommentLayout", children = listOf(n("$path/a", "tv_user_name", author), n("$path/t", "tv_content", body), n("$path/d", "newTimePoiIpTransTv", time)) + children, visible = visible)

    @Test fun counterRejectsInvalidNumbersAndParsesSecondOfSeven() {
        assertEquals(PageCounter(2, 7), profile.parsePageCounter("图片,第2张,共7张,双指左划或右划即可查看更多内容"))
        listOf("图片,第0张,共7张", "图片,第8张,共7张", "图片,第2张,共999999999999999张", "2/7", "图片,第1张,共0张").forEach { assertNull(profile.parsePageCounter(it)) }
    }

    @Test fun largestVisibleImageIsSelectedAndBoundsAreClipped() {
        val invisible = n("0/0", "photoImageView", bounds = UiBounds(-1000, 250, 0, 2105))
        val image = n("0/1", "photoImageView", desc = "图片,第2张,共7张", bounds = UiBounds(-1, 250, 1081, 2105))
        val snap = snapshot(n("0", "imageListView", children = listOf(invisible, image)))
        assertTrue(profile.matches(snap))
        assertEquals(PageCounter(2, 7), profile.pageCounter(snap))
        assertEquals(UiBounds(0, 250, 1080, 2105), profile.imageBounds(snap))
        assertFalse(profile.matches(snap.copy(packageName = "com.other.app")))
        assertFalse(profile.matches(snap.copy(activityName = "PaymentActivity")))
    }

    @Test fun exactAndShortIdsRespectAncestorAndVisibility() {
        val outside = n("0", "com.xingin.xhs:id/tv_content", "不属于评论")
        val inside = n("1/0", "com.xingin.xhs:id/tv_content", "合成评论", visible = false)
        val snap = snapshot(outside, n("1", "noteDetailRV", children = listOf(inside)))
        assertEquals(listOf(inside), profile.find(snap, "commentText", false))
        assertTrue(profile.find(snap, "commentText", true).isEmpty())
        assertFalse(LocatorCandidate(viewId = "com.other:id/tv_content").matches(inside, snap))
    }

    @Test fun startingSecondPageRewindsAndCapturesExactlySevenDistinctPages() {
        val tracker = CarouselTracker()
        assertEquals(CarouselTracker.Step.REWIND, tracker.observe(PageCounter(2, 7)).step)
        assertEquals(CarouselTracker.Step.CAPTURE, tracker.observe(PageCounter(1, 7)).step)
        for (i in 1..7) {
            if (i > 1) assertEquals(CarouselTracker.Step.CAPTURE, tracker.observe(PageCounter(i, 7)).step)
            val result = tracker.acceptCapture(PageCounter(i, 7), "synthetic-distinct-frame-$i")
            assertEquals(if (i == 7) CarouselTracker.Step.FINISHED else CarouselTracker.Step.ADVANCE, result.step)
        }
        assertEquals((1..7).toSet(), tracker.capturedPages)
        assertTrue(tracker.complete)
    }

    @Test fun missingFirstPageCannotClaimCompleteAfterUnchangedRewind() {
        val tracker = CarouselTracker()
        repeat(3) { assertEquals(CarouselTracker.Step.REWIND, tracker.observe(PageCounter(2, 7)).step) }
        assertEquals(CarouselTracker.Step.ASK_USER, tracker.observe(PageCounter(2, 7)).step)
        assertFalse(tracker.complete)
        assertEquals((1..7).toList(), tracker.missingPages)
    }

    @Test fun rejectsJumpChangedTotalAndDuplicateScreenshots() {
        val jump = CarouselTracker(); jump.observe(PageCounter(1, 7)); jump.acceptCapture(PageCounter(1, 7), "a")
        assertEquals(CarouselTracker.Step.STOP, jump.observe(PageCounter(3, 7)).step)
        val changed = CarouselTracker(); changed.observe(PageCounter(1, 7))
        assertEquals(CarouselTracker.Step.STOP, changed.observe(PageCounter(1, 8)).step)
        val repeated = CarouselTracker(); repeated.observe(PageCounter(1, 2)); repeated.acceptCapture(PageCounter(1, 2), "same"); repeated.observe(PageCounter(2, 2))
        assertEquals(CarouselTracker.Step.STOP, repeated.acceptCapture(PageCounter(2, 2), "same").step)
        assertFalse(repeated.complete)
    }

    @Test fun nestedRepliesKeepHierarchyAndDoNotMixParentText() {
        val child = comment("0/0/child", true, "合成乙", "子回复")
        val parent = comment("0/0", false, "合成甲", "主评论", children = listOf(child))
        val comments = CommentParser.extract(snapshot(n("0", "noteDetailRV", children = listOf(parent))), profile)
        assertEquals(2, comments.size)
        assertEquals("主评论", comments.first().text)
        assertEquals(comments.first().id, comments.last().parentId)
        assertEquals("ancestor", comments.last().parentRelation)
    }

    @Test fun siblingRowsRecoverProfileDeclaredGroupsAndRetainOffscreenText() {
        val rows = listOf(
            n("0/0", "ll_content", children = listOf(comment("0/0/0", false, "合成甲", "主一", visible = false))),
            comment("0/1", true, "合成乙", "回复一", visible = false),
            n("0/2", children = listOf(n("0/2/0", "loadMoreTV", "展开 2 条回复", visible = false))),
            n("0/3", "ll_content", children = listOf(comment("0/3/0", false, "合成丙", "主二", visible = false))),
            comment("0/4", true, "合成丁", "回复二", visible = false)
        )
        val comments = CommentParser.extract(snapshot(n("0", "noteDetailRV", children = rows)), profile)
        assertEquals(4, comments.size)
        val byText = comments.associateBy { it.text }
        assertEquals(byText.getValue("主一").id, byText.getValue("回复一").parentId)
        assertEquals(byText.getValue("主二").id, byText.getValue("回复二").parentId)
        assertTrue(comments.filter { it.isReply }.all { it.parentRelation == "siblingGroup" && !it.parentUnresolved })
        assertTrue(CommentParser.extract(snapshot(n("0", "noteDetailRV", children = rows)), profile, true).isEmpty())
    }

    @Test fun unknownRowOrMissingParentPreventsSpeculativeReplyLink() {
        val rows = listOf(comment("0/0", false, "甲", "主"), n("0/1", "unknownRow"), comment("0/2", true, "乙", "回复"))
        val reply = CommentParser.extract(snapshot(n("0", "noteDetailRV", children = rows)), profile).single { it.isReply }
        assertNull(reply.parentId); assertTrue(reply.parentUnresolved)
    }

    @Test fun dedupDoesNotMergeDifferentAuthorsOrTimesOrDuplicateOccurrences() {
        val rows = listOf(comment("0/0", false, "甲", "相同", "一"), comment("0/1", false, "乙", "相同", "一"), comment("0/2", false, "甲", "相同", "二"), comment("0/3", false, "甲", "相同", "一"))
        val parsed = CommentParser.extract(snapshot(n("0", "noteDetailRV", children = rows)), profile)
        val accumulator = CommentAccumulator()
        assertEquals(4, accumulator.merge(parsed)); assertEquals(0, accumulator.merge(parsed)); assertEquals(4, accumulator.parentCount)
    }

    @Test fun displayedTotalIsIndependentFromExtractedTotalAndEndStatus() {
        val snap = snapshot(n("0", text = "共 34 条评论"), n("1", "noteDetailRV", children = listOf(comment("1/0", false, "合成用户", "只有一条"))))
        assertEquals(34, CommentParser.displayedCount(snap, profile)); assertEquals(1, CommentParser.extract(snap, profile).size)
        val tracker = CommentProgressTracker()
        tracker.afterRead(1, "same", false, false)
        repeat(2) { assertEquals(CommentProgressTracker.StopReason.CONTINUE, tracker.afterRead(0, "same", false, true)) }
        assertEquals(CommentProgressTracker.StopReason.SUSPECTED_END, tracker.afterRead(0, "same", false, true)); assertFalse(tracker.endReached)
        assertEquals(CommentProgressTracker.StopReason.CONFIRMED_END, tracker.afterRead(0, "same", true, false)); assertTrue(tracker.endReached)
    }

    @Test fun noNewCommentsWhileViewportMovesIsNotEnd() {
        val tracker = CommentProgressTracker(maxScrolls = 5)
        repeat(4) { assertEquals(CommentProgressTracker.StopReason.CONTINUE, tracker.afterRead(0, "position-$it", false, true)) }
        assertEquals(CommentProgressTracker.StopReason.SCROLL_LIMIT, tracker.afterRead(0, "position-5", false, true))
    }

    @Test fun profileRejectsExecutableFieldsUnboundedLimitsAndUnsafeRegex() {
        for (json in listOf(
            JSONObject(BuiltInProfiles.XIAOHONGSHU_JSON).put("script", "shell command"),
            JSONObject(BuiltInProfiles.XIAOHONGSHU_JSON).apply { getJSONObject("limits").put("maxScrolls", 100000) },
            JSONObject(BuiltInProfiles.XIAOHONGSHU_JSON).apply { getJSONObject("locators").getJSONArray("title").getJSONObject(0).put("textRegex", "(a+)+") }
        )) assertThrows(IllegalArgumentException::class.java) { CaptureProfile.fromJson(json.toString()) }
    }

    @Test fun secondAppUsesSameDeclarativeKernel() {
        val json = JSONObject(BuiltInProfiles.XIAOHONGSHU_JSON).put("id", "synthetic.second-app").put("packageName", "dev.example.reader").put("activityPatterns", org.json.JSONArray().put(".*ReaderActivity"))
        val second = CaptureProfile.fromJson(json.toString())
        val snap = UiSnapshot("dev.example.reader", "dev.example.ReaderActivity", 1080, 2340, listOf(n("0", "imageListView", children = listOf(n("0/0", "photoImageView", desc = "图片,第1张,共3张")))))
        assertTrue(second.matches(snap)); assertEquals(PageCounter(1, 3), second.pageCounter(snap)); assertNotNull(second.imageBounds(snap))
        assertFalse(profile.matches(snap))
    }

    @Test fun visiblePasswordStopsProfileAndComments() {
        val snap = snapshot(n("0", "imageListView", desc = "图片,第1张,共1张"), UiNode("1", password = true, bounds = UiBounds(0, 0, 100, 100)))
        assertFalse(profile.matches(snap)); assertTrue(CommentParser.extract(snap, profile).isEmpty())
    }
}
