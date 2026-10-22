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
import dev.zhanqu.app.core.ForegroundWindowPolicy
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
    private var snapshotJob: Job? = null
    private var snapshotWindowEpoch = 0L
    private var snapshotTargetPackage: String? = null
    private var snapshotTargetWindowId = -1
    private var currentActivity: String? = null
    private var targetPackage: String? = null
    private var targetWindowId: Int? = null
    private var ownActionUntil = 0L
    private var destroyed = false
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { snapshotJob?.cancel(); pauseCapture("屏幕已关闭，请解锁并回到原页面后手动继续") }
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureHub.service = this
        reader = UiTreeReader(this) { currentActivity }
        overlay = CaptureOverlay(this, ::startCapture, { pauseCapture("用户暂停") }, ::resumeCapture, ::stopCapture, ::openResults, ::exportPageSnapshot)
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
            // Comment parent/reply rows use structural containers which Android may otherwise prune.
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
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
        if (snapshotJob?.let { !it.isCompleted } == true || captureJob?.let { !it.isCompleted } == true || (!options.images && !options.comments)) return
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
        targetWindowId = reader.foregroundWindowId()
        configureEvents(true)
        CaptureHub.mutableResult.value = null
        val newRunner = CaptureSessionRunner(profile, reader, GestureController(this, ::markOwnAction) { hidden -> overlay.temporarilyHide(hidden) },
            ScreenshotProvider(this) { hidden -> overlay.temporarilyHide(hidden) }, ::markOwnAction,
            { state -> if (!destroyed) CaptureHub.mutableState.value = state.copy(error = CaptureHub.storageError.value ?: state.error) },
            { note -> if (!destroyed) CaptureHub.mutableResult.value = note; CapturePersistence.enqueue(applicationContext, note) })
        runner = newRunner
        captureJob = scope.launch {
            try { newRunner.run(options) }
            finally { runner = null; targetPackage = null; targetWindowId = null; if (!destroyed) configureEvents(false) }
        }
    }
    private fun exportPageSnapshot() {
        if (destroyed || captureJob?.let { !it.isCompleted } == true || snapshotJob?.let { !it.isCompleted } == true) return
        if (!getSharedPreferences("privacy", Context.MODE_PRIVATE).getBoolean("disclosure", false)) {
            android.widget.Toast.makeText(this, "请先在粘取首页同意无障碍用途与隐私说明", android.widget.Toast.LENGTH_LONG).show()
            openResults(); return
        }
        snapshotJob = scope.launch {
            val previous = CaptureHub.mutableState.value
            CaptureHub.mutableState.value = previous.copy(snapshotting = true, message = "正在生成页面快照", error = null)
            configureEvents(true)
            // Establish the target and epoch before the exporter first yields.
            var epoch: Long? = null
            try {
                val file = PageSnapshotExporter(this@ZhanQuAccessibilityService) { overlay.temporarilyHide(it) }.capture { pkg, windowId ->
                    if (destroyed) throw CancellationException("Service destroyed")
                    val expected = epoch
                    if (expected == null) {
                        snapshotTargetPackage = pkg
                        snapshotTargetWindowId = windowId
                        epoch = snapshotWindowEpoch
                    }
                    else if (expected != snapshotWindowEpoch) throw CaptureFailure("页面发生切换，请稳定后重试")
                }
                CaptureHub.mutableState.value = previous.copy(message = "页面快照已保存在本机", error = null)
                startActivity(Intent(this@ZhanQuAccessibilityService, dev.zhanqu.app.PageSnapshotActivity::class.java)
                    .putExtra("snapshotFile", file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: TimeoutCancellationException) {
                if (!destroyed) {
                    CaptureHub.mutableState.value = previous.copy(message = "生成页面快照超时，请重试", error = "生成超时")
                    android.widget.Toast.makeText(this@ZhanQuAccessibilityService, "生成页面快照超时，请重试", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                if (!destroyed) CaptureHub.mutableState.value = previous.copy(message = "页面快照已取消")
                throw e
            } catch (e: Exception) {
                if (!destroyed) {
                    val message = e.message ?: "页面快照生成失败，请重试"
                    CaptureHub.mutableState.value = previous.copy(message = message, error = message)
                    android.widget.Toast.makeText(this@ZhanQuAccessibilityService, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                snapshotTargetPackage = null
                snapshotTargetWindowId = -1
                if (!destroyed) { configureEvents(false); CaptureHub.mutableState.value = CaptureHub.mutableState.value.copy(snapshotting = false) }
            }
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
        val windowStateEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val windowsEvent = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val foregroundPackage = if (windowStateEvent || windowsEvent) reader.foregroundPackage() else null
        val foregroundWindowId = if (windowStateEvent || windowsEvent) reader.foregroundWindowId() else null
        fun changedTarget(expectedPackage: String, expectedWindowId: Int): Boolean {
            if (foregroundPackage != expectedPackage || foregroundWindowId != expectedWindowId) return true
            return windowStateEvent && ForegroundWindowPolicy.changesSnapshotTarget(pkg, event.windowId,
                expectedPackage, expectedWindowId, foregroundPackage, foregroundWindowId, packageName,
                windows.firstOrNull { it.id == event.windowId }?.let { it.isActive || it.isFocused })
        }
        val snapshotPackage = snapshotTargetPackage
        if (snapshotJob?.isActive == true && snapshotPackage != null && (windowStateEvent || windowsEvent) &&
            changedTarget(snapshotPackage, snapshotTargetWindowId)) snapshotWindowEpoch++
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == "com.xingin.xhs") {
            val className = event.className?.toString()
            if (className?.endsWith("Activity") == true) currentActivity = className
        }
        if (captureJob?.isActive != true) return
        val capturePackage = targetPackage
        val captureWindow = targetWindowId
        if ((windowStateEvent || windowsEvent) && capturePackage != null && captureWindow != null &&
            changedTarget(capturePackage, captureWindow)) {
            pauseCapture("目标页面或前台焦点已变化，请返回原页面后手动继续")
        }
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            pauseCapture("检测到触摸操作，已暂停；确认页面后手动继续")
        } else if (pkg != packageName && SystemClock.uptimeMillis() > ownActionUntil &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            pauseCapture("检测到非采集操作，已暂停；确认页面后手动继续")
        }
    }
    override fun onInterrupt() { snapshotJob?.cancel(); pauseCapture("系统中断了无障碍服务，请确认页面后继续") }
    override fun onDestroy() {
        destroyed = true
        snapshotJob?.cancel()
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
