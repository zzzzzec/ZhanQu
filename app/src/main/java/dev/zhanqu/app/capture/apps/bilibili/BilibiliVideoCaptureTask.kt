package dev.zhanqu.app.capture.apps.bilibili

import dev.zhanqu.app.capture.*
import dev.zhanqu.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal class BilibiliVideoCaptureTask(
    private val read: () -> LiveScreen,
    private val click: (UiNode, Int) -> Boolean,
    private val scroll: suspend (UiBounds, Int) -> Boolean,
    private val update: (CaptureUiState) -> Unit,
    private val publish: (CapturedNote) -> Unit,
    private val scrollBackward: suspend (UiBounds, Int) -> Boolean = { _, _ -> false }
) : CaptureTask {
    private val paused = MutableStateFlow(false)
    private var note = CapturedNote(sourcePackage = CaptureApp.BILIBILI.packageName, captureKind = "bilibili_video")
    private val comments = linkedMapOf<String, ParsedComment>()
    private val completedThreads = mutableSetOf<String>()
    private val attemptedThreads = mutableSetOf<String>()
    private val errors = linkedSetOf<String>()
    private var window = -1
    private var width = 0
    private var height = 0
    private var reconfirm = false
    private var mainEnd = false
    private var stage = "bilibili_video"
    private class Restart : Exception()

    override fun pause(reason: String) { if (!paused.value) { reconfirm = true; paused.value = true; status(reason) } }
    override fun resume() { paused.value = false }
    private fun status(message: String, running: Boolean = true, error: String? = null) = update(CaptureUiState(
        connected = true, running = running, paused = paused.value, detectedApp = CaptureApp.BILIBILI,
        stage = stage, message = message, commentsCaptured = comments.size, error = error))
    private fun save(state: String = "partial") {
        if (note.title.isBlank()) return
        publish(note.copy(comments = comments.values.mapIndexed { i, c -> c.copy(captureOrder = i) }, status = state,
            endReached = mainEnd, expandedReplyGroups = completedThreads.size,
            unexpandedGroups = (attemptedThreads - completedThreads).size, errors = errors.toList()))
    }
    private fun sameVideo(page: BilibiliVideoPage): Boolean = page.title == note.title && page.author == note.author &&
        (note.video?.bvid.isNullOrBlank() || page.video.bvid == note.video?.bvid)
    private suspend fun ready(): LiveScreen {
        while (true) {
            currentCoroutineContext().ensureActive()
            paused.first { !it }
            try {
                val live = read()
                if (live.windowId != window || live.snapshot.screenWidth != width || live.snapshot.screenHeight != height)
                    throw CaptureFailure("窗口变化，请返回原视频简介后继续")
                if (live.snapshot.hasSensitiveFields()) throw CaptureFailure("请完成验证后继续")
                if (reconfirm) {
                    val page = BilibiliPageReader.video(live.snapshot)
                    if (page == null || !sameVideo(page)) throw CaptureFailure("请返回原视频，展开简介后继续")
                    reconfirm = false
                    throw Restart()
                }
                return live
            } catch (e: CaptureFailure) { pause(e.message.orEmpty()) }
        }
    }
    private suspend fun tap(node: UiNode) {
        val fresh = ready()
        if (!click(node, fresh.windowId)) throw CaptureFailure("页面已变化，未点击目标控件")
        delay(650)
    }
    private suspend fun move(region: UiBounds) {
        ready()
        if (!scroll(region, window)) throw CaptureFailure("滚动未完成")
        delay(650)
    }
    private suspend fun returnToFirst(first: ParsedComment, parent: ParsedComment? = null) {
        // Folded comments may be inserted above the current viewport, in chronological order.
        for (step in 0 until 100) {
            val page = BilibiliPageReader.comments(ready().snapshot, parent) ?: throw CaptureFailure("评论列表不可用")
            if (page.firstComment?.author == first.author && page.firstComment?.text == first.text) return
            if (!scrollBackward(page.region, window)) throw CaptureFailure("无法返回评论顶部")
            delay(450)
        }
        throw CaptureFailure("返回评论顶部达到上限")
    }
    private suspend fun mainTop() {
        for (step in 0 until 100) {
            val page = BilibiliPageReader.comments(ready().snapshot) ?: throw CaptureFailure("评论列表不可用")
            if (page.detail) throw CaptureFailure("请关闭评论详情")
            if (page.atTop || (page.end && page.comments.isEmpty())) return
            status("正在返回评论顶部")
            if (!scrollBackward(page.region, window)) throw CaptureFailure("无法返回评论顶部")
            delay(450)
        }
        throw CaptureFailure("返回评论顶部达到上限")
    }
    private suspend fun intro(): BilibiliVideoPage {
        var live = ready()
        if (BilibiliPageReader.comments(live.snapshot)?.detail == true) throw CaptureFailure("请关闭评论详情后开始")
        if (BilibiliPageReader.video(live.snapshot) == null) {
            val tab = BilibiliPageReader.introTab(live.snapshot) ?: throw CaptureFailure("请打开 B 站视频详情页")
            tap(tab); live = ready()
        }
        var page = BilibiliPageReader.video(live.snapshot) ?: throw CaptureFailure("请滚回视频简介顶部")
        page.expand?.let { tap(it); page = BilibiliPageReader.video(ready().snapshot) ?: throw CaptureFailure("简介展开失败") }
        if (note.title.isNotBlank() && !sameVideo(page)) throw CaptureFailure("视频已变化，采集已停止")
        if (page.video.bvid.isBlank()) throw CaptureFailure("未读到视频 BV 号，请展开完整简介")
        return page
    }
    override suspend fun run() {
        try {
            val initial = read()
            window = initial.windowId; width = initial.snapshot.screenWidth; height = initial.snapshot.screenHeight
            withTimeout(10 * 60 * 1000L) {
                while (true) {
                    try { capture(); break } catch (_: Restart) {
                        attemptedThreads.retainAll(completedThreads)
                        status("继续采集")
                    }
                }
            }
            finish(if (mainEnd && errors.isEmpty()) "complete" else "partial", if (mainEnd && errors.isEmpty()) "视频与评论已保存" else "已保存部分内容")
        } catch (_: TimeoutCancellationException) { errors += "采集超时"; finish("partial", "已保存部分内容") }
        catch (_: CancellationException) { finish("stopped", "已保存") }
        catch (e: Exception) { errors += e.message.orEmpty().ifBlank { "采集失败" }; finish("partial", errors.last(), errors.last()) }
    }
    private suspend fun capture() {
        stage = "bilibili_video"; status("正在采集视频信息")
        val page = intro()
        note = note.copy(title = page.title, author = page.author, body = page.video.description,
            video = page.video, displayedCommentCount = page.commentCount)
        save()
        tap(page.commentTab ?: throw CaptureFailure("未找到视频评论页"))
        stage = "bilibili_comments"
        mainTop()
        var previous = ""
        var unchanged = 0
        val expandedFooters = mutableSetOf<String>()
        var firstComment: ParsedComment? = null
        for (step in 0 until 100) {
            var current = BilibiliPageReader.comments(ready().snapshot) ?: throw CaptureFailure("未识别到评论列表")
            if (current.detail) throw CaptureFailure("评论列表发生变化")
            if (firstComment == null) firstComment = current.firstComment
            current.comments.forEach {
                if (!(it.textState == "preview" && it.parentId in attemptedThreads) && comments[it.id]?.textState != "complete") comments[it.id] = it
            }
            save(); status("评论 ${comments.values.count { !it.isReply }} · 回复 ${comments.values.count { it.isReply }}")
            // One expansion at a time; re-read the main list after returning before choosing another action.
            val link = current.replyLinks.firstOrNull { it.parent.id !in attemptedThreads }
            if (link != null) {
                attemptedThreads += link.parent.id
                tap(link.node)
                captureReplies(link)
                current = BilibiliPageReader.comments(ready().snapshot) ?: throw CaptureFailure("未返回主评论列表")
                if (current.detail) throw CaptureFailure("未关闭评论详情")
                continue
            }
            if (current.end || (note.displayedCommentCount == 0 && current.comments.isEmpty())) { mainEnd = true; break }
            val signature = current.comments.joinToString { it.id } + current.replyLinks.joinToString { it.parent.id }
            val more = current.loadMore
            if (more != null && expandedFooters.add(signature)) {
                tap(more); firstComment?.let { returnToFirst(it) }; previous = ""; unchanged = 0; continue
            }
            unchanged = if (signature == previous) unchanged + 1 else 0
            if (unchanged >= 3) { errors += "主评论滚动无进展，未读到结尾"; break }
            previous = signature
            if (step == 99) { errors += "达到主评论采集上限"; break }
            move(current.region)
        }
        // Check identity again after traversing the comment pages, including possible automatic next-video playback.
        intro()
    }
    private suspend fun captureReplies(link: BilibiliReplyLink) {
        var page = BilibiliPageReader.comments(ready().snapshot, link.parent)
            ?: throw CaptureFailure("未打开评论详情")
        if (!page.detail || page.firstComment?.author != link.parent.author || page.firstComment?.text != link.parent.text)
            throw CaptureFailure("评论楼层变化，未采集回复")
        comments.entries.removeAll { it.value.parentId == link.parent.id && it.value.textState == "preview" }
        val expected = page.replyCount ?: link.count
        var previous = ""
        var unchanged = 0
        var complete = false
        val expandedFooters = mutableSetOf<String>()
        for (step in 0 until 80) {
            page = BilibiliPageReader.comments(ready().snapshot, link.parent) ?: throw CaptureFailure("回复列表不可用")
            if (!page.detail) throw CaptureFailure("已离开评论详情")
            page.comments.forEach { comments[it.id] = it }
            val count = comments.values.count { it.parentId == link.parent.id }
            status("正在采集回复 · $count${expected?.let { "/$it" }.orEmpty()}")
            save()
            if (page.end || (expected != null && count >= expected)) { complete = true; break }
            val signature = page.comments.joinToString { it.id }
            val more = page.loadMore
            if (more != null && expandedFooters.add(signature)) {
                tap(more); returnToFirst(link.parent, link.parent); previous = ""; unchanged = 0; continue
            }
            unchanged = if (signature == previous) unchanged + 1 else 0
            if (unchanged >= 3) break
            previous = signature
            if (step < 79) move(page.region)
        }
        if (complete) completedThreads += link.parent.id else errors += "${link.parent.author}的回复未读到结尾"
        val close = BilibiliPageReader.comments(ready().snapshot, link.parent)?.close ?: throw CaptureFailure("未找到评论详情关闭按钮")
        tap(close)
        save()
    }
    private fun finish(state: String, message: String, error: String? = null) {
        stage = "finished"; paused.value = false; save(state); status(message, false, error)
    }
}
