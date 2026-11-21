package android.os
class Message
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun removeCallbacks(runnable: Runnable) {}
    fun postDelayed(runnable: Runnable, delayMs: Long) = true
}
object Build {
    object VERSION { const val SDK_INT = 35 }
    object VERSION_CODES { const val Q = 29 }
}
