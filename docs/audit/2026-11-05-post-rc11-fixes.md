# rc11 之后修复轮（2026-11-05）

> 基线：`1.0.0-rc11` / versionCode 11（未改版本号，全部改动在「未发布」段）
> 目标：把「会伤数据」和「设置改完不生效」两类问题清掉，并补上可回归的单元测试。
> 本地验证：`./gradlew testDebugUnitTest`（256 例 / 0 失败 / 2 跳过）、
> `:app:assembleDebug`（arm64 15.4 MB）通过。视觉与手势仍需真机复核（见 §5）。

## 1. 下载

| 问题 | 位置 | 修法 |
|---|---|---|
| 清空已下载会删除成品文件 | `ModuleBackends.DownloadAdapter.clearFinished()` | 改为 `remove(id, deleteFile = false)` |
| 「只删记录」实际不删、还变成失败 | `DownloadQueue.remove` 只有单参版本，适配层用 `cancel()` 顶替 | `remove(id, deleteFile)` 下沉到队列，适配层直传 |
| 下载速度恒为 0 B/s | `toTask()` 里 `speedBytesPerSec = 0L` | 新增 `SpeedMeter`，队列按采样增量估算，UI 读 `queue.speedOf(id)` |
| 列表可能停在 99% | `flow.sample(250)` 丢最后一条 | 队列侧 200ms 节流，终态走 `publish()`；界面改 `collect` |
| 进度回调过密 | 每次分片回调都重建整张列表、每 800ms 之外还刷通知 | UI 200ms / 落盘与通知 800ms |
| 防盗链站点下载 403 | `DownloadCenter.enqueue` 只接受 URL/文件名/页面地址 | 增加 referer / userAgent / mimeType / headers（带默认值） |
| 文件名丢失 | WebView `DownloadListener` 的 `contentDisposition` 未使用 | 新增 `DownloadNames` 解析；`BrowserView` 的 pageUrl 改为当前页 |
| `video.mp4.mp4` | `DownloadPaths` 无条件拼扩展名 | `Files.ensureExtension` + `uniqueFile` 同步修正 |
| 探测多发一次完整 GET | `MediaProbe` 调的是 `Http.request()`（GET） | 新增 `Http.headRequest()`，非 2xx 再退回 Range 探测 |
| 并发任务只有一条通知刷新 | `lastProgressAt` 是 companion 单值 | 按 recordId 分别节流 |

## 2. 备份 / 数据 / 隐私

- 历史恢复保留 `visitedAt`（`HistoryStore.importAll` 按时间倒序合并）。
- 书签、收藏改为批量导入：保留创建时间与顺序（逐条 `add(0, …)` 会反转）。
- 脚本恢复走 `ScriptStore.restoreOne()`：不要求 `==UserScript==` 头，
  保留 `installedAt/updatedAt/enabled`。原实现用 `install()`，无头脚本恢复「假成功」。
- `PrivacyCleaner.clearCache()` 跳过 `cache/downloads` 与 `cache/backup-work`，
  避免「清缓存」把进行中的分片删掉；探测用 WebView 用完 `destroy()`。
- `FavoriteStore` 补锁（播放页、备份恢复、列表页可能并发）。
- 路径硬化：分块 part 名、任务临时目录、脚本 id 都做字符清洗；
  「删除下载」只允许删下载目录内的文件（queue.json 损坏也不会误删别处）。

## 3. 设置与浏览器行为

- DoH：`applyMode` / `applyRules` 让模式与规则立即生效；规则编辑区保留草稿；
  「导入/导出」实现为剪贴板导入导出。
- 网页设置：`BrowserSurface.refreshConfig()`，`BrowserFragment.onResume` 先应用配置再 reload。
- UserScript：`BrowserClient.onBeforeNavigate` + `loadUrl` 前注册 document-start 脚本。
- 地址条 / 页内查找的 `addressVisible`、`findVisible` 在 `onViewCreated/onDestroyView` 重置。
- 首页「文件下载」只统计 `DONE`。
- 广告拦截的 `lastRefreshAt` 只在引擎真正构建时更新。
- 自定义搜索模板必须含 `%s`，运行时缺占位符回退默认引擎。

