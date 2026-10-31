# 全功能审计：正常 / 主流 / 轻量（2026-10-31，rc4）

> 范围：六个模块 + 38 个界面。方法：逐屏点检入口是否接到实现、读依赖与构建产物量体积、
> 对照 Android / 浏览器类应用的通行做法。数字来自本机 `assembleRelease` 产物
> （`app-arm64-v8a-release.apk`，3.11 MB）与 `apkanalyzer`。

## 一、正常：功能是否真的能用

图例：✅ 已接通并有测试 · ⚠️ 可用但有已知限制 · 🚫 明确未接

| 功能 | 状态 | 证据 / 说明 |
|---|---|---|
| 浏览（多标签、UA、无图、夜间、长按、页内查找） | ✅ | `web` 55 条单测；`BrowserClient` 走拦截 → 嗅探 → DoH |
| 地址栏提链（一段文本多个链接） | ✅ | app / web 两份 `LinkExtractor` 各有单测 |
| 加密 DNS：三档分流 + 五路竞速 + L2 隧道 | ✅ | `DnsRules` / `DohDns` / `TunnelProxy` 单测 + 联网实测 |
| 加密 DNS：**上游选择 / 自定义上游** | ✅ | 本轮修：选择过去只写设置不生效；现在自动=竞速、选中=固定（失败才回落）、自定义=独占 |
| 广告拦截（订阅 / 计数 / 元素隐藏 / 白名单） | ✅ | B4 49 条单测；面板走 `AdBlockManager` |
| UserScript（GM_* / 三段注入） | ✅ | document-start / end / idle 均已注入 |
| 播放（Media3、倍速、跳片头、PiP、断点续播） | ✅ | 播放页已改为拉起 `VideoPlayerActivity` |
| 下载（系统委派 / 分块 / M3U8 + remux） | ⚠️ | 队列与解析有单测；**面板速度字段恒 0**（B3 未上报瞬时速度） |
| 嗅探 → 播放 → 下载 链路 | ✅ | `MediaItem` 契约 + `PlaybackSource` 映射 |
| AI（供应商 / 总结 / 划词翻译） | ⚠️ | 供应商与 SSE 有单测；总结 / 翻译的界面入口仍未接（能力在库里） |
| 备份（本地 / WebDAV / 定时） | ✅ | 已接 B4 `BackupPipeline`；含 SHA256 校验与合并 |
| 隐私（退出清数据 / 无痕） | ✅ | `PrivacyCleaner` 已接 `MainActivity.onDestroy` |
| 默认浏览器（ROLE_BROWSER + 外链分发） | ✅ | manifest 四组 filter；`requestDefaultBrowser()` |
| 设置体系（38 屏导航） | ✅ | `SettingItem` 点击入口已修，14 个入口 + 9 个子页全通 |
| 扫码 | ✅ | CameraX 取景 + ZXing 解码（Y 平面转灰度、按 `rotationDegrees` 摆正），不引第三方扫码 SDK |
| 投屏 / 小程序 / 视频手势 | 🚫 | 按 `TECH-PLAN §1.2` 明确裁掉 |

**结论**：主干（浏览 / DoH / 拦截 / 脚本 / 播放 / 下载 / 备份 / 设置）是通的；
缺口集中在扫码解码、AI 的界面入口、下载速度显示三处，都不影响主体。

## 二、主流：是否跟上通行做法

| 维度 | 现状 | 评价 |
|---|---|---|
| 构建链 | Gradle 9.7.0 + AGP 9.0.1 + Kotlin 2.3.20 + JDK 21 | ✅ 当前主流水位 |
| SDK | compileSdk / targetSdk 37，minSdk 24 | ✅ 覆盖绝大多数设备，且用上新行为 |
| 架构 | 6 模块单向依赖 `app → web/player/download/ai → core`，`core/api` 放契约 | ✅ 模块化清晰，无反向依赖 |
| 状态与存储 | DataStore（设置）+ Room（书签 / 历史 / 下载表）+ kotlinx.serialization | ✅ 无业务 SharedPreferences、无 Gson |
| 密钥 | Android Keystore AES-GCM（AI Key、WebDAV 密码） | ✅ 不落明文 |
| 网络 | OkHttp 5 + 自研 DoH（RFC 8484 编解码、竞速、隧道） | ✅ 无第三方 DoH 库，行为可控 |
| 播放 | Media3 1.9（不含 session） | ✅ 官方推荐路线 |
| 界面 | 纯 View + ViewBinding，无 Compose | ✅ 与「轻量」目标一致（Compose 会带 ~3 MB） |
| 返回与沉浸 | `enableOnBackInvokedCallback` + `OnBackPressedDispatcher`、`WindowCompat` edge-to-edge | ✅ 适配预测性返回 |
| 主题 | `Theme.Olfact`（MaterialComponents DayNight）+ `values-night` | ✅ 深色模式可用 |
| 混淆与瘦身 | R8 + 资源压缩 + 资源名混淆 + ABI split | ✅ 见第三节 |
| 发版 | tag → CI 签名 → GitHub Release，密钥走 secrets | ✅ 本地不出正式包 |
| 测试 | 6 模块 JVM 单测全绿；1 个 Robolectric UI 用例（CI 跑） | ⚠️ UI 层覆盖薄，靠真机冒烟补 |
| CI | 单测 + **lint** + assembleDebug + tag 发版 | ✅ 本轮补 lint job（0 error 放行，报告存档）；仍缺 instrumented 测试 |
| 依赖版本管理 | `gradle/libs.versions.toml` 统一管理插件与依赖版本 | ✅ 本轮完成迁移，模块里只剩 `libs.xxx` |
| 崩溃与诊断 | 自研 `Log` + 日志页，无第三方上报 | ✅ 隐私取向合理（用户可选） |
| 明文流量 | `usesCleartextTraffic=true` | ✅ 浏览器必需；非浏览器应用应加 network-security-config |

