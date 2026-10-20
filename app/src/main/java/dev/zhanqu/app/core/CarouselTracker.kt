package dev.zhanqu.app.core

/** Pure state validation; the Android adapter performs a gesture only after receiving a step. */
class CarouselTracker(private val maxPages: Int = 50, private val maxRetries: Int = 2) {
    enum class Step { REWIND, CAPTURE, ADVANCE, FINISHED, ASK_USER, STOP }
    data class Decision(val step: Step, val reason: String? = null)
    private var total: Int? = null
    private var current: Int? = null
    private var expected: Int? = null
    private var rewinding = true
    private var retries = 0
    private val hashesByPage = linkedMapOf<Int, String>()
    val errors = mutableListOf<String>()
    val capturedPages: Set<Int> get() = hashesByPage.keys.toSet()
    val missingPages: List<Int> get() = total?.let { n -> (1..n).filter { it !in hashesByPage } }.orEmpty()
    val complete: Boolean get() = total != null && missingPages.isEmpty() && errors.isEmpty()

    fun observe(counter: PageCounter?): Decision {
        if (counter == null) return Decision(Step.ASK_USER, "无法可靠识别页码；请回到第 1 页后重试")
        if (counter.total > maxPages) return fail("图片总数超过采集上限")
        if (total != null && total != counter.total) return fail("图片总数发生变化，可能已经切换笔记")
        total = counter.total
        val previous = current
        val target = expected
        if (target != null && counter.current != target) {
            if (counter.current == previous) {
                retries++
                if (retries > maxRetries) return Decision(Step.ASK_USER, "滑动后页码未变化；已停止重试")
                return Decision(if (rewinding) Step.REWIND else Step.ADVANCE, "页码尚未变化，有限重试")
            }
            return fail("页码发生跳跃：期望 $target，实际 ${counter.current}")
        }
        current = counter.current
        expected = null
        retries = 0
        if (rewinding && counter.current > 1) {
            expected = counter.current - 1
            return Decision(Step.REWIND)
        }
        rewinding = false
        return when {
            counter.current !in hashesByPage -> Decision(Step.CAPTURE)
            counter.current == counter.total && complete -> Decision(Step.FINISHED)
            counter.current < counter.total -> {
                expected = counter.current + 1
                Decision(Step.ADVANCE)
            }
            else -> Decision(Step.STOP, "存在缺页，结果仅为部分采集")
        }
    }

    fun acceptCapture(counter: PageCounter, contentHash: String): Decision {
        if (contentHash.isBlank()) return fail("截图为空或没有有效指纹")
        if (counter.current != current || counter.total != total || expected != null) return fail("截图时页面状态已改变")
        if (hashesByPage.any { it.key != counter.current && it.value == contentHash }) return fail("不同页出现相同截图，请检查页面是否完成渲染")
        hashesByPage[counter.current] = contentHash
        return if (counter.current == counter.total) {
            if (complete) Decision(Step.FINISHED) else Decision(Step.STOP, "未从首页完整采集")
        } else {
            expected = counter.current + 1
            Decision(Step.ADVANCE)
        }
    }

    private fun fail(reason: String): Decision { errors += reason; return Decision(Step.STOP, reason) }
}

/** Stable-content exhaustion is explicitly different from an observed end-of-list marker. */
class CommentProgressTracker(private val maxScrolls: Int = 300, private val stableLimit: Int = 3) {
    enum class StopReason { CONTINUE, CONFIRMED_END, SUSPECTED_END, SCROLL_LIMIT }
    private var previousFingerprint: String? = null
    private var stableRounds = 0
    var scrollCount: Int = 0
        private set
    var endReached: Boolean = false
        private set
    fun afterRead(newComments: Int, viewportFingerprint: String, explicitEnd: Boolean, performedScroll: Boolean): StopReason {
        if (explicitEnd) { endReached = true; return StopReason.CONFIRMED_END }
        if (performedScroll) {
            scrollCount++
            stableRounds = if (newComments == 0 && viewportFingerprint == previousFingerprint) stableRounds + 1 else 0
        }
        previousFingerprint = viewportFingerprint
        if (stableRounds >= stableLimit) return StopReason.SUSPECTED_END
        if (scrollCount >= maxScrolls) return StopReason.SCROLL_LIMIT
        return StopReason.CONTINUE
    }
}
