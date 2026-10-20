package dev.zhanqu.app.export

import dev.zhanqu.app.capture.CapturedNote
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteExporter {
 fun markdown(note: CapturedNote): String = buildString {
  appendLine("# " + note.title.ifBlank { "未识别标题" })
  appendLine()
  appendLine("来源：${note.sourcePackage}  作者：${note.author.ifBlank { "未识别" }}")
  appendLine("采集时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(note.capturedAt)))
  appendLine("状态：${note.status}；图页已尝试 ${note.pages.size}，OCR完成 ${note.pages.count { it.state == "complete" }}，待核对/失败 ${note.pages.count { it.state != "complete" }}，页面总数 ${note.reportedPages ?: "未知"}")
  appendLine()
  if (note.body.isNotBlank()) { appendLine(note.body); appendLine() }
  note.pages.sortedBy { it.pageIndex }.forEach { page ->
   appendLine("## 图页 ${page.pageIndex}" + (page.totalPages?.let { "/$it" } ?: ""))
   appendLine(page.text.ifBlank { "（未识别到文字，请人工核对）" })
   page.error?.let { appendLine("错误：$it") }
   appendLine()
  }
  appendLine("## 评论")
  appendLine("页面宣称：${note.displayedCommentCount ?: "未知"}；主评论：${note.comments.count { !it.isReply }}；回复：${note.comments.count { it.isReply }}")
  appendLine("确认到底：${note.endReached}；已展开组：${note.expandedReplyGroups}；未展开组：${note.unexpandedGroups}")
  appendLine("说明：到底仅表示观察到列表结束；评论总量口径、隐藏回复与启发式去重均可能造成遗漏。")
  note.comments.forEach { c ->
   appendLine((if (c.parentUnresolved) "- [父级未确认的回复] " else if (c.parentId == null) "- " else "  - 回复 ${c.parentId}: ") + "${c.author ?: "未知作者"}: ${c.text}")
  }
  if (note.errors.isNotEmpty()) { appendLine("\n## 未完成与异常"); note.errors.distinct().forEach { appendLine("- $it") } }
 }
 fun plain(markdown: String): String = markdown.lineSequence().joinToString("\n") { it.replace(Regex("^#{1,6} "), "") }
 fun json(note: CapturedNote, editedText: String = markdown(note)): String = JSONObject().apply {
  put("schemaVersion", 2); put("id", note.id); put("sourcePackage", note.sourcePackage)
  put("title", note.title); put("author", note.author); put("body", note.body)
  put("capturedAt", note.capturedAt); put("status", note.status); put("editedText", editedText)
  put("reportedPages", note.reportedPages ?: JSONObject.NULL)
  put("displayedCount", note.displayedCommentCount ?: JSONObject.NULL)
  put("endReached", note.endReached); put("expandedReplyGroups",note.expandedReplyGroups); put("unexpandedGroups",note.unexpandedGroups)
  put("pages", JSONArray().apply { note.pages.sortedBy { it.pageIndex }.forEach { p -> put(JSONObject().apply {
   put("pageIndex",p.pageIndex);put("totalPages",p.totalPages ?: JSONObject.NULL);put("text",p.text);put("contentHash",p.contentHash)
   put("rawText",p.rawText);put("layoutVersion",p.layoutVersion);put("inputWidth",p.inputWidth ?: JSONObject.NULL);put("inputHeight",p.inputHeight ?: JSONObject.NULL)
   put("removedArtifacts",JSONArray().apply { p.removedArtifacts.forEach { a -> put(JSONObject().apply { put("text",a.text);put("reason",a.reason);put("bounds",JSONArray(listOf(a.left,a.top,a.right,a.bottom))) }) } })
   put("blocks",JSONArray().apply { p.blocks.forEach { block -> put(JSONObject().apply { put("text",block.text);put("lines",JSONArray().apply { block.lines.forEach { line -> put(JSONObject().apply { put("text",line.text);put("bounds",JSONArray(listOf(line.left,line.top,line.right,line.bottom))) }) } }) }) } })
   put("state",p.state);put("error",p.error ?: JSONObject.NULL);put("engine",p.engine);put("capturedAt",p.capturedAt)
  }) } })
  put("comments", JSONArray().apply { note.comments.forEach { c -> put(JSONObject().apply {
   put("id",c.id);put("parentId",c.parentId ?: JSONObject.NULL);put("author",c.author);put("text",c.text)
   put("parentRelation",c.parentRelation);put("isReply",c.isReply);put("parentUnresolved",c.parentUnresolved);put("visibleAtCapture",c.visible);put("sourceFingerprint",c.sourceFingerprint)
   put("timeText",c.timeText);put("regionText",c.regionText);put("likeText",c.likeText);put("captureOrder",c.captureOrder)
  }) } })
  put("errors",JSONArray(note.errors))
 }.toString(2)
}
