package dev.zhanqu.app.core

/** Presentation-only decisions; service/runner remain the authority for capture safety. */
internal object CaptureControlPolicy {
    fun canStart(running: Boolean, images: Boolean, comments: Boolean) = !running && (images || comments)
    fun canControl(running: Boolean, stage: String) = running && stage != "stopping"
    fun status(running: Boolean, paused: Boolean, stage: String): String = when {
        stage == "stopping" -> "正在保存"
        running && paused -> "已暂停"
        running -> "采集中"
        stage == "finished" -> "已保留结果"
        else -> "准备就绪"
    }
}
