package cn.olfact.download
import kotlinx.coroutines.flow.MutableStateFlow
/** Facade shape only: adapter mapping tests use actual DownloadRecord/Spec/Status sources. */
class DownloadFacade {
    val queue = Queue()
    @Volatile var onOperation: (String) -> Unit = {}
    suspend fun enqueue(spec: DownloadSpec) {}
    fun pause(id: String) { onOperation("pause:$id") }
    fun resume(id: String) { onOperation("resume:$id") }
    fun remove(id: String, deleteFile: Boolean) { onOperation("remove:$id:$deleteFile") }
    fun removeAll(ids: List<String>, deleteFile: Boolean) { onOperation("removeAll:${ids.joinToString()}:$deleteFile") }
    class Queue {
        val items = MutableStateFlow<List<DownloadRecord>>(emptyList())
        fun speedOf(id: String) = 0L
    }
}
