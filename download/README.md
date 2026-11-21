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
| `m3u8/M3u8Merger` | remux 失败时保留完整拼接流（TS / fMP4）；实际可播放性仍取决于编码与设备 |
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

## 恢复语义（重要）

- 自研引擎（CHUNKED/M3U8）：进程被杀后重进 → 自动重新排队，part/分片文件续传。
- 系统引擎（SYSTEM）：`DownloadManager` 在进程外继续下载，重进时复用 `systemDownloadId` 继续跟踪，**不会重复入队**；
  但系统下载器没有"暂停"，点暂停等于取消（进度丢失），恢复时重新下载。
- 入队只通过 `WorkManager` 调度，约束（仅 WiFi / 充电）由它保证；`DownloadQueue.kick()` 会绕过约束，只在"立即下载"时显式调用。

## 与方案的三处差异（已记录，勿在合并时回退）

1. **Room → JSON 原子写**：`DownloadStore` 是接口，默认实现 `JsonDownloadStore` 用 `tmp + rename` 持久化。
   Room 单库属于 B1 的 core 存储，B3 不引入 KSP 插件（会把构建风险转嫁给全部批次）。
   B1 落地 Room 后写一个 `RoomDownloadStore : DownloadStore` 即可替换，队列代码零改动。
2. **WorkManager 2.9.1 → 2.12.0**：2.9.1 在仓库里无法解析（404），2.12.0 已缓存且 API 兼容。
3. **新增 `kotlinx-serialization-json` 依赖**：只用于队列 JSON 编解码（不用 `@Serializable` 插件）。

## 验证

```bash
./gradlew :download:assembleDebug --offline
./gradlew :download:testDebugUnitTest --offline
```

覆盖：分块计划/合并/续传、M3U8 解析（master/media/KEY/MAP/BYTERANGE/CUE-OUT）、
广告过滤边界（含"全命中回退"）、AES-128、队列编解码、m3u8 端到端（MockWebServer：选码率→
下分片→过滤广告→合并→重试）。

### Windows 本地轻量验证（2026-11-21）

在仓库根目录执行：

```powershell
./tools/test-download-player.ps1 -ResolveDependencies # 首次解析小型 JVM 依赖
./tools/test-download-player.ps1                      # 之后可离线运行
./tools/test-download-player.ps1 -CheckRemuxTypes -ResolveDependencies # 可选：真实 Media3 API 编译
```

脚本复用工作区已有的隔离 JDK/Kotlin；其他环境通过 `OLFACT_JAVA_HOME`、
`OLFACT_KOTLIN_HOME`、`OLFACT_TEST_LIB_DIR` 指定工具与依赖目录，不安装 Android SDK。
依赖写到仓库外的工作区缓存，编译产物在 `download/build/pure-jvm/`，均不应入库。
完整 Gradle 编译、Android lint 和 Android 单测仍按 CI 执行。

本次 download/player 联合轻量回归 **124 例通过**。编译与执行真实的分块/HLS/文件/队列/
持久化/纯播放状态代码，HTTP 测试使用真实 OkHttp + 本地 MockWebServer。
`tools/download-player-support/` 仅替代 Android Context/URI/Environment/Log、
DownloadManager、通知、原生 TsRemuxer 和 Media3 MIME 常量；不证明系统下载器、通知、
后台服务、Media3 remux、PiP 或实际音视频播放通过。
另已用实际 Media3 1.9.0 common/extractor/muxer 的小型 AAR 对 TsRemuxer 做类型编译通过
（只替代 OptIn 标记注解），未执行媒体运行时；可用上面的 `-CheckRemuxTypes` 复现。

本轮修复要点：只删已完成系统下载记录时保留文件；恢复/重试重新调度且丢弃失效系统任务 id；
暂停一个任务不再取消同批其他任务，新任务及时使用空闲并发位；URL 大小写不同的媒体路径不再误去重；
Worker 中断保持可重新调度，迟到进度不覆盖暂停/删除；
队列写入失败可见且保留原队列，改名中断可从 `.bak` 恢复；分块严格校验范围并补齐短响应；
成品先暂存校验再发布，同名成品不覆盖，并发目标名预留；HLS 按重定向后的地址解析相对路径、
实际下载 BYTERANGE、按分片内容标识续传，半成品不作为缓存；remux 保留样本 offset 指向的
后续数据，结束封装失败视为 remux 失败。默认 HTTP 客户端使用 core 的可选 `AppDns`。

限制：不新增 DASH 下载、后台播放或其他已砍能力。HLS 仍只支持 identity AES-128，
其他加密方式明确失败；同一媒体列表中途切换初始化分片会明确报错。断点分块源在 URL 与长度
都不变时被服务器原地替换，仍需用户删除任务后重下；本轮不宣称跨版本远端内容一致性校验。
系统下载器的暂停/恢复、只删记录保留文件、后台约束与通知，以及真实 TS/fMP4 可播放性待设备验收。

### 本轮已读清单

基线 50 个跟踪文件全部逐文件阅读：30 个 `src/main` Kotlin、15 个测试、2 个资源、
README、构建脚本与 manifest；并阅读本轮新增文件。模块内没有未读自有文件；第三方依赖、
构建产物与完整历史审计文档不计入模块源码覆盖。

- 根：`README.md`、`build.gradle.kts`、`src/main/AndroidManifest.xml`。
- 主包：`ChunkDownloader`、`ChunkMerger`、`ChunkPlanner`、`DownloadConfig`、`DownloadFacade`、
  `DownloadKeys`、`DownloadKind`、`DownloadPaths`、`DownloadRecord`、`DownloadSettings`、
  `DownloadSpec`、`DownloadStatus`、`MediaProbe`、`SystemDownloader`（均为 `.kt`）。
- 子包：`external/ExternalDownloaders`；`m3u8/AdBreakFilter`、`Aes128Decryptor`、
  `M3u8Downloader`、`M3u8Merger`、`M3u8Model`、`M3u8Parser`、`TsRemuxer`、新增 `SampleData`；
  `notify/DownloadNotifier`；`queue/DownloadQueue`、`DownloadRecordCodec`、`DownloadStore`、
  `DownloadWorker`；`util/Files`、`Http`、`SpeedMeter`（均为 `.kt`）。
- 原测试：`AdBreakFilterTest`、`Aes128DecryptorTest`、`ChunkDownloaderTest`、`ChunkMergerTest`、
  `ChunkPartNameTest`、`ChunkPlannerTest`、`DownloadRecordCodecTest`、`M3u8DownloaderTest`、
  `M3u8MergerTest`、`M3u8ParserTest`、`MediaProbeTest`、`queue/JsonDownloadStoreRemoveAllTest`、
  `queue/RequeueCandidatesTest`、`util/FilesNameTest`、`util/SpeedMeterTest`。
- 新测试：`DownloadPathsTest`、`SampleDataTest`、`queue/DownloadQueueRegressionTest`、
  `queue/DownloadStoreRecoveryTest`、`util/HttpCancellationTest`（均为 `.kt`）。
- 资源：`src/main/res/values/strings.xml`、`src/main/res/values-en/strings.xml`。
