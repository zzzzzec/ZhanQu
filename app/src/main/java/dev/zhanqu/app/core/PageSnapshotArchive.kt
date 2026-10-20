package dev.zhanqu.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independent GKD-shaped data writer. No GKD source code or rule execution. */
object PageSnapshotArchive {
    const val MAX_JSON_BYTES = 8 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    fun json(snapshot: UiSnapshot, id: Long, screenshotBounds: UiBounds, device: Map<String, Any> = emptyMap()): ByteArray {
        require(id > 0 && snapshot.packageName.isNotBlank())
        require(snapshot.screenWidth in 1..16384 && snapshot.screenHeight in 1..16384)
        val nodes = JSONArray()
        var count = 0
        fun visit(node: UiNode, parent: Int, index: Int, depth: Int) {
            require(depth <= 80 && count < 6000) { "页面节点过多或树层级异常" }
            val nodeId = count++
            fun nullable(value: String?): Any = value ?: JSONObject.NULL
            val b = node.bounds
            val attr = JSONObject().put("id", nullable(node.viewId)).put("vid", nullable(node.viewId?.takeIf { it.startsWith(snapshot.packageName + ":id/") }?.substringAfter(":id/")))
                .put("name", nullable(node.className)).put("text", nullable(if (node.password) null else node.text))
                .put("desc", nullable(if (node.password) null else node.description))
                .put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom)
                .put("width", b.width).put("height", b.height).put("clickable", node.clickable)
                .put("scrollable", node.scrollable).put("visibleToUser", node.visible).put("password", node.password)
                .put("focusable", node.focusable).put("checkable", node.checkable).put("checked", node.checked ?: JSONObject.NULL)
                .put("editable", node.editable).put("longClickable", node.longClickable)
                .put("index", node.path.substringAfterLast('/').toIntOrNull() ?: index).put("depth", depth).put("childCount", node.children.size)
            nodes.put(JSONObject().put("id", nodeId).put("pid", parent).put("idQf", JSONObject.NULL).put("textQf", JSONObject.NULL).put("attr", attr))
            node.children.forEachIndexed { i, child -> visit(child, nodeId, i, depth + 1) }
        }
        snapshot.roots.forEachIndexed { i, node -> visit(node, -1, i, 0) }
        require(count > 0)
        val metadata = JSONObject().put("producer", "ZhanQu").put("formatVersion", 1)
            .put("activityStatus", if (snapshot.activityName == null) "unknown" else "observed")
            .put("screenshotPresent", true).put("screenshotFile", "$id.png")
            .put("screenshotCoordinateSpace", "screen; excluded pixels are blank")
            .put("capturedBounds", JSONArray(listOf(screenshotBounds.left, screenshotBounds.top, screenshotBounds.right, screenshotBounds.bottom)))
            .put("passwordTextExcluded", true).put("atomicCapture", false)
        val value = JSONObject().put("id", id).put("appId", snapshot.packageName)
            .put("activityId", snapshot.activityName ?: JSONObject.NULL)
            .put("screenWidth", snapshot.screenWidth).put("screenHeight", snapshot.screenHeight)
            .put("isLandscape", snapshot.screenWidth > snapshot.screenHeight)
            .put("appInfo", JSONObject.NULL).put("gkdAppInfo", JSONObject.NULL)
            .put("device", JSONObject(device)).put("appVersion", snapshot.appVersion ?: JSONObject.NULL)
            .put("nodes", nodes).put("zhanqu", metadata)
        return value.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_JSON_BYTES) { "页面文字过多，快照大小超限" } }
    }

    fun write(output: OutputStream, id: Long, json: ByteArray, png: ByteArray) {
        require(id > 0 && json.isNotEmpty() && json.size <= MAX_JSON_BYTES)
        require(png.isNotEmpty() && png.size <= MAX_IMAGE_BYTES) { "截图大小超限" }
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf("$id.json" to json, "$id.png" to png)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
}
