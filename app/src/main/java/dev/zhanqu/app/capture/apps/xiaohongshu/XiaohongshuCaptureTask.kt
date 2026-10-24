package dev.zhanqu.app.capture.apps.xiaohongshu

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.zhanqu.app.core.*
import dev.zhanqu.app.capture.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class XiaohongshuCaptureTask(
    private val options: XiaohongshuCaptureOptions,
    private val profile: CaptureProfile,
    private val reader: UiTreeReader,
    private val gestures: GestureController,
    private val screenshots: ScreenshotProvider,
    private val ownAction: () -> Unit,
    private val update: (CaptureUiState) -> Unit,
    private val publish: (CapturedNote) -> Unit
) : CaptureTask {
    private val paused = MutableStateFlow(false)
    private var needsIdentityReconfirmation = false
    private var note = CapturedNote(sourcePackage = profile.packageName, captureKind = "xhs_note")
    private var stage = "starting"
    private var pauseMessage = ""
    private var targetWindow = -1
    private var width = 0
    private var height = 0
    private var initialTitle = ""
    private var initialAuthor = ""
    private var expandedGroups = 0
    private var unexpandedGroups = 0
    private var endReached = false
    private val pages = mutableListOf<CapturedPage>()
    private val errors = mutableListOf<String>()
    private val comments = CommentAccumulator()

    override fun pause(reason: String) { needsIdentityReconfirmation = true; paused.value = true; pauseMessage = reason; status(reason) }
    override fun resume() { paused.value = false; status("正在确认当前页面") }
    private fun status(message: String, error: String? = null, running: Boolean = true) {
        update(CaptureUiState(true, running, paused.value, stage, message, pages.count { it.state == "complete" }, note.reportedPages, comments.comments.size, error))
    }
    private fun text(snapshot: UiSnapshot, locator: String): String = profile.find(snapshot, locator, false)
        .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }.distinct().joinToString("\n")

    override suspend fun run() {
        try {
            withTimeout(30 * 60 * 1000L) {
                val first = reader.read(profile.packageName)
                if (!profile.matches(first.snapshot)) throw CaptureFailure("当前页面与适配规则不匹配。请打开小红书图文笔记详情页")
                targetWindow = first.windowId; width = first.snapshot.screenWidth; height = first.snapshot.screenHeight
                initialTitle = text(first.snapshot, "title"); initialAuthor = text(first.snapshot, "author")
                if (initialTitle.isBlank() && initialAuthor.isBlank()) throw CaptureFailure("无法读取笔记标题或作者，不能确认所选笔记")
                validate(first)
                val reportedCount = profile.find(first.snapshot, "displayedCommentCount", false).asSequence()
                    .mapNotNull { Regex("共\\s*(\\d+)\\s*条评论").find(it.text.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
                note = note.copy(title = initialTitle, author = initialAuthor, body = text(first.snapshot, "body"), reportedPages = profile.pageCounter(first.snapshot)?.total, displayedCommentCount = reportedCount)
                publishPartial(); status("已确认目标页面，正在采集")
                if (options.images) captureImages()
                if (options.comments) captureComments(options.expandReplies)
                stage = "finished"
                // A successful traversal still does not assert total comments or OCR accuracy.
                val completeImages = !options.images || (note.reportedPages != null && pages.count { it.state == "complete" } == note.reportedPages && pages.map { it.pageIndex }.toSet().size == note.reportedPages)
                if (options.comments && note.displayedCommentCount != null && comments.comments.size != note.displayedCommentCount)
                    errors += "页面宣称评论数与实际提取数不一致，可能统计口径不同或仍有遗漏"
                if (options.comments && comments.unresolvedReplyCount > 0)
                    errors += "部分回复的父评论无法确定，层级完整性未确认"
                val completeComments = !options.comments || (endReached && unexpandedGroups == 0 && comments.unresolvedReplyCount == 0 &&
                    note.displayedCommentCount != null && comments.comments.size == note.displayedCommentCount)
                note = result(if (completeImages && completeComments && errors.isEmpty()) "complete" else "partial")
                publish(note)
                status("已保存", running = false)
            }
        } catch (e: TimeoutCancellationException) {
            errors += "任务达到 30 分钟安全上限，已保存部分结果"; finish("partial", errors.last())
        } catch (e: CancellationException) {
            finish("stopped", "已保存", error = null)
        } catch (e: Exception) {
            errors += (e.message ?: "采集失败，请重试当前页").take(240); finish("partial", errors.last())
        }
    }
    private fun finish(state: String, message: String, error: String? = message) {
        pages.replaceAll { if (it.state == "processing") it.copy(state = "interrupted", error = "本页 OCR 未完成，请重新采集") else it }
        stage = "finished"; paused.value = false; publish(result(state)); status(message, error, false)
    }
    private fun result(state: String = "partial") = note.copy(pages = pages.sortedBy { it.pageIndex }.toList(), comments = comments.comments.toList(), status = state,
        endReached = endReached, expandedReplyGroups = expandedGroups, unexpandedGroups = unexpandedGroups, errors = errors.distinct().toList())
    private fun publishPartial() { publish(result()) }

    private fun validate(screen: LiveScreen, imageStage: Boolean = stage != "comments" && stage != "comments_entry") {
        val s = screen.snapshot
        if (screen.windowId != targetWindow) throw CaptureFailure("页面窗口发生变化，请回到原笔记后继续")
        if (s.activityName != null && profile.activityPatterns.isNotEmpty() && profile.activityPatterns.none { Regex(it).matches(s.activityName) })
            throw CaptureFailure("页面 Activity 已变化，请返回原笔记后继续")
        if (s.screenWidth != width || s.screenHeight != height) throw CaptureFailure("屏幕方向或尺寸变化，请恢复原方向后继续")
        if (s.allNodes().any { it.password }) throw CaptureFailure("页面含敏感输入区域，已暂停")
        val safetyPrompts = setOf("安全验证", "请完成验证", "人机验证", "请输入支付密码", "身份验证", "操作过于频繁", "请先登录")
        if (s.allNodes().any { it.visible && it.text?.trim() in safetyPrompts }) throw CaptureFailure("出现验证、登录或安全提示，请人工处理后继续")
        val title = text(s, "title"); val author = text(s, "author")
        if (needsIdentityReconfirmation) {
            if ((initialTitle.isNotBlank() && title != initialTitle) || (initialAuthor.isNotBlank() && author != initialAuthor))
                throw CaptureFailure("暂停后需要重新核对原笔记，请回到原标题/作者可见的位置再恢复")
            needsIdentityReconfirmation = false
        }
        if (imageStage && ((initialTitle.isNotBlank() && title.isBlank()) || (initialAuthor.isNotBlank() && author.isBlank())))
            throw CaptureFailure("原笔记身份字段消失，已暂停以免采到其他页面")
        if (title.isNotBlank() && initialTitle.isNotBlank() && title != initialTitle) throw CaptureFailure("笔记标题变化，已暂停以免采到其他页面")
        if (author.isNotBlank() && initialAuthor.isNotBlank() && author != initialAuthor) throw CaptureFailure("笔记作者变化，已暂停")
        if (imageStage && !profile.matches(s)) throw CaptureFailure("已离开已选择的图文页面，请返回后继续")
        if (stage == "comments_entry" && !profile.matches(s) && profile.find(s, profile.commentFlow.scrollContainer, false).isEmpty() &&
            !(profile.find(s,"noteScroll").isNotEmpty() && ((author.isNotBlank() && author == initialAuthor) || (title.isNotBlank() && title == initialTitle))))
            throw CaptureFailure("评论入口页面身份无法确认，请返回原笔记")
        if (stage == "comments" && profile.find(s, profile.commentFlow.scrollContainer, false).isEmpty()) throw CaptureFailure("评论容器消失或页面发生跳转，请返回原页面")
    }
    private suspend fun ready(): LiveScreen {
        while (true) {
            currentCoroutineContext().ensureActive()
            paused.first { !it }
            try { return reader.read(profile.packageName).also { validate(it) } }
            catch (e: CaptureFailure) { pause(e.message.orEmpty()) }
        }
    }
    private suspend fun stableImage(): LiveScreen {
        var previous: String? = null
        val deadline = SystemClock.elapsedRealtime() + profile.limits.actionTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val screen = ready()
            val counter = profile.pageCounter(screen.snapshot)
            val bounds = profile.imageBounds(screen.snapshot)
            val loading = screen.snapshot.allNodes().any { it.visible && it.className?.endsWith("ProgressBar") == true }
            val image = profile.imageNode(screen.snapshot)
            if (!loading && image != null && ImageCapturePolicy.completeRegion(image, screen.snapshot.displayBounds) == null)
                throw CaptureFailure("当前图片没有完整显示或无法确认完整边界。请返回图文顶部、完整显示图片后重试；本次不将裁切图计为完成页")
            val signature = "$counter:$bounds"
            if (!loading && bounds != null && signature == previous) return screen
            previous = if (loading) null else signature
            delay(250)
        }
        throw CaptureFailure("图片布局或加载状态不稳定，请等待图片显示完整后继续")
    }
    private suspend fun movePage(from: PageCounter, forward: Boolean): PageCounter {
        val expected = from.current + if (forward) 1 else -1
        for (attempt in 0..2) {
            val fresh = ready()
            val counter = profile.pageCounter(fresh.snapshot) ?: throw CaptureFailure("图片页码不可识别，已停止自动翻页")
            if (counter.current == expected && counter.total == from.total) return counter
            if (counter != from) throw CaptureFailure("检测到跳页或总页数变化，已停止以免遗漏")
            val region = profile.find(fresh.snapshot, profile.imageFlow.gestureRegion).firstOrNull()?.bounds?.androidRect()
                ?: profile.imageBounds(fresh.snapshot)?.androidRect() ?: throw CaptureFailure("找不到可见图片翻页区域")
            if (!region.intersect(0, 0, width, height)) throw CaptureFailure("图片翻页区域不在屏幕内")
            status(if (forward) "正在翻到第 $expected 页" else "正在回到第 $expected 页")
            val accepted = gestures.swipe(region, true, forward, targetWindow)
            if (!accepted && attempt == 2) throw CaptureFailure("系统取消或拒绝滑动，请检查是否触摸了屏幕")
            val deadline = SystemClock.elapsedRealtime() + profile.limits.actionTimeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val after = ready()
                val next = profile.pageCounter(after.snapshot)
                if (next != null && next != from) {
                    if (next.current != expected || next.total != from.total) throw CaptureFailure("图片跳页，预期 $expected，实际 ${next.current}；结果保持不完整")
                    return next
                }
                delay(160)
            }
        }
        throw CaptureFailure("连续翻页失败；请手动切到第 $expected 页后重新开始，现有结果已保存")
    }
    private suspend fun captureImages() = coroutineScope {
        stage = "images"
        var current = profile.pageCounter(ready().snapshot) ?: throw CaptureFailure("无法确定图片页号，请先切到第 1 页。本版不推测未知页数")
        if (current.total !in 1..profile.limits.maxPages || current.current !in 1..current.total) throw CaptureFailure("页数超出规则允许范围")
        repeat(current.current - 1) { current = movePage(current, false) }
        if (current.current != 1) throw CaptureFailure("无法可靠回到首页，请手动切到第 1 页再开始")
        data class Frame(val index: Int, val total: Int, val bitmap: Bitmap, val hash: String)
        val queue = Channel<Frame>(1, onUndeliveredElement = { it.bitmap.recycle() })
        val worker = launch {
            val engine = MlKitChineseEngine()
            try {
                for (frame in queue) {
                    // A running ML Kit task retains its bitmap until its callback completes.
                    withContext(NonCancellable) {
                        try {
                            val recognized = engine.recognize(frame.bitmap, frame.index, frame.total)
                            val text = recognized.formattedText
                            val warning = if (text.isBlank()) "本页 OCR 未识别出文字，请人工检查" else null
                            pages.removeAll { it.pageIndex == frame.index }
                            pages += CapturedPage(frame.index, frame.total, text, frame.hash, if (warning == null) "complete" else "needs_review", warning, blocks = recognized.blocks, rawText = recognized.rawText, removedArtifacts = recognized.removedArtifacts, layoutVersion = recognized.layoutVersion, inputWidth = frame.bitmap.width, inputHeight = frame.bitmap.height)
                            if (warning != null) errors += "第 ${frame.index} 页：$warning"
                            publishPartial(); status("已识别 ${pages.count { it.state == "complete" }} / ${frame.total} 页")
                        } catch (e: Exception) {
                            pages.removeAll { it.pageIndex == frame.index }
                            pages += CapturedPage(frame.index, frame.total, "", frame.hash, "failed", "OCR 处理失败")
                            errors += "第 ${frame.index} 页 OCR 失败"; publishPartial()
                        } finally { frame.bitmap.recycle() }
                    }
                    ensureActive()
                }
            } finally { engine.close() }
        }
        val hashes = mutableSetOf<String>()
        try {
            while (true) {
                val screen = stableImage()
                val check = profile.pageCounter(screen.snapshot) ?: throw CaptureFailure("当前图片页码丢失")
                if (check != current) throw CaptureFailure("图片页码在截图前发生变化")
                val region = profile.imageNode(screen.snapshot)?.let { ImageCapturePolicy.completeRegion(it, screen.snapshot.displayBounds) }?.androidRect()
                    ?: throw CaptureFailure("图片未完整显示，本次不将裁切图计为完成页")
                status("正在截取第 ${current.current} / ${current.total} 页")
                val sessionContext = currentCoroutineContext()
                val bitmap = try {
                    screenshots.capture(targetWindow, region) {
                        sessionContext.ensureActive()
                        if (paused.value) throw CapturePausedException()
                        val actual = reader.read(profile.packageName); validate(actual)
                        if (profile.pageCounter(actual.snapshot) != current) throw CaptureFailure("截图期间发生翻页，已停止")
                        if (profile.imageBounds(actual.snapshot)?.androidRect() != region) throw CaptureFailure("截图期间图片区域变化，已停止以免裁切错误")
                        if (profile.imageNode(actual.snapshot)?.let { ImageCapturePolicy.completeRegion(it, actual.snapshot.displayBounds) }?.androidRect() != region)
                            throw CaptureFailure("截图期间图片完整边界变化，已停止以免遗漏")
                    }
                } catch (_: CapturePausedException) {
                    ready() // Resume is always an explicit user action; then obtain a new image.
                    continue
                }
                var sent = false
                try {
                    val hash = withContext(Dispatchers.Default) {
                        val pixels = ByteBuffer.allocate(bitmap.byteCount); bitmap.copyPixelsToBuffer(pixels)
                        MessageDigest.getInstance("SHA-256").digest(pixels.array()).joinToString("") { "%02x".format(it) }
                    }
                    if (paused.value) { ready(); continue }
                    currentCoroutineContext().ensureActive()
                    if (!hashes.add(hash)) throw CaptureFailure("第 ${current.current} 页与已有截图完全重复，请确认图片已加载；未将其计为新页")
                    pages += CapturedPage(current.current, current.total, "", hash, "processing")
                    publishPartial()
                    // Ownership transfers before send: cancelled send may already have delivered its element.
                    sent = true
                    queue.send(Frame(current.current, current.total, bitmap, hash))
                } finally { if (!sent && !bitmap.isRecycled) bitmap.recycle() }
                if (current.current == current.total) break
                current = movePage(current, true)
            }
            queue.close(); worker.join()
        } finally {
            queue.cancel(); worker.cancelAndJoin()
        }
    }
    private suspend fun captureComments(expand: Boolean) {
        stage = "comments_entry"
        enterCommentRegion()
        stage = "comments"
        var stable = 0
        var expansionFailures = 0
        val seenButtons = mutableSetOf<String>()
        for (scroll in 0 until profile.limits.maxScrolls) {
            var screen = ready()
            if (comments.comments.isEmpty() && XiaohongshuPageGeometry.hasEmptyComments(screen.snapshot)) {
                note = note.copy(displayedCommentCount = 0); endReached = true; break
            }
            val new = comments.merge(CommentParser.extract(screen.snapshot, profile))
            val count = profile.find(screen.snapshot, "displayedCommentCount", false).asSequence()
                .mapNotNull { Regex("共\\s*(\\d+)\\s*条评论").find(it.text.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
            if (count != null) note = note.copy(displayedCommentCount = count)
            publishPartial(); status("评论已提取 ${comments.comments.size} 条，页面显示 ${note.displayedCommentCount ?: "未知"}")
            val buttons = profile.find(screen.snapshot, profile.commentFlow.expandLocator)
            if (!expand) unexpandedGroups = maxOf(unexpandedGroups, profile.find(screen.snapshot, profile.commentFlow.expandLocator, false).size)
            if (expand && expansionFailures < profile.limits.maxExpandFailures) {
                for (button in buttons.take(10)) {
                    val key = "${button.path}:${button.text}:${comments.comments.lastOrNull()?.id}"
                    if (!seenButtons.add(key)) continue
                    screen = ready()
                    val fresh = profile.find(screen.snapshot, profile.commentFlow.expandLocator).firstOrNull { it.path == button.path && it.text == button.text } ?: continue
                    val actionNode = replyActionNode(screen.snapshot, fresh)
                    ownAction()
                    if (actionNode != null && click(actionNode)) {
                        val deadline = SystemClock.elapsedRealtime() + profile.limits.actionTimeoutMs
                        var added = 0
                        while (SystemClock.elapsedRealtime() < deadline && added == 0) {
                            delay(180)
                            val after = ready()
                            added += comments.merge(CommentParser.extract(after.snapshot, profile))
                        }
                        if (added > 0) expandedGroups++ else { unexpandedGroups++; expansionFailures++ }
                        publishPartial()
                    } else { expansionFailures++; unexpandedGroups++ }
                    if (expansionFailures >= profile.limits.maxExpandFailures) { errors += "部分楼中楼未能展开"; break }
                }
            }
            screen = ready()
            if (profile.find(screen.snapshot, "commentEnd").isNotEmpty()) {
                unexpandedGroups = maxOf(unexpandedGroups, profile.find(screen.snapshot, profile.commentFlow.expandLocator, false).size)
                endReached = true; break
            }
            val before = contentSignature(screen.snapshot)
            val list = profile.find(screen.snapshot, profile.commentFlow.scrollContainer).firstOrNull()
                ?: throw CaptureFailure("评论列表不在可见区域，请手动进入评论区再开始评论模式")
            ownAction()
            val accepted = if (list.scrollable) reader.perform(profile.packageName, targetWindow, list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) else false
            if (!accepted) {
                val r = XiaohongshuPageGeometry.commentScrollRegion(screen.snapshot, list)?.androidRect()
                    ?: throw CaptureFailure("评论可滑动区域太小，请手动上滑正文、露出评论区域后继续")
                if (!gestures.swipe(r, false, true, targetWindow)) { errors += "评论列表无法滚动"; break }
            }
            val deadline = SystemClock.elapsedRealtime() + profile.limits.actionTimeoutMs
            var after = screen
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(200); after = ready()
                if (contentSignature(after.snapshot) != before) break
            }
            // A native scroll can be accepted without moving a nested list. Verify before counting no-progress.
            if (accepted && contentSignature(after.snapshot) == before) {
                val fresh = ready()
                val freshList = profile.find(fresh.snapshot, profile.commentFlow.scrollContainer).firstOrNull()
                    ?: throw CaptureFailure("评论容器已变化")
                val region = XiaohongshuPageGeometry.commentScrollRegion(fresh.snapshot, freshList)
                    ?: throw CaptureFailure("评论可滑动区域太小，请手动露出评论后继续")
                if (!gestures.swipe(region.androidRect(), false, true, targetWindow)) throw CaptureFailure("系统拒绝评论滚动")
                delay(450); after = ready()
            }
            val added = comments.merge(CommentParser.extract(after.snapshot, profile))
            val unchanged = contentSignature(after.snapshot) == before
            stable = if (unchanged && new == 0 && added == 0) stable + 1 else 0
            if (stable >= profile.limits.maxConsecutiveNoNewContent) { errors += "连续滚动无新增内容，疑似列表底部，但未确认完整"; break }
            if (scroll == profile.limits.maxScrolls - 1) errors += "达到评论滚动上限，保留部分结果"
        }
        if (!expand && unexpandedGroups > 0) errors += "未启用展开楼中楼，部分回复可能未采集"
        publishPartial()
    }
    /** Move the declared note-scroll region only until the declared comment list is on screen. */
    private suspend fun enterCommentRegion() {
        var unchanged = 0
        repeat(10) { attempt ->
            val screen = ready()
            if (XiaohongshuPageGeometry.hasEmptyComments(screen.snapshot)) return
            val plan = CommentEntryPlanner.plan(screen.snapshot, profile, preferCommentButton = unchanged >= 2)
            if (plan == CommentEntryPlanner.Plan.Ready) return
            status("正在进入评论区域（${attempt + 1}/10）")
            val before = contentSignature(screen.snapshot)
            val moved = when (plan) {
                is CommentEntryPlanner.Plan.Scroll -> {
                    ownAction()
                    val native = plan.node.scrollable && reader.perform(profile.packageName,targetWindow,plan.node,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    native || gestures.swipe(plan.region.androidRect(),false,true,targetWindow)
                }
                is CommentEntryPlanner.Plan.Swipe -> gestures.swipe(plan.region.androidRect(),false,true,targetWindow)
                is CommentEntryPlanner.Plan.Click -> {
                    click(plan.node)
                }
                else -> throw CaptureFailure("未找到可确认的评论入口或笔记滚动区域；请手动进入评论区，现有图文已保留")
            }
            if (!moved) throw CaptureFailure("系统拒绝进入评论区域，现有图文已保留；可手动进入评论区后重试")
            val deadline = SystemClock.elapsedRealtime() + profile.limits.actionTimeoutMs
            var changed = false
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(180)
                val after = ready()
                if (profile.find(after.snapshot,profile.commentFlow.scrollContainer).isNotEmpty()) return
                if (contentSignature(after.snapshot) != before) { changed = true; break }
            }
            unchanged = if (changed) 0 else unchanged + 1
            if (unchanged >= 3) throw CaptureFailure("连续3次进入评论区未发生有效变化，已停止并保留图文；请手动定位评论区")
        }
        throw CaptureFailure("已达到进入评论区的10次滚动上限，保留已完成结果")
    }

    private fun click(action: UiNode): Boolean {
        ownAction()
        return reader.perform(profile.packageName, targetWindow, action, AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** A text label may delegate clicks to a nearby container, but never to the whole list or an unrelated row. */
    private fun replyActionNode(snapshot: UiSnapshot, label: UiNode): UiNode? {
        val lists = profile.find(snapshot, profile.commentFlow.scrollContainer, false)
        fun allowed(node: UiNode) = node.clickable && node.visible && node.bounds.intersects(snapshot.displayBounds) &&
            lists.any { node.path.startsWith(it.path + "/") }
        if (allowed(label)) return label
        return snapshot.ancestorsOf(label.path).asReversed().take(3).firstOrNull(::allowed)
    }
    private fun contentSignature(s: UiSnapshot): String = s.allNodes().filter { it.visible }.joinToString("|") { "${it.path}:${it.text}:${it.description}:${it.bounds}" }.hashCode().toString()
}
