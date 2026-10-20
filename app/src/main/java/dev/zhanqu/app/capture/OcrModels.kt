package dev.zhanqu.app.capture

/** Engine text and coordinates are retained verbatim, independently of the reading layout. */
data class OcrTextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
data class OcrTextBlock(val text: String, val lines: List<OcrTextLine>)
data class OcrRemovedArtifact(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val reason: String
)
data class OcrResult(
    val rawText: String,
    val blocks: List<OcrTextBlock>,
    val formattedText: String = rawText,
    val removedArtifacts: List<OcrRemovedArtifact> = emptyList(),
    val layoutVersion: String = "spatial-1"
)
