package dev.zhanqu.app.core
import org.junit.Assert.*
import org.junit.Test
class CommentEntryPlannerTest {
 private val p=BuiltInProfiles.xiaohongshu()
 private val bounds=UiBounds(0,250,1080,2105)
 private fun snap(vararg nodes:UiNode)=UiSnapshot("com.xingin.xhs","com.xingin.matrix.notedetail.NoteDetailActivity",1080,2340,nodes.toList())
 private fun n(id:String,path:String="0",visible:Boolean=true,b:UiBounds=bounds,children:List<UiNode> = emptyList(),clickable:Boolean=false)=UiNode(path,id,bounds=b,visible=visible,children=children,clickable=clickable)
 @Test fun originalScrollContainerIsPreferred() { assertTrue(CommentEntryPlanner.plan(snap(n("nestedScrollLayout")),p) is CommentEntryPlanner.Plan.Scroll) }
 @Test fun alternateHeaderAndContentLayoutAreBoundedFallbacks() {
  listOf("nested_header","noteContentLayout").forEach { assertTrue(CommentEntryPlanner.plan(snap(n(it)),p) is CommentEntryPlanner.Plan.Scroll) }
 }
 @Test fun noteContentRequiresKnownRootAncestor() {
  assertEquals(CommentEntryPlanner.Plan.Missing,CommentEntryPlanner.plan(snap(n("noteContent")),p))
  assertTrue(CommentEntryPlanner.plan(snap(n("noteDetailRoot",children=listOf(n("noteContent","0/0")))),p) is CommentEntryPlanner.Plan.Scroll)
 }
 @Test fun offscreenCommentListDoesNotMeanEntryReached() {
  val s=snap(n("noteDetailRV",visible=false,b=UiBounds(0,2471,1080,2340)),n("nested_header","1"))
  assertTrue(CommentEntryPlanner.plan(s,p) is CommentEntryPlanner.Plan.Scroll)
 }
 @Test fun visibleCommentListIsReady() { assertEquals(CommentEntryPlanner.Plan.Ready,CommentEntryPlanner.plan(snap(n("noteDetailRV")),p)) }
 @Test fun missingParentCanUseVerifiedCarouselVerticalSwipe() {
  val image=n("photoImageView","0/0").copy(description="图片,第4张,共4张")
  assertTrue(CommentEntryPlanner.plan(snap(n("imageListView",children=listOf(image))),p) is CommentEntryPlanner.Plan.Swipe)
 }
 @Test fun onlySpecificCommentButtonCanBeClicked() {
  val s=snap(n("engageBarLayout",children=listOf(n("noteCommentLayout","0/0",clickable=true))))
  assertTrue(CommentEntryPlanner.plan(s,p) is CommentEntryPlanner.Plan.Click)
  listOf("inputCommentTV","noteLikeLayout","noteCollectLayout").forEach { id ->
   assertEquals(CommentEntryPlanner.Plan.Missing,CommentEntryPlanner.plan(snap(n("engageBarLayout",children=listOf(n(id,"0/0",clickable=true)))),p))
  }
 }
 @Test fun foreignPackageOrPasswordIsRejected() {
  assertEquals(CommentEntryPlanner.Plan.Missing,CommentEntryPlanner.plan(snap(n("nested_header")).copy(packageName="foreign"),p))
  assertEquals(CommentEntryPlanner.Plan.Missing,CommentEntryPlanner.plan(snap(n("nested_header").copy(password=true)),p))
 }
 @Test fun verifiedCommentButtonCanBeTriedAfterUnchangedScrolls() {
  val s=snap(n("nested_header"),n("engageBarLayout","1",children=listOf(n("noteCommentLayout","1/0",clickable=true))))
  assertTrue(CommentEntryPlanner.plan(s,p) is CommentEntryPlanner.Plan.Scroll)
  assertTrue(CommentEntryPlanner.plan(s,p,preferCommentButton=true) is CommentEntryPlanner.Plan.Click)
 }
}
