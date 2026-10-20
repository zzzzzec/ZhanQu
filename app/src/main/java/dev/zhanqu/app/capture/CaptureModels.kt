package dev.zhanqu.app.capture

import dev.zhanqu.app.core.ParsedComment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CaptureOptions(val images: Boolean = true, val comments: Boolean = false, val expandReplies: Boolean = false)
data class CapturedPage(val pageIndex: Int, val totalPages: Int?, val text: String, val contentHash: String, val state: String = "complete", val error: String? = null, val engine: String = "ML Kit Chinese bundled", val capturedAt: Long = System.currentTimeMillis(), val blocks: List<OcrTextBlock> = emptyList(), val rawText: String = text, val removedArtifacts: List<OcrRemovedArtifact> = emptyList(), val layoutVersion: String = "legacy", val inputWidth: Int? = null, val inputHeight: Int? = null)
data class CapturedNote(
    val id: String = UUID.randomUUID().toString(), val sourcePackage: String, val title: String = "", val author: String = "", val body: String = "",
    val capturedAt: Long = System.currentTimeMillis(), val pages: List<CapturedPage> = emptyList(), val comments: List<ParsedComment> = emptyList(),
    val status: String = "partial", val reportedPages: Int? = null, val displayedCommentCount: Int? = null,
    val endReached: Boolean = false, val expandedReplyGroups: Int = 0, val unexpandedGroups: Int = 0, val errors: List<String> = emptyList()
)
data class CaptureUiState(
    val connected: Boolean = false, val running: Boolean = false, val paused: Boolean = false,
    val stage: String = "idle", val message: String = "请先启用粘取无障碍服务", val pagesCaptured: Int = 0,
    val reportedPages: Int? = null, val commentsCaptured: Int = 0, val error: String? = null,
    val snapshotting: Boolean = false
)

/** No content is read until a user presses Start in the service overlay. */
object CaptureHub {
    internal val mutableState = MutableStateFlow(CaptureUiState())
    internal val mutableResult = MutableStateFlow<CapturedNote?>(null)
    internal val storageError = MutableStateFlow<String?>(null)
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()
    val result: StateFlow<CapturedNote?> = mutableResult.asStateFlow()
    internal var service: ZhanQuAccessibilityService? = null
    fun showControls() { service?.showControls() }
    fun hideControls() { service?.hideControls() }
    fun pause() { service?.pauseCapture("用户暂停") }
    fun resume() { service?.resumeCapture() }
    fun stop() { service?.stopCapture() }
}
