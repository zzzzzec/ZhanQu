package dev.zhanqu.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportedSnapshot(val snapshot: UiSnapshot, val nodeCount: Int, val textNodeCount: Int, val warnings: List<String>)

/** Reads offline snapshot data only; never extracts archive paths or executes GKD rules/code. */
object GkdSnapshotImporter {
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 40 * 1024 * 1024
    private const val MAX_ENTRIES = 64

    fun importZip(input: InputStream): ImportedSnapshot {
        val jsonCandidates = mutableListOf<ImportedSnapshot>()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_ENTRIES) { "快照 ZIP 文件数量超限" }
                require(!entry.name.startsWith('/') && entry.name.split('/', '\\').none { it == ".." }) { "快照 ZIP 含有不安全路径" }
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val isJson = entry.name.endsWith(".json", ignoreCase = true)
                val output = if (isJson) ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                var entryBytes = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    entryBytes += read
                    require(total <= MAX_TOTAL_BYTES) { "快照 ZIP 解压大小超限" }
                    if (isJson) {
                        require(entryBytes <= MAX_JSON_BYTES) { "快照 JSON 大小超限" }
                        output!!.write(buffer, 0, read)
                    }
                }
                if (isJson) {
                    val text = output!!.toString("UTF-8")
                    // GKD ZIPs can contain app metadata beside the node snapshot; select by structure.
                    val warnings = mutableListOf<String>()
                    val obj = readJson(text, warnings)
                    if (obj.has("nodes") || obj.has("root") || obj.has("roots")) jsonCandidates += fromJsonObject(obj, warnings)
                }
                zip.closeEntry()
            }
        }
        require(jsonCandidates.size == 1) { if (jsonCandidates.isEmpty()) "ZIP 中没有完整的节点快照 JSON" else "ZIP 含有多个节点快照，请仅导入一个页面" }
        return jsonCandidates.single()
    }

    fun importJson(json: String): ImportedSnapshot {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "快照 JSON 大小超限" }
        val warnings = mutableListOf<String>()
        return fromJsonObject(readJson(json, warnings), warnings)
    }

    private fun fromJsonObject(root: JSONObject, warnings: MutableList<String>): ImportedSnapshot {
        val appInfo = root.optJSONObject("appInfo")
        val packageName = root.optionalString("appId") ?: root.optionalString("packageName") ?: appInfo?.optionalString("id") ?: appInfo?.optionalString("packageName")
        require(!packageName.isNullOrBlank()) { "快照缺少应用包名" }
        val activity = root.optionalString("activityId") ?: root.optionalString("activityName")
        val screen = root.optJSONObject("screen")
        val width = root.optInt("screenWidth", screen?.optInt("width", 0) ?: 0)
        val height = root.optInt("screenHeight", screen?.optInt("height", 0) ?: 0)
        require(width in 1..16384 && height in 1..16384) { "快照缺少有效屏幕尺寸" }
        val version = appInfo?.optionalString("versionName") ?: root.optionalString("appVersion")
        val nodes = root.optJSONArray("nodes")
        val roots: List<UiNode>
        if (nodes != null && (nodes.length() == 0 || nodes.getJSONObject(0).has("pid") || nodes.getJSONObject(0).has("parentId"))) {
            require(nodes.length() in 1..UiNode.MAX_NODES) { "快照节点数量超限或为空" }
            val byId = linkedMapOf<String, JSONObject>()
            val children = mutableMapOf<String, MutableList<String>>()
            val rootIds = mutableListOf<String>()
            val parents = mutableMapOf<String, String?>()
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val id = if (node.has("id") && !node.isNull("id")) node.get("id").toString() else i.toString()
                require(byId.put(id, node) == null) { "快照包含重复节点 ID" }
                val parent = when {
                    node.has("pid") && !node.isNull("pid") -> node.get("pid").toString()
                    node.has("parentId") && !node.isNull("parentId") -> node.get("parentId").toString()
                    else -> null
                }.takeUnless { it == "-1" }
                require(parent != id) { "快照节点不能以自己为父节点" }
                parents[id] = parent
            }
            for ((id, parent) in parents) {
                if (parent == null || parent !in byId) {
                    rootIds += id
                    if (parent != null) warnings += "快照有缺失的父节点，已保留为独立根节点"
                } else children.getOrPut(parent) { mutableListOf() }.add(id)
            }
            val visited = mutableSetOf<String>()
            val active = mutableSetOf<String>()
            fun build(id: String, path: String, depth: Int): UiNode {
                require(depth <= 128 && active.add(id)) { "快照树过深或含有循环" }
                visited += id
                val node = copyNode(byId.getValue(id), path, children[id].orEmpty().mapIndexed { index, child -> build(child, "$path/$index", depth + 1) })
                active.remove(id)
                return node
            }
            roots = rootIds.mapIndexed { index, id -> build(id, index.toString(), 0) }
            require(visited.size == byId.size) { "快照含有不可达或循环节点" }
        } else {
            val nested = root.optJSONArray("roots") ?: nodes ?: root.optJSONObject("root")?.let { JSONArray().put(it) }
            require(nested != null && nested.length() > 0) { "快照没有节点树" }
            var count = 0
            fun build(value: JSONObject, path: String, depth: Int): UiNode {
                require(++count <= UiNode.MAX_NODES && depth <= 128) { "快照节点数量超限或树过深" }
                val childArray = value.optJSONArray("children") ?: JSONArray()
                val childNodes = (0 until childArray.length()).map { build(childArray.getJSONObject(it), "$path/$it", depth + 1) }
                return copyNode(value, path, childNodes)
            }
            roots = (0 until nested.length()).map { build(nested.getJSONObject(it), it.toString(), 0) }
        }
        val snapshot = UiSnapshot(packageName, activity, width, height, roots, version)
        val all = snapshot.allNodes().toList()
        if (activity == null) warnings += "快照未提供 Activity，离线页面识别只能依赖节点锚点"
        if (all.any { it.password }) warnings += "密码节点的文字和描述已清除"
        return ImportedSnapshot(snapshot, all.size, all.count { !it.text.isNullOrEmpty() }, warnings.distinct())
    }

    private fun copyNode(value: JSONObject, path: String, children: List<UiNode>): UiNode {
        val attr = value.optJSONObject("attr") ?: value
        val sensitive = attr.optBoolean("password", attr.optBoolean("isPassword", false))
        return UiNode(
            path = path,
            viewId = attr.optionalString("id")?.takeIf { ':' in it || '/' in it } ?: attr.optionalString("vid") ?: attr.optionalString("viewId") ?: attr.optionalString("resourceId"),
            className = attr.optionalString("name") ?: attr.optionalString("className") ?: attr.optionalString("class"),
            text = if (sensitive) null else attr.rawString("text"),
            description = if (sensitive) null else attr.rawString("desc") ?: attr.rawString("description") ?: attr.rawString("contentDescription"),
            bounds = parseBounds(attr),
            clickable = attr.optBoolean("clickable", false), scrollable = attr.optBoolean("scrollable", false),
            visible = attr.optBoolean("visibleToUser", attr.optBoolean("visible", true)),
            password = sensitive, children = children, focusable = attr.optBoolean("focusable", false),
            checkable = attr.optBoolean("checkable", false), checked = if (attr.isNull("checked")) null else attr.optBoolean("checked"),
            editable = attr.optBoolean("editable", false), longClickable = attr.optBoolean("longClickable", false)
        )
    }

    private fun JSONObject.rawString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun parseBounds(attr: JSONObject): UiBounds {
        val bounds = attr.opt("bounds")
        if (bounds is JSONArray && bounds.length() == 4) return UiBounds(bounds.getInt(0), bounds.getInt(1), bounds.getInt(2), bounds.getInt(3))
        if (bounds is JSONObject) return UiBounds(bounds.optInt("left"), bounds.optInt("top"), bounds.optInt("right"), bounds.optInt("bottom"))
        if (bounds is String) {
            val numbers = Regex("-?\\d+").findAll(bounds).mapNotNull { it.value.toIntOrNull() }.take(5).toList()
            if (numbers.size == 4) return UiBounds(numbers[0], numbers[1], numbers[2], numbers[3])
        }
        return UiBounds(attr.optInt("left"), attr.optInt("top"), attr.optInt("right"), attr.optInt("bottom"))
    }

    /** Android's JSONObject accepts duplicate metadata keys; normalize identically in JVM regressions. */
    private fun readJson(source: String, warnings: MutableList<String>): JSONObject {
        val tokener = JSONTokener(source)
        var tokens = 0
        fun parse(depth: Int): Any {
            require(depth <= 128 && ++tokens <= 200_000) { "JSON 嵌套或元素数量超限" }
            return when (val first = tokener.nextClean()) {
                '{' -> {
                    val obj = JSONObject()
                    if (tokener.nextClean() == '}') obj else {
                        tokener.back()
                        while (true) {
                            require(tokener.nextClean() == '"') { "JSON 对象键不完整" }
                            val key = tokener.nextString('"')
                            require(tokener.nextClean() == ':') { "JSON 对象缺少冒号" }
                            val value = parse(depth + 1)
                            if (obj.has(key)) warnings += "JSON 存在重复键；保留最后一个值"
                            obj.put(key, value)
                            when (tokener.nextClean()) { '}' -> break; ',' -> Unit; else -> error("JSON 对象不完整") }
                        }
                        obj
                    }
                }
                '[' -> {
                    val array = JSONArray()
                    if (tokener.nextClean() == ']') array else {
                        tokener.back()
                        while (true) {
                            array.put(parse(depth + 1))
                            when (tokener.nextClean()) { ']' -> break; ',' -> Unit; else -> error("JSON 数组不完整") }
                        }
                        array
                    }
                }
                '"' -> tokener.nextString('"')
                else -> {
                    require(first != '\u0000') { "JSON 被截断" }
                    val value = StringBuilder().append(first)
                    while (true) {
                        val c = tokener.next()
                        if (c == '\u0000') break
                        if (c == ',' || c == ']' || c == '}' || c.isWhitespace()) { tokener.back(); break }
                        require(value.length < 256) { "JSON 数值长度超限" }
                        value.append(c)
                    }
                    when (val token = value.toString()) {
                        "true" -> true
                        "false" -> false
                        "null" -> JSONObject.NULL
                        else -> {
                            require(token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) { "JSON 含有无效值或已被截断" }
                            token.toLongOrNull() ?: (token.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("JSON 数值无效"))
                        }
                    }
                }
            }
        }
        val root = parse(0)
        require(tokener.nextClean() == '\u0000' && root is JSONObject) { "需要完整的 JSON 对象" }
        return root
    }
}
