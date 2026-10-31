# 更新日志

本项目遵循「版本号 + CHANGELOG 顶部段落」的发版约定；`release` job 会把对应版本段落
原样贴进 GitHub Release。

## [1.0.0-rc2] - 2026-10-31

设置项点不动、备份存空包、播放页只是壳——这一版把「合并后没接上的线」一次性接完，并把首页插画换成原版资源。

### 修复

- **设置里点不进去（阻断）**：`SettingItem` 只在 `SWITCH/CHECKBOX` 分支注册点击监听，
  `CHEVRON/NONE`（跳转、单选、命令）整行没有 listener，`onClickAction` 永远不触发。
  改为「单一注册点 + 行内交互与外部动作叠加」，并补 4 条 Robolectric 回归用例
  （`SettingItemClickTest`，CI 跑）。
- **备份存了个空包**：`BackupManager` 读的是已经没人写的 SharedPreferences，设置迁到
  DataStore 后备份内容永远是空表。改为接 B4 的 `BackupPipeline`：
  设置（带类型前缀）/书签/历史/脚本/订阅/白名单 → zip + manifest(SHA256) → 本地或 WebDAV；
  恢复走整包校验 + 只增不减合并；WebDAV 密码改 Keystore 加密存储，旧明文自动迁移。
- **定时备份不生效**：`AutoBackupScheduler` 从未被调用，「每天/每周」现在真的会排 WorkManager，
  改了开关立即重排；新增 `BackupInterval.WEEK`。
- **播放页是壳**：点播放只弹「player 模块接入后由此起播」。现在直接拉起 B3 的
  `VideoPlayerActivity`（Media3 + PiP + 倍速 + 断点续播），进页面即起播。
- **加密 DNS 开关不生效**：之前只在冷启动读一次设置，界面里开/关要重启才起作用；
  现在切开关立即 start/stop 引擎。
- **退出清数据没接**：`PrivacyCleaner` 从未调用，现在 `isFinishing` 且开关打开时清
  缓存 / Cookie / WebStorage / 表单 / 历史（旋转等配置变更不会误清）。

### 界面资源

- 首页插画、新标签页插画改用**原版安装包里的海鸥图**（`res/kI.png`，裁掉透明留白后
  按 2.1:1 输出 WebP，xxhdpi + mdpi 两档），删掉之前手绘的 `img_bird.xml` 与渐变占位
  `bg_home_hero.xml`。
- 首页右下风景卡继续用原版 `res/kM.webp`（与安装包字节一致）。

### 审计

- 新增 `docs/audit/2026-10-30-merge-audit.md` 的「第二轮：可达性审计」：
  逐屏点检 30+ 入口、列出仍缺的接入（扫码解码、投屏已裁、下载速度字段）。

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
