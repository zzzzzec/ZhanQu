package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.zhanqu.app.MainActivity
import dev.zhanqu.app.core.BuiltInProfiles
import dev.zhanqu.app.core.CaptureProfile
import dev.zhanqu.app.data.NoteDatabase
import dev.zhanqu.app.data.StoredNote
import dev.zhanqu.app.export.NoteExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** The service is inert until the user explicitly presses Start on its own overlay. */
class ZhanQuAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reader: UiTreeReader
    private lateinit var overlay: CaptureOverlay
    private var runner: CaptureSessionRunner? = null
    private var captureJob: Job? = null
    private var currentActivity: String? = null
    private var targetPackage: String? = null
    private var ownActionUntil = 0L
    private var destroyed = false
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) pauseCapture("屏幕已关闭，请解锁并回到原页面后手动继续")
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureHub.service = this
        reader = UiTreeReader(this) { currentActivity }
        overlay = CaptureOverlay(this, ::startCapture, { pauseCapture("用户暂停") }, ::resumeCapture, ::stopCapture, ::openResults)
        configureEvents(false)
        CaptureHub.mutableState.value = CaptureUiState(connected = true, message = "打开小红书图文详情页，再在悬浮入口点击开始")
        scope.launch { CaptureHub.state.collect { overlay.update(it) } }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        showControls(false)
    }
    private fun configureEvents(running: Boolean) {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // Window-change events from other packages are required only while a user-owned session is active.
            packageNames = if (running) null else arrayOf("com.xingin.xhs", packageName)
            notificationTimeout = 100
        }
    }
    fun showControls() = showControls(true)
    private fun showControls(expanded: Boolean) {
        if (!::overlay.isInitialized) return
        runCatching { overlay.show(expanded) }.onFailure {
            CaptureHub.mutableState.value = CaptureHub.mutableState.value.copy(error = "系统未允许无障碍悬浮入口，请重新连接无障碍服务", message = "悬浮入口不可用")
        }
    }
    fun hideControls() { if (::overlay.isInitialized) overlay.hide() }
    private fun markOwnAction() { ownActionUntil = SystemClock.uptimeMillis() + 1300L }
    private fun startCapture(options: CaptureOptions) {
        if (captureJob?.let { !it.isCompleted } == true || (!options.images && !options.comments)) return
        if (!getSharedPreferences("privacy", Context.MODE_PRIVATE).getBoolean("disclosure", false)) {
            CaptureHub.mutableState.value = CaptureUiState(connected = true, message = "请先在粘取首页阅读并同意无障碍用途与隐私说明")
            openResults()
            return
        }
        val profile = BuiltInProfiles.xiaohongshu()
        if (reader.foregroundPackage() != profile.packageName) {
            CaptureHub.mutableState.value = CaptureUiState(connected = true, message = "请先打开小红书图文笔记详情页，再点击开始", error = "当前应用不在允许名单")
            return
        }
        targetPackage = profile.packageName
        configureEvents(true)
        CaptureHub.mutableResult.value = null
        val newRunner = CaptureSessionRunner(profile, reader, GestureController(this, ::markOwnAction) { hidden -> overlay.temporarilyHide(hidden) },
            ScreenshotProvider(this) { hidden -> overlay.temporarilyHide(hidden) }, ::markOwnAction,
            { state -> if (!destroyed) CaptureHub.mutableState.value = state.copy(error = CaptureHub.storageError.value ?: state.error) },
            { note -> if (!destroyed) CaptureHub.mutableResult.value = note; CapturePersistence.enqueue(applicationContext, note) })
        runner = newRunner
        captureJob = scope.launch {
            try { newRunner.run(options) }
            finally { runner = null; targetPackage = null; if (!destroyed) configureEvents(false) }
        }
    }
    fun pauseCapture(reason: String) { if (captureJob?.isActive == true) runner?.pause(reason) }
    fun resumeCapture() {
        if (captureJob?.isActive != true) return
        if (reader.foregroundPackage() != targetPackage) { pauseCapture("请先返回原目标应用，再点击继续"); return }
        markOwnAction()
        runner?.resume()
    }
    fun stopCapture() {
        if (captureJob?.let { !it.isCompleted } != true) return
        CaptureHub.mutableState.value = CaptureHub.mutableState.value.copy(message = "正在结束；等待正在处理的 OCR 完成并保存", stage = "stopping")
        captureJob?.cancel(CancellationException("User stopped capture"))
    }
    private fun openResults() {
        pauseCapture("已打开粘取，请回到原页面后手动继续")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == "com.xingin.xhs") {
            val className = event.className?.toString()
            if (className?.endsWith("Activity") == true) currentActivity = className
        }
        if (captureJob?.isActive != true) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == targetPackage) {
            pauseCapture("目标应用出现页面或窗口切换，请确认仍是原笔记后手动继续")
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val foreground = reader.foregroundPackage()
            if (foreground != targetPackage) pauseCapture("前台应用或系统窗口已变化，请返回原页面后手动继续")
        }
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            pauseCapture("检测到触摸操作，已暂停；确认页面后手动继续")
        } else if (pkg != packageName && SystemClock.uptimeMillis() > ownActionUntil &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            pauseCapture("检测到非采集操作，已暂停；确认页面后手动继续")
        }
    }
    override fun onInterrupt() { pauseCapture("系统中断了无障碍服务，请确认页面后继续") }
    override fun onDestroy() {
        destroyed = true
        captureJob?.cancel()
        if (::overlay.isInitialized) overlay.hide()
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        if (CaptureHub.service === this) CaptureHub.service = null
        CaptureHub.mutableState.value = CaptureHub.mutableState.value.copy(connected = false, running = false, paused = false, message = "无障碍服务已断开，已完成内容保留在本地笔记")
        // Persistence is process-owned, so the final stopped/OCR checkpoint can drain after teardown.
        scope.cancel()
        WindowSafety.clearOwnOverlays()
        super.onDestroy()
    }
}
