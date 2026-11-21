package cn.olfact.download.m3u8
import java.io.File
object TsRemuxer {
    fun remux(files: List<File>, target: File, checkActive: () -> Unit = {}): File = error("Requires Media3 on Android")
}
