package dev.zhanqu.app.core

import org.json.JSONArray
import org.json.JSONObject

/** Bounded declarative selectors. Profiles contain no executable scripts or network endpoints. */
data class LocatorCandidate(
    val viewId: String? = null,
    val className: String? = null,
    val text: String? = null,
    val textRegex: String? = null,
    val descRegex: String? = null,
    val ancestorViewId: String? = null,
    val parentViewId: String? = null,
    val clickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val requireVisibleBounds: Boolean = false
) {
    private val textPattern = textRegex?.let(::checkedRegex)
    private val descriptionPattern = descRegex?.let(::checkedRegex)
    fun matches(node: UiNode, snapshot: UiSnapshot): Boolean {
        if (node.password) return false
        if (viewId != null && !matchesId(node.viewId, viewId)) return false
        if (className != null && node.className != className) return false
        if (text != null && node.text != text) return false
        if (textPattern != null && !textPattern.containsMatchIn(node.text.orEmpty().take(8192))) return false
        if (descriptionPattern != null && !descriptionPattern.containsMatchIn(node.description.orEmpty().take(8192))) return false
        if (clickable != null && node.clickable != clickable) return false
        if (scrollable != null && node.scrollable != scrollable) return false
        if (requireVisibleBounds && (!node.visible || !node.bounds.intersects(snapshot.displayBounds))) return false
        if (ancestorViewId != null || parentViewId != null) {
            val ancestors = snapshot.ancestorsOf(node.path)
            if (ancestorViewId != null && ancestors.none { matchesId(it.viewId, ancestorViewId) }) return false
            if (parentViewId != null && !matchesId(ancestors.lastOrNull()?.viewId, parentViewId)) return false
        }
        return true
    }
    companion object {
        fun fromJson(value: JSONObject): LocatorCandidate {
            value.requireOnly(setOf("viewId", "resourceId", "className", "class", "text", "textRegex", "descRegex", "contentDescriptionRegex", "ancestorViewId", "parentViewId", "clickable", "scrollable", "requireVisibleBounds"))
            require(value.length() > 0) { "Empty selector is not allowed" }
            return LocatorCandidate(
                viewId = value.optionalString("viewId") ?: value.optionalString("resourceId"),
                className = value.optionalString("className") ?: value.optionalString("class"),
                text = value.optionalString("text"), textRegex = value.optionalString("textRegex"),
                descRegex = value.optionalString("descRegex") ?: value.optionalString("contentDescriptionRegex"),
                ancestorViewId = value.optionalString("ancestorViewId"), parentViewId = value.optionalString("parentViewId"),
                clickable = value.optionalBoolean("clickable"), scrollable = value.optionalBoolean("scrollable"),
                requireVisibleBounds = value.optBoolean("requireVisibleBounds", false)
            ).also { selector ->
                require(listOf(selector.viewId, selector.className, selector.text, selector.textRegex, selector.descRegex).any { it != null } || selector.scrollable == true) { "A selector needs an identifying constraint" }
            }
        }
    }
}

data class ProfileLimits(
    val maxPages: Int = 50,
    val maxScrolls: Int = 300,
    val actionTimeoutMs: Long = 5000L,
    val maxConsecutiveNoNewContent: Int = 3,
    val maxExpandFailures: Int = 3
)

data class ImageFlow(
    val strategy: String = "horizontalCarousel",
    val startPolicy: String = "rewindToFirstOrAsk",
    val screenshotRegion: String = "currentImage",
    val pageCounter: String = "pageIndicator",
    val gestureRegion: String = "carousel"
)

data class CommentFlow(val strategy: String = "nestedCommentScroll", val scrollContainer: String = "commentList", val parentLocator: String = "mainComment", val childLocator: String = "subComment", val expandLocator: String = "expandReplies", val replyRelation: String = "ancestor")

