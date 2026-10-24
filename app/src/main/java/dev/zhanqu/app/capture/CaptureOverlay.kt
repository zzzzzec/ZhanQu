package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
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
import dev.zhanqu.app.core.CompactOverlayPolicy
import dev.zhanqu.app.core.OverlayPresentationPolicy
import dev.zhanqu.app.core.CaptureApp
import dev.zhanqu.app.capture.apps.xiaohongshu.XiaohongshuCaptureOptions

/** Single accessibility-owned, non-focusable window. No SYSTEM_ALERT_WINDOW permission. */
internal class CaptureOverlay(
    private val service: AccessibilityService,
    private val start: (XiaohongshuCaptureOptions) -> Unit,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val stop: () -> Unit,
    private val openResults: () -> Unit,
    private val exportSnapshot: () -> Unit = {}
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var detailsExpanded = false
    private var infoButton: ImageButton? = null
    private var entryButton: ImageButton? = null
    private var dragActive = false
    private var renderPending = false
    private var wrappedContent: LinearLayout? = null
    private var hiddenForCapture = false
    private var ownedWindowTitle: String? = null
    private var latest = CaptureUiState(connected = true)
    private var progress: TextView? = null
    private var counts: TextView? = null
    private var badge: TextView? = null
    private var bar: ProgressBar? = null
    private var pauseButton: View? = null
    private var stopButton: View? = null
    private var startButton: View? = null
    private var snapshotButton: View? = null
    private var selection = CompactOverlayPolicy.Selection()
    private val images get() = selection.images
    private val comments get() = selection.comments
    private val replies get() = selection.replies
    private var lastViewport: Rect? = null
    private var dockEdge = OverlayPlacement.Edge.RIGHT
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
    fun show(expand: Boolean = false) { expanded = expand; if (!expand) detailsExpanded = false; render() }
    fun hide() {
        root?.let { runCatching { windows.removeView(it) } }; root = null; params = null; dragActive = false; renderPending = false; wrappedContent = null
        ownedWindowTitle?.let(WindowSafety::retireOwnOverlay); ownedWindowTitle = null; clearReferences()
    }
    fun temporarilyHide(hidden: Boolean) { registerWindow(); hiddenForCapture = hidden; root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    fun update(state: CaptureUiState) {
        val changedMode = latest.running != state.running || latest.detectedApp != state.detectedApp; latest = state
        if (changedMode && root != null) render() else updateLabels()
    }
    private fun updateLabels() {
        val status = CompactOverlayPolicy.status(latest.running, latest.paused, latest.stage, latest.error)
        badge?.text = status
        badge?.setTextColor(if (latest.error != null) Color.rgb(160, 75, 40) else green)
        entryButton?.contentDescription = "粘取，$status${if (latest.error != null) "，有异常" else ""}，轻点展开，拖动移动"
        entryButton?.setColorFilter(if (latest.error != null) Color.rgb(255, 191, 113) else Color.WHITE)
        root?.takeIf { !expanded }?.background = rounded(Color.argb(OverlayPresentationPolicy.entryOpacity(latest.running, latest.error != null), 39, 47, 48), 19)
        infoButton?.setColorFilter(if (latest.error != null) Color.rgb(160, 75, 40) else muted)
        progress?.text = buildString { append(latest.message); latest.error?.takeIf { it != latest.message }?.let { append("\n$it") } }
        progress?.setTextColor(if (latest.error != null) Color.rgb(153, 71, 41) else muted)
        counts?.text = if (latest.detectedApp == CaptureApp.ZHIHU) "${latest.answersCaptured} 条回答"
            else if (latest.detectedApp == CaptureApp.BILIBILI) "${latest.commentsCaptured} 条评论与回复"
            else OverlayPresentationPolicy.counts(latest.pagesCaptured, latest.reportedPages, latest.commentsCaptured)
        counts?.contentDescription = counts?.text
        pauseButton?.apply {
            contentDescription = if (latest.paused) "继续采集" else "暂停采集"
            tooltipText = contentDescription
            when (this) {
                is ImageButton -> setImageDrawable(OverlayIcon(if (latest.paused) "play" else "pause", Color.WHITE))
                is TextView -> { text = if (latest.paused) "继续" else "暂停"; setCompoundDrawables(glyph(if (latest.paused) "play" else "pause", Color.WHITE), null, null, null) }
            }
        }
        pauseButton?.isEnabled = CaptureControlPolicy.canControl(latest.running, latest.stage); stopButton?.isEnabled = CaptureControlPolicy.canControl(latest.running, latest.stage)
        snapshotButton?.isEnabled = !latest.running && !latest.snapshotting
        startButton?.isEnabled = canStart()
        listOfNotNull(startButton, snapshotButton, pauseButton, stopButton).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        bar?.apply {
            val total = latest.reportedPages
            isIndeterminate = latest.running && !latest.paused && (total == null || total <= 0)
            max = total?.coerceAtLeast(1) ?: 1; progress = latest.pagesCaptured.coerceAtMost(max)
        }
        refitLater()
    }
    private fun canStart() = !latest.snapshotting && !latest.running &&
        (latest.detectedApp != CaptureApp.XIAOHONGSHU || CaptureControlPolicy.canStart(false, images, comments))
    private fun label(value: String, size: Float = 13f, color: Int = ink) = TextView(service).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4)) }
    private fun icon(name: String, description: String, primary: Boolean = false, click: () -> Unit) = ImageButton(service).apply {
        contentDescription = description; tooltipText = description
        minimumWidth = dp(CompactOverlayPolicy.TOUCH_TARGET_DP); minimumHeight = dp(CompactOverlayPolicy.TOUCH_TARGET_DP)
        setPadding(dp(13), dp(13), dp(13), dp(13)); scaleType = ImageView.ScaleType.FIT_CENTER
        setImageDrawable(OverlayIcon(name, if (primary) Color.WHITE else ink))
        background = rounded(if (primary) green else Color.TRANSPARENT, 16)
        setOnClickListener { click() }
    }
    private fun glyph(name: String, color: Int) = OverlayIcon(name, color).apply { setBounds(0, 0, dp(20), dp(20)) }
    /** One accessible target includes both the glyph and its label. */
    private fun action(name: String, title: String, description: String = title, primary: Boolean = false,
                       vertical: Boolean = false, checked: Boolean? = null, click: () -> Unit) = TextView(service).apply {
        text = title; textSize = if (vertical) 12f else 14f
        gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val foreground = if (primary) Color.WHITE else if (checked == false) muted else green
        setTextColor(foreground)
        minimumHeight = dp(CompactOverlayPolicy.TOUCH_TARGET_DP)
        minimumWidth = dp(CompactOverlayPolicy.TOUCH_TARGET_DP)
        setPadding(dp(6), dp(4), dp(6), dp(4)); compoundDrawablePadding = dp(3)
        if (vertical) setCompoundDrawables(null, glyph(name, foreground), null, null)
        else setCompoundDrawables(glyph(name, foreground), null, null, null)
        val fill = if (primary) green else if (checked == true) Color.rgb(222, 239, 231) else Color.TRANSPARENT
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 26, 105, 84)), rounded(fill, 16), rounded(Color.WHITE, 16))
        isClickable = true; isFocusable = true
        contentDescription = description; tooltipText = description
        checked?.let { isSelected = it; stateDescription = if (it) "已开启" else "已关闭" }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = if (checked != null) "android.widget.ToggleButton" else "android.widget.Button"
                if (checked != null) { info.isCheckable = true; info.isChecked = checked }
            }
        }
        setOnClickListener { click() }
    }
    private fun column() = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.addSpaced(view: View) { addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
    private fun clearReferences() { progress = null; counts = null; badge = null; bar = null; pauseButton = null; stopButton = null; startButton = null; snapshotButton = null; infoButton = null; entryButton = null }
    private fun render() {
        // Keep the touched view and its gesture origin stable until UP/CANCEL.
        if (dragActive) { renderPending = true; return }
        renderPending = false
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
        panel.removeAllViews()
        panel.background = rounded(if (expanded) cream else Color.argb(OverlayPresentationPolicy.entryOpacity(latest.running, latest.error != null), 39, 47, 48), if (expanded) 26 else 19)
        panel.elevation = dp(if (expanded) 10 else 3).toFloat()
        panel.setPadding(dp(if (expanded) 6 else 2), dp(2), dp(if (expanded) 6 else 2), dp(if (expanded) 6 else 2))
        if (!expanded) {
            val entry = icon("entry", "粘取，轻点展开，拖动移动") { show(true) }
            entry.minimumWidth = dp(CompactOverlayPolicy.ENTRY_TARGET_DP); entry.minimumHeight = dp(CompactOverlayPolicy.ENTRY_TARGET_DP)
            entry.setPadding(dp(9), dp(9), dp(9), dp(9))
            entryButton = entry
            attachDrag(entry)
            panel.addView(entry, LinearLayout.LayoutParams(dp(CompactOverlayPolicy.ENTRY_TARGET_DP), dp(CompactOverlayPolicy.ENTRY_TARGET_DP)))
            // Running capture always exposes pause without first opening the menu.
            if (latest.running) {
                pauseButton = icon(if (latest.paused) "play" else "pause", "暂停或继续采集", true) { togglePause() }
                panel.addView(pauseButton, LinearLayout.LayoutParams(dp(CompactOverlayPolicy.ENTRY_TARGET_DP), dp(CompactOverlayPolicy.ENTRY_TARGET_DP)))
            }
        } else {
            val header = row()
            val handle = row().apply {
                val grip = ImageView(service).apply { setImageDrawable(OverlayIcon("grip", muted)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                addView(grip, LinearLayout.LayoutParams(dp(20), dp(24)))
                badge = label("", 11f, green).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                addView(badge, LinearLayout.LayoutParams(0, -2, 1f))
                minimumHeight = dp(40); contentDescription = "拖动移动悬浮窗，轻点收起"
            }
            attachDrag(handle); header.addView(handle, LinearLayout.LayoutParams(0, dp(40), 1f))
            infoButton = icon("info", "查看状态") { detailsExpanded = !detailsExpanded; render() }
            header.addView(infoButton, LinearLayout.LayoutParams(dp(40), dp(40)))
            header.addView(icon("collapse", "收起") { show(false) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            panel.addView(header)
            val body = column()
            if (latest.running || latest.pagesCaptured > 0 || latest.commentsCaptured > 0 || latest.answersCaptured > 0) {
                counts = label("", 11f, muted).apply { gravity = Gravity.CENTER; contentDescription = "已采图页、总页数与评论数" }
                body.addView(counts)
                bar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
                    progressTintList = ColorStateList.valueOf(green); indeterminateTintList = ColorStateList.valueOf(green)
                }
                body.addView(bar, LinearLayout.LayoutParams(-1, dp(3)).apply { bottomMargin = dp(6) })
            }
            if (detailsExpanded) {
                progress = label("", 12f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; setTextIsSelectable(true) }
                body.addView(progress)
            }
            if (!latest.running) {
                body.addView(label(when (latest.detectedApp) {
                    CaptureApp.XIAOHONGSHU -> "小红书 · 图文与评论"
                    CaptureApp.ZHIHU -> "知乎 · 回答"
                    CaptureApp.BILIBILI -> "B站 · 视频与评论"
                    null -> "打开要采集的页面"
                }, 12f))
            }
            if (!latest.running && latest.detectedApp == CaptureApp.XIAOHONGSHU) {
                val options = row()
                options.addView(action("image", "图文", "提取图文", vertical = true, checked = images) { selection = selection.toggleImages(); render() }, LinearLayout.LayoutParams(0, -2, 1f))
                options.addView(action("comment", "评论", "提取评论", vertical = true, checked = comments) { selection = selection.toggleComments(); render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
                options.addView(action("reply", "回复", "包含评论回复", vertical = true, checked = replies) { selection = selection.toggleReplies(); render() }, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(options)
            }
            panel.addView(ScrollView(service).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
            if (latest.running) {
                val actions = row()
                pauseButton = action(if (latest.paused) "play" else "pause", if (latest.paused) "继续" else "暂停", primary = true) { togglePause() }
                stopButton = action("stop", "保存并结束", "结束并保存", vertical = true) { if (CaptureControlPolicy.canControl(latest.running, latest.stage)) stop() }
                actions.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
                panel.addSpaced(actions)
            } else {
                startButton = action("play", "提取", "开始提取", primary = true) {
                    if (canStart()) {
                        show(false); start(XiaohongshuCaptureOptions(images, comments, replies))
                    }
                }
                val actions = row()
                actions.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(icon("library", "打开笔记库") { openResults() }, LinearLayout.LayoutParams(dp(48), dp(48)))
                actions.addView(icon("close", "隐藏悬浮入口") { hide() }, LinearLayout.LayoutParams(dp(48), dp(48)))
                panel.addSpaced(actions)
                snapshotButton = action("snapshot", "页面快照", "导出当前页面截图和节点 ZIP") {
                    if (!latest.running && !latest.snapshotting) exportSnapshot()
                }
                panel.addSpaced(snapshotButton!!)
            }
        }
        updateLabels()
        val viewport = WindowSafety.contentViewport(service)
        lastViewport = Rect(viewport)
        val width = minOf(dp(if (expanded) CompactOverlayPolicy.PANEL_WIDTH_DP else CompactOverlayPolicy.ENTRY_WIDTH_DP), viewport.width() - dp(16)).coerceAtLeast(1)
        fitPanel(panel, width, viewport)
        val existing = params
        if (existing == null) {
            val identity = "ZhanQuOverlay:" + java.util.UUID.randomUUID().toString(); ownedWindowTitle = identity; WindowSafety.registerOwnOverlay(identity)
            params = WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { title = identity; gravity = Gravity.TOP or Gravity.LEFT; x = OverlayPlacement.constrain(0, positionY, width, 0, viewport.left, viewport.top, viewport.right, viewport.bottom, dp(8), edge = dockEdge).x; y = positionY.coerceAtLeast(viewport.top + dp(8)) }
            windows.addView(panel, params)
        } else {
            existing.width = width
            // Use the requested width now; view.width may still describe the previous mode.
            clampPosition()
        }
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
        val width = layout.width.takeIf { it > 0 } ?: view.width
        if (snap) dockEdge = OverlayPlacement.nearestEdge(layout.x, width, area.left, area.right, dp(8))
        val position = OverlayPlacement.constrain(layout.x, layout.y, width, view.height,
            area.left, area.top, area.right, area.bottom, dp(8), edge = if (dragActive) null else dockEdge)
        layout.x = position.x; layout.y = position.y
        positionY = layout.y; windows.updateViewLayout(view, layout)
    }
    private fun finishDragLayout() {
        if (renderPending) render() else { clampPosition(); refitLater() }
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
                MotionEvent.ACTION_UP -> { dragActive = false; if (dragging) clampPosition(true) else view.performClick(); finishDragLayout(); true }
                MotionEvent.ACTION_CANCEL -> { dragActive = false; if (dragging) clampPosition(true); finishDragLayout(); true }
                else -> false
            }
        }
    }
}
