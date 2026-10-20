package dev.zhanqu.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.zhanqu.app.capture.*
import dev.zhanqu.app.data.*
import dev.zhanqu.app.export.NoteExporter
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Keeps in-progress text and pending export intact across configuration changes. */
class NoteEditorState : ViewModel() {
 val selected = mutableStateOf<StoredNote?>(null)
 val editor = mutableStateOf("")
 val dirty = mutableStateOf(false)
 val exportContent = mutableStateOf<String?>(null)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MaterialTheme(colorScheme = lightColorScheme(
   primary = Color(0xFF1A6954), onPrimary = Color.White,
   primaryContainer = Color(0xFFDDEDE3), onPrimaryContainer = Color(0xFF163E31),
   background = Color(0xFFF5F7F2), surface = Color(0xFFFFFFFF),
   onSurface = Color(0xFF1C2F2D), onSurfaceVariant = Color(0xFF61736D),
   surfaceVariant = Color(0xFFEDF1E9), outline = Color(0xFFB7C5BA)
  )) { ZhanQuScreen() } }
 }
 @Composable private fun ZhanQuScreen() {
  val scope = rememberCoroutineScope()
  val dao = remember { NoteDatabase.get(this).notes() }
  val state by CaptureHub.state.collectAsState()
  val captured by CaptureHub.result.collectAsState()
  val editorState: NoteEditorState = viewModel()
  var query by rememberSaveable { mutableStateOf("") }
  val notes by remember(query) { dao.search(query) }.collectAsState(initial = emptyList())
  var selected by editorState.selected
  val editorRequester = remember { BringIntoViewRequester() }
  LaunchedEffect(selected?.id) { if(selected != null) editorRequester.bringIntoView() }
  var pendingSelection by remember { mutableStateOf<StoredNote?>(null) }
  var editor by editorState.editor
  var editorDirty by editorState.dirty
  var message by remember { mutableStateOf("") }
  var importing by remember { mutableStateOf(false) }
  var consent by remember { mutableStateOf(getSharedPreferences("privacy",MODE_PRIVATE).getBoolean("disclosure", false)) }
  var clearDialog by remember { mutableStateOf(false) }
  var exportContent by editorState.exportContent
  val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
   val content = exportContent
   exportContent = null
   if (uri != null && content == null) message = "导出内容因系统重启已失效，未写入文件。请重新选择笔记并导出。"
   else if (uri != null && content != null) scope.launch {
    message = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("无法打开文件"); "已导出" }.getOrElse { "导出失败：${it.message}" }
   }
  }
  val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
   if (uri != null) scope.launch {
    importing=true; message="正在本机识别所选原图…"
    try {
     val result=LocalImageOcr.recognize(applicationContext,uri)
     val markdown=NoteExporter.markdown(result)
     val record=StoredNote(result.id,result.title,result.author,result.sourcePackage,result.capturedAt,result.status,NoteExporter.json(result),markdown)
     if (!editorDirty) { selected=record;editor=markdown }
     message="原图识别完成；JSON保留原始文字与排版结果，未上传图片。"
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch(e: Exception) { message="原图识别或保存失败：${e.message ?: "请换一张图片重试"}" }
    finally { importing=false }
   }
  }
  LaunchedEffect(captured) {
   captured?.let { n ->
    val text = NoteExporter.markdown(n)
    val record = StoredNote(n.id,n.title.ifBlank{"未识别标题"},n.author,n.sourcePackage,n.capturedAt,n.status,NoteExporter.json(n),text)
    val saved = dao.find(n.id)?.takeIf { it.userEdited } ?: record
    // A running checkpoint is saved but never replaces edits to an older note.
    if (!editorDirty && (selected == null || selected?.id == n.id && selected?.userEdited != true)) { selected = saved; editor = saved.editedText }
   }
  }
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
   Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=20.dp, vertical=24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
     Column {
      Text("粘取",style=MaterialTheme.typography.headlineLarge, fontWeight=FontWeight.Bold)
      Text("把值得留下的，整理好。",style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
     Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(50)) {
      Text("仅在本机",Modifier.padding(horizontal=14.dp,vertical=8.dp),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
     }
    }
    if (!consent) {
     SectionCard {
      Text("开始前，先了解权限",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
      Text("粘取只在您点击开始后，读取所选应用当前页面的文字与图像，并执行有限翻页、滚动和展开回复。")
      Text("内容在本机处理，只有您主动分享或导出才离开本机。无障碍权限可能接触敏感信息，请仅在愿意采集的页面启动。")
      Text("本应用是个人内容整理工具，不是面向残障人士的辅助工具。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      Button(modifier=Modifier.fillMaxWidth(),onClick={ consent=true; getSharedPreferences("privacy",MODE_PRIVATE).edit().putBoolean("disclosure",true).apply() }) { Text("了解并同意，开始使用") }
     }
    } else {
     SectionCard {
      FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Text("采集工作台",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
       Text(if(state.connected) "● 已连接" else "○ 待连接",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
      }
      Text(if(state.connected) "打开小红书图文笔记，用悬浮入口选择内容。" else "先开启无障碍服务，再回到您想保存的笔记。",color=MaterialTheme.colorScheme.onSurfaceVariant)
      Button(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),onClick={if(state.connected) { CaptureHub.showControls(); message="切换到目标笔记后，点击悬浮面板中的「开始提取」。" } else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}) { Text(if(state.connected) "打开悬浮采集台" else "连接无障碍服务") }
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
       Metric("已采集图页", "${state.pagesCaptured} / ${state.reportedPages ?: "—"}",Modifier.weight(1f))
       Metric("已采集评论", "${state.commentsCaptured}",Modifier.weight(1f))
      }
      Text(state.message,style=MaterialTheme.typography.bodyMedium,color=if(state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
      state.error?.takeIf { it != state.message }?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
      if(state.running) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Button(enabled=state.stage!="stopping",onClick={if(state.paused) CaptureHub.resume() else CaptureHub.pause()}){Text(if(state.paused)"继续采集" else "暂停采集")}
       OutlinedButton(enabled=state.stage!="stopping",onClick={CaptureHub.stop()}){Text("结束并保存")}
      }
      if(state.connected) TextButton(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("管理无障碍权限")}
      Text("切换页面、锁屏或异常时会暂停。不同手机的触摸检测可能不同，您也可随时手动暂停或结束。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
     OutlinedButton(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!state.running && !importing,onClick={importImage.launch(arrayOf("image/*"))}) { Text(if(importing)"正在本机识别…" else "从相册导入 · 离线识字") }
    }
    if(message.isNotBlank()) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(16.dp)) {
     Text(message,Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodyMedium)
    }
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
     Text("本地笔记库",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
     Text("${notes.size} 条",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
    }
    OutlinedTextField(query,{query=it},placeholder={Text("搜索标题或正文")},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
    if(notes.isEmpty()) SectionCard {
     Text(if(query.isBlank()) "留住第一份灵感" else "没有找到相关笔记",style=MaterialTheme.typography.titleMedium)
     Text(if(query.isBlank()) "使用悬浮采集台，或导入一张图片。完成的内容会保存在这里。" else "试试更短的关键词，或清空搜索。",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    selected?.let { n ->
     SectionCard(Modifier.bringIntoViewRequester(editorRequester)) {
      FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Text("预览与校正",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
       if(editorDirty) Text("未保存",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelMedium)
      }
      Text("OCR 可能有误，导出前请检查。原始识别结果保留在 JSON 中。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(editor,{editor=it;editorDirty=true},modifier=Modifier.fillMaxWidth().heightIn(min=240.dp,max=480.dp),shape=RoundedCornerShape(16.dp),label={Text("Markdown 全文")})
      FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
       Button(enabled=editorDirty,onClick={
        val savingId=n.id; val savingText=editor
        scope.launch {
         try {
          dao.saveEdit(savingId,savingText)
          val saved=dao.find(savingId)
          if(selected?.id==savingId) { selected=saved; if(editor==savingText) editorDirty=false }
          message=if(editorDirty) "已保存刚才的版本；新修改尚未保存" else "校正已保存"
         } catch(e: kotlinx.coroutines.CancellationException) { throw e }
         catch(e: Exception) { message="保存失败，请重试" }
        }
       }){Text("保存")}
       TextButton(onClick={getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("粘取",editor));message="已复制到剪贴板"}){Text("复制")}
       TextButton(onClick={startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,editor)},"分享提取文本"))}){Text("分享")}
      }
      HorizontalDivider()
      Text("导出为",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
      FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
       OutlinedButton(onClick={exportContent=editor;saveFile.launch("粘取-${n.id}.md")}){Text("MD")}
       OutlinedButton(onClick={exportContent=NoteExporter.plain(editor);saveFile.launch("粘取-${n.id}.txt")}){Text("TXT")}
       OutlinedButton(onClick={exportContent=JSONObject(n.rawJson).put("editedText",editor).toString(2);saveFile.launch("粘取-${n.id}.json")}){Text("JSON")}
      }
     }
    }
    notes.forEach { n ->
     Card(Modifier.fillMaxWidth().clickable { if(editorDirty && selected?.id != n.id) pendingSelection=n else if(selected?.id != n.id) { selected=n; editor=n.editedText; editorDirty=false } },shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=if(selected?.id==n.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
      Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Text(n.title.ifBlank{"未命名笔记"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
       Text(n.editedText.replace(Regex("[#*\\n]+")," ").take(100),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       Text("${when(n.status){"complete"->"采集完成";"stopped"->"已停止 · 保留结果";else->"部分结果 · 待核对"}}  ·  ${SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(Date(n.capturedAt))}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
      }
     }
    }
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
     TextButton(enabled=!state.running && !importing,onClick={clearDialog=true}){Text("清空本地记录",color=MaterialTheme.colorScheme.error)}
     Text("不请求网络权限 · 内容留在您的设备",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Text("小红书图文适配 · 实机兼容性需验证",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
  }
  pendingSelection?.let { next -> AlertDialog(onDismissRequest={pendingSelection=null},title={Text("有尚未保存的校正")},text={Text("切换笔记将丢弃当前未保存的修改。您也可以返回先保存。")},confirmButton={TextButton(onClick={selected=next;editor=next.editedText;editorDirty=false;pendingSelection=null}){Text("放弃修改并切换")}},dismissButton={TextButton(onClick={pendingSelection=null}){Text("返回编辑")}}) }
  if(clearDialog) AlertDialog(onDismissRequest={clearDialog=false},title={Text("清空全部记录？")},text={Text("这会删除本机保存的采集文本，无法撤销。导出的文件不受影响。")},confirmButton={TextButton(onClick={scope.launch{dao.clear();CaptureHub.mutableResult.value=null;selected=null;editor="";editorDirty=false;pendingSelection=null;clearDialog=false;message="本地记录已清空"}}){Text("删除全部")}},dismissButton={TextButton(onClick={clearDialog=false}){Text("取消")}})
 }
 @Composable private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
   Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
  }
 }
 @Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
   Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
    Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
 }
}
