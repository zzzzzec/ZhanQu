package dev.zhanqu.app.capture

import dev.zhanqu.app.capture.apps.zhihu.ZhihuAnswerCaptureTask
import dev.zhanqu.app.capture.apps.xiaohongshu.XiaohongshuCaptureOptions
import dev.zhanqu.app.capture.apps.xiaohongshu.XiaohongshuCaptureTask
import dev.zhanqu.app.core.BuiltInProfiles
import dev.zhanqu.app.core.CaptureApp
import dev.zhanqu.app.capture.apps.bilibili.BilibiliVideoCaptureTask
import android.view.accessibility.AccessibilityNodeInfo

/** Shared lifecycle only; deliberately has no selectors, page model or configurable steps. */
internal interface CaptureTask {
    suspend fun run()
    fun pause(reason: String)
    fun resume()
}

internal class CaptureTaskFactory(
    private val reader: UiTreeReader,
    private val gestures: GestureController,
    private val screenshots: ScreenshotProvider,
    private val ownAction: () -> Unit,
    private val update: (CaptureUiState) -> Unit,
    private val publish: (CapturedNote) -> Unit
) {
    fun create(app: CaptureApp, xiaohongshuOptions: XiaohongshuCaptureOptions): CaptureTask = when (app) {
        CaptureApp.XIAOHONGSHU -> XiaohongshuCaptureTask(
            xiaohongshuOptions, BuiltInProfiles.xiaohongshu(), reader, gestures, screenshots, ownAction, update, publish)
        CaptureApp.ZHIHU -> ZhihuAnswerCaptureTask(
            { reader.read(CaptureApp.ZHIHU.packageName) },
            { region, window -> gestures.swipe(region.androidRect(), false, true, window) }, update, publish)
        CaptureApp.BILIBILI -> BilibiliVideoCaptureTask(
            { reader.read(CaptureApp.BILIBILI.packageName) },
            { node, window -> ownAction(); reader.confirmsTap(CaptureApp.BILIBILI.packageName, window, node, node) &&
                reader.perform(CaptureApp.BILIBILI.packageName, window, node, AccessibilityNodeInfo.ACTION_CLICK) },
            { region, window -> gestures.swipe(region.androidRect(), false, true, window) }, update, publish,
            { region, window -> gestures.swipe(region.androidRect(), false, false, window) })
    }
}
