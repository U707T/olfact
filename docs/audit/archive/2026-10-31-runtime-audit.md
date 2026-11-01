# 运行时审计：性能 / 线程 / 进程（2026-10-31，对应 1.0.0-rc6）

> 范围：`app` / `core` / `web` / `player` / `download` / `ai` 六个模块，约 2.2 万行 Kotlin。
> 方法：静态取证（线程原语、主线程 IO、共享可变状态、无界集合、回调线程）+ 产物度量
> （dex 25,206 方法 / 3.65 MB，release APK 3.17 MiB/ABI）。
> **本次只出报告，没有改代码**；修复建议按 P0→P2 给出。

## 0. 结论（先看这五条）

| # | 问题 | 后果 |
|---|---|---|
| **P0-1** | GM 桥在 XHR 线程调 `evaluateJavascript` | WebView 非主线程调用，未定义行为，偶发崩溃 |
| **P0-2** | 嗅探结果 `SnifferFeed` 无锁跨线程写读 | 播放页/嗅探弹层可能 `ConcurrentModificationException` |
| **P0-3** | 历史/书签整表 JSON 在主线程序列化并落盘 | 每次翻页一次 jank（1000 条时约几十毫秒） |
| **P0-4** | 历史/书签/下载列表全量 `addView`（无回收） | 1000 条 ≈ 6000 个 View，主线程构建，长列表直接卡死 |
| **P0-5** | 下载跑在无前台服务的 `CoroutineWorker` 里 | Worker 有约 10 分钟执行上限，大文件会被打断重试 |

## 1. 线程模型全景

| 线程 | 谁在跑 | 约束 |
|---|---|---|
| **main** | Activity/Fragment、所有 View 构建、`DataStore.get`（内存快照）、`BookmarkStore.persist`、`HistoryStore.persist`、`DohManagerImpl.stop()`、`AppBackup.restore()` 前的 `readBytes()` | **不应有磁盘/网络/大对象序列化** |
| **Chromium 渲染/IO** | `shouldInterceptRequest`（嗅探、拦截判断）、`onPageFinished` 回调 | 回调里做重活会拖慢页面；**不可直接碰 UI** |
| **OkHttp Dispatcher** | 订阅更新、AI 流式、WebDAV、分块下载 | 自带线程池，需注意取消 |
| **`olfact-doh-dns`(4)** | DoH 查询并发 | 固定 4 线程 |
| **`olfact-tunnel`(cached)** | 本地 CONNECT 隧道，**每连接一个线程** | 页面并发资源多时会膨胀 |
| **`olfact-gm-xhr`(2)** | 油猴 `GM_xmlhttpRequest` | 回调 `emit` 直接调 WebView（见 P0-1） |
| **`olfact-scan`(1)** | CameraX 每帧解码 | 每帧 2 次整帧分配（见 P1-7） |
| **WorkManager** | 下载队列、定时备份 | 10 分钟执行上限（见 P0-5） |
| **8 个自建协程域** | `ModuleBackends`×2、`AppBackup`、`DataStoreSettings`、`AdBlockManager`、`DohManagerImpl`、`UpstreamPool`、`DownloadQueue` | App 级常驻，不取消（可接受） |

线程/协程原语统计：`launch {}` 26 处、`withContext` 44 处、`synchronized` 38 处、`@Volatile` 47 处、
`Dispatchers.IO` 38 处、`Dispatchers.Main` 1 处（其余走 lifecycleScope，默认主线程）。

## 2. P0：正确性 / 稳定性

### P0-1 GM 桥在非主线程操作 WebView
- 证据：`core/script/GmBridge.kt:163` `resolve()` 里 `emit(...)`；`emit` 实现是
  `app/data/ModuleBackends.kt` → `InjectionBus.evaluate` → `WebBrowserSurface.kt:92`
  `view.evaluateJavascript(js, null)`。`resolve` 由 XHR 执行线程（`olfact-gm-xhr`）调用。
- 影响：`evaluateJavascript` 必须在 UI 线程；跨线程调用是未定义行为，
  可能静默丢失回调，也可能抛异常。
- 修法：`InjectionBus.evaluate` 统一 `Handler(Looper.getMainLooper()).post { ... }`；
  `WebBrowserSurface.evaluateJavascript` 同样加主线程分派（对 AI 调用也更安全）。

### P0-2 嗅探结果跨线程无锁
- 证据：`app/ui/player/SnifferFeed.kt:12` `val items = mutableListOf<MediaItem>()`；
  写：`ModuleBackends` 的 `SnifferSink.onMediaDetected` ← `web/browser/BrowserClient.kt:45-71`
  的 `shouldInterceptRequest`（Chromium IO 线程）；读：`SnifferSheet` / `PlayerFragment`（主线程）。
