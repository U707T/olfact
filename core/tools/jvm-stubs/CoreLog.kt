package cn.olfact.core.log

object Log {
    fun w(message: String) = System.err.println(message)
    fun e(error: Throwable, message: String) = System.err.println("$message: ${error.message}")
}
