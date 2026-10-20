package dev.zhanqu.app.core

/** Presentation only. Never changes capture safety or authorizes a new capture. */
internal object CompactOverlayPolicy {
    const val ENTRY_WIDTH_DP = 56
    const val PANEL_WIDTH_DP = 216
    const val TOUCH_TARGET_DP = 48

    fun status(running: Boolean, paused: Boolean, stage: String, error: String?): String = when {
        stage == "stopping" -> "保存中"
        error != null -> if (error.startsWith("窗口遮挡")) "有遮挡" else "需处理"
        running && paused -> "已暂停"
        running -> "采集中"
        stage == "finished" -> "已保存"
        else -> "就绪"
    }

    data class Selection(val images: Boolean = true, val comments: Boolean = false, val replies: Boolean = false) {
        fun toggleImages() = copy(images = !images)
        fun toggleComments() = copy(comments = !comments, replies = if (comments) false else replies)
        fun toggleReplies() = copy(replies = !replies, comments = comments || !replies)
    }
}
