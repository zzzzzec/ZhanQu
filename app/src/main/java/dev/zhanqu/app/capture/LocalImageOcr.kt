package dev.zhanqu.app.capture

import android.content.Context
import dev.zhanqu.app.data.NoteDatabase
import dev.zhanqu.app.data.StoredNote
import dev.zhanqu.app.export.NoteExporter
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Explicit SAF-selected image only; no filesystem scan, uploads or background collection. */
internal object LocalImageOcr {
    suspend fun recognize(context: Context, uri: Uri): CapturedNote = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val resolver = context.contentResolver
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri)) { decoder, info, _ ->
            require(info.size.width.toLong()*info.size.height <= 20_000_000L) { "图片超过2000万像素，请先裁切需要的正文区域" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val engine=MlKitChineseEngine()
        try {
            // ML Kit owns the bitmap until its Task completes even if the Activity leaves foreground.
            withContext(NonCancellable) {
                val result=engine.recognize(bitmap)
                val note=CapturedNote(capturedAt=startedAt,sourcePackage="local.selected-image",title="本地图片 OCR",reportedPages=1,
                    status=if(result.formattedText.isBlank()) "partial" else "complete",
                    pages=listOf(CapturedPage(1,1,result.formattedText,"",state=if(result.formattedText.isBlank()) "needs_review" else "complete",
                        blocks=result.blocks,rawText=result.rawText,removedArtifacts=result.removedArtifacts,layoutVersion=result.layoutVersion,inputWidth=bitmap.width,inputHeight=bitmap.height)),
                    errors=listOf("本地选择图片的识别结果，未进行原应用页序或评论采集；请人工核对文字。"))
                val markdown=NoteExporter.markdown(note)
                withContext(Dispatchers.IO) { NoteDatabase.get(context.applicationContext).notes().saveCheckpoint(
                    StoredNote(note.id,note.title,note.author,note.sourcePackage,note.capturedAt,note.status,NoteExporter.json(note),markdown)) }
                note
            }
        } finally { engine.close();bitmap.recycle() }
    }
}
