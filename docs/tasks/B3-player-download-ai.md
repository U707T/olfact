# B3 · 播放 + 下载 + AI（M2 + M3 + M7，13 天）

> 分支：`feat/b3-media` ｜ 只改 `player/**`、`download/**`、`ai/**`

## 目标

把"嗅探到的视频能播、能下、能合"，加上 AI 三件套（供应商/网页总结/划词翻译）。

## 交付物

### 1. player（Media3 1.9.0，无 session）
- `VideoPlayerActivity`：`ExoPlayer` + 自定义 `PlayerView` 控制条（倍速、跳片头片尾、锁定）
- `PipController`：`PictureInPictureParams.setAutoEnterEnabled`（API 31+），低版本降级
- `PlayerGesture`：双击快进/快退、长按倍速（**只做这三个，视频亮度/音量手势已砍**）
- `MediaItemFactory`：从 `MediaSniffer` 的 `MediaItem` 构造 HLS/MP4/MPD 播放项，带 UA/Referer/Header
- `FallbackPlayer`：外部播放器委派（20+ 包名 deeplink 清单，从原版 `PlayerChooser` 抄包名）

### 2. download
- `SystemDownloader`：单文件走系统 `DownloadManager`（断点、通知、队列全托管）
- `ChunkDownloader`：多线程分块（OkHttp Range）、并发上限、分块大小可配
- `M3u8Downloader`：playlist 解析 → 分片并发下载 → 进度/失败重试
- `M3u8Merger`：`media3-muxer` 的 `Mp4Muxer` 做 TS→MP4 remux；失败降级"仅拼接 .ts"
- `DownloadQueue`：WorkManager 约束（WiFi/充电）、并发任务数、队列持久化（Room）
- `DownloadNotifier`：进度/完成/失败通知，点击进列表
- `ExternalDelegate`：ADM/IDM/FDM/Aria2 等 11 种外部下载器 deeplink
- **不做**：迅雷 SDK、磁力/BT

### 3. ai（`ai/`）
- `ProviderStore`：多供应商（name/baseUrl/apiKey/model），key 用 B1 的 Keystore 加密
- `ChatClient`：OkHttp + `okhttp-sse` 流式；可取消、超时、失败重试、token 计数
- `WebSummarizer`：Readability 抽正文 → 分段摘要 → 结果卡片（可复制、可追问）
- `Translator`：划词浮层（选中文本 → 流式译文）；整页双语对照由 B2 注入回调触发
- `AiError`：常见错误（401/429/超时/内容超长）给出可读提示

### 4. 单元测试
- M3U8 playlist 解析（含广告分片过滤的边界）、分块合并、SSE 分帧

## 验收

| 项 | 标准 |
|---|---|
| 播放 | 从嗅探列表点一条 m3u8 能起播，PiP 可切 |
| 倍速 | 0.5~3.0 生效，跳片头片尾精确到秒 |
| 下载 | 单文件走系统下载器；m3u8 下完得到可播放 mp4 |
| 队列 | 杀进程重进，任务状态不丢 |
| AI | 总结 10 秒内出首字，可随时取消；翻译支持中英互译 |

## 禁止

- 不引 ffmpeg（用 media3-muxer，失败就降级拼接）
- 不引 media3-session / 前台播放服务
- 不碰 `web/`、`app/`、`core/adblock`

## 开局 prompt

```
读 /workspace/olfact/docs/TECH-PLAN.md（§7 其它子系统里的下载/播放/AI）和 docs/tasks/README.md，
然后按 docs/tasks/B3-player-download-ai.md 执行。

分支 feat/b3-media，只改 player/、download/、ai/。
播放源用 core/api 的 MediaSniffer 数据类，不要依赖 web 模块实现。
先做 player（Media3 起播 + PiP + 倍速），再做 download（系统下载器 → 分块 → M3U8 → muxer 合并），
最后做 ai（供应商 → SSE → 总结 → 划词翻译）。
每步 ./gradlew :player:assembleDebug --offline 之类的单模块验证。
```
