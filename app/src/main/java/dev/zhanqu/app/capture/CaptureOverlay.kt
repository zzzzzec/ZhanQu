package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.*
import kotlin.math.abs
import dev.zhanqu.app.core.OverlayPlacement
import dev.zhanqu.app.core.CaptureControlPolicy

/** Single accessibility-owned, non-focusable window. No SYSTEM_ALERT_WINDOW permission. */
internal class CaptureOverlay(
    private val service: AccessibilityService,
    private val start: (CaptureOptions) -> Unit,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val stop: () -> Unit,
    private val openResults: () -> Unit
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var dragActive = false
    private var wrappedContent: LinearLayout? = null
    private var hiddenForCapture = false
    private var ownedWindowTitle: String? = null
    private var latest = CaptureUiState(connected = true)
    private var progress: TextView? = null
    private var counts: TextView? = null
    private var badge: TextView? = null
    private var bar: ProgressBar? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var startButton: Button? = null
    private var images = true
    private var comments = false
    private var replies = false
    private var lastViewport: Rect? = null
    private var positionX: Int? = null
    private var positionY = dp(96)
    private val ink = Color.rgb(28, 47, 45)
    private val muted = Color.rgb(91, 111, 106)
    private val green = Color.rgb(26, 105, 84)
    private val cream = Color.rgb(248, 250, 246)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun registerWindow() {
        val node = root?.createAccessibilityNodeInfo()
        val id = try { node?.windowId ?: -1 } finally { @Suppress("DEPRECATION") node?.recycle() }
        if (id >= 0) ownedWindowTitle?.let { WindowSafety.registerOwnOverlay(it, id) }
    }
    fun show(expand: Boolean = true) { expanded = expand; render() }
    fun hide() {
        root?.let { runCatching { windows.removeView(it) } }; root = null; params = null; dragActive = false; wrappedContent = null
        ownedWindowTitle?.let(WindowSafety::retireOwnOverlay); ownedWindowTitle = null; clearReferences()
    }
    fun temporarilyHide(hidden: Boolean) { registerWindow(); hiddenForCapture = hidden; root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    fun update(state: CaptureUiState) {
        val changedMode = latest.running != state.running; latest = state
        if (changedMode && root != null) render() else updateLabels()
    }
    private fun updateLabels() {
        badge?.text = CaptureControlPolicy.status(latest.running, latest.paused, latest.stage)
        progress?.text = buildString { append(latest.message); latest.error?.takeIf { it != latest.message }?.let { append("\n$it") } }
        progress?.setTextColor(if (latest.error != null) Color.rgb(153, 71, 41) else muted)
        counts?.text = "图页  ${latest.pagesCaptured} / ${latest.reportedPages ?: "—"}     评论  ${latest.commentsCaptured}"
        pauseButton?.text = if (latest.paused) "继续" else "暂停"
        pauseButton?.isEnabled = CaptureControlPolicy.canControl(latest.running, latest.stage); stopButton?.isEnabled = CaptureControlPolicy.canControl(latest.running, latest.stage)
        startButton?.isEnabled = CaptureControlPolicy.canStart(latest.running, images, comments)
        listOfNotNull(startButton, pauseButton, stopButton).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        bar?.apply {
            val total = latest.reportedPages
            isIndeterminate = latest.running && !latest.paused && (total == null || total <= 0)
            max = total?.coerceAtLeast(1) ?: 1; progress = latest.pagesCaptured.coerceAtMost(max)
        }
        refitLater()
    }
    private fun label(value: String, size: Float = 13f, color: Int = ink) = TextView(service).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4)) }
    private fun button(value: String, primary: Boolean = false, click: () -> Unit) = Button(service).apply {
        text = value; textSize = 14f; isAllCaps = false; minHeight = dp(48); minimumWidth = 0
        setPadding(dp(12), dp(4), dp(12), dp(4)); setTextColor(if (primary) Color.WHITE else green)
        background = rounded(if (primary) green else Color.rgb(231, 239, 232), 14); setOnClickListener { click() }
    }
    private fun column() = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addSpaced(view: View) { addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
    private fun clearReferences() { progress = null; counts = null; badge = null; bar = null; pauseButton = null; stopButton = null; startButton = null }
    private fun render() {
        dragActive = false
        clearReferences()
        val panel = root ?: column().apply {
            elevation = dp(10).toFloat(); background = rounded(cream)
            viewTreeObserver.addOnGlobalLayoutListener {
                val area = WindowSafety.contentViewport(service)
                if (root === this && lastViewport != area) {
                    lastViewport = Rect(area)
                    post { if (root === this) render() }
                }
            }
            addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                if (r - l != oldR - oldL || b - t != oldB - oldT) post { if (root === this) clampPosition() }
            }
        }.also { root = it }
        wrappedContent = null
        panel.removeAllViews(); panel.setPadding(dp(12), dp(8), dp(12), dp(12))
        val header = row()
        val handle = column().apply {
            addView(label("⠿  粘取", 17f).apply { setTypeface(typeface, Typeface.BOLD) })
            badge = label("", 11f, green); addView(badge)
            contentDescription = "粘取控制栏，拖动可移动，轻点${if (expanded) "收起" else "展开"}"; minimumHeight = dp(48)
        }
        attachDrag(handle); header.addView(handle, LinearLayout.LayoutParams(0, -2, 1f))
        if (expanded) header.addView(button("收起") { show(false) })
        else if (latest.running) { pauseButton = button(if (latest.paused) "继续" else "暂停") { togglePause() }; header.addView(pauseButton) }
        panel.addView(header)
        if (expanded) {
            val body = column()
            counts = label("", 15f).apply { setTypeface(typeface, Typeface.BOLD) }; body.addSpaced(counts!!)
            bar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply { progressTintList = ColorStateList.valueOf(green); indeterminateTintList = ColorStateList.valueOf(green) }
            body.addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6); bottomMargin = dp(8) })
            progress = label("", 13f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }; body.addView(progress)
            if (!latest.running) {
                body.addSpaced(label("采集内容", 12f, muted))
                fun toggle(title: String, checked: Boolean) = CheckBox(service).apply { text = title; textSize = 14f; setTextColor(ink); isChecked = checked; minHeight = dp(48); buttonTintList = ColorStateList.valueOf(green) }
                val imageToggle = toggle("图文 · 离线 OCR", images)
                val commentToggle = toggle("评论", comments)
                val replyToggle = toggle("展开楼中楼回复", replies)
                imageToggle.setOnCheckedChangeListener { _, value -> images = value; updateLabels() }
                commentToggle.setOnCheckedChangeListener { _, value -> comments = value; if (!value) replyToggle.isChecked = false; updateLabels() }
                replyToggle.setOnCheckedChangeListener { _, value -> replies = value; if (value) commentToggle.isChecked = true }
                body.addView(imageToggle); body.addView(commentToggle); body.addView(replyToggle)
                body.addView(label("先打开目标笔记，确认页面后开始。\n仅在本机处理，不上传页面内容。", 12f, muted))
            } else body.addView(label("拖动控制栏会先暂停。继续前请确认原笔记。", 12f, muted))
            val scroll = ScrollView(service).apply { addView(body) }
            panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
            if (latest.running) {
                val actions = row()
                pauseButton = button(if (latest.paused) "继续" else "暂停", true) { togglePause() }
                stopButton = button("结束并保存") { if (CaptureControlPolicy.canControl(latest.running, latest.stage)) stop() }
                actions.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
                actions.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1.3f)); panel.addSpaced(actions)
            } else {
                startButton = button("开始提取", true) { if (CaptureControlPolicy.canStart(latest.running, images, comments)) { show(false); start(CaptureOptions(images, comments, replies)) } }
                panel.addSpaced(startButton!!)
                val footer = row()
                footer.addView(button("笔记库") { openResults() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
                footer.addView(button("隐藏") { hide() }, LinearLayout.LayoutParams(0, -2, 1f)); panel.addSpaced(footer)
            }
        }
        updateLabels()
        val viewport = WindowSafety.contentViewport(service)
        lastViewport = Rect(viewport)
        val width = minOf(dp(if (expanded) 300 else if (latest.running) 188 else 112), viewport.width() - dp(16)).coerceAtLeast(1)
        fitPanel(panel, width, viewport)
        val existing = params
        if (existing == null) {
            val identity = "ZhanQuOverlay:" + java.util.UUID.randomUUID().toString(); ownedWindowTitle = identity; WindowSafety.registerOwnOverlay(identity)
            params = WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { title = identity; gravity = Gravity.TOP or Gravity.LEFT; x = positionX ?: (viewport.right - width - dp(8)); y = positionY.coerceAtLeast(viewport.top + dp(8)) }
            windows.addView(panel, params)
        } else { existing.width = width; windows.updateViewLayout(panel, existing) }
        panel.visibility = if (hiddenForCapture) View.INVISIBLE else View.VISIBLE
        panel.post { if (root === panel) { clampPosition(); registerWindow() } }
    }
    private fun refitLater() {
        root?.let { panel -> panel.post {
            if (root === panel && expanded && !dragActive) {
                val width = params?.width ?: return@post
                fitPanel(panel, width, WindowSafety.contentViewport(service))
                panel.requestLayout()
            }
        } }
    }
    private fun fitPanel(panel: LinearLayout, width: Int, viewport: Rect) {
        // Measure desired content, not the window's potentially clipped measured height.
        // On very small screens/huge fonts, make the whole panel scroll rather than lose actions.
        if (expanded && !dragActive) {
            val previousScrollY = if (wrappedContent != null) (panel.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
            wrappedContent?.let { content ->
                panel.removeAllViews()
                while (content.childCount > 0) {
                    val child = content.getChildAt(0); content.removeViewAt(0); panel.addView(child)
                }
                wrappedContent = null
            }
            (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<ScrollView>()
                .firstOrNull()?.layoutParams?.height = -2
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val available = (viewport.height() - dp(16)).coerceAtLeast(1)
            val details = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<ScrollView>().firstOrNull()
            if (details != null && panel.measuredHeight > available) {
                val fixedHeight = panel.measuredHeight - details.measuredHeight
                if (available - fixedHeight >= dp(64)) details.layoutParams.height = available - fixedHeight
                else {
                    val content = column()
                    while (panel.childCount > 0) {
                        val child = panel.getChildAt(0); panel.removeViewAt(0); content.addView(child)
                    }
                    wrappedContent = content
                    val all = ScrollView(service).apply { addView(content) }
                    all.post { if (root === panel && all.parent === panel) all.scrollTo(0, previousScrollY) }
                    panel.addView(all, LinearLayout.LayoutParams(-1,
                        (available - panel.paddingTop - panel.paddingBottom).coerceAtLeast(1)))
                }
            }
        }
    }
    private fun togglePause() { if (CaptureControlPolicy.canControl(latest.running, latest.stage)) { if (latest.paused) resume() else pause() } }
    private fun clampPosition(snap: Boolean = false) {
        val view = root ?: return; val layout = params ?: return; val area = WindowSafety.contentViewport(service)
        val position = OverlayPlacement.constrain(layout.x, layout.y, view.width, view.height,
            area.left, area.top, area.right, area.bottom, dp(8), snap)
        layout.x = position.x; layout.y = position.y
        positionX = layout.x; positionY = layout.y; windows.updateViewLayout(view, layout)
    }
    private fun attachDrag(handle: View) {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var dragging = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        handle.setOnClickListener { show(!expanded) }
        handle.setOnTouchListener { view, event ->
            val layout = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragActive = true; downX = event.rawX; downY = event.rawY; originX = layout.x; originY = layout.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; if (latest.running && !latest.paused) pause() }
                    if (dragging) { layout.x = originX + dx.toInt(); layout.y = originY + dy.toInt(); clampPosition() }; true
                }
                MotionEvent.ACTION_UP -> { dragActive = false; if (dragging) clampPosition(true) else view.performClick(); refitLater(); true }
                MotionEvent.ACTION_CANCEL -> { dragActive = false; if (dragging) clampPosition(true); refitLater(); true }
                else -> false
            }
        }
    }
}
