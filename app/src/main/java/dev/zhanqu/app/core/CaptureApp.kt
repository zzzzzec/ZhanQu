package dev.zhanqu.app.core

/** Package routing only. Page recognition and workflows live in each app's own code. */
enum class CaptureApp(val packageName: String, val displayName: String) {
    XIAOHONGSHU("com.xingin.xhs", "小红书"),
    ZHIHU("com.zhihu.android", "知乎"),
    BILIBILI("tv.danmaku.bili", "B站");

    companion object {
        fun detect(packageName: String?): CaptureApp? = entries.firstOrNull { it.packageName == packageName }
    }
}
