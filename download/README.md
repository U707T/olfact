# download · 下载（系统 DownloadManager + 分块 + M3U8 + muxer 合并）

只改本目录。对外入口：`DownloadFacade.get(context)`。

## 引擎选择

| 类型 | 引擎 | 说明 |
|---|---|---|
| 普通单文件 | `SYSTEM`（默认） | `DownloadManager`，断点/通知/队列全托管；`preferSystemDownloader=false` 时改走分块 |
| 普通单文件（大文件/需要分块） | `CHUNKED` | OkHttp Range 多线程 + part 文件续传 + 顺序合并 |
| m3u8 | `M3U8` | 解析 → 广告过滤 → 分片并发 → AES-128 解密 → TS→MP4 remux |
| mpd | 不支持 | `DownloadKind.DASH` 只做识别，不解析（方案无此需求） |

## 类地图

| 文件 | 作用 |
|---|---|
| `DownloadSpec` / `DownloadRecord` / `DownloadStatus` | 输入 / 持久化记录 / 状态机 |
| `SystemDownloader` | DownloadManager 封装（头、Metered、通知、进度查询） |
| `MediaProbe` | HEAD + `Range: bytes=0-0` 探测大小与 Range 支持 |
| `ChunkPlanner` / `ChunkDownloader` / `ChunkMerger` | 分块计划、并发下载（可续传）、按序合并 |
| `m3u8/M3u8Parser` | master/media、EXT-X-KEY、EXT-X-MAP、BYTERANGE、CUE-OUT、DATERANGE |
| `m3u8/AdBreakFilter` | 广告分片过滤（CUE-OUT / URL 关键词），全命中时回退完整列表 |
| `m3u8/M3u8Downloader` | 分片并发、重试、解密、进度 |
| `m3u8/TsRemuxer` | `TsExtractor` → `Mp4Muxer` remux（不引 ffmpeg） |
| `m3u8/M3u8Merger` | remux 失败自动降级"仅拼接 .ts"，保证一定有可播文件 |
| `queue/DownloadQueue` | 并发调度、进度节流写盘、取消/暂停/重试、系统下载对齐 |
| `queue/DownloadWorker` | WorkManager 约束（WiFi/充电）+ 队列驱动 |
| `queue/JsonDownloadStore` | 原子写 JSON 持久化（见下方"与方案差异"） |
| `notify/DownloadNotifier` | 进度/完成/失败通知，点击进下载列表 |
| `external/ExternalDownloaders` | 12 种外部下载器 deeplink（ADM/1DM/FDM/Aria2/迅雷…） |

## 用法

```kotlin
val facade = DownloadFacade.get(context).apply { contentActivity = DownloadListActivity::class.java }
val record = facade.enqueue(
    DownloadSpec(
        url = "https://cdn.example.com/live/index.m3u8",
        kind = DownloadKind.HLS,
        fileName = "第 1 集",
        referer = "https://example.com/play/1",
        pageTitle = "示例站",
    )
)
facade.queue.items.collect { list -> /* 渲染列表 */ }
facade.pause(record.id); facade.resume(record.id); facade.remove(record.id)
```

## 与方案的三处差异（已记录，勿在合并时回退）

1. **Room → JSON 原子写**：`DownloadStore` 是接口，默认实现 `JsonDownloadStore` 用 `tmp + rename` 持久化。
   Room 单库属于 B1 的 core 存储，B3 不引入 KSP 插件（会把构建风险转嫁给全部批次）。
   B1 落地 Room 后写一个 `RoomDownloadStore : DownloadStore` 即可替换，队列代码零改动。
2. **WorkManager 2.9.1 → 2.12.0**：2.9.1 在仓库里无法解析（404），2.12.0 已缓存且 API 兼容。
3. **新增 `kotlinx-serialization-json` 依赖**：只用于队列 JSON 编解码（不用 `@Serializable` 插件）。

## 验证

```bash
./gradlew :download:assembleDebug --offline
./gradlew :download:testDebugUnitTest --offline   # 37 个用例
```

覆盖：分块计划/合并/续传、M3U8 解析（master/media/KEY/MAP/BYTERANGE/CUE-OUT）、
广告过滤边界（含"全命中回退"）、AES-128、队列编解码、m3u8 端到端（MockWebServer：选码率→
下分片→过滤广告→合并→重试）。
