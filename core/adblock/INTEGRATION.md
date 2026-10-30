# B4 交付说明（拦截 / 脚本 / 数据）

本文件是给 B1（合并构建）、B2（web）、B5（UI）看的对接清单。三块代码都在 `core` 模块内，
包名分别是 `cn.olfact.core.adblock` / `.script` / `.backup`。

## 0. 目录与文件所有权

| 内容 | 路径 |
|---|---|
| 拦截 | `core/src/main/java/cn/olfact/core/adblock/**`、`core/src/main/assets/adblock/*.txt` |
| 脚本 | `core/src/main/java/cn/olfact/core/script/**` |
| 备份/隐私 | `core/src/main/java/cn/olfact/core/backup/**` |
| Rust FFI | `core/adblock/rust/**`、`core/adblock/ci-snippet.yml` |
| 单测 | `core/src/test/java/cn/olfact/core/{adblock,script,backup}/**` |
| 契约接口（临时落地） | `core/src/main/java/cn/olfact/core/api/{AdBlock,Injection}.kt` |

`core/api/AdBlock.kt`、`core/api/Injection.kt` 与 `docs/tasks/README.md` §3 **逐字一致**，
B1 产出同签名版本后直接覆盖即可，实现类不受影响。

## 1. B1 需要合并的两处改动

1. **`core/build.gradle.kts`**
   - 新增插件 `org.jetbrains.kotlin.plugin.serialization`；
   - 新增依赖：`kotlinx-serialization-json:1.11.0`、`okhttp:5.5.1`、`work-runtime-ktx:2.12.0`；
   - 新增 `kotlin { compilerOptions { jvmTarget = JVM_17 } }`（**基线缺这条，Java 17 与 Kotlin 默认 21 会直接编译失败**，`app/web/player/ai` 同样需要）。
2. **`.github/workflows/ci.yml`**：把 `core/adblock/ci-snippet.yml` 的 job 合进去（输出到 `core/src/main/jniLibs`）。

## 2. B2（web）怎么接

```kotlin
val adblock: AdBlockManager = AdBlockManager.get(context)   // start() 已在内部触发

override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
    val pageHost = view.url?.host.orEmpty()
    val type = ResourceType.guess(request.url.toString())   // 有 request.isForMainFrame 时可传 DOCUMENT
    if (adblock.block(request.url.toString(), pageHost, type)) {   // 判断 + 计数一步到位
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
    return null
}

// onPageStarted / onPageCommitVisible 时注入隐藏 CSS
val css = adblock.cosmeticCss(host)
if (css.isNotEmpty()) view.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=${'$'}{JSON.stringify(css)};document.documentElement.appendChild(s);})()", null)
```

UserScript：

```kotlin
// Application 启动时装配一次
val scriptStore = ScriptStore(File(filesDir, "userscripts"))
val gmBridge = GmBridge(GmValueStore(File(filesDir, "gm")), GmBridge.defaultClient(), emit = { js -> currentWebView?.evaluateJavascript(js, null) })
webView.addJavascriptInterface(GmJsInterface(gmBridge), "__olfactGmBridge")
val scriptSource: ScriptSource = ScriptSourceImpl(scriptStore, gmBridge)

// 页面开始时
scriptSource.documentStartScripts(url).forEach { webView.evaluateJavascript(it, null) }
// document-end / idle
scriptSource.scriptsFor(url, RunAt.DOCUMENT_END).forEach { webView.evaluateJavascript(it.js, null) }
```

> `ScriptSourceImpl.scriptsFor` 是 B4 额外提供的（契约里只有 document-start）。
> 用完记得在 WebView 销毁时 `gmBridge.shutdown()`。

## 3. B5（UI）怎么读

`AdBlockManager.state: StateFlow<AdBlockState>` 里已有设置页要的全部字段：

| 字段 | 用途 |
|---|---|
| `engineKind` / `nativeAvailable` / `nativeError` | "引擎：Rust / Kotlin 降级" |
| `ruleCount` | 规则总数 |
| `subscriptions` | 订阅列表（标题/开关/上次更新/条数/错误） |
| `stats.today` / `stats.total` / `stats.byType` | 拦截计数 |
| `whitelist` | 白名单 |

操作：`setEnabled` / `setSubscriptionEnabled` / `addSubscription` / `removeSubscription` /
`updateAll(force)` / `updateOne` / `addWhitelist` / `removeWhitelist` / `resetStats` / `flush`。

## 4. 备份怎么接

```kotlin
val pipeline = BackupPipeline(
    workDir = cacheDir,
    providers = BackupProviders(
        settings  = object : SettingsSection { /* 读写 DataStore */ },
        bookmarks = object : ListSection<BookmarkRecord> { /* 读写 Room */ },
        history   = object : ListSection<HistoryRecord> { /* 读写 Room */ },
        scripts   = ScriptBackupSection(scriptStore),
        subscriptions = SubscriptionBackupSection(SubscriptionStore(File(filesDir, "adblock"))),
        whitelist = WhitelistBackupSection(WhitelistStore(File(filesDir, "adblock/whitelist.json")))
    ),
    targetProvider = { BackupTarget.Local(File(filesDir, "backups")) }   // 或 WebDav(client, "olfact")
)
BackupRuntime.runner = pipeline                       // WorkManager 触发点
AutoBackupScheduler(context).schedule(BackupInterval.H6)
```

注意两点：

- 备份里的订阅/白名单是**另一个 store 实例**写盘的，导入完成后要调 `adblockManager.reload()`，
  否则内存里还是旧清单（`reload()` 会重读文件并重建引擎）。
- `ScriptBackupSection` 必须拿到**注入用的同一个** `ScriptStore` 实例，否则恢复出来的脚本不会立刻生效。

WebDAV：`WebDavClient(baseUrl, user, pass)` 支持 `list / put / get / delete / mkcol / exists / test`。
隐私：`PrivacyCleaner(context).clearAll(listOf(Cleanable { /* 清搜索记录 */ }))`。

## 5. 验收自查

| 项 | 状态 | 证据 |
|---|---|---|
| 订阅生效、典型广告位消失 | ✅ 单测 | `KotlinAdBlockEngineTest`（域名锚点/子域/例外/三方/类型/domain 限定） |
| 元素隐藏不占位 | ✅ | `cosmeticCss` 输出 `{display:none!important}`，`AdBlockManagerTest` |
| 计数与实际相符 | ✅ | `block()` 计数 + `BlockStats` 落盘，`AdBlockManagerTest` |
| 三个常用油猴脚本可跑 | ✅（能力齐） | `UserScriptTest` / `GmBridgeTest`（GM_getValue/setValue/xhr/addStyle/log/info） |
| 订阅更新（ETag/304/HTML 拒绝） | ✅ | `SubscriptionUpdaterTest` |
| 备份→清数据→恢复 | ✅ | `BackupPipelineTest`（真实 zip 往返 + 删除目录后恢复） |
| 备份损坏不破坏现有数据 | ✅ | `BackupRoundTripTest`（篡改 SHA / zip-slip / 版本过高） |
| WebDAV 上传/下载/列表 | ✅ | `WebDavClientTest`（PROPFIND/PUT/GET/DELETE/MKCOL） |
| `.so` 缺失仍能启动并拦截 | ✅ | `AdBlockEngineFactory` 兜底 + `AdBlockManagerTest`（`preferNative=false` 路径） |
| `.so` 真实编译 | ⏳ CI | 沙箱只有 cargo 1.75 且无 Android target；job 见 `core/adblock/ci-snippet.yml` |
