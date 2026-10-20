package dev.zhanqu.app.export

import dev.zhanqu.app.capture.*
import dev.zhanqu.app.core.ParsedComment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoteExporterTest {
 @Test fun preservesPageOrderErrorsAndHonestCounts() {
  val n=CapturedNote(id="synthetic",sourcePackage="com.example.fixture",title="合成笔记",reportedPages=7,displayedCommentCount=34,
   pages=listOf(CapturedPage(2,7,"第二页","hash2"),CapturedPage(1,7,"第一页","hash1")),
   comments=listOf(ParsedComment("a",null,"合成甲","正文"),ParsedComment("b","a","合成乙","回复")),errors=listOf("第3页失败"))
  val md=NoteExporter.markdown(n)
  assertTrue(md.indexOf("图页 1/7")<md.indexOf("图页 2/7"))
  assertTrue(md.contains("页面宣称：34；主评论：1；回复：1"))
  assertTrue(md.contains("确认到底：false"));assertTrue(md.contains("第3页失败"))
  val json=JSONObject(NoteExporter.json(n,"人工校正"))
  assertEquals("人工校正",json.getString("editedText"))
  assertEquals(1,json.getJSONArray("pages").getJSONObject(0).getInt("pageIndex"))
  assertEquals("a",json.getJSONArray("comments").getJSONObject(1).getString("parentId"))
  assertFalse(json.getBoolean("endReached"))
 }
 @Test fun unresolvedReplyIsNotCountedAsParent() {
  val n=CapturedNote(sourcePackage="test.fixture",comments=listOf(ParsedComment("r",null,null,"未解析父级",isReply=true,parentUnresolved=true)))
  assertTrue(NoteExporter.markdown(n).contains("主评论：0；回复：1"))
  assertTrue(JSONObject(NoteExporter.json(n)).getJSONArray("comments").getJSONObject(0).getBoolean("parentUnresolved"))
 }
 @Test fun unknownCountsRemainUnknown() {
  val n=CapturedNote(sourcePackage="test.fixture")
  assertTrue(NoteExporter.markdown(n).contains("页面总数 未知"))
  assertTrue(JSONObject(NoteExporter.json(n)).isNull("reportedPages"))
  assertEquals("标题\n正文",NoteExporter.plain("# 标题\n正文"))
 }
 @Test fun rawOcrIsPreservedSeparatelyFromFormattingAndLegacyDefaults() {
  val raw="2/4\n合成第一行\n合成第二行"
  val page=CapturedPage(2,4,"合成第一行合成第二行","h",rawText=raw,layoutVersion="spatial-1")
  val obj=JSONObject(NoteExporter.json(CapturedNote(sourcePackage="fixture",pages=listOf(page))))
  assertEquals(2,obj.getInt("schemaVersion"))
  val p=obj.getJSONArray("pages").getJSONObject(0)
  assertEquals(raw,p.getString("rawText"));assertEquals("合成第一行合成第二行",p.getString("text"))
  assertEquals("旧版文本",CapturedPage(1,1,"旧版文本","h").rawText)
 }
}
