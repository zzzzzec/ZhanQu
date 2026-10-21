package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ScreenshotProvider(private val service: AccessibilityService, private val hideOverlay: (Boolean) -> Unit) {
    suspend fun capture(windowId: Int, area: Rect, validate: () -> Unit): Bitmap {
        hideOverlay(true)
        try {
            delay(180)
            validate()
            val visibleArea = WindowSafety.visibleArea(service, area)
            val initialWindows = WindowSafety.check(service, windowId)
            var bitmap: Bitmap? = null
            val windowBounds = Rect()
            if (Build.VERSION.SDK_INT >= 34) {
                val window = service.windows.firstOrNull { it.id == windowId } ?: throw CaptureFailure("目标窗口已经消失")
                window.getBoundsInScreen(windowBounds)
            }
            for (attempt in 0..2) {
                try {
                    validate()
                    if (WindowSafety.check(service, windowId) != initialWindows)
                        throw CaptureFailure("截图重试前窗口状态变化，请确认原页面后继续")
                    bitmap = withTimeoutOrNull(5000) { captureOnce(windowId) } ?: throw CaptureFailure("系统截图回调超时，请稍后重试")
                    break
                }
                catch (e: ScreenshotException) {
                    if (e.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT || attempt == 2) throw CaptureFailure(e.message.orEmpty())
                    delay(450L * (attempt + 1)); validate()
                }
            }
            val full = bitmap ?: throw CaptureFailure("截图未返回图像")
            try {
                validate()
                if (WindowSafety.check(service, windowId) != initialWindows)
                    throw CaptureFailure("截图期间目标窗口发生变化，请确认页面后重试")
                val local = Rect(visibleArea)
                if (Build.VERSION.SDK_INT >= 34) {
                    if (full.width != windowBounds.width() || full.height != windowBounds.height())
                        throw CaptureFailure("窗口截图坐标不匹配，请退出分屏；本次不使用不确定的裁切结果")
                    local.offset(-windowBounds.left, -windowBounds.top)
                }
                if (local.left < 0 || local.top < 0 || local.right > full.width || local.bottom > full.height || local.isEmpty)
                    throw CaptureFailure("窗口尺寸变化或截图区域不匹配，请关闭分屏并恢复竖屏后继续")
                val cropped = Bitmap.createBitmap(full, local.left, local.top, local.width(), local.height())
                return if (cropped === full) full.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw CaptureFailure("无法复制截图区域") else cropped
            } finally { full.recycle() }
        } finally { hideOverlay(false) }
    }

    private suspend fun captureOnce(windowId: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: throw IllegalStateException("截图格式不可用")
                    val copy = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                    if (copy == null) throw IllegalStateException("无法复制截图")
                    if (continuation.isActive) continuation.resume(copy) { _, lostBitmap, _ -> lostBitmap.recycle() } else copy.recycle()
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                val message = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "系统截图限频，请稍后继续"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "系统没有授予无障碍截图权限"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "该窗口受系统安全保护，无法截图"
                    else -> "系统拒绝截图（错误 $errorCode），请检查安全窗口和无障碍权限"
                }
                if (continuation.isActive) continuation.resumeWithException(ScreenshotException(errorCode, message))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
    private class ScreenshotException(val code: Int, message: String) : Exception(message)
}
