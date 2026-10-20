package dev.zhanqu.app.core

import java.security.MessageDigest

data class ParsedComment(
    val id: String,
    val parentId: String?,
    val author: String?,
    val text: String,
    val timeText: String? = null,
    val regionText: String? = null,
    val likeText: String? = null,
    val captureOrder: Int = 0,
    val sourceFingerprint: String = id,
    val isReply: Boolean = parentId != null,
    val parentUnresolved: Boolean = false,
    val visible: Boolean = true,
    val parentRelation: String = if (parentId != null) "ancestor" else if (isReply) "unresolved" else "none"
)

object CommentParser {
    /**
     * Text already present in the selected page tree may be read even when outside the viewport.
     * Visibility restricts actions and screenshots, not the truth of available node text.
     * We never claim that the tree represents every comment in the remote list.
     */
    fun extract(snapshot: UiSnapshot, profile: CaptureProfile, visibleOnly: Boolean = false): List<ParsedComment> {
        if (snapshot.packageName != profile.packageName || snapshot.hasSensitiveFields()) return emptyList()
        val parents = profile.find(snapshot, profile.commentFlow.parentLocator, visibleOnly = false)
        val replies = profile.find(snapshot, profile.commentFlow.childLocator, visibleOnly = false)
        val parentPaths = parents.map { it.path }.toSet()
        val replyPaths = replies.map { it.path }.toSet()
        val containers = (parents + replies).associateBy { it.path }
        if (containers.isEmpty()) return emptyList()
        val ancestorCache = mutableMapOf<String, List<UiNode>>()
        fun ancestors(node: UiNode) = ancestorCache.getOrPut(node.path) { snapshot.ancestorsOf(node.path) }
        fun owner(node: UiNode): String? = (ancestors(node) + node).lastOrNull { it.path in containers }?.path
        val fields = listOf("commentText", "commentAuthor", "commentTime", "commentRegion", "commentLikes").associateWith { name ->
            profile.find(snapshot, name, visibleOnly = false).groupBy { owner(it) }
        }
        fun field(path: String, name: String): String? = fields[name]?.get(path)?.asSequence()?.filterNot { it.password }?.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }?.firstOrNull()
        // A profile may explicitly declare list-row grouping. Only uninterrupted, recognized
        // parent/reply/expand rows in the same list count; an unrelated row resets the group.
        val siblingParents = mutableMapOf<String, UiNode>()
        if (profile.commentFlow.replyRelation == "ancestorOrPrecedingSiblingGroup") {
            val expandPaths = profile.find(snapshot, profile.commentFlow.expandLocator, false).map { it.path }.toSet()
            for (list in profile.find(snapshot, profile.commentFlow.scrollContainer, false)) {
                var previousParent: UiNode? = null
                for (row in list.children) {
                    val descendants = row.descendants().toList()
                    val rowParents = descendants.filter { it.path in parentPaths }
                    val rowReplies = descendants.filter { it.path in replyPaths }
                    when {
                        rowParents.size == 1 -> previousParent = rowParents.single()
                        rowParents.size > 1 -> previousParent = null
                        rowReplies.isNotEmpty() -> previousParent?.let { parent -> rowReplies.forEach { siblingParents[it.path] = parent } }
                        descendants.any { it.path in expandPaths } -> Unit
                        else -> previousParent = null
                    }
                }
            }
        }
        val comments = mutableListOf<ParsedComment>()
        val resolved = mutableMapOf<String, ParsedComment>()
        val occurrences = mutableMapOf<String, Int>()
        val resolving = mutableSetOf<String>()
        fun parse(node: UiNode): ParsedComment? {
            resolved[node.path]?.let { return it }
            if (!resolving.add(node.path)) return null
            val text = field(node.path, "commentText")
            if (text == null) { resolving.remove(node.path); return null }
            val reply = node.path in replyPaths
            val ancestorParent = if (reply) ancestors(node).lastOrNull { it.path in parentPaths } else null
            val parentNode = ancestorParent ?: if (reply) siblingParents[node.path] else null
            val parent = parentNode?.let { parse(it) }
            val author = field(node.path, "commentAuthor")
            val time = field(node.path, "commentTime")
            val region = field(node.path, "commentRegion")
            val rawFingerprint = fingerprint(parent?.id, author, text, time, region, if (reply) "reply" else "parent")
            val occurrence = occurrences.getOrDefault(rawFingerprint, 0)
            occurrences[rawFingerprint] = occurrence + 1
            val comment = ParsedComment(
                id = "$rawFingerprint-$occurrence", parentId = parent?.id, author = author, text = text,
                timeText = time, regionText = region, likeText = field(node.path, "commentLikes"),
                captureOrder = comments.size, sourceFingerprint = rawFingerprint,
                isReply = reply, parentUnresolved = reply && parent == null,
                visible = node.visible && node.bounds.intersects(snapshot.displayBounds),
                parentRelation = when { !reply -> "none"; parent == null -> "unresolved"; ancestorParent != null -> "ancestor"; else -> "siblingGroup" }
            )
            resolved[node.path] = comment
            comments += comment
            resolving.remove(node.path)
            return comment
        }
        containers.values.sortedWith(compareBy<UiNode> { it.bounds.top }.thenBy { it.bounds.left }).forEach { parse(it) }
        return if (visibleOnly) comments.filter { it.visible } else comments.toList()
    }

    fun displayedCount(snapshot: UiSnapshot, profile: CaptureProfile): Int? {
        val pattern = Regex("共\\s*([0-9]+)\\s*条评论")
        return profile.find(snapshot, "displayedCommentCount", visibleOnly = false).asSequence().mapNotNull {
            pattern.find(it.text.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull()
    }
}

/** Heuristic identity: author/text/time/parent and occurrence distinguish visible duplicates. */
class CommentAccumulator {
    private val stored = linkedMapOf<String, ParsedComment>()
    val comments: List<ParsedComment> get() = stored.values.toList()
    val parentCount: Int get() = stored.values.count { !it.isReply }
    val replyCount: Int get() = stored.values.count { it.isReply }
    val unresolvedReplyCount: Int get() = stored.values.count { it.parentUnresolved }
    fun merge(comments: List<ParsedComment>): Int {
        var added = 0
        for (comment in comments) {
            val old = stored[comment.id]
            if (old == null) { stored[comment.id] = comment.copy(captureOrder = stored.size); added++ }
            else if (comment.likeText != old.likeText || comment.visible != old.visible) {
                stored[comment.id] = comment.copy(captureOrder = old.captureOrder)
            }
        }
        return added
    }
}

internal fun fingerprint(vararg parts: String?): String {
    val input = parts.joinToString("|") { part -> if (part == null) "-1:" else "${part.length}:$part" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
