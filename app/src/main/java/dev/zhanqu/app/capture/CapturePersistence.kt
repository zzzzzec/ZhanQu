package dev.zhanqu.app.capture

import android.content.Context
import dev.zhanqu.app.data.NoteDatabase
import dev.zhanqu.app.data.StoredNote
import dev.zhanqu.app.export.NoteExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** Serial process-owned writes; only the newest checkpoint per note is retained while the DB is busy. */
internal object CapturePersistence {
    private data class Pending(val context: Context, val note: CapturedNote)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val failedIds = mutableSetOf<String>()
    private val saveFailure = "本地保存失败，请立即打开结果导出；尚有未成功保存的记录"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch {
            for (ignored in signal) {
                while (pending.isNotEmpty()) {
                    val next = pending.entries.firstOrNull() ?: break
                    if (!pending.remove(next.key, next.value)) continue
                    val (context, note) = next.value
                    try {
                        val markdown = NoteExporter.markdown(note)
                        NoteDatabase.get(context).notes().saveCheckpoint(StoredNote(note.id, note.title, note.author,
                            note.sourcePackage, note.capturedAt, note.status, NoteExporter.json(note, markdown), markdown))
                        failedIds -= note.id
                    } catch (_: Exception) {
                        failedIds += note.id
                    }
                    withContext(Dispatchers.Main) {
                        val previous = CaptureHub.storageError.value
                        val error = if (failedIds.isEmpty()) null else saveFailure
                        CaptureHub.storageError.value = error
                        if (error != null || CaptureHub.mutableState.value.error == previous)
                            CaptureHub.mutableState.value = CaptureHub.mutableState.value.copy(error = error)
                    }
                }
            }
        }
    }
    fun enqueue(context: Context, note: CapturedNote) {
        pending[note.id] = Pending(context.applicationContext, note)
        signal.trySend(Unit)
    }
}