- 影响：`add` 与 `filter` 并发 → `ConcurrentModificationException`；`items.any {}` 还是
  O(n) 线性判重，列表越长越慢。
- 修法：`synchronized` 或 `CopyOnWriteArrayList`；用 `LinkedHashSet` 判重；
  加容量上限（如 200 条，按 pageUrl 淘汰）。

### P0-3 主线程整表 JSON 持久化
- 证据：`app/data/BookmarkStore.kt:106-111` `persist()` 遍历全部条目构造 `JSONArray`
  再 `SharedPreferences.apply()`；调用点 `HistoryStore.record()`（第 76 行）由
  `BrowserFragment.onPageFinished` 触发（主线程），**每加载一个页面执行一次**。
- 影响：1000 条历史 ≈ 100 KB JSON 序列化 + 提交；低端机可见掉帧，
  上限设成「不限」(100000) 时会直接 ANR。
- 修法：`record()` 只更新内存 + 打脏标记，落盘节流（如 2s）并放 `Dispatchers.IO`；
  或迁到 `core` 已有的 Room `history` 表（B1 建了表，B5 没用）。

### P0-4 列表页全量 inflate（无回收）
- 证据：`HistoryFragment.kt:53-85`（按日期分组 `entries.forEach { addView }`）、
  `BookmarksFragment.kt:49`、`DownloadListFragment.kt:85`；每行 `ListRow` 含 4 个 TextView + 2 个 ImageView。
- 影响：默认上限 1000 条 → 约 6000 个 View 在主线程构建；「不限」档位直接卡死/ANR；
  滚动无复用，内存随条目线性增长。
- 修法：换 `RecyclerView`（需加回依赖，约 200 KB），或至少分页（先渲 50 条 + 「加载更多」）。

### P0-5 下载没有前台服务
- 证据：`download/queue/DownloadWorker.kt:22` `CoroutineWorker.doWork()` 里跑完整队列；
  全仓无 `setForeground` / `setExpedited` / `ForegroundInfo`。
- 影响：WorkManager 普通 Worker 有约 10 分钟执行上限，大文件会被掐断；
  `doWork` 返回 `retry` 后重新排队，用户看到「进度反复重置」。
- 修法：长下载改 `setForeground`（带进度通知，需 `FOREGROUND_SERVICE_DATA_SYNC` 权限），
  或把分块下载移出自研前台 Service；短期至少在 Worker 里加 `setForeground`。

## 3. P1：明显卡顿 / 资源风险

| # | 问题 | 证据 | 修法 |
|---|---|---|---|
| P1-6 | 每个分块都全量重排 + UI 全量重建 | `DownloadQueue.kt:330-340` 每次进度 `store.all().map{}.sortedByDescending{}`；`DownloadListFragment.kt:45` `flow.collect { render() }` | 进度单独 `StateFlow`，UI 只更新那一行；列表更新节流 250ms |
| P1-7 | 扫码每帧两次整帧分配 | `ScanActivity.decode()`：`ByteArray(w*h)` + `rotate()` 再分配；未设 `setTargetResolution`，1080p 每帧约 4 MB | `setTargetResolution(1280x720)`；复用缓冲；旋转只改读取顺序 |
| P1-8 | 隧道每连接一个线程 | `TunnelProxy.kt:71` `newCachedThreadPool` | 固定池（32）+ 队列，或 NIO/协程 |
| P1-9 | 备份全内存 + 主线程读文件 | `BackupPipeline.kt:102` `ByteArrayOutputStream`；`AppBackup.kt:121/140` `file.readBytes()` 在调用线程 | 流式 zip 到文件；`readBytes` 放 IO；预览只读 manifest |
| P1-10 | M3U8 分片整段进内存 | `M3u8Downloader.kt` `fetchBytesWithRetry` 返回 `ByteArray`，8 并发 | 边下边写临时文件；流式解密 |
| P1-11 | DoH 开关在主线程关隧道 | `DohFragment` toggle → `engine.stop()` → `tunnel.stop()` 关 socket + `shutdownNow` | 放 `Dispatchers.IO`，UI 立即置灰 |
| P1-13 | 8 个 WebView 常驻，无内存回调 | `TabManager.maxLiveTabs = 8`；全仓无 `onTrimMemory`/`onLowMemory` | 低端机降到 4；`onTrimMemory` 释放后台标签 |
| P1-14 | 设置页每次交互整页重建 | `SettingsPageFragment.refresh()`、`pages/*.render()` 都 `removeAllViews()` | 只更新变化行，或改 RecyclerView |

