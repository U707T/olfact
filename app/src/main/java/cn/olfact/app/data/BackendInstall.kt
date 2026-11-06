package cn.olfact.app.data

import android.content.Context
import cn.olfact.ai.AiFacade
import cn.olfact.core.adblock.AdBlockManager
import cn.olfact.core.api.BrowserSurfaceFactory
import cn.olfact.core.api.DohManager
import cn.olfact.core.api.DownloadTask
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.api.ScriptSource
import cn.olfact.core.api.ScriptStore
import cn.olfact.core.api.UserScript
import cn.olfact.core.script.GmBridge
import cn.olfact.core.script.GmValueStore
import cn.olfact.core.script.ScriptSourceImpl
import cn.olfact.app.ui.browser.WebBrowserSurface
import cn.olfact.download.DownloadFacade
import cn.olfact.web.doh.DohManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 把 B2/B3/B4 的真实实现接到 B5 界面约定的 `core/api` 接口上。
 *
 * 为什么需要这一层：四个批次是并行开发的，各自按任务书的契约编码，
 * 合并时模型字段并不完全一致（例如 B3 的 `DownloadRecord` vs 面板的 `DownloadTask`、
 * B4 的 `cn.olfact.core.script.UserScript` vs 面板的轻量 `UserScript`）。
 * 适配集中在本文件，界面代码（38 屏）无需改动，模块内部实现也不用迁就 UI。
 *
 * 冷启动策略：只有「首帧就要用」的（嗅探回调、渲染器工厂）同步注册；
 * DoH / 拦截 / 脚本 / AI / 下载 都要读盘（订阅 JSON、脚本索引、队列文件），统一放到
 * 后台线程，Application.onCreate 不再等它们。界面读的是 `OlfactServices`，晚几百毫秒
 * 注册也能正常工作（面板在未注册时会显示「未接入」，下此进入即正常）。
 */
object ModuleBackends {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var doh: DohAdapter? = null

    /** 备份/恢复要拿到**注入用的同一份**仓库实例，否则恢复出来的脚本不会立刻生效。 */
    var scriptStore: cn.olfact.core.script.ScriptStore? = null
        private set

    /** 白名单/订阅的备份恢复后要 `reload()`，所以也留一个引用。 */
    var adBlockManager: AdBlockManager? = null
        private set

    /** 后台初始化是否已完成（诊断页/日志用）。 */
    @Volatile
    var ready: Boolean = false
        private set

    fun install(context: Context) {
        val app = context.applicationContext
        // 纯对象，无 IO：浏览器壳第一帧就要
        installBrowser(app)

        // 其余模块要读盘，挪到后台；失败也不该让进程起不来
        scope.launch {
            val began = System.currentTimeMillis()
            runCatching {
                installDoh(app)
                installAdBlock(app)
                installScripts(app)
                installAi(app)
                installDownloads(app)
            }.onFailure {
                android.util.Log.w("olfact", "模块初始化失败", it)
            }
            ready = true
            android.util.Log.i("olfact", "模块初始化完成，用时 ${System.currentTimeMillis() - began} ms")

            // 备份的数据段依赖脚本仓库与拦截管理器，必须排在它们之后
            runCatching { AppBackup.install(app) }
                .onFailure { android.util.Log.w("olfact", "备份初始化失败", it) }
        }
    }

    // ------------------------------------------------------------------ DoH (B2)

    private fun installDoh(app: Context) {
        if (OlfactServices.doh != null) return
        val manager = DohManagerImpl(app)
        val adapter = DohAdapter(manager, AppSettings.repo)
        doh = adapter
        OlfactServices.doh = adapter
        OlfactServices.dohController = adapter
        if (AppSettings.get(K.DOH_ENABLED)) {
            scope.launch { runCatching { adapter.startFromSettings() } }
        }
    }

    /** B2 渲染器用它判断某个 host 是否该走 DoH（粘性会话决策）。 */
    fun shouldUseDoh(host: String): Boolean = doh?.shouldUseDoh(host) ?: false

    // -------------------------------------------------------------- 拦截 (B4)

    private fun installAdBlock(app: Context) {
        if (OlfactServices.adBlockController != null) return
        val manager = AdBlockManager.get(app)
        adBlockManager = manager
        manager.setEnabled(AppSettings.get(K.AD_ENABLED))
        OlfactServices.adBlock = manager
        OlfactServices.adBlockController = AdBlockAdapter(manager)
    }

    // -------------------------------------------------------------- 脚本 (B4)

    private fun installScripts(app: Context) {
        if (OlfactServices.scriptStore != null && OlfactServices.scripts != null) return
        val store = cn.olfact.core.script.ScriptStore(File(app.filesDir, "userscripts"))
        scriptStore = store
        val gmValues = GmValueStore(File(app.filesDir, "gm"))
        val bridge = GmBridge(
            values = gmValues,
            http = GmBridge.defaultClient(),
            emit = { js -> InjectionBus.evaluate(js) },
            gmEnabled = { AppSettings.get(K.SCRIPT_GM_BRIDGE) }
        )
        val source: ScriptSource = ScriptSourceImpl(store, bridge)
        OlfactServices.scripts = source
        OlfactServices.scriptStore = ScriptAdapter(store, gmValues)
    }

    // ---------------------------------------------------------------- AI (B3)

    private fun installAi(app: Context) {
        if (OlfactServices.aiProviders != null) return
        OlfactServices.aiProviders = AiAdapter(AiFacade.get(app).providers)
    }

    // ------------------------------------------------------------ 下载 (B3)

    private fun installDownloads(app: Context) {
        if (OlfactServices.downloads != null) return
        val facade = DownloadFacade.get(app)
        // 通知点击要能回到应用里的下载详情
        facade.contentActivity = cn.olfact.app.MainActivity::class.java
        OlfactServices.downloads = DownloadAdapter(facade, scope, app)
    }

    // ------------------------------------------------------------ 浏览 (B2)

    private fun installBrowser(app: Context) {
        if (OlfactServices.browserSurfaceFactory != null) return
        OlfactServices.sniffer = SnifferSink
        OlfactServices.browserSurfaceFactory = BrowserSurfaceFactory { WebBrowserSurface(app) }
    }
}
