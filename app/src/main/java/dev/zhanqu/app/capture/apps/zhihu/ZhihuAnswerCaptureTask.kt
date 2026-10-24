package dev.zhanqu.app.capture.apps.zhihu

import dev.zhanqu.app.capture.*
import dev.zhanqu.app.core.CaptureApp
import dev.zhanqu.app.core.UiBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** First scene: the currently open answer under a question. Never uses XHS's carousel/comment flow. */
internal class ZhihuAnswerCaptureTask(
    private val readPage: () -> LiveScreen,
    private val scrollForward: suspend (UiBounds, Int) -> Boolean,
    private val update: (CaptureUiState) -> Unit,
    private val publish: (CapturedNote) -> Unit
) : CaptureTask {
    private val paused = MutableStateFlow(false)
    private var note = CapturedNote(sourcePackage = CaptureApp.ZHIHU.packageName, captureKind = "zhihu_answer")
    private var answer = CapturedAnswer()
    private val observations = mutableListOf<AnswerObservation>()
    private val paragraphs = ZhihuParagraphs()
    private val errors = mutableListOf<String>()
    private var windowId = -1
    private var width = 0
    private var height = 0
    private var reconfirm = false
    private var stage = "starting"

    override fun pause(reason: String) { if (paused.value) return; reconfirm = true; paused.value = true; status(reason) }
    override fun resume() { paused.value = false; status("正在核对原问题和回答作者") }
    private fun status(message: String, running: Boolean = true, error: String? = null) {
        update(CaptureUiState(connected = true, running = running, paused = paused.value, stage = stage,
            message = message, error = error, detectedApp = CaptureApp.ZHIHU,
            answersCaptured = if (observations.isEmpty()) 0 else 1))
    }
    private fun save(state: String = "partial") {
        val captured = answer.copy(text = paragraphs.text(), observations = observations.toList(),
            status = state, errors = errors.distinct())
        publish(note.copy(status = state, answers = if (observations.isEmpty()) emptyList() else listOf(captured),
            errors = errors.distinct()))
    }
    private fun parse(live: LiveScreen): ZhihuAnswerScreen {
        val snapshot = live.snapshot
        if (live.windowId != windowId || snapshot.screenWidth != width || snapshot.screenHeight != height)
            throw CaptureFailure("窗口或屏幕尺寸变化，请返回原知乎回答")
        val prompts = setOf("安全验证", "请完成验证", "人机验证", "请先登录", "身份验证", "操作过于频繁")
        if (snapshot.allNodes().any { it.password || (it.visible && it.text?.trim() in prompts) })
            throw CaptureFailure("出现登录或验证页面，请人工处理后继续")
        val page = ZhihuPageReader.read(snapshot) ?: throw CaptureFailure("未识别到知乎回答正文，请回到原回答")
        if (page.author != answer.author || (page.question.isNotBlank() && page.question != note.title))
            throw CaptureFailure("问题或回答作者变化，请返回原回答")
        if (reconfirm && page.question != note.title)
            throw CaptureFailure("请回到原标题可见的位置，再继续核对原回答")
        reconfirm = false
        return page
    }
    private suspend fun ready(): ZhihuAnswerScreen {
        while (true) {
            currentCoroutineContext().ensureActive()
            paused.first { !it }
            try { return parse(readPage()) }
            catch (e: CaptureFailure) { pause(e.message.orEmpty()) }
        }
    }
    override suspend fun run() {
        try {
            withTimeout(10 * 60 * 1000L) {
                status("正在识别知乎问题与当前回答")
                val initial = readPage()
                val first = ZhihuPageReader.read(initial.snapshot)
                    ?: throw CaptureFailure("请打开知乎问题下的一条回答，并保持标题和作者可见后开始")
                if (first.question.isBlank()) throw CaptureFailure("请先滚回回答顶部，显示问题标题后开始")
                windowId = initial.windowId; width = initial.snapshot.screenWidth; height = initial.snapshot.screenHeight
                note = note.copy(title = first.question, author = first.author)
                answer = answer.copy(author = first.author)
                stage = "zhihu_answer"
                var previous: ZhihuAnswerScreen? = null
                var unchanged = 0
                for (step in 0 until MAX_SCROLLS) {
                    val page = ready()
                    val same = page.paragraphs == previous?.paragraphs && page.imageDescriptions == previous?.imageDescriptions &&
                        page.imageCount == previous?.imageCount
                    unchanged = if (same) unchanged + 1 else 0
                    if (!same) {
                        observations += AnswerObservation(observations.size + 1, page.paragraphs, page.imageDescriptions, page.imageCount)
                        paragraphs.append(page.paragraphs)
                        save()
                    }
                    if (page.endReached) {
                        answer = answer.copy(endReached = true)
                        break
                    }
                    if (unchanged >= 2) {
                        errors += "滚动无进展，未读到回答结尾"
                        break
                    }
                    if (step == MAX_SCROLLS - 1) { errors += "达到滚动上限"; break }
                    previous = page
                    status("正在采集回答 · ${observations.size}")
                    // Fresh identity check immediately before the bounded gesture; no cross-answer navigation.
                    val fresh = ready()
                    if (!scrollForward(fresh.scrollRegion, windowId))
                        throw CaptureFailure("系统取消或拒绝滚动，已保留部分回答")
                    delay(650)
                }
                finish(if (answer.endReached && errors.isEmpty()) "complete" else "partial", if (answer.endReached) "回答已保存" else "已保存部分内容")
            }
        } catch (e: TimeoutCancellationException) {
            errors += "任务达到 10 分钟安全上限"; finish("partial", "已保存部分知乎回答")
        } catch (e: CancellationException) {
            finish("stopped", "已保存")
        } catch (e: Exception) {
            val message = e.message.orEmpty().ifBlank { "知乎采集失败" }.take(240)
            errors += message; finish("partial", message, message)
        }
    }
    private fun finish(state: String, message: String, error: String? = null) {
        stage = "finished"; paused.value = false; save(state); status(message, false, error)
    }
    private companion object { const val MAX_SCROLLS = 40 }
}
