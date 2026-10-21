package dev.zhanqu.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import dev.zhanqu.app.core.PageSnapshotArchive
import dev.zhanqu.app.core.UiBounds
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File

/** One explicit read, one screenshot, one verification read. Never scrolls, taps or sends data. */
internal class PageSnapshotExporter(private val service: AccessibilityService, private val overlayHidden: (Boolean) -> Unit) {
    suspend fun capture(validateSession: (String, Int) -> Unit): File = withTimeout(20_000) {
        // Activity events are intentionally not monitored for arbitrary apps while idle.
        val reader = UiTreeReader(service) { null }
        overlayHidden(true)
        var pending: File? = null
        try {
            val pkg = reader.foregroundPackage() ?: throw CaptureFailure("请保持要导出的应用页面在前台")
            if (pkg == service.packageName) throw CaptureFailure("请先打开要导出的应用页面")
            val before = reader.read(pkg, 2500)
            validateSession(pkg, before.windowId)
            delay(220)
            validateSession(pkg, before.windowId)
            val snapshot = before.snapshot
            if (snapshot.screenWidth.toLong() * snapshot.screenHeight > 16_000_000L)
                throw CaptureFailure("屏幕尺寸过大，无法安全生成快照")
            val window = service.windows.firstOrNull { it.id == before.windowId } ?: throw CaptureFailure("目标窗口已经消失")
            val bounds = Rect().also(window::getBoundsInScreen)
            val area = WindowSafety.visibleArea(service, bounds)
            val identity = WindowSafety.check(service, before.windowId)
            fun validate() {
                validateSession(pkg, before.windowId)
                if (!service.getSystemService(android.os.PowerManager::class.java).isInteractive ||
                    service.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
                    throw CaptureFailure("屏幕已锁定或关闭，本次快照已取消")
                if (reader.foregroundPackage() != pkg ||
                    WindowSafety.check(service, before.windowId) != identity)
                    throw CaptureFailure("页面或窗口已变化，请回到原页面后重试")
            }
            // Outer finally owns hiding; do not briefly restore between screenshot and final tree check.
            val crop = ScreenshotProvider(service) {}.capture(before.windowId, area, ::validate)
            val png = try {
                validate()
                val after = reader.read(pkg, 2500)
                if (after != before) throw CaptureFailure("页面内容在截图期间变化，请等页面稳定后重试")
                if (area.left < 0 || area.top < 0 || area.right > snapshot.screenWidth || area.bottom > snapshot.screenHeight ||
                    crop.width != area.width() || crop.height != area.height()) throw CaptureFailure("截图坐标变化，请重试")
                withContext(Dispatchers.Default) {
                    val full = Bitmap.createBitmap(snapshot.screenWidth, snapshot.screenHeight, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(full).drawBitmap(crop, area.left.toFloat(), area.top.toFloat(), null)
                        val bytes = object : ByteArrayOutputStream() {
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                require(count.toLong() + len <= PageSnapshotArchive.MAX_IMAGE_BYTES) { "截图大小超限" }
                                super.write(b, off, len)
                            }
                            override fun write(b: Int) {
                                require(count < PageSnapshotArchive.MAX_IMAGE_BYTES) { "截图大小超限" }; super.write(b)
                            }
                        }
                        check(full.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "截图编码失败" }
                        bytes.toByteArray()
                    } finally { full.recycle() }
                }
            } finally { crop.recycle() }
            validate()
            val id = System.currentTimeMillis()
            val json = PageSnapshotArchive.json(snapshot, id, UiBounds(area.left, area.top, area.right, area.bottom),
                mapOf("device" to Build.DEVICE, "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER,
                    "brand" to Build.BRAND, "sdkInt" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE))
            val exported = withContext(Dispatchers.IO) {
                val dir = File(service.cacheDir, "page-snapshots").apply { check(isDirectory || mkdirs()) }
                // Local cache only, bounded retention; never touches note storage.
                dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".zip") || it.name.endsWith(".partial")) &&
                    System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
                if (dir.listFiles().orEmpty().sumOf { it.length() } > 160L * 1024 * 1024)
                    throw CaptureFailure("快照缓存已满，请在系统应用设置中清除缓存后重试（不要清除应用数据）")
                val token = java.util.UUID.randomUUID().toString()
                val part = File(dir, "page-$id-$token.partial").also { pending = it }
                part.outputStream().use { PageSnapshotArchive.write(it, id, json, png) }
                currentCoroutineContext().ensureActive()
                File(dir, "page-$id-$token.zip").also { check(part.renameTo(it)) { "无法保存快照" }; pending = it }
            }
            currentCoroutineContext().ensureActive()
            pending = null
            exported
        } finally { pending?.delete(); overlayHidden(false) }
    }
}