## 三、轻量：体积、内存、启动

### 3.1 体积

| 指标 | 数值 |
|---|---|
| release APK（单 ABI） | **3.11 MB** |
| 原版安装包（参照） | 41.7 MB（约 7%） |
| dex（未压缩） | 4.56 MB / 27k 个方法 |
| res | 0.63 MB（资源名已混淆，如 `res/3n.webp`） |
| resources.arsc | 0.24 MB |
| assets | 0.13 MB（内置拦截清单 + 脚本） |
| 原生库 | 7 KB（只有 datastore 的计数器 so） |

dex 占比前三：`androidx` 966 KB（其中 CameraX 774 KB）、`com.google.zxing` 320 KB、`cn.olfact` 270 KB。

### 3.2 本轮增减

- **加**：CameraX（`camera-core/camera2/lifecycle/view`）用于扫码取景，dex +774 KB、APK +260 KB；
  换来的是「相机预览 + 自动对焦 + 旋转帧解码」不用自己写 Camera1 回调。
- **减**：删掉全仓 0 引用的 `androidx.recyclerview`、`lifecycle-viewmodel-ktx`、`download` 的 `media3-datasource`。
- `download` 的 `kotlinx-serialization-json` 从 1.8.1 对齐到 1.11.0。

### 3.2.1 历史瘦身

- 删掉全仓 0 引用的 `androidx.recyclerview`、`lifecycle-viewmodel-ktx`。
- 删掉 `download` 里未使用的 `media3-datasource`（TsRemuxer 只用 extractor / muxer / common）。
- `download` 的 `kotlinx-serialization-json` 从 1.8.1 对齐到 1.11.0。
- 上一轮删依赖的结果是 APK 基本持平（±0.5 KB）——**R8 早就把无引用的类裁掉了**，
  真正决定体积的是「引了什么库」，不是「删了几个 compile 依赖」。

### 3.3 还能再瘦的地方（未做，记录）

| 项 | 潜在收益 | 代价 |
|---|---|---|
| 用 ~20 KB 的自研 QR 编码替换 `zxing-core` | ~300 KB dex | 要自己实现 Reed-Solomon，正确性风险高 |
| 把 `media3-exoplayer-dash` 变成可选 | ~40 KB | DASH 站点会退化 |

### 3.4 启动与内存

- `Application.onCreate` 只同步注册「首帧就要用」的嗅探回调与渲染器工厂；
  DoH / 拦截 / 脚本 / AI / 下载这五个要读盘的模块改到 IO 线程初始化，
  备份流水线挂在这条后台链路末尾（它依赖脚本仓库与拦截管理器）。
- `DownloadQueue` 首帧不再读队列文件（先给空表，后台 `publish()` 补一次）；
  WorkManager 的自动备份排程也移出主线程（首次 `getInstance` 要建库）。
- 效果：冷启动不再等订阅 / 脚本索引 / 队列 JSON 的读盘；代价是打开设置页的前几百毫秒里，
  拦截 / 脚本面板可能显示「未接入」，重新进入即正常。
- WebView 侧：8 个活动实例 + LRU 回收；release 不带调试校验库。
- 未引 LeakCanary（只在 debug 用也仍是一个依赖），按「轻量」暂不引。

## 四、结论与下一步

- **正常**：主干可用（浏览 / DoH / 拦截 / 脚本 / 播放 / 下载 / 备份 / 设置 / **扫码**）；
  剩 AI 总结与翻译的界面入口、下载面板速度字段两处。
- **主流**：技术栈与做法对齐 2026 年 Android 工程实践；lint 已进 CI，依赖版本已收进 catalog。
- **轻量**：单 ABI 3.11 MB（原版 41.7 MB ≈ 7.5%）；CameraX 占 260 KB，是这轮唯一的体积增长。

下一步：① AI 总结/翻译接界面；② 下载瞬时速度；③ instrumented 冒烟（真机）；
④ 资源里 49 个未使用项与 31 处硬编码文案（lint warning，可批量清）。