## 4. 启动 / 播放 / 内部

- `AppSettings.init` 等 `awaitLoaded(1s)`：修冷启动夜间模式、字体缩放、广告开关回落默认值。
- 播放器 `onStart` 重建被 `onStop` 释放的播放器；`onNewIntent` 换片前保存进度。
- 播放页起播按 URL 从 `SnifferFeed` 取回嗅探到的请求头（Cookie / Authorization / UA），
  不再只补 Referer；`SnifferSheet` 也改为先记入 Feed 再打开播放页。
- DoH 隧道：处理池 32→64，转发线程独立池，每条隧道 2 线程（原 3）。
- `UpstreamPool` 用 `AtomicBoolean` 保证同一时刻只有一场竞速。
- 确认对话框新增 `positiveLabel`：清空/关闭/取消收藏不再一律显示「删除」。

## 5. 仍需真机复核

1. 下载：完成一条 → 列表左滑/长按删除记录 → 文件仍在；「清空已下载」后文件仍在；
   详情页「删除下载」文件被删。
2. 下载中点心「清除缓存」→ 任务继续（分片没被删）。
3. 网页设置逐项改（无图 / 夜间 / 字号 / 桌面 UA / JS）→ 返回浏览器立即生效。
4. DoH 开、切模式、改规则 → 状态页的规则数与模式同步变化，不需重启。
5. 安装一个 document-start 脚本 → 点链接进入新页面也生效。
6. 播放中切后台再回来 → 从原进度继续，不是黑屏。
7. 强制夜间 + 字体缩放：冷启动即生效（不再先跟随系统）。
8. 并发下载 2–3 个文件 → 每条通知的进度都在动；点通知进详情。
9. M3U8 下载 → 成品 `.mp4` 可播放（remux 模式修正后，不再动不动降级成 `.ts`）。
10. 超过 10 分钟的大文件下载 → 不被系统掐断（前台服务类型已声明）。

## 6. lint：从 3 个 error 修到 0

`./gradlew :core:lintDebug :download:lintDebug :player:lintDebug :web:lintDebug :ai:lintDebug :app:lintDebug`
全部跑过，0 error（warning 只存档）。

| 模块 | 问题 | 修法 |
|---|---|---|
| download | `SpecifyForegroundServiceType`：Android 14+ WorkManager 前台服务未声明类型 | 清单里 `tools:node="merge"` 覆盖 `SystemForegroundService`（app 合并清单已确认 `dataSync`） |
| download | `MissingPermission`：无通知权限时 `notify` 会抛 | 先 `checkSelfPermission(POST_NOTIFICATIONS)` |
| download | `WrongConstant`：`TsExtractor(MODE_HLS)` 实际传的是 payload reader flags | 显式 `TsExtractor(MODE_HLS, 0, DEFAULT_TIMESTAMP_SEARCH_BYTES)` |
| player | `UnsafeOptInUsageError` × 11（Media3 `@UnstableApi`） | 类上 `@androidx.annotation.OptIn(UnstableApi::class)` |

> 库模块单独 lint 看不到依赖 AAR 的清单合并结果，`download` 里对该检查做了精确 disable，
> 真正的服务类型声明仍然生效（已验证 app 的 merged manifest）。

## 7. 本轮新增测试

```
download/src/test/.../SpeedMeterTest.kt           7
download/src/test/.../FilesNameTest.kt            6
download/src/test/.../MediaProbeTest.kt           4
web/src/test/.../DownloadNamesTest.kt             8
core/src/test/.../ScriptBackupSectionTest.kt      4
app/src/test/.../SearchEnginesTest.kt            10
```

总计 258 例（rc11 基线 218 例），2 例跳过（Robolectric 在 aarch64 沙箱不可用）。