class CaptureProfile private constructor(
    val id: String,
    val schemaVersion: Int,
    val packageName: String,
    val activityPatterns: List<String>,
    val priority: Int,
    val locators: Map<String, List<LocatorCandidate>>,
    private val requiredAnchors: List<LocatorCandidate>,
    private val optionalAnchors: List<LocatorCandidate>,
    val limits: ProfileLimits,
    val imageFlow: ImageFlow,
    val commentFlow: CommentFlow,
    val pageCounterRegex: String,
    val versionMin: String?,
    val versionMax: String?,
    val json: String
) {
    private val activities = activityPatterns.map(::checkedRegex)
    private val counterPattern = checkedRegex(pageCounterRegex)

    fun matches(snapshot: UiSnapshot): Boolean {
        if (snapshot.packageName != packageName || snapshot.hasSensitiveFields()) return false
        if (snapshot.activityName != null && activities.isNotEmpty() && activities.none { it.matches(snapshot.activityName) }) return false
        // Unknown app version is not treated as proof of compatibility with a bounded version range.
        if ((versionMin != null || versionMax != null) && snapshot.appVersion == null) return false
        val version = snapshot.appVersion
        if (version != null && versionMin != null && compareVersions(version, versionMin) < 0) return false
        if (version != null && versionMax != null && compareVersions(version, versionMax) > 0) return false
        val nodes = snapshot.allNodes().toList()
        if (!requiredAnchors.all { selector -> nodes.any { selector.matches(it, snapshot) } }) return false
        if (optionalAnchors.isNotEmpty() && optionalAnchors.none { selector -> nodes.any { selector.matches(it, snapshot) } }) return false
        return true
    }

    /** Ordered fallback candidates, not a union. Duplicate IDs remain disambiguatable by ancestry/bounds. */
    fun find(snapshot: UiSnapshot, locatorName: String, visibleOnly: Boolean = true): List<UiNode> {
        if (snapshot.packageName != packageName) return emptyList()
        val nodes = snapshot.allNodes().toList()
        for (candidate in locators[locatorName].orEmpty()) {
            val matches = nodes.filter {
                (!visibleOnly || (it.visible && it.bounds.intersects(snapshot.displayBounds))) && candidate.matches(it, snapshot)
            }
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    fun pageCounter(snapshot: UiSnapshot): PageCounter? {
        val candidates = find(snapshot, imageFlow.pageCounter).sortedByDescending { it.bounds.intersection(snapshot.displayBounds).area }
        val counters = candidates.mapNotNull { node -> parsePageCounter(node.description.orEmpty()) ?: parsePageCounter(node.text.orEmpty()) }.distinct()
        // Several partially visible carousel children can have their own labels; largest visible wins.
        return counters.firstOrNull()
    }

    fun parsePageCounter(value: String): PageCounter? {
        val match = counterPattern.find(value.take(8192)) ?: return null
        if (match.groupValues.size < 3) return null
        val current = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        return if (current > 0 && total in 1..limits.maxPages && current <= total) PageCounter(current, total) else null
    }

    fun imageNode(snapshot: UiSnapshot): UiNode? = find(snapshot, imageFlow.screenshotRegion)
        .filter { val bounds = it.bounds.intersection(snapshot.displayBounds); bounds.width >= 32 && bounds.height >= 32 }
        .maxByOrNull { it.bounds.intersection(snapshot.displayBounds).area }

    fun imageBounds(snapshot: UiSnapshot): UiBounds? = imageNode(snapshot)?.bounds?.intersection(snapshot.displayBounds)

    companion object {
        fun fromJson(json: String): CaptureProfile {
            require(json.length <= 128 * 1024) { "Profile is too large" }
            val root = JSONObject(json)
            root.requireOnly(setOf("schemaVersion", "id", "packageName", "activityPatterns", "versionRange", "priority", "recognition", "locators", "data", "flows", "limits", "output"))
            require(root.getInt("schemaVersion") == 1) { "Unsupported profile schema" }
            val id = root.getString("id").also { require(it.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid profile ID" } }
            val pkg = root.getString("packageName").also { require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid target package" } }
            val activities = root.optJSONArray("activityPatterns")?.strings(20).orEmpty()
            activities.forEach(::checkedRegex)
            val recognition = root.getJSONObject("recognition")
            recognition.requireOnly(setOf("all", "any"))
            fun anchors(key: String): List<LocatorCandidate> = recognition.optJSONArray(key)?.let { array ->
                require(array.length() <= 20)
                (0 until array.length()).map { index ->
                    val rule = array.getJSONObject(index)
                    rule.requireOnly(setOf("exists"))
                    LocatorCandidate.fromJson(rule.getJSONObject("exists"))
                }
            }.orEmpty()
            val required = anchors("all")
            val optional = anchors("any")
            require(required.isNotEmpty() || optional.isNotEmpty()) { "At least one page recognition anchor is required" }
            val locatorJson = root.getJSONObject("locators")
            require(locatorJson.length() in 1..64) { "Invalid number of locators" }
            val locators = locatorJson.keys().asSequence().associateWith { key ->
                require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
                val candidates = locatorJson.getJSONArray(key)
                require(candidates.length() in 1..16) { "Invalid number of locator candidates" }
                (0 until candidates.length()).map { LocatorCandidate.fromJson(candidates.getJSONObject(it)) }
            }
            val limit = root.optJSONObject("limits") ?: JSONObject()
            limit.requireOnly(setOf("maxPages", "maxScrolls", "actionTimeoutMs", "maxConsecutiveNoNewContent", "maxExpandFailures"))
            val limits = ProfileLimits(limit.optInt("maxPages", 50), limit.optInt("maxScrolls", 300), limit.optLong("actionTimeoutMs", 5000), limit.optInt("maxConsecutiveNoNewContent", 3), limit.optInt("maxExpandFailures", 3))
            require(limits.maxPages in 1..100 && limits.maxScrolls in 1..500 && limits.actionTimeoutMs in 500..30_000 && limits.maxConsecutiveNoNewContent in 1..10 && limits.maxExpandFailures in 1..10) { "Unsafe workflow limits" }
            val flows = root.optJSONObject("flows") ?: JSONObject()
            flows.requireOnly(setOf("images", "comments"))
            val images = flows.optJSONObject("images") ?: JSONObject()
            images.requireOnly(setOf("strategy", "startPolicy", "screenshotRegion", "pageCounter", "advance", "waitFor", "extract", "stopWhen"))
            val advance = images.optJSONObject("advance") ?: JSONObject()
            advance.requireOnly(setOf("gesture", "region"))
            require(advance.optString("gesture", "swipeLeft") == "swipeLeft") { "Unsupported image gesture" }
            require(images.optString("waitFor", "pageCounterChanged") == "pageCounterChanged")
            require(images.optString("extract", "ocr") == "ocr")
            require(images.optString("stopWhen", "currentPageEqualsTotal") == "currentPageEqualsTotal")
            val imageFlow = ImageFlow(images.optString("strategy", "horizontalCarousel"), images.optString("startPolicy", "rewindToFirstOrAsk"), images.optString("screenshotRegion", "currentImage"), images.optString("pageCounter", "pageIndicator"), advance.optString("region", "carousel"))
            require(imageFlow.strategy == "horizontalCarousel" && imageFlow.startPolicy == "rewindToFirstOrAsk") { "Unsupported image workflow" }
            val comments = flows.optJSONObject("comments") ?: JSONObject()
            comments.requireOnly(setOf("strategy", "scrollContainer", "extractParents", "extractChildren", "expand", "stopWhen", "replyRelation"))
            require(comments.optString("stopWhen", "endOfListOrStable") == "endOfListOrStable")
            val commentFlow = CommentFlow(comments.optString("strategy", "nestedCommentScroll"), comments.optString("scrollContainer", "commentList"), comments.optString("extractParents", "mainComment"), comments.optString("extractChildren", "subComment"), comments.optString("expand", "expandReplies"), comments.optString("replyRelation", "ancestor"))
            require(commentFlow.strategy == "nestedCommentScroll") { "Unsupported comment workflow" }
            require(commentFlow.replyRelation in setOf("ancestor", "ancestorOrPrecedingSiblingGroup")) { "Unsupported comment relationship" }
            val data = root.optJSONObject("data") ?: JSONObject()
            data.requireOnly(setOf("pageCounterRegex"))
            val counter = data.optString("pageCounterRegex", "图片[,，]第\\s*(\\d+)张[,，]共\\s*(\\d+)张")
            val parsedCounter = checkedRegex(counter)
            require(parsedCounter.toPattern().matcher("").groupCount() == 2) { "Page counter must have exactly two capture groups" }
            val output = root.optJSONObject("output")
            if (output != null) {
                output.requireOnly(setOf("commentDeduplication", "pageOrder"))
                require(output.optString("commentDeduplication", "parentAuthorTextTime") == "parentAuthorTextTime")
                require(output.optString("pageOrder", "numeric") == "numeric")
            }
            val version = root.optJSONObject("versionRange")
            version?.requireOnly(setOf("min", "max"))
            val min = version?.optionalString("min")
            val max = version?.optionalString("max")
            listOfNotNull(min, max).forEach { require(it.matches(Regex("\\d+(\\.\\d+){0,4}"))) }
            return CaptureProfile(id, 1, pkg, activities, root.optInt("priority", 0).coerceIn(-100, 100), locators, required, optional, limits, imageFlow, commentFlow, counter, min, max, json)
        }
    }
}

class ProfileRegistry(initial: List<CaptureProfile> = listOf(BuiltInProfiles.xiaohongshu())) {
    private val profiles = initial.associateBy { it.id }.toMutableMap()
    @Synchronized fun register(profile: CaptureProfile) { require(profiles.size < 32 || profiles.containsKey(profile.id)); profiles[profile.id] = profile }
    @Synchronized fun select(snapshot: UiSnapshot): CaptureProfile? = profiles.values.sortedByDescending { it.priority }.firstOrNull { it.matches(snapshot) }
    @Synchronized fun all(): List<CaptureProfile> = profiles.values.toList()
}

internal fun matchesId(actual: String?, expected: String): Boolean = actual != null && if ('/' in expected) actual == expected else actual.substringAfterLast('/') == expected
internal fun JSONObject.optionalString(key: String): String? = if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null
internal fun JSONObject.optionalBoolean(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
internal fun JSONObject.requireOnly(allowed: Set<String>) { require(keys().asSequence().all { it in allowed }) { "Unsupported profile field" } }
private fun JSONArray.strings(max: Int): List<String> { require(length() <= max); return (0 until length()).map(::getString) }
internal fun checkedRegex(pattern: String): Regex {
    require(pattern.length in 1..512) { "Regex is too long or empty" }
    // Avoid executable extensions, lookarounds, backreferences and repeated groups that allow catastrophic backtracking.
    require(!Regex("\\\\[1-9]|\\(\\?[=!<]|\\)[*+{]").containsMatchIn(pattern)) { "Unsafe regular expression" }
    return Regex(pattern)
}
private fun compareVersions(left: String, right: String): Int {
    val a = left.split('.').map { it.toIntOrNull() ?: -1 }
    val b = right.split('.').map { it.toIntOrNull() ?: -1 }
    for (i in 0 until maxOf(a.size, b.size)) { val result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 }); if (result != 0) return result }
    return 0
}
