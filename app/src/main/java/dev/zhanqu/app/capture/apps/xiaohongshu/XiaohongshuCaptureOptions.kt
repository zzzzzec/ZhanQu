package dev.zhanqu.app.capture.apps.xiaohongshu

/** These choices belong to the XHS scene, never to the shared task lifecycle. */
internal data class XiaohongshuCaptureOptions(
    val images: Boolean = true, val comments: Boolean = false, val expandReplies: Boolean = false
)
