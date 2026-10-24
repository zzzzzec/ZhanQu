package dev.zhanqu.app.capture.apps.bilibili

import dev.zhanqu.app.capture.CapturedVideo
import dev.zhanqu.app.core.*

internal data class BilibiliVideoPage(val title: String, val author: String, val video: CapturedVideo,
    val expand: UiNode?, val commentTab: UiNode?, val commentCount: Int?)
internal data class BilibiliReplyLink(val parent: ParsedComment, val node: UiNode, val count: Int?)
internal data class BilibiliCommentsPage(val detail: Boolean, val comments: List<ParsedComment>,
    val replyLinks: List<BilibiliReplyLink>, val region: UiBounds, val close: UiNode?, val end: Boolean,
    val firstComment: ParsedComment?, val replyCount: Int?, val loadMore: UiNode? = null, val atTop: Boolean = false)

/** Bilibili's native video and cmt3 layouts. All selectors belong to this app. */
internal object BilibiliPageReader {
    private fun valid(s: UiSnapshot) = s.packageName == CaptureApp.BILIBILI.packageName && !s.hasSensitiveFields()
    private fun UiNode.nodes() = descendants().toList()
    private fun List<UiNode>.id(id: String) = firstOrNull { it.shortViewId == id }
    private fun UiNode?.value() = this?.text?.replace("\u200b", "")?.trim().orEmpty()
    private fun List<UiNode>.value(id: String) = id(id).value()
    fun action(s: UiSnapshot, label: UiNode): UiNode? =
        (listOf(label) + s.ancestorsOf(label.path).asReversed().take(3)).firstOrNull {
            it.clickable && it.visible && s.displayBounds.contains(it.bounds) && it.bounds.contains(label.bounds)
        }
    fun introTab(s: UiSnapshot): UiNode? = if (!valid(s)) null else s.allNodes()
        .firstOrNull { it.shortViewId == "tab_title" && it.text == "简介" }?.let { action(s, it) }

    fun video(s: UiSnapshot): BilibiliVideoPage? {
        if (!valid(s)) return null
        val all = s.allNodes().toList()
        if (all.none { it.shortViewId == "video_container" }) return null
        // ViewPager keeps the hidden intro tree mounted while the comment tab is selected.
        if (all.any { it.shortViewId == "cmt3_recycler" && it.visible && it.bounds.intersects(s.displayBounds) }) return null
        // The video title shares a list row with its expansion arrow. Ads and recommendations also use title/desc IDs.
        val recycler = all.firstOrNull { it.shortViewId == "recycler" && it.visible && it.children.any { row ->
            row.nodes().any { it.shortViewId == "views" && it.visible && it.bounds.intersects(s.displayBounds) } } } ?: return null
        val titleRow = recycler.children.firstOrNull { it.nodes().id("arrow") != null && it.nodes().id("title") != null }
            ?: return null
        val titleNodes = titleRow.nodes()
        val title = titleNodes.value("title")
        val stats = recycler.children.firstOrNull { it.nodes().id("views") != null }?.nodes().orEmpty()
        val author = recycler.nodes().value("author_name")
        if (title.isBlank() || author.isBlank()) return null
        val desc = stats.value("desc")
        fun count(id: String): String = recycler.nodes().id(id)?.nodes()?.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
            ?.firstOrNull().orEmpty()
        val video = CapturedVideo(bvid = Regex("BV[0-9A-Za-z]{10}").find(desc)?.value.orEmpty(),
            description = desc, views = stats.value("views"), danmakus = stats.value("danmakus"),
            publishedAt = stats.value("time"), online = stats.value("online"),
            duration = all.firstOrNull { it.shortViewId in setOf("player_duration", "total_time") }.value(),
            likes = count("like_num"), coins = count("coin_num"), favorites = count("favorite_num"), shares = count("share_num"))
        val arrow = titleNodes.id("arrow")?.takeIf { it.description == "展开更多，按钮" }?.let { action(s, it) }
        val tab = all.firstOrNull { it.shortViewId == "tab_title" && it.text == "评论" }?.let { action(s, it) }
        val total = all.firstOrNull { it.shortViewId == "tab_sub_title" }?.text?.toIntOrNull()
        return BilibiliVideoPage(title, author, video, arrow, tab, total)
    }

