package dev.zhanqu.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zhanqu.app.capture.*
import dev.zhanqu.app.data.*
import dev.zhanqu.app.export.NoteExporter
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps in-progress text and pending export intact across configuration changes. */
class NoteEditorState : ViewModel() {
 val selected = mutableStateOf<StoredNote?>(null)
 val editor = mutableStateOf("")
 val dirty = mutableStateOf(false)
 val exportContent = mutableStateOf<String?>(null)
}

@OptIn(ExperimentalLayoutApi::class)
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
  var editor by editorState.editor
  var editorDirty by editorState.dirty
  var exportContent by editorState.exportContent
  var message by remember { mutableStateOf("") }
  val snackbar = remember { SnackbarHostState() }
  var importing by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  var consent by remember { mutableStateOf(getSharedPreferences("privacy",MODE_PRIVATE).getBoolean("disclosure", false)) }
  var clearDialog by rememberSaveable { mutableStateOf(false) }
  var leaveDialog by rememberSaveable { mutableStateOf(false) }
  var help by rememberSaveable { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  var exportMenu by remember { mutableStateOf(false) }
  val libraryScroll = rememberLazyListState()
  val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA) }
  fun closeEditor() { if(editorDirty) leaveDialog=true else selected=null }
  BackHandler(enabled=selected != null) { closeEditor() }
  LaunchedEffect(message) {
   if(message.isNotBlank()) { snackbar.showSnackbar(message, withDismissAction=true); message="" }
  }
  val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
   val content = exportContent
   exportContent = null
   if(uri != null && content == null) message="导出已中断，请重新导出"
   else if(uri != null && content != null) scope.launch {
    message=runCatching {
     contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("无法打开文件")
     "已导出"
    }.getOrElse { "导出失败，请重试" }
   }
  }
  val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
   if(uri != null) scope.launch {
    importing=true
    try {
     val result=LocalImageOcr.recognize(applicationContext,uri)
     val markdown=NoteExporter.markdown(result)
     val record=StoredNote(result.id,result.title,result.author,result.sourcePackage,result.capturedAt,result.status,NoteExporter.json(result),markdown)
     if(!editorDirty) { selected=record; editor=markdown; editorDirty=false }
     message="图片已识别并保存"
    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
    catch(e: Exception) { message="识别失败：${e.message ?: "请换一张图片重试"}" }
    finally { importing=false }
   }
  }
  LaunchedEffect(captured) {
   captured?.let { n ->
    // Refresh only an already-open note. Background checkpoints never navigate away from the library.
    if(!editorDirty && selected?.id == n.id && selected?.userEdited != true) {
     val record=StoredNote(n.id,n.title.ifBlank{"未识别标题"},n.author,n.sourcePackage,n.capturedAt,n.status,NoteExporter.json(n),NoteExporter.markdown(n))
     val saved=dao.find(n.id)?.takeIf { it.userEdited } ?: record
     // Room suspends: the user may edit or leave while the lookup is in flight.
     if(!editorDirty && selected?.id == n.id && selected?.userEdited != true) {
      selected=saved; editor=saved.editedText
     }
    }
   }
  }
  Scaffold(containerColor=MaterialTheme.colorScheme.background, snackbarHost={SnackbarHost(snackbar)}, contentWindowInsets=WindowInsets.safeDrawing) { insets ->
   val note=selected
   if(note == null) {
    LazyColumn(Modifier.fillMaxSize().padding(insets),state=libraryScroll,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
     item {
      Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
       Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("粘取",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text("收集 · 整理 · 留下",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       }
       Box {
        TextButton(onClick={menu=true}) { Text("更多") }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
         DropdownMenuItem(text={Text("使用帮助")},onClick={menu=false;help=true})
         if(consent) DropdownMenuItem(text={Text("权限设置")},onClick={menu=false;startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))})
         DropdownMenuItem(text={Text("清空笔记",color=MaterialTheme.colorScheme.error)},enabled=!state.running && !importing,onClick={menu=false;clearDialog=true})
        }
       }
      }
     }
     item {
      if(!consent) SectionCard {
       Text("开启页面采集",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
       Text("开启无障碍权限后，粘取会在您点击开始时读取当前页面文字与图像，并自动翻页、滚动和展开回复。页面快照只读取您当前打开的页面，不翻页、不点击。",style=MaterialTheme.typography.bodyMedium)
       Text("内容在本机处理，仅在您分享或导出时离开设备。无障碍权限可能接触敏感信息，请仅在您愿意采集的页面启动。",style=MaterialTheme.typography.bodyMedium)
       Text("粘取是个人内容整理工具，不是面向残障人士的辅助工具。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       Button(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),onClick={consent=true;getSharedPreferences("privacy",MODE_PRIVATE).edit().putBoolean("disclosure",true).apply()}) { Text("同意并继续") }
      } else SectionCard {
       Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(if(state.running) { if(state.paused) "采集已暂停" else "正在采集" } else "保存眼前的内容",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        StatusLabel(if(state.connected) "已就绪" else "待连接")
       }
       if(state.running) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
         Metric("图页", "${state.pagesCaptured} / ${state.reportedPages ?: "—"}",Modifier.weight(1f))
         Metric("评论", "${state.commentsCaptured}",Modifier.weight(1f))
        }
        Text(state.message,style=MaterialTheme.typography.bodySmall,color=if(state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
         Button(modifier=Modifier.weight(1f),enabled=state.stage!="stopping",onClick={if(state.paused) CaptureHub.resume() else CaptureHub.pause()}) { Text(if(state.paused) "继续" else "暂停") }
         OutlinedButton(modifier=Modifier.weight(1f),enabled=state.stage!="stopping",onClick={CaptureHub.stop()}) { Text(if(state.stage=="stopping") "正在保存…" else "结束并保存") }
        }
       } else {
        Text(if(state.connected) "在小红书图文笔记中，用悬浮按钮开始；其他应用可导出页面快照。" else "连接无障碍服务，即可从页面采集。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),onClick={if(state.connected) { CaptureHub.showControls(); message="悬浮入口已打开，前往想保存的笔记" } else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}) { Text(if(state.connected) "打开悬浮采集" else "连接无障碍服务") }
        state.error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
       }
       HorizontalDivider(color=MaterialTheme.colorScheme.surfaceVariant)
       TextButton(modifier=Modifier.fillMaxWidth(),enabled=!state.running && !importing,onClick={importImage.launch(arrayOf("image/*"))}) {
        if(importing) { CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if(importing) "正在识别图片…" else "从图片导入")
       }
      }
     }
     item {
      Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
       Text("笔记",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
       Text(if(query.isBlank()) "${notes.size}" else "${notes.size} 个结果",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
     item {
      OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("搜索标题或正文")},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth(),trailingIcon=if(query.isNotEmpty()) {{ TextButton(onClick={query=""}) { Text("清除") } }} else null)
     }
     if(notes.isEmpty()) item {
      SectionCard {
       Text(if(query.isBlank()) "第一份收藏，从这里开始" else "没有找到相关笔记",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
       Text(if(query.isBlank()) "采集页面或导入图片，文字会自动保存到这里。" else "换个关键词，或清空搜索。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
       if(query.isNotBlank()) TextButton(onClick={query=""}) { Text("查看全部笔记") }
      }
     }
     items(notes,key={it.id}) { n ->
      Card(Modifier.fillMaxWidth().clickable { selected=n; editor=n.editedText; editorDirty=false },shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
       Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(n.title.ifBlank{"未命名笔记"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(n.editedText.replace(Regex("[#*\\n]+")," ").take(120),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
         Text(dateFormat.format(Date(n.capturedAt)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
         StatusLabel(when(n.status) { "complete" -> "已保存"; "stopped" -> "部分内容"; else -> "待核对" })
        }
       }
      }
     }
    }
   } else {
    BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
     val compactEditor = maxHeight < 500.dp * LocalDensity.current.fontScale
     Column(Modifier.fillMaxWidth().then(if(compactEditor) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
      TextButton(onClick={closeEditor()}) { Text("返回笔记") }
      Button(enabled=editorDirty && !saving,onClick={
       val savingId=note.id; val savingText=editor
       saving=true
       scope.launch {
        try {
         dao.saveEdit(savingId,savingText)
         val saved=dao.find(savingId) ?: error("笔记不存在")
         if(selected?.id==savingId) { selected=saved; if(editor==savingText) editorDirty=false }
         message=if(editorDirty) "已保存；还有新修改" else "已保存"
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { message="保存失败，请重试" }
        finally { saving=false }
       }
      }) { Text(if(saving) "保存中…" else "保存") }
     }
     Text(note.title.ifBlank{"未命名笔记"},style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
     Text(if(editorDirty) "未保存的修改" else "${dateFormat.format(Date(note.capturedAt))} · 已保存",style=MaterialTheme.typography.labelMedium,color=if(editorDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
     OutlinedTextField(value=editor,onValueChange={editor=it;editorDirty=it != note.editedText},modifier=Modifier.fillMaxWidth().then(if(compactEditor) Modifier.heightIn(min=240.dp,max=400.dp) else Modifier.weight(1f)),shape=RoundedCornerShape(20.dp),label={Text("正文 · Markdown")},textStyle=MaterialTheme.typography.bodyLarge)
     FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
      OutlinedButton(onClick={getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("粘取",editor));message="已复制"}) { Text("复制") }
      OutlinedButton(onClick={startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain";putExtra(Intent.EXTRA_TEXT,editor) },"分享笔记"))}) { Text("分享") }
      Box {
       OutlinedButton(enabled=exportContent==null,onClick={exportMenu=true}) { Text("导出") }
       DropdownMenu(expanded=exportMenu,onDismissRequest={exportMenu=false}) {
        DropdownMenuItem(text={Text("Markdown (.md)")},onClick={exportMenu=false;exportContent=editor;saveFile.launch("粘取-${note.id}.md")})
        DropdownMenuItem(text={Text("纯文本 (.txt)")},onClick={exportMenu=false;exportContent=NoteExporter.plain(editor);saveFile.launch("粘取-${note.id}.txt")})
        DropdownMenuItem(text={Text("完整数据 (.json)")},onClick={exportMenu=false;runCatching { JSONObject(note.rawJson).put("editedText",editor).toString(2) }.onSuccess { exportContent=it;saveFile.launch("粘取-${note.id}.json") }.onFailure { message="原始数据无法读取，请导出文本" }})
       }
      }
     }
     }
    }
   }
  }
  if(leaveDialog) AlertDialog(onDismissRequest={leaveDialog=false},title={Text("保留这次修改？")},text={Text("返回后，未保存的修改将丢失。")},confirmButton={TextButton(onClick={leaveDialog=false}) { Text("继续编辑") }},dismissButton={TextButton(onClick={selected=null;editorDirty=false;leaveDialog=false}) { Text("放弃修改") }})
  if(clearDialog) AlertDialog(onDismissRequest={clearDialog=false},title={Text("清空全部笔记？")},text={Text("本机笔记将被永久删除，导出的文件不受影响。")},confirmButton={TextButton(onClick={scope.launch {
   try { dao.clear();CaptureHub.mutableResult.value=null;selected=null;editor="";editorDirty=false;clearDialog=false;message="笔记已清空" }
   catch(e: kotlinx.coroutines.CancellationException) { throw e }
   catch(e: Exception) { clearDialog=false;message="清空失败，请重试" }
  }}) { Text("删除全部",color=MaterialTheme.colorScheme.error) }},dismissButton={TextButton(onClick={clearDialog=false}) { Text("取消") }})
  if(help) AlertDialog(onDismissRequest={help=false},title={Text("使用粘取")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   HelpItem("页面快照","在任意应用的目标页面打开悬浮入口，点击页面快照，生成截图与节点 ZIP 后自行保存或分享；不会自动上传。受保护页面、密码区域和遮挡窗口不会导出。")
   HelpItem("采集页面","连接无障碍服务，打开悬浮入口，再前往小红书图文笔记。选择内容后点击开始。采集结果会自动保存。")
   HelpItem("暂停与继续","切换页面或锁屏会暂停采集。您也可以随时暂停，或结束并保存已有内容。")
   HelpItem("整理与导出","点开笔记可修改文字，保存后保留您的校正。复制、分享和导出使用当前正文；JSON 另含原始识别结果。识别文字可在这里核对。")
   HelpItem("本机处理","粘取不请求网络权限。页面文字和所选图片均在设备上处理，仅在您主动分享或导出时离开本机。不同设备和页面的采集效果可能不同。")
  }},confirmButton={TextButton(onClick={help=false}) { Text("知道了") }})
 }
 @Composable private fun HelpItem(title: String, body: String) {
  Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
   Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
   Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
 @Composable private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
   Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
  }
 }
 @Composable private fun StatusLabel(text: String) {
  Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(50)) {
   Text(text,Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
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
