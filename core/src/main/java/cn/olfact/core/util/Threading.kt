package cn.olfact.core.util

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 线程切换。模块之间统一走这里，别在各处 new Thread。
 */
object Threading {

    val main: CoroutineDispatcher get() = Dispatchers.Main.immediate

    val io: CoroutineDispatcher = Dispatchers.IO

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 已在主线程则同步执行，否则 post。 */
    fun onMain(block: () -> Unit) {
        if (isMainThread()) block() else mainHandler.post(block)
    }

    fun onMainDelayed(delayMs: Long, block: () -> Unit) {
        mainHandler.postDelayed(block, delayMs)
    }

    suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** 便捷：`Threading.launchIo(scope) { ... }`。 */
    fun launchIo(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(Dispatchers.IO, block = block)
}