## 4. P2：可优化项

| # | 问题 | 证据 |
|---|---|---|
| P2-15 | 每次调用编译正则 | `app/link/LinkExtractor.kt:74,82` 在函数内 `Regex(...)`（web 版已预编译） |
| P2-16 | 首页/历史页每次 `onResume` 重建 | `HomeFragment.onResume`、`HistoryFragment.render()` |
| P2-17 | 嗅探结果无上限 | `SnifferFeed.items` 只增不减 |
| P2-18 | 标签页保存序列化在主线 | `BrowserSession.save()` 在 `MainActivity.onPause` |
| P2-19 | `AppLog` 假定单线程 | 目前调用点全在主线程（MainActivity / LogFragment），但 `ArrayDeque` + `listeners` 没有约束；将来后台线程一写就是数据竞争 | 加 `synchronized`（成本可忽略） |

## 5. 进程与生命周期

| 项 | 现状 | 评价 |
|---|---|---|
| 进程模型 | 单进程（未用 `android:process`） | 合理；WebView 渲染进程由系统托管 |
| 渲染进程崩溃 | `onRenderProcessGone` 已处理（拆 WebView + 提示） | ✅ rc5 已修 |
| 大内存 | `largeHeap=false` | ✅ |
| 内存回调 | 无 `onTrimMemory` / `onLowMemory` | ⚠️ P1-13 |
| 下载执行 | WorkManager（无前台服务） | ⚠️ P0-5 |
| 定时备份 | WorkManager 周期任务 | ✅ 轻量 |
| 依赖带入组件 | `androidx.startup.InitializationProvider`、`SystemJobService`、`SystemForegroundService`、Room `MultiInstanceInvalidationService`、CameraX `MetadataHolderService` | 正常；Room 多实例服务单进程用不到 |
| 进程死亡恢复 | 设置（DataStore）、标签（`BrowserSession`）、下载队列（JSON） | ⚠️ 历史/书签可能丢最后一次未落盘改动（同 P0-3） |
| 无痕 | Cookie 快照隔离 + 不写历史 | ✅ |

## 6. 启动与首帧预算（静态估算）

`Application.onCreate`：`Core.init` + `DataStoreSettingsRepository` 创建（异步加载）+
`BookmarkStore/HistoryStore` 读 `SharedPreferences`（**主线程**，条目多时几十毫秒）+
`ModuleBackends.install`（同步只装嗅探/渲染器）+ `AppBackup.install`（在 IO 链尾）。

首帧：`MainActivity` inflate + `Nav.openHome()` → 首页 5 快捷 + 3 统计 + 最近访问 + 语录，
每块都是代码 new View。

**建议**：书签/历史首次读盘也挪 IO（先给空列表，读完再通知），debug 包开 `StrictMode`
（diskRead/diskWrite/network）把主线程 IO 一次抓干净。

## 7. 怎么度量（复现与回归）

| 目标 | 方法 |
|---|---|
| 启动 | `adb shell am start -W -n cn.olfact.browser/.MainActivity`；Macrobenchmark `StartupTimingMetric` |
| 掉帧 | `adb shell dumpsys gfxinfo cn.olfact.browser reset` → 操作 → 再看 janky frames |
| 主线程 IO | debug 包临时开 `StrictMode` |
| 线程 | `adb shell ps -T -p <pid>`；Perfetto 看主线程长任务 |
| 内存 | `adb shell dumpsys meminfo cn.olfact.browser`；`am send-trim-memory` 模拟低内存 |
| 长下载 | `adb shell dumpsys jobscheduler | grep olfact` 看 Worker 是否被停 |

## 8. 修复路线（按投入产出）

1. **一天内**：P0-1（主线程分派）、P0-2（加锁 + 上限）、P2-19（日志加锁）、
   P2-15（正则提字段）、P1-11（DoH 停止放 IO）。
2. **两三天**：P0-3（落盘节流或迁 Room）、P1-6（进度分通道 + UI 节流）、
   P1-7（扫码分辨率与缓冲复用）。
3. **一到两周**：P0-4（三张列表换 RecyclerView）、P0-5（下载前台服务）、
   P1-9/P1-10（备份与 M3U8 流式化）。
4. **持续**：P1-13（按内存回收 WebView）、P1-14（设置页局部刷新）。

> 本报告只做审计，未修改代码。P0-1/P0-2 属于「偶发崩溃」类，建议下一版优先修；
> P0-3/P0-4 是用户能直接感知的卡顿，排在第二。
