# B3 交付报告 · 播放 + 下载 + AI

> 分支 `feat/b3-media` ｜ 只改 `player/**`、`download/**`、`ai/**`（另加本报告文件）
> 状态：**代码完成 + 单测全绿 + 三模块 AAR 与整包 APK 构建通过**；真机项见文末清单。

## 1. 交付物对照

| 任务书条目 | 状态 | 落点 |
|---|---|---|
| `VideoPlayerActivity`（ExoPlayer + 自绘控制条） | ✅ | `player/.../VideoPlayerActivity.kt`、`ui/PlayerControlsView.kt` |
| `PipController`（31+ autoEnter，低版本降级） | ✅ | `player/.../PipController.kt` |
| `PlayerGesture`（双击/长按倍速/单击） | ✅ | `player/.../gesture/PlayerGestureController.kt` |
| `MediaItemFactory`（HLS/MP4/MPD + UA/Referer/Header） | ✅ | `player/.../MediaItemFactory.kt` |
| `FallbackPlayer`（20+ 包名 deeplink） | ✅（24 个） | `player/.../external/FallbackPlayer.kt` |
| `SystemDownloader` | ✅ | `download/.../SystemDownloader.kt` |
| `ChunkDownloader`（OkHttp Range、并发上限、块大小可配、续传） | ✅ | `download/.../ChunkDownloader.kt`、`ChunkPlanner.kt` |
| `M3u8Downloader`（解析/并发/重试/进度） | ✅ | `download/.../m3u8/M3u8Downloader.kt` |
| `M3u8Merger`（media3-muxer remux，失败降级拼接） | ✅ | `download/.../m3u8/TsRemuxer.kt`、`M3u8Merger.kt` |
| `DownloadQueue`（WorkManager 约束/并发/持久化） | ✅（持久化用 JSON，见 §4） | `download/.../queue/*` |
| `DownloadNotifier` | ✅ | `download/.../notify/DownloadNotifier.kt` |
| `ExternalDelegate`（11 种） | ✅（12 种） | `download/.../external/ExternalDownloaders.kt` |
| `ProviderStore`（多供应商 + Keystore 加密） | ✅ | `ai/.../ProviderStore.kt`、`crypto/KeystoreSecretBox.kt` |
| `ChatClient`（SSE 流式、可取消、重试、token） | ✅ | `ai/.../net/ChatClient.kt`、`SseFramer.kt` |
| `WebSummarizer`（抽正文 → 分段摘要 → 卡片） | ✅（Flow 事件流） | `ai/.../summarize/*` |
| `Translator`（划词浮层 + 整页双语回调） | ✅ | `ai/.../translate/*` |
| `AiError`（401/429/超时/超长） | ✅ | `ai/.../error/*` |
| 单测：M3U8 解析/分块合并/SSE 分帧 | ✅（另加 12 个测试类） | `player|download|ai/src/test/**` |

## 2. 验证记录

```bash
# 三模块构建
./gradlew :player:assembleDebug :download:assembleDebug :ai:assembleDebug --offline
# BUILD SUCCESSFUL；产物 player-debug.aar / download-debug.aar / ai-debug.aar

# 单测：15 + 37 + 29 = 81 个用例，0 失败
./gradlew :player:testDebugUnitTest :download:testDebugUnitTest :ai:testDebugUnitTest --offline
```

覆盖到的行为（不是"编译过"而已）：

- `ChunkDownloaderTest`：起一个 MockWebServer，4 线程 × 100B 分块下载 1000B，
  **逐字节比对**源文件；服务器不支持 Range 时退化单线程；预置 part 文件后续传（只补缺失字节）。
- `M3u8DownloaderTest`：master 选最高码率 → 下分片 → CUE-OUT 广告被过滤 → 合并；
  分片连续 500 两次后重试成功。
- `M3u8MergerTest`：remux 成功产 mp4；remux 抛错/输出为空 → 降级拼接 `.ts`，失败残file 被清掉。
- `M3u8ParserTest`：media/master、相对 URI、EXT-X-KEY/IV、EXT-X-MAP、BYTERANGE、CUE-OUT、引号内逗号。
- `AdBreakFilterTest`：CUE-OUT、URL 关键词、"全部命中回退完整列表"、拼接点 discontinuity。
- `SseFramerTest`：多行 data、注释、CRLF、未闭合事件。
- `ReadabilityExtractorTest`：script/style/nav/footer 去除、短段落丢弃、实体解码。
- 整包：`:app:assembleDebug` 通过（含 player Activity、PiP 属性、POST_NOTIFICATIONS 的 manifest 合并），
  debug APK 11.0 MB。

