package dev.zhanqu.app.capture.apps.zhihu

import dev.zhanqu.app.core.CaptureApp
import dev.zhanqu.app.core.UiBounds
import dev.zhanqu.app.core.UiNode
import dev.zhanqu.app.core.UiSnapshot

internal data class ZhihuAnswerScreen(
    val question: String, val author: String, val paragraphs: List<String>,
    val imageDescriptions: List<String>, val imageCount: Int, val scrollRegion: UiBounds,
    val endReached: Boolean = false
)

/** Native answer-detail layout observed in a user-supplied snapshot. No broad text scraping. */
internal object ZhihuPageReader {
    fun read(snapshot: UiSnapshot): ZhihuAnswerScreen? {
        if (snapshot.packageName != CaptureApp.ZHIHU.packageName) return null
        fun ordered(roots: List<UiNode>): List<UiNode> = buildList {
            val pending = java.util.ArrayDeque<UiNode>()
            roots.asReversed().forEach(pending::addFirst)
            while (pending.isNotEmpty() && size < UiNode.MAX_NODES) {
                val node = pending.removeFirst(); add(node)
                node.children.asReversed().forEach(pending::addFirst)
            }
        }
        val nodes = ordered(snapshot.roots)
        fun UiNode.onScreen() = visible && !password && bounds.intersects(snapshot.displayBounds)
        fun textIn(node: UiNode?) = node?.let { ordered(listOf(it)) }?.filter { it.onScreen() }
            ?.mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
        fun first(id: String) = nodes.firstOrNull { it.shortViewId == id && it.onScreen() }
        val container = first("custom_recycler_view") ?: return null
        val content = ordered(listOf(container))
        // The follow button, author bio and vote counts are not answer content.
        val question = textIn(first("header_view")).firstOrNull().orEmpty()
        // A single RecyclerView can hold the current answer's footer and several following answers.
        // Document order matters: the footer BEFORE the next author header still belongs to this answer.
        val identity = content.firstOrNull { it.onScreen() && it.shortViewId in setOf("author_info_view", "bottom_left_follow_title") }
        val author = when (identity?.shortViewId) {
            "author_info_view" -> textIn(identity).firstOrNull().orEmpty()
            "bottom_left_follow_title" -> identity.text?.trim().orEmpty()
            else -> first("bottom_left_follow_title")?.text?.trim().orEmpty()
        }
        val start = if (identity?.shortViewId == "author_info_view") content.indexOf(identity) else 0
        val nextAuthor = content.indices.firstOrNull { it > start && content[it].shortViewId == "author_info_view" &&
            content[it].onScreen() && content[it] !== identity } ?: content.size
        val section = content.subList(start, nextAuthor)
        val endIndex = section.indexOfFirst { it.onScreen() && it.shortViewId in setOf("end_info_view", "out_comment_view") }
        val body = if (endIndex >= 0) section.take(endIndex) else section
        val top = first("toolbarContainer")?.bounds?.bottom ?: (snapshot.screenHeight / 10)
        val bottom = nodes.filter { it.shortViewId == "overlay_container" && it.onScreen() &&
            it.bounds.top > snapshot.screenHeight / 2 }.minOfOrNull { it.bounds.top }
            ?: (snapshot.screenHeight * 9 / 10)
        val region = container.bounds.intersection(UiBounds(0, top, snapshot.screenWidth, bottom))
        if (region.width < 80 || region.height < 160) return null
        val paragraphs = body.filter { it.shortViewId == "holder_text" && it.onScreen() }
            .flatMap { textIn(it) }
        val captions = body.filter { it.shortViewId == "image_describe" && it.onScreen() }
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
        val images = body.count { it.shortViewId == "image_normal" && it.onScreen() }
        if (author.isBlank() || (paragraphs.isEmpty() && images == 0 && endIndex < 0)) return null
        return ZhihuAnswerScreen(question, author, paragraphs, captions, images, region, endIndex >= 0)
    }
}

/** Consecutive overlap only: identical paragraphs elsewhere are retained, never globally deduplicated. */
internal class ZhihuParagraphs {
    private val paragraphs = mutableListOf<String>()
    fun append(next: List<String>) {
        val overlap = (minOf(paragraphs.size, next.size) downTo 1)
            .firstOrNull { count -> paragraphs.takeLast(count) == next.take(count) } ?: 0
        paragraphs += next.drop(overlap)
    }
    fun text(): String = paragraphs.joinToString("\n\n")
}
