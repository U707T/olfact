# 更新日志

本项目遵循「版本号 + CHANGELOG 顶部段落」的发版约定；`release` job 会把对应版本段落
原样贴进 GitHub Release。

## [1.0.0-rc1] - 2026-10-30

首个合并版：B1 地基、B2 浏览内核 + DoH、B3 播放/下载/AI、B4 拦截/脚本/备份、B5 界面复刻
五条并行分支首次合入主干并出包。

### 新增

- **浏览内核**：`BrowserView`（UA/缩放/无图/夜间/长按/下载）、`BrowserClient`（拦截 → 嗅探 →
  DoH 粘性）、多标签与 LRU 回收、Cookie 快照隔离、地址栏 200ms 防抖与文本提链、站点配置、UA 管理。
- **加密 DNS（DoH）**：自研 RFC1035/8484 编解码、五路上游竞速、TTL/负缓存、bootstrap IP 表、
  本地 CONNECT 隧道 + `ProxyController`（L2）、熔断与切网重建，三档分流规则（智能/仅指定/仅排除）。
- **播放**：Media3 播放页、倍速/跳片头、PiP、嗅探结果列表与收藏。
- **下载**：系统 DownloadManager 委派 + 自研分块 + M3U8（AES-128 解密、广告分片过滤、TS remux）、
  队列/并发约束、断点续传、通知、外部下载器委派。
- **AI**：OpenAI 兼容多供应商（密钥 Keystore AES-GCM 加密）、SSE 流式、网页总结（Readability +
  分块）、划词/整页翻译。
- **拦截**：adblock-rust JNI 壳 + Kotlin 倒排降级引擎（2 万条上限）、uBO/ABP 子集解析、
  订阅更新（ETag/304）、元素隐藏、白名单、拦截计数。
- **脚本**：UserScript 元数据解析、GM_* 桥、document-start/end/idle 三段注入。
- **数据**：zip + JSON 备份（manifest + SHA256 校验、zip-slip 防护）、只增不减合并、WebDAV、
  WorkManager 定时备份、隐私清理。
- **界面**：38 个界面、`core/uikit` 组件库（BottomBar/UrlBar/SettingItem/…）、设置页声明式 Spec、
  默认浏览器接管（`RoleManager.ROLE_BROWSER`）、外链分发与多链接批量开标签。
- **CI**：push 跑单测 + `assembleDebug`；打 `v*` tag 自动签名出 `assembleRelease` 并发 Release。

### 变更 / 集成

- 设置统一走 `core` 的 DataStore 单例（原 B5 的 SharedPreferences 实现删除），备份与各模块读同一份。
- 新增 `app/data/ModuleBackends.kt`：把 B2/B3/B4 的实现适配到 `core/api` 契约
  （`DownloadRecord→DownloadTask`、B4 `UserScript→` 面板模型、`DohStatus` 映射等）。
- `core/api` 在冻结的五个契约之外，补上 UI 需要的扩展文件：`BrowserSurface.kt`、
  `AdBlockPanel.kt`、`Script.kt`。
- `core/script/ScriptStore` 新增 `save(meta, code)`，脚本编辑器不再依赖 `==UserScript==` 头。
- `core` 的 OkHttp 依赖改为 `api`（`GmBridge.defaultClient()` 的签名里是 `OkHttpClient`）。
- ABI split 产物为 `app-<abi>-{debug,release}.apk`，CI 上传路径改为通配。

### 移除

- B5 兜底实现里的明文 AI 供应商存储（`LocalProviderStore`）：密钥只能进 Keystore 加密存储。
- B2 自带的 `core/api/MediaSniffer.kt` 重复契约（与 m0 的 `Media.kt` 冲突）。

### 已知限制

- 无真机验收：沙箱没有 `/dev/kvm`、adb 不可用，视觉对照表见 `docs/tasks/B5-DELIVERY.md` §3，
  20 条手工冒烟用例见 `docs/tasks/B5-SMOKE.md`，需在真机上补跑。
- 扫码仅到「相机 + 取景框 + 手动输入」，ZXing 解码未接。
- `libadblock_ffi.so` 需手动触发 CI job 编译；未带 .so 时自动降级 Kotlin 引擎。
- release 体积 2.8 MB/ABI（debug 12.6 MB/ABI），低于 15 MB 目标。