> 注：`app/build.gradle.kts` 缺少 Kotlin jvmTarget 配置，直接构建会报
> `Inconsistent JVM-target compatibility (java 17 vs kotlin 21)`。验证整包时我**临时**补了 17 并在验证后还原，
> 仓库里没有这处改动。请 B1 统一修复（见 §5）。

## 3. 对外接口（B5/B2 直接照抄）

```kotlin
// 1) 播放
PlaybackIntents.play(context, source.toPlaybackSource(), PlaybackOptions(startPositionMs = 0))

// 2) 下载
val facade = DownloadFacade.get(context).apply { contentActivity = DownloadListActivity::class.java }
facade.enqueue(DownloadSpec(url, kind = DownloadKind.HLS, fileName = "第 1 集", referer = pageUrl))
facade.queue.items.collect { list -> render(list) }   // StateFlow<List<DownloadRecord>>

// 3) AI
val ai = AiFacade.get(context)
val provider = ai.providers.selected() ?: return
ai.summarizer.summarize(provider, ai.providers.apiKey(provider), article).collect { ... }
ai.translator.translate(provider, key, text).collect { piece -> ... }
```

与 `core/api` 的适配是**单向 10 行**：`MediaItem -> PlaybackSource`（`player/README.md` 有现成代码）。
本模块没有 import `core` 或 `web`，符合"不依赖别人实现"的约束。

## 4. 与方案的三处差异（需在合并说明里保留）

1. **队列持久化用 JSON 原子写，不是 Room**：`DownloadStore` 保持接口；Room 单库属于 B1 的 core 存储，
   B3 引入 KSP 插件会把构建风险扩散到全部批次。B1 落地 Room 后加一个 `RoomDownloadStore` 即可，队列无改动。
2. **WorkManager 2.9.1 → 2.12.0**：2.9.1 在仓库解析 404；2.12.0 已缓存、API 兼容。
3. **download 增加 `kotlinx-serialization-json`**：仅用于队列编解码（不启用 `@Serializable` 插件）。

另外把 media3 依赖补全为 `muxer/extractor/common/datasource`（downloader 需要），player 增加 `exoplayer-dash`（MPD 播放）。

## 5. 需要其它批次配合（P0）

1. **B1**：给所有含 Kotlin 的模块补 JVM target 17，否则 `:app`、`:core`、`:web` 一加代码就构建失败：
   ```kotlin
   kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
   ```
   （或统一 `java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }`。）
2. **B1**：`core/api` 落地后提供 `MediaItem`（url/kind/title/pageUrl/headers/UA/Referer/live），
   字段名与 `PlaybackSource` 对齐可省掉适配层。
3. **B5**：`DownloadFacade.contentActivity` 要在 Application 启动时注入；通知权限（Android 13+）在下载页首启请求。
4. **B2**：整页双语用 `BilingualInjector` 的三个脚本（collect/apply/clear）；划词文本用 `selectionScript()`。

## 6. 真机验收清单（沙箱内无设备/无 Android 运行时，未执行）

| 项 | 步骤 | 预期 |
|---|---|---|
| 起播 | 嗅探列表点一条 m3u8 | 3s 内起播，控制条自动隐藏 |
| 倍速 | 点 `1.0x` 选 2.0x；长按画面 | 立即生效，松手恢复；重进页面记住速度 |
| 跳片头尾 | 传入 `skipIntroMs/skipOutroMs` | 对应时段出现按钮，点击精确 seek |
| PiP | 播放中按 Home | Android 12+ 自动进 PiP；8~11 手动进 |
| 断点 | 播到 1:00 退出再进 | 从 1:00 续播；播完自动清进度 |
| 系统下载 | 下 mp4 | 通知栏有进度，完成后可点开 |
| 分块下载 | 关掉 `preferSystemDownloader` 下大文件，中途杀进程 | 重进继续，part 文件续传 |
| m3u8 | 下带广告的直播回放 | 得到可播 mp4；失败时得到 .ts（日志有降级原因） |
| 队列 | 连排 3 个任务 | 同时最多 2 个；杀进程重进状态不丢 |
| AI | 总结一篇长文 | 10s 内出首字；点取消立即停；401/429 有中文提示 |
| 划词 | 长按选中英文 | 浮层流式出中文，可复制 |

## 7. 文件清单

```
player/  15 个 Kotlin 文件 + 2 个布局 + 10 个图标 + 主题/字符串（zh/en）
download/ 21 个 Kotlin 文件 + 2 个字符串文件
ai/      14 个 Kotlin 文件 + 2 个字符串文件
共 81 个单测用例（player 15 / download 37 / ai 29）
```
