package dev.zhanqu.app.core

import dev.zhanqu.app.capture.OcrResult
import dev.zhanqu.app.capture.OcrTextBlock
import dev.zhanqu.app.capture.OcrTextLine
import org.junit.Assert.*
import org.junit.Test

/** Synthetic coordinates and text only. These tests do not run or assess the ML Kit recognizer. */
class OcrLayoutProcessorTest {
    private fun line(text: String, left: Int = 60, top: Int, right: Int = 900, height: Int = 32) =
        OcrTextLine(text, left, top, right, top + height)
    private fun block(vararg lines: OcrTextLine) = OcrTextBlock(lines.joinToString("\n") { it.text }, lines.toList())
    private fun format(vararg blocks: OcrTextBlock, page: Int? = null, total: Int? = null, raw: String? = null) =
        OcrLayoutProcessor.process(raw ?: blocks.joinToString("\n\n") { it.text }, blocks.toList(), 1000, 1800, page, total)

    @Test fun wrappedChineseLinesBecomeParagraphsWithoutChangingCharacters() {
        val result = format(block(line("合成段落的前半，", top = 200), line("后半保留原字。", top = 248, right = 500),
            line("第二段内容", top = 340), line("继续。", top = 388, right = 280)))
        assertEquals("合成段落的前半，后半保留原字。\n\n第二段内容继续。", result.formattedText)
    }

    @Test fun fragmentedHighlightRowsAndSeparateOcrBlocksRejoinGeometrically() {
        val result = format(block(line("普通文字", top = 200, right = 480)), block(line("标记文字", left = 480, top = 201)),
            block(line("下一视觉行。", top = 249, right = 520)))
        assertEquals("普通文字标记文字下一视觉行。", result.formattedText)
    }

    @Test fun independentColumnsReadDownEachColumnBeforeMovingRight() {
        val result = format(block(line("右栏第一行", left = 560, top = 200), line("右栏结束。", left = 560, top = 248, right = 790)),
            block(line("左栏第一行", top = 200, right = 440), line("左栏结束。", top = 248, right = 360)))
        assertEquals("左栏第一行左栏结束。\n\n右栏第一行右栏结束。", result.formattedText)
    }

    @Test fun fullWidthHeadingAndFooterDoNotMakeColumnsInterleave() {
        val result = format(block(line("全宽标题", top = 50, height = 44)),
            block(line("左一", top = 200, right = 440), line("左二", top = 248, right = 440)),
            block(line("右一", left = 560, top = 200), line("右二", left = 560, top = 248)),
            block(line("全宽页脚", top = 370)))
        assertEquals("全宽标题\n\n左一左二\n\n右一右二\n\n全宽页脚", result.formattedText)
    }

    @Test fun insetSmallLetteringStaysSeparateWhileArticleWidensBelowIt() {
        val result = format(
            block(line("图中小字甲", left = 60, top = 210, right = 450, height = 14), line("图中小字乙", left = 60, top = 250, right = 470, height = 14)),
            block(line("右侧正文前半", left = 495, top = 200), line("继续到", left = 495, top = 248)),
            block(line("下方全宽正文。", left = 60, top = 296), line("第二段独立。", left = 60, top = 410, right = 500)))
        assertEquals("图中小字甲\n\n图中小字乙\n\n右侧正文前半继续到下方全宽正文。\n\n第二段独立。", result.formattedText)
    }

    @Test fun onlyExpectedIsolatedCornerCounterIsRemovedWithCoordinates() {
        val counter = line("2 / 4", left = 850, top = 20, right = 930, height = 20)
        val result = format(block(counter), block(line("保留正文", top = 300)), page = 2, total = 4)
        assertEquals("保留正文", result.formattedText)
        assertEquals(1, result.removedArtifacts.size)
        assertEquals("2 / 4", result.removedArtifacts.single().text)
        assertEquals("page_counter", result.removedArtifacts.single().reason)
        assertEquals(850, result.removedArtifacts.single().left)
        assertEquals(40, result.removedArtifacts.single().bottom)
    }

    @Test fun unknownOrMismatchedCounterIsNotRemoved() {
        val counter = block(line("2/4", left = 850, top = 20, right = 930, height = 20))
        val body = block(line("合成正文", top = 300))
        assertTrue(format(counter, body).formattedText.contains("2/4"))
        assertTrue(format(counter, body, page = 1, total = 4).formattedText.contains("2/4"))
        assertTrue(format(counter, body, page = 2, total = 7).removedArtifacts.isEmpty())
    }

    @Test fun InteriorFractionsAndCountersSharingTextBlockAreRetained() {
        val body = block(line("分数举例", top = 300), line("2/4", top = 348, right = 150))
        assertTrue(format(body, page = 2, total = 4).formattedText.contains("2/4"))
        val shared = block(line("2/4", left = 850, top = 20, right = 930, height = 20), line("与正文同块", top = 300))
        assertTrue(format(shared, page = 2, total = 4).removedArtifacts.isEmpty())
    }

