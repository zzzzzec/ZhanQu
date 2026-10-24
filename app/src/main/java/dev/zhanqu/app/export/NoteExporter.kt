package dev.zhanqu.app.export

import dev.zhanqu.app.capture.CapturedNote
import dev.zhanqu.app.core.CaptureApp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoteExporter {
 fun markdown(note: CapturedNote): String = buildString {
  appendLine("# " + note.title.ifBlank { "未识别标题" })
  appendLine()
  appendLine("来源：${CaptureApp.detect(note.sourcePackage)?.displayName ?: note.sourcePackage}  作者：${note.author.ifBlank { "未识别" }}")
  appendLine("采集时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(note.capturedAt)))
  val answerCapture = note.captureKind == "zhihu_answer"
  val status = when (note.status) { "complete" -> "已完成"; "stopped" -> "已保存"; else -> "未完成" }
  if (answerCapture) appendLine("状态：$status；回答 ${note.answers.size} 条")
  else if (note.video != null) appendLine("状态：$status；视频")
  else appendLine("状态：$status；图页 ${note.pages.size}/${note.reportedPages ?: "未知"}")
  appendLine()
  note.video?.let { video ->
   val fields = listOf("BV号" to video.bvid, "播放量" to video.views, "弹幕量" to video.danmakus,
    "发布时间" to video.publishedAt, "正在观看" to video.online, "时长" to video.duration,
    "点赞" to video.likes, "投币" to video.coins, "收藏" to video.favorites, "分享" to video.shares)
   fields.filter { it.second.isNotBlank() }.forEach { appendLine("${it.first}：${it.second}") }
   appendLine("\n## 简介")
  }
  if (note.body.isNotBlank()) { appendLine(note.body); appendLine() }
  note.pages.sortedBy { it.pageIndex }.forEach { page ->
   appendLine("## 图页 ${page.pageIndex}" + (page.totalPages?.let { "/$it" } ?: ""))
   appendLine(page.text.ifBlank { "（未识别到文字）" })
   page.error?.let { appendLine("错误：$it") }
   appendLine()
  }
  note.answers.forEachIndexed { index, answer ->
   appendLine("## 回答 ${index + 1} · ${answer.author.ifBlank { "未识别作者" }}")
   appendLine(answer.text.ifBlank { "（未读取到文字）" })
   val captions = answer.observations.flatMap { it.imageDescriptions }.distinct()
   if (captions.isNotEmpty()) {
    appendLine("\n### 图片说明")
    captions.forEach { appendLine("- $it") }
   }
   appendLine()
  }
  if (!answerCapture) {
  appendLine("## 评论")
  appendLine("页面评论数：${note.displayedCommentCount ?: "未知"}；主评论：${note.comments.count { !it.isReply }}；回复：${note.comments.count { it.isReply }}")
  note.comments.forEach { c ->
   appendLine((if (c.parentUnresolved) "- [父级未确认的回复] " else if (c.parentId == null) "- " else "  - 回复 ${c.parentId}: ") + "${c.author ?: "未知作者"}: ${c.text}")
  }
  }
  if (note.errors.isNotEmpty()) { appendLine("\n## 未完成与异常"); note.errors.distinct().forEach { appendLine("- $it") } }
 }
 fun plain(markdown: String): String = markdown.lineSequence().joinToString("\n") { it.replace(Regex("^#{1,6} "), "") }
 fun json(note: CapturedNote, editedText: String = markdown(note)): String = JSONObject().apply {
  put("schemaVersion", if (note.video != null) 4 else if (note.captureKind == "zhihu_answer") 3 else 2); put("id", note.id); put("sourcePackage", note.sourcePackage)
  put("captureKind", note.captureKind)
  note.video?.let { v -> put("video", JSONObject().apply {
   put("bvid", v.bvid); put("description", v.description); put("views", v.views); put("danmakus", v.danmakus)
   put("publishedAt", v.publishedAt); put("online", v.online); put("duration", v.duration)
   put("likes", v.likes); put("coins", v.coins); put("favorites", v.favorites); put("shares", v.shares)
  }) }
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
   put("textState",c.textState)
   put("timeText",c.timeText);put("regionText",c.regionText);put("likeText",c.likeText);put("captureOrder",c.captureOrder)
  }) } })
  put("answers", JSONArray().apply { note.answers.forEach { answer -> put(JSONObject().apply {
   put("id", answer.id); put("author", answer.author); put("text", answer.text); put("status", answer.status)
   put("errors", JSONArray(answer.errors)); put("endReached", answer.endReached)
   put("observations", JSONArray().apply { answer.observations.forEach { observation -> put(JSONObject().apply {
    put("index", observation.index); put("paragraphs", JSONArray(observation.paragraphs))
    put("imageDescriptions", JSONArray(observation.imageDescriptions)); put("imageCount", observation.imageCount)
   }) } })
  }) } })
  put("errors",JSONArray(note.errors))
 }.toString(2)
}
