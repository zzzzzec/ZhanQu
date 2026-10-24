package dev.zhanqu.app.capture.apps.zhihu

import dev.zhanqu.app.capture.*
import dev.zhanqu.app.core.*
import dev.zhanqu.app.export.NoteExporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ZhihuCaptureTest {
    private fun node(path: String, id: String? = null, text: String? = null,
        bounds: UiBounds = UiBounds(20, 250, 1000, 400), children: List<UiNode> = emptyList()) =
        UiNode(path, id?.let { "com.zhihu.android:id/$it" }, text = text, bounds = bounds, children = children)

    private fun screen(paragraphs: List<String> = listOf("合成正文甲", "合成正文乙"),
        author: String = "合成作者", title: String = "合成问题？"): UiSnapshot {
        val body = paragraphs.mapIndexed { index, text -> node("0/0/${index + 2}", "holder_text", children =
            listOf(node("0/0/${index + 2}/0", text = text))) }
        return UiSnapshot(CaptureApp.ZHIHU.packageName, screenWidth = 1080, screenHeight = 2340, roots = listOf(
            node("0", bounds = UiBounds(0, 0, 1080, 2340), children = listOf(
                node("0/0", "custom_recycler_view", bounds = UiBounds(0, 0, 1080, 2340), children = listOf(
                    node("0/0/0", "header_view", children = listOf(node("0/0/0/0", text = title), node("0/0/0/1", text = "123 个回答"))),
                    node("0/0/1", "author_info_view", children = listOf(node("0/0/1/0", text = author), node("0/0/1/1", text = "合成简介")))
                ) + body + listOf(
                    node("0/0/90", "image_describe", "合成图片说明"),
                    node("0/0/91", "image_describe", "屏外说明", UiBounds(0, 2400, 1080, 2340)),
                    node("0/0/92", "statusTv", "999"), node("0/0/93", text = "广告内容"))),
                node("0/1", "toolbarContainer", bounds = UiBounds(0, 0, 1080, 200)),
                node("0/2", "overlay_container", bounds = UiBounds(0, 2160, 1080, 2340)),
                node("0/3", "holder_text", "容器外推荐内容")
            ))))
    }

    @Test fun routesOnlyExactSupportedPackages() {
        assertEquals(CaptureApp.ZHIHU, CaptureApp.detect("com.zhihu.android"))
        assertEquals(CaptureApp.XIAOHONGSHU, CaptureApp.detect("com.xingin.xhs"))
        assertNull(CaptureApp.detect("com.zhihu.android.fake")); assertNull(CaptureApp.detect(null))
        assertEquals(CaptureApp.BILIBILI, CaptureApp.detect("tv.danmaku.bili"))
    }

    @Test fun scopesAnswerContentAndExcludesChromeOffscreenCaptionsAndOtherApps() {
        val page = ZhihuPageReader.read(screen())!!
        assertEquals("合成问题？", page.question); assertEquals("合成作者", page.author)
        assertEquals(listOf("合成正文甲", "合成正文乙"), page.paragraphs)
        assertEquals(listOf("合成图片说明"), page.imageDescriptions)
        assertEquals(UiBounds(0, 200, 1080, 2160), page.scrollRegion)
        assertNull(ZhihuPageReader.read(screen().copy(packageName = "com.xingin.xhs")))
        assertNull(ZhihuPageReader.read(screen().copy(roots = emptyList())))
    }

    @Test fun keepsNonAdjacentRepeatedParagraphsButMergesConsecutiveViewportOverlap() {
        val paragraphs = ZhihuParagraphs()
        paragraphs.append(listOf("甲", "乙")); paragraphs.append(listOf("乙", "丙"))
        paragraphs.append(listOf("丁", "甲"))
        assertEquals("甲\n\n乙\n\n丙\n\n丁\n\n甲", paragraphs.text())
    }

    private fun boundaryScreen(onlyFooter: Boolean = false): UiSnapshot {
        val original = screen()
        val root = original.roots.single()
        val container = root.children.first()
        val current = if (onlyFooter) listOf(node("0/0/50", "out_comment_view", children =
            listOf(node("0/0/50/0", text = "评论内容不应混入正文")))) else container.children +
            node("0/0/50", "end_info_view", "编辑于 合成时间")
        val siblings = listOf(node("0/0/51", "bottom_left_follow_title", "合成作者"),
            node("0/0/52", "author_info_view", children = listOf(node("0/0/52/0", text = "下条作者"))),
            node("0/0/53", "holder_text", children = listOf(node("0/0/53/0", text = "下条回答不能混入"))))
        return original.copy(roots = listOf(root.copy(children = listOf(container.copy(children = current + siblings)) + root.children.drop(1))))
    }

    @Test fun stopsAtCurrentAnswerEndBeforeFollowingAnswerInSameRecycler() {
        val page = ZhihuPageReader.read(boundaryScreen())!!
        assertEquals("合成作者", page.author); assertTrue(page.endReached)
        assertEquals(listOf("合成正文甲", "合成正文乙"), page.paragraphs)
    }

    @Test fun currentFooterBeforeNextHeaderIdentifiesEndWithoutCapturingNextAnswer() {
        val page = ZhihuPageReader.read(boundaryScreen(onlyFooter = true))!!
        assertEquals("合成作者", page.author); assertTrue(page.endReached)
        assertTrue(page.paragraphs.isEmpty())
    }

    @Test fun confirmedEndSavesMarkerAndDoesNotSwipeToAnotherAnswer() = runTest {
        val saved = mutableListOf<CapturedNote>()
        ZhihuAnswerCaptureTask({ LiveScreen(boundaryScreen(), 7) },
            { _, _ -> fail("Confirmed answer end must not scroll"); false }, {}, saved::add).run()
        assertTrue(saved.last().answers.single().endReached)
        assertEquals("complete", saved.last().status)
        assertTrue(saved.last().errors.isEmpty())
        assertFalse(saved.last().answers.single().text.contains("下条回答"))
    }

    @Test fun repeatedViewportStopsWithPartialAnswerAndExportPreservesObservations() = runTest {
        val saved = mutableListOf<CapturedNote>(); var swipes = 0
        val task = ZhihuAnswerCaptureTask({ LiveScreen(screen(), 7) }, { _, window ->
            assertEquals(7, window); swipes++; true
        }, {}, saved::add)
        task.run() // The Zhihu task has no XHS options at all.
        val result = saved.last()
        assertEquals(2, swipes); assertEquals("partial", result.status); assertFalse(result.endReached)
        assertTrue(result.pages.isEmpty()); assertTrue(result.comments.isEmpty())
        assertEquals(1, result.answers.size); assertEquals(1, result.answers.single().observations.size)
        val json = JSONObject(NoteExporter.json(result))
        assertEquals(3, json.getInt("schemaVersion"))
        assertEquals("zhihu_answer", json.getString("captureKind"))
        assertEquals(1, json.getJSONArray("answers").getJSONObject(0).getJSONArray("observations").length())
        val markdown = NoteExporter.markdown(result)
        assertTrue(markdown.contains("合成正文甲")); assertTrue(markdown.contains("合成图片说明"))
        assertFalse(markdown.contains("## 评论")); assertFalse(markdown.contains("图页已尝试"))
    }

    @Test fun changedAnswerPausesBeforeCollectingOrScrollingItAndCancelPreservesCheckpoint() = runTest {
        var current = screen(); var swipes = 0
        val states = mutableListOf<CaptureUiState>(); val saved = mutableListOf<CapturedNote>()
        val task = ZhihuAnswerCaptureTask({ LiveScreen(current, 7) }, { _, _ ->
            swipes++; current = screen(listOf("另一条回答不能混入"), author = "另一个作者"); true
        }, states::add, saved::add)
        val job = launch { task.run() }
        runCurrent(); advanceTimeBy(700); runCurrent()
        assertTrue(states.last().paused); assertEquals(1, swipes)
        job.cancel(); runCurrent()
        assertEquals("stopped", saved.last().status)
        assertTrue(saved.last().errors.isEmpty())
        assertFalse(saved.last().answers.single().text.contains("另一条回答"))
        assertFalse(states.last().running)
    }

    @Test fun questionChangeWithSameAuthorAlsoPausesBeforeSecondScroll() = runTest {
        var current = screen(); var swipes = 0
        val states = mutableListOf<CaptureUiState>(); val saved = mutableListOf<CapturedNote>()
        val task = ZhihuAnswerCaptureTask({ LiveScreen(current, 7) }, { _, _ ->
            swipes++; current = screen(listOf("别的问题正文"), title = "另一个问题？"); true
        }, states::add, saved::add)
        val job = launch { task.run() }
        runCurrent(); advanceTimeBy(700); runCurrent()
        assertTrue(states.last().paused); assertEquals(1, swipes)
        job.cancel(); runCurrent()
        assertEquals("合成问题？", saved.last().title)
    }

    @Test fun missingAnswerDoesNotScroll() = runTest {
        val saved = mutableListOf<CapturedNote>()
        ZhihuAnswerCaptureTask({ LiveScreen(screen().copy(roots = emptyList()), 7) },
            { _, _ -> fail("Unrecognized page must not scroll"); false }, {}, saved::add).run()
        assertTrue(saved.last().answers.isEmpty()); assertEquals("partial", saved.last().status)
    }

    @Test fun suppliedPrivateSnapshotWhenExplicitlyProvided() {
        val path = System.getenv("ZHANQU_ZHIHU_SNAPSHOT_ZIP")
        assumeTrue("Private snapshot is optional and never committed", !path.isNullOrBlank())
        val snapshot = File(path!!).inputStream().use { GkdSnapshotImporter.importZip(it).snapshot }
        val page = ZhihuPageReader.read(snapshot)!!
        assertTrue(page.question.isNotBlank()); assertTrue(page.author.isNotBlank())
        assertTrue(page.paragraphs.isNotEmpty()); assertTrue(page.imageCount > 0)
        assertTrue(page.imageDescriptions.isNotEmpty())
        assertTrue(page.scrollRegion.bottom < snapshot.screenHeight)
    }
}