    @Test fun nearbyContentMakesCornerCounterAmbiguous() {
        val result = format(block(line("2/4", left = 850, top = 20, right = 930, height = 20)),
            block(line("分数：", left = 760, top = 20, right = 842, height = 20)), block(line("正文", top = 300)), page = 2, total = 4)
        assertTrue(result.formattedText.contains("2/4"))
        assertTrue(result.removedArtifacts.isEmpty())
    }

    @Test fun extendedThinStandaloneRuleIsRemovedButPipeGlyphAndLetterAreRetained() {
        val result = format(block(line("|", left = 10, top = 150, right = 13, height = 100)),
            block(line("|", left = 100, top = 400, right = 104)), block(line("I", left = 100, top = 500, right = 104, height = 100)),
            block(line("普通正文", top = 300)))
        assertEquals(listOf("isolated_vertical_rule"), result.removedArtifacts.map { it.reason })
        assertTrue(result.formattedText.contains("|"))
        assertTrue(result.formattedText.contains("I"))
    }

    @Test fun ruleInMixedSourceBlockIsRetainedConservatively() {
        val result = format(block(line("|", left = 10, top = 150, right = 13, height = 100), line("同块内容", top = 300)))
        assertTrue(result.removedArtifacts.isEmpty())
        assertTrue(result.formattedText.contains("|"))
    }

    @Test fun rawEngineTextAndSourceBlocksStayExact() {
        val raw = "  費規內\n\n错字不纠正\n2/4  "
        val blocks = listOf(block(line("費規內", top = 200), line("错字不纠正", top = 248)),
            block(line("2/4", left = 850, top = 20, right = 930, height = 20)))
        val before = blocks.map { it.copy(lines = it.lines.toList()) }
        val layout = OcrLayoutProcessor.process(raw, blocks, 1000, 1800, 2, 4)
        val result = OcrResult(raw, blocks, layout.formattedText, layout.removedArtifacts)
        assertEquals(raw, result.rawText)
        assertEquals(before, result.blocks)
        assertEquals("費規內错字不纠正", result.formattedText)
        assertEquals("spatial-1", result.layoutVersion)
    }

    @Test fun missingOrOutOfFrameGeometryFallsBackToRawText() {
        val raw = " 原始顺序\n\n 原空白 "
        assertEquals(raw, format(block(OcrTextLine("文字", 0, 0, 0, 0)), raw = raw).formattedText)
        assertEquals(raw, format(block(line("文字", left = -1, top = 200)), raw = raw).formattedText)
        assertEquals(raw, format(OcrTextBlock("无行坐标", emptyList()), raw = raw).formattedText)
    }

    @Test fun shortLineDoesNotBecomeAnInventedContinuation() {
        val result = format(block(line("短行", top = 200, right = 200), line("另一行很长", top = 248)))
        assertEquals("短行\n另一行很长", result.formattedText)
    }

    @Test fun listItemsKeepTheirBoundaries() {
        val result = format(block(line("1. 第一项", top = 200), line("2. 第二项", top = 248), line("• 第三项", top = 296)))
        assertEquals("1. 第一项\n\n2. 第二项\n\n• 第三项", result.formattedText)
    }

    @Test fun latinWordsKeepSpacesAndCjkDoesNotAcquireThem() {
        assertEquals("sample words continue", format(block(line("sample words", top = 200), line("continue", top = 248))).formattedText)
        assertEquals("正文token继续。", format(block(line("正文token", top = 200), line("继续。", top = 248))).formattedText)
    }

    @Test fun eachPageKeepsItsOwnContinuationAndNoPunctuationIsInvented() {
        val first = format(block(line("这一页最后是继", top = 200)))
        val next = format(block(line("续，但页面独立。", top = 200)))
        assertEquals("这一页最后是继", first.formattedText)
        assertEquals("续，但页面独立。", next.formattedText)
    }

    @Test fun duplicateRecognizedOccurrencesAreNotDeduplicated() {
        val result = format(block(line("相同内容", top = 200)), block(line("相同内容", top = 400)))
        assertEquals("相同内容\n\n相同内容", result.formattedText)
    }

    @Test fun counterOnlyImageIsTooAmbiguousToEmpty() {
        val result = format(block(line("2/4", left = 850, top = 20, right = 930, height = 20)), page = 2, total = 4)
        assertEquals("2/4", result.formattedText)
        assertTrue(result.removedArtifacts.isEmpty())
    }

    @Test fun invalidImageSizeFallsBackToRawWithoutThrowing() {
        assertEquals("原文", OcrLayoutProcessor.process("原文", listOf(block(line("文字", top = 200))), 0, 0).formattedText)
    }
}
