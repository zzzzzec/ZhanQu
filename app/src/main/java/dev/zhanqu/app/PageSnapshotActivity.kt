package dev.zhanqu.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SnapshotSaveState : ViewModel() {
    var saving by mutableStateOf(false)
    fun save(context: Context, source: File, uri: android.net.Uri) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: error("无法打开保存位置")
                }
                Toast.makeText(context, "ZIP 已保存", Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, "保存失败，请重新选择位置", Toast.LENGTH_LONG).show() }
            finally { saving = false }
        }
    }
}

/** Private review screen. Nothing leaves the app until the user chooses Save or Share. */
class PageSnapshotActivity : ComponentActivity() {
    private var file: File? = null
    private var pickerPending by mutableStateOf(false)
    private val saveState: SnapshotSaveState by viewModels()
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pickerPending = false
        val source = file
        if (uri != null && source != null) saveState.save(applicationContext, source, uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerPending = savedInstanceState?.getBoolean("pickerPending") ?: false
        val dir = File(cacheDir, "page-snapshots")
        val name = intent.getStringExtra("snapshotFile").orEmpty()
        file = name.takeIf { it.matches(Regex("page-[0-9]+-[a-f0-9-]+\\.zip")) }
            ?.let { File(dir, it) }?.takeIf { it.canonicalFile.parentFile == dir.canonicalFile && it.isFile }
        if (file == null) { Toast.makeText(this, "快照已过期，请重新导出", Toast.LENGTH_LONG).show(); finish(); return }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1A6954), onPrimary = Color.White,
                background = Color(0xFFF5F7F2), surface = Color.White, onSurface = Color(0xFF1C2F2D),
                onSurfaceVariant = Color(0xFF61736D))) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.height(16.dp))
                        Text("页面快照", style = MaterialTheme.typography.headlineMedium)
                        Text("截图 + 页面结构", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("已保存在本机", style = MaterialTheme.typography.titleMedium)
                                Text("ZIP · %.1f MB".format(java.util.Locale.ROOT, file!!.length() / (1024.0 * 1024.0)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("可能包含私人内容，发送前请确认。", style = MaterialTheme.typography.bodyMedium)
                                var details by remember { mutableStateOf(false) }
                                TextButton(onClick = { details = !details }) { Text(if (details) "收起说明" else "隐私说明") }
                                if (details) Text("截图不会自动打码；页面文字与截图仅在您选择保存或分享后交给对应应用。屏幕中未采集的区域留空。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = { share() }, enabled = !saveState.saving && !pickerPending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("分享 ZIP") }
                        OutlinedButton(onClick = { save() }, enabled = !saveState.saving && !pickerPending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (saveState.saving) "正在保存…" else if (pickerPending) "选择保存位置…" else "保存 ZIP")
                        }
                        TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭") }
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pickerPending", pickerPending)
        super.onSaveInstanceState(outState)
    }
    private fun save() {
        if (pickerPending || saveState.saving) return
        val source = file?.takeIf { it.isFile } ?: return
        pickerPending = true
        try { saveDocument.launch(source.name) }
        catch (_: Exception) { pickerPending = false; Toast.makeText(this, "无法打开保存位置选择器", Toast.LENGTH_LONG).show() }
    }
    private fun share() {
        val source = file?.takeIf { it.isFile } ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.pageSnapshots", source)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("页面快照", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享页面快照"))
        } catch (_: Exception) { Toast.makeText(this, "无法打开分享，请尝试保存 ZIP", Toast.LENGTH_LONG).show() }
    }
}
