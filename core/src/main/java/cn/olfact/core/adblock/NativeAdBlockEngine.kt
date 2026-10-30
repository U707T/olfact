package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log
import java.io.File

/**
 * adblock-rust 的 JNI 壳。
 *
 * Rust 侧（`core/adblock/rust`）先导出一层 C ABI
 * `olfact_adblock_engine_create/check/cosmetic/destroy`，再由同文件的 JNI 函数包一层；
 * 这样 `.so` 也能被非 JVM 的宿主直接 `dlopen`。
 *
 * **方法名/签名改动 = 二进制契约改动**：改了必须同步 `core/adblock/rust/src/lib.rs` 的导出名。
 */
class NativeAdBlockEngine private constructor(
    private var handle: Long,
    override val ruleCount: Int
) : AdBlockEngine {

    override val kind: EngineKind = EngineKind.RUST

    override fun check(url: String, sourceUrl: String?, type: ResourceType): Boolean {
        val h = handle
        if (h == 0L) return false
        return runCatching { nativeCheck(h, url, sourceUrl ?: "", type.code) }.getOrDefault(false)
    }

    override fun cosmeticSelectors(pageUrl: String): List<String> {
        val h = handle
        if (h == 0L) return emptyList()
        val raw = runCatching { nativeCosmetic(h, pageUrl) }.getOrNull().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeDestroy(h) }
    }

    // ---- 与 Rust 的 JNI 导出一一对应（见 core/adblock/rust/src/lib.rs）----
    private external fun nativeCreate(rulesText: String, enginePath: String?): Long
    private external fun nativeCheck(handle: Long, url: String, sourceUrl: String, typeCode: Int): Boolean
    private external fun nativeCosmetic(handle: Long, pageUrl: String): String
    private external fun nativeDestroy(handle: Long)

    companion object {
        internal fun create(rulesText: String, enginePath: File?): NativeAdBlockEngine? {
            if (!AdBlockNative.available) return null
            val instance = NativeAdBlockEngine(0L, SubscriptionUpdater.countRules(rulesText))
            val h = try {
                instance.nativeCreate(rulesText, enginePath?.absolutePath)
            } catch (t: Throwable) {
                B4Log.w("NativeEngine", "nativeCreate 失败，回退 Kotlin 引擎", t)
                return null
            }
            if (h == 0L) {
                B4Log.w("NativeEngine", "nativeCreate 返回 0，回退 Kotlin 引擎")
                return null
            }
            instance.handle = h
            return instance
        }
    }
}

/** `.so` 探测与版本信息。失败只记日志，绝不抛给调用方。 */
object AdBlockNative {

    const val LIBRARY = "adblock_ffi"

    @Volatile
    var loadError: String? = null
        private set

    val available: Boolean by lazy { load() }

    /** Rust 侧 `olfact_adblock_version()`，用于设置页显示"引擎：Rust 0.9.x"。 */
    val version: String by lazy {
        if (!available) "-"
        else runCatching { AdBlockNativeLib.nativeVersion() }.getOrDefault("unknown")
    }

    private fun load(): Boolean = try {
        System.loadLibrary(LIBRARY)
        B4Log.d("AdBlockNative", "已加载 lib$LIBRARY.so")
        true
    } catch (t: Throwable) {
        loadError = "${t.javaClass.simpleName}: ${t.message}"
        B4Log.w("AdBlockNative", "lib$LIBRARY.so 不可用，走 Kotlin 降级引擎（$loadError）")
        false
    }
}

/** 单独一层，避免 JNI 顶层函数和实例方法混在一起（导出名不同）。 */
internal object AdBlockNativeLib {
    external fun nativeVersion(): String
}
