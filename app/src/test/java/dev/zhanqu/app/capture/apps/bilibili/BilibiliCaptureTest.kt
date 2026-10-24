package dev.zhanqu.app.capture.apps.bilibili

import dev.zhanqu.app.capture.*
import dev.zhanqu.app.core.*
import dev.zhanqu.app.export.NoteExporter
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BilibiliCaptureTest {
    private fun n(id: String = "", text: String = "", children: List<UiNode> = emptyList(), clickable: Boolean = false,
        description: String? = null) = UiNode("", "tv.danmaku.bili:id/$id", text = text.ifEmpty { null },
        bounds = UiBounds(0, 800, 1080, 2100), children = children, clickable = clickable, description = description)
    private fun s(vararg nodes: UiNode): UiSnapshot {
        fun paths(n: UiNode, path: String): UiNode = n.copy(path = path, children = n.children.mapIndexed { i, c -> paths(c, "$path/$i") })
        return UiSnapshot(CaptureApp.BILIBILI.packageName, screenWidth = 1080, screenHeight = 2340,
            roots = listOf(paths(n(children = nodes.toList()), "0")))
    }
    private fun intro(bvid: String = "BV1234567890") = s(n("video_container"),
        n("title", "广告标题"), n("desc", "广告简介"),
        n("recycler", children = listOf(n("author_name", "测试UP"),
            n(children = listOf(n("title", "测试视频"), n("arrow", description = "收起简介，按钮"))),
            n(children = listOf(n("views", "1.2万"), n("danmakus", "321"), n("time", "2026年10月24日"), n("desc", "$bvid\n测试简介"))))),
        n("tab_title", "评论", clickable = true), n("tab_sub_title", "2"))
    private fun comment(author: String, body: String) = n(children = listOf(n("user_name", author),
        n("primary_message", body), n("description", "10月24日 上海"), n("like_count", "12")))
    private fun main(end: Boolean = false) = s(n("video_container"), n("tab_title", "简介", clickable = true),
        n("cmt3_recycler", children = listOf(n("title", "热门评论"), comment("甲", "主评论"),
            n(children = listOf(n("text", "共1条回复")), clickable = true)) + if (end) listOf(n("footer", "没有更多评论了")) else emptyList()))
    private fun replies(wrong: Boolean = false) = s(n("cmt3_page_title_container"), n("close", clickable = true),
        n("cmt3_recycler", children = listOf(comment(if (wrong) "别人" else "甲", "主评论"), n("title", "相关回复共1条"), comment("乙", "子评论"))))

    @Test fun scopesMetadataAwayFromAdsAndPreservesDisplayedCounts() {
        val p = BilibiliPageReader.video(intro())!!
        assertEquals("测试视频", p.title); assertEquals("BV1234567890", p.video.bvid)
        assertEquals("1.2万", p.video.views); assertEquals("321", p.video.danmakus)
        assertFalse(p.video.description.contains("广告"))
        assertNull(BilibiliPageReader.video(intro().copy(packageName = "tv.danmaku.bili.fake")))
    }
    @Test fun separatesMainRowsAndExpandsOnlyKnownParents() {
        val p = BilibiliPageReader.comments(main())!!
        assertEquals(1, p.comments.size); assertEquals(p.comments.single().id, p.replyLinks.single().parent.id)
        assertEquals(1, p.replyLinks.single().count)
        val reply = BilibiliPageReader.comments(replies(), p.comments.single())!!
        assertEquals(1, reply.comments.size); assertEquals("子评论", reply.comments.single().text)
        assertEquals(p.comments.single().id, reply.comments.single().parentId)
        assertEquals("detailPage", reply.comments.single().parentRelation)
    }
    @Test fun orphanExpansionDoesNotBorrowAParentAcrossUnknownRows() {
        val p = BilibiliPageReader.comments(s(n("cmt3_recycler", children = listOf(comment("甲", "评论"),
            n("advert", "广告"), n(children = listOf(n("text", "共3条回复")), clickable = true)))))!!
        assertTrue(p.replyLinks.isEmpty())
    }
    @Test fun traversesReplyDetailReturnsAndExportsVideoWithoutImageFieldsInMarkdown() = runTest {
        var current = intro(); val saved = mutableListOf<CapturedNote>()
        val task = BilibiliVideoCaptureTask({ LiveScreen(current, 7) }, { node, _ ->
            current = when { node.shortViewId == "close" -> main(true)
                node.text == "简介" -> intro()
                node.text == "评论" -> main()
                else -> replies() }; true
        }, { _, _ -> fail("All rows fit without scrolling"); false }, {}, saved::add)
        task.run()
        val result = saved.last()
        assertEquals("complete", result.status); assertEquals(2, result.comments.size)
        assertEquals(1, result.expandedReplyGroups); assertTrue(result.endReached)
        val json = JSONObject(NoteExporter.json(result))
        assertEquals(4, json.getInt("schemaVersion")); assertEquals("BV1234567890", json.getJSONObject("video").getString("bvid"))
        val md = NoteExporter.markdown(result)
        assertTrue(md.contains("播放量：1.2万")); assertTrue(md.contains("子评论")); assertFalse(md.contains("图页"))
    }
    @Test fun wrongReplyParentStopsBeforeSavingOtherPeoplesThread() = runTest {
        var current = intro(); val saved = mutableListOf<CapturedNote>()
        BilibiliVideoCaptureTask({ LiveScreen(current, 7) }, { node, _ ->
            current = if (node.text == "评论") main() else replies(true); true
        }, { _, _ -> fail("Wrong parent must not scroll"); false }, {}, saved::add).run()
        assertEquals("partial", saved.last().status)
        assertTrue(saved.last().comments.none { it.isReply })
        assertTrue(saved.last().errors.any { it.contains("楼层变化") })
    }
    @Test fun finalVideoIdentityChangeCannotClaimComplete() = runTest {
        var current = intro(); val saved = mutableListOf<CapturedNote>()
        BilibiliVideoCaptureTask({ LiveScreen(current, 7) }, { node, _ ->
            current = when { node.shortViewId == "close" -> main(true)
                node.text == "简介" -> intro("BV0000000000")
                node.text == "评论" -> main(); else -> replies() }; true
        }, { _, _ -> false }, {}, saved::add).run()
        assertEquals("partial", saved.last().status); assertTrue(saved.last().errors.any { it.contains("视频已变化") })
    }
    @Test fun unsupportedPageNeverClicksOrScrolls() = runTest {
        var actions = 0
        BilibiliVideoCaptureTask({ LiveScreen(s(n("title", "首页推荐")), 7) }, { _, _ -> actions++; true },
            { _, _ -> actions++; true }, {}, {}).run()
        assertEquals(0, actions)
    }
    @Test fun stoppingDuringCommentsSavesNormallyWithoutInventingAnErrorOrEndMarker() = runTest {
        var current = intro(); val saved = mutableListOf<CapturedNote>(); val states = mutableListOf<CaptureUiState>()
        BilibiliVideoCaptureTask({ LiveScreen(current, 7) }, { node, _ ->
            if (node.text == "评论") { current = main(); true } else throw CancellationException("User stopped capture")
        }, { _, _ -> true }, states::add, saved::add).run()
        assertEquals("stopped", saved.last().status); assertTrue(saved.last().errors.isEmpty())
        assertFalse(saved.last().endReached); assertEquals("主评论", saved.last().comments.single().text)
        assertNull(states.last().error); assertFalse(states.last().running)
        assertTrue(NoteExporter.markdown(saved.last()).contains("状态：已保存"))
    }
    @Test fun inlineRepliesKeepTheirParentWithoutAnExpansionButton() {
        val p = BilibiliPageReader.comments(s(n("cmt3_recycler", children = listOf(comment("甲", "主评论"),
            n(children = listOf(n("secondary_message", "乙: 回复 甲: 好的")))))))!!
        assertEquals(2, p.comments.size)
        assertEquals(p.comments.first().id, p.comments.last().parentId)
        assertEquals("乙", p.comments.last().author); assertEquals("回复 甲: 好的", p.comments.last().text)
        assertEquals("preview", p.comments.last().textState)
    }
    @Test fun foldedCommentsAreAnActionAndExpandedFooterEndsOnlyTheVisibleList() {
        val before = BilibiliPageReader.comments(s(n("cmt3_recycler", children = listOf(comment("甲", "评论"),
            n(children = listOf(n("text", "展开更多评论")), clickable = true)))))!!
        assertNotNull(before.loadMore); assertFalse(before.end)
        val after = BilibiliPageReader.comments(s(n("cmt3_recycler", children = listOf(comment("甲", "评论"),
            n("text", "已为您展开部分评论")))))!!
        assertNull(after.loadMore); assertTrue(after.end)
        val hidden = n("text", "已为您展开部分评论").copy(visible = false)
        assertFalse(BilibiliPageReader.comments(s(n("cmt3_recycler", children = listOf(comment("甲", "评论"), hidden))))!!.end)
    }
    @Test fun hiddenCachedIntroDoesNotOverrideSelectedCommentsTab() {
        val cached = s(intro().roots.single(), main().roots.single())
        assertNull(BilibiliPageReader.video(cached))
        assertNotNull(BilibiliPageReader.introTab(cached))
    }
    @Test fun repeatedCaptureReturnsFromRememberedBottomBeforeCollecting() = runTest {
        var current = intro(); var backwards = 0; val saved = mutableListOf<CapturedNote>()
        val bottom = s(n("video_container"), n("tab_title", "简介", clickable=true),
            n("cmt3_recycler", children=listOf(comment("末尾作者", "末尾评论"), n("footer", "没有更多评论了"))))
        BilibiliVideoCaptureTask({ LiveScreen(current, 7) }, { node, _ ->
            current = when { node.shortViewId == "close" -> main(true)
                node.text == "简介" -> intro()
                node.text == "评论" -> bottom; else -> replies() }; true
        }, { _, _ -> false }, {}, saved::add, scrollBackward={ _, _ -> backwards++; current=main(); true }).run()
        assertEquals(1, backwards); assertEquals("complete", saved.last().status)
        assertEquals("主评论", saved.last().comments.first().text)
        assertTrue(saved.last().comments.none { it.author == "末尾作者" })
    }
}
