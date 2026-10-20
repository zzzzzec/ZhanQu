package dev.zhanqu.app.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dev.zhanqu.app.core.OcrLayoutProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface OcrEngine : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap, pageIndex: Int? = null, totalPages: Int? = null): OcrResult
}

internal class MlKitChineseEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    override suspend fun recognize(bitmap: Bitmap, pageIndex: Int?, totalPages: Int?): OcrResult {
        val recognized = suspendCoroutine<OcrResult> { continuation ->
            // The full-resolution bitmap is passed directly; no JPEG round trip or downsampling.
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
                val result = try {
                    val blocks = text.textBlocks.map { block -> OcrTextBlock(block.text, block.lines.map { line ->
                        val r = line.boundingBox ?: android.graphics.Rect()
                        OcrTextLine(line.text, r.left, r.top, r.right, r.bottom)
                    }) }
                    OcrResult(text.text, blocks)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                    return@addOnSuccessListener
                }
                continuation.resume(result)
            }.addOnFailureListener { continuation.resumeWithException(it) }
        }
        // ML Kit success listeners can run on Main. Geometry work must not block the controls.
        val layout = withContext(Dispatchers.Default) {
            OcrLayoutProcessor.process(recognized.rawText, recognized.blocks, bitmap.width, bitmap.height, pageIndex, totalPages)
        }
        return recognized.copy(formattedText = layout.formattedText, removedArtifacts = layout.removedArtifacts)
    }
    override fun close() { recognizer.close() }
}
