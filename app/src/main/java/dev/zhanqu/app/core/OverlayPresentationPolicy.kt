package dev.zhanqu.app.core

/** Visual presentation only; never controls capture eligibility or window ownership. */
internal object OverlayPresentationPolicy {
    fun entryOpacity(running: Boolean, needsAttention: Boolean): Int =
        if (running || needsAttention) 230 else 160

    fun counts(pages: Int, reportedPages: Int?, comments: Int): String {
        val pageCount = pages.coerceAtLeast(0)
        val total = reportedPages?.takeIf { it > 0 && it >= pageCount }
        val pageLabel = if (total != null) "$pageCount/$total 页" else "$pageCount 页"
        return "$pageLabel · ${comments.coerceAtLeast(0)} 条评论"
    }
}