    fun comments(s: UiSnapshot, parent: ParsedComment? = null): BilibiliCommentsPage? {
        if (!valid(s)) return null
        val all = s.allNodes().toList()
        val list = all.firstOrNull { it.shortViewId == "cmt3_recycler" && it.visible && it.bounds.intersects(s.displayBounds) } ?: return null
        val detail = all.any { it.shortViewId == "cmt3_page_title_container" }
        val rows = list.children
        val comments = mutableListOf<ParsedComment>()
        val links = mutableListOf<BilibiliReplyLink>()
        var current: ParsedComment? = null
        var first: ParsedComment? = null
        for (row in rows) {
            val nodes = row.nodes()
            val body = nodes.value("primary_message")
            val author = nodes.value("user_name")
            if (body.isNotBlank() && author.isNotBlank()) {
                val root = comment(null, author, body, nodes.value("description"), nodes.value("like_count"))
                if (first == null) first = root
                if (detail && parent != null && root.author == parent.author && root.text == parent.text) {
                    current = parent
                    continue
                }
                val parsed = if (detail && parent != null) comment(parent.id, author, body, root.timeText.orEmpty(), root.likeText.orEmpty()) else root
                comments += parsed
                current = if (!detail) parsed else parent
            } else if (!detail) {
                if (current != null) nodes.filter { it.shortViewId == "secondary_message" }.forEach { preview ->
                    val raw = preview.value()
                    val separator = raw.indexOf(':').takeIf { it > 0 } ?: raw.indexOf('：').takeIf { it > 0 }
                    if (separator != null) comments += comment(current!!.id, raw.take(separator).trim(),
                        raw.drop(separator + 1).trim(), "", "").copy(textState = "preview")
                }
                val link = nodes.firstOrNull { it.shortViewId == "text" && Regex("共[0-9]+条回复").matches(it.value()) }
                if (link != null && current != null) {
                    action(s, link)?.let { links += BilibiliReplyLink(current!!, it, Regex("[0-9]+").find(link.value())?.value?.toIntOrNull()) }
                } else if (nodes.none { it.shortViewId == "secondary_message" }) current = null
                // Preview replies can be truncated. The full detail list is the source of saved replies.
            }
        }
        val bottom = all.filter { it.shortViewId == "cmt3_fake_input_bar_container" && it.visible }.minOfOrNull { it.bounds.top } ?: s.screenHeight
        val region = list.bounds.intersection(UiBounds(0, 0, s.screenWidth, bottom))
        if (region.height < 120 || region.width < 80) return null
        val texts = list.nodes().filter { it.visible && it.bounds.intersects(region) }.map { it.value() }
        val end = texts.any { it in setOf("没有更多评论了", "没有更多回复了", "没有更多了", "- 没有更多了 -", "还没有评论", "快来发表第一条评论吧", "已为您展开部分评论") }
        val replies = texts.firstNotNullOfOrNull { Regex("相关回复共([0-9]+)条").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        return BilibiliCommentsPage(detail, comments, links, region,
            all.firstOrNull { it.shortViewId == "close" && it.visible }?.let { action(s, it) }, end, first, replies,
            list.nodes().firstOrNull { it.shortViewId == "text" && it.value() == "展开更多评论" }?.let { action(s, it) },
            list.nodes().any { it.shortViewId == "title" && it.value() in setOf("热门评论", "最新评论", "全部评论") &&
                it.visible && it.bounds.intersects(region) })
    }
    private fun comment(parent: String?, author: String, text: String, time: String, likes: String): ParsedComment {
        val id = fingerprint("bilibili", parent, author, text)
        return ParsedComment(id, parent, author, text, timeText = time, likeText = likes,
            parentRelation = if (parent == null) "none" else "detailPage")
    }
}
