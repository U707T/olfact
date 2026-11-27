# olfact browser

用 Kotlin 重写「嗅觉浏览器 6.82」的核心能力：现代化、可维护、轻量。
原版（含规则引擎、迅雷 SDK、磁力、投屏等）只作为视觉与行为参照，范围见
[`docs/TECH-PLAN.md`](docs/TECH-PLAN.md)。

**源码版本：`1.0.0-rc16`（versionCode 16）** ·
[已发布版本](https://github.com/U707T/olfact/releases)

- **minSdk 24 / targetSdk 37**，Gradle 9.7.0 + AGP 9.0.1 + Kotlin 2.3.20，JDK 21
- 纯 View 体系（不引 Compose），release 开 R8 + 资源压缩 + ABI split
- 本地优先定向 JVM 回归；完整 Android 检查和发版按 GitHub Actions 执行

## 功能

- **浏览**：WebView 内核、多标签、默认浏览器接管、DoH（内置/自定义上游）、
  广告拦截、普通 UserScript、页内查找、长按菜单（锚定浮层）、错误页。
- **下载**：系统委派 + 分块 + M3U8（AES-128 流式解密 / remux），
  队列持久化、前台服务通知、全部暂停/继续。
- **播放**：Media3、PiP、倍速、断点续播；详情页只做起播入口，不伪造控制条。
- **数据**：书签 / 历史 / 收藏 / 脚本 / 设置；zip 备份 v2（本地 / WebDAV / 定时）。
- **工具**：扫一扫（CameraX + ZXing）、当前页二维码、网络日志。
- **AI**：OpenAI 兼容供应商、网页总结、整页翻译。

## 模块

```
app       壳、导航、界面、默认浏览器接管、模块装配（ModuleBackends）
core      日志/工具/设置(DataStore)/Room/加密 + adblock + script + backup + uikit
web       浏览内核（BrowserView/Client）+ DoH（规则/编码/竞速/隧道）
player    Media3 播放页、PiP、倍速与跳片头
download  系统委派 + 分块 + M3U8（AES-128/remux）+ 队列/通知
ai        供应商管理、SSE 流式、网页总结、整页翻译
```

依赖方向固定：`app → web/player/download/ai → core`，反向依赖会被编译期挡住
（`core/api` 里只有接口与纯数据契约）。

## 本地轻量验证与 CI

2026-11-21 新增 Windows 定向 JVM 入口。默认复用工作区相邻的 `.panelfm-tools/`
中的 JDK 21 / Kotlin 2.4.10 和 `.tools/olfact/lib/` 缓存；可用各脚本的
`-ToolRoot` / `-DependencyRoot` 指向已有工具。生产版本仍以 version catalog 为准，
轻量工具版本不同于生产 Kotlin 2.3.20，不能据此宣称 Android 构建通过。

在仓库根目录依次执行：

```powershell
# 首次获取 Maven 依赖；已有缓存时可跳过两条 fetch，并去掉 -ResolveDependencies
python ./web/tools/fetch-test-deps.py
python ./web/tools/fetch-test-deps.py --shared
./tools/test-download-player.ps1 -ResolveDependencies -CheckRemuxTypes
./core/tools/test-core-ai.ps1
./web/tools/test-web.ps1
./app/tools/test-app-data.ps1
```

应用集成入口依赖前两组 core/AI、web 的成功编译产物，会检查产物新鲜度。
依赖下载及替身范围详见[下载说明](download/README.md)、[核心审计](core/AUDIT.md)、
[Web 说明](web/README.md)、[应用轻量验证说明](app/tools/support-data/README.md)。
轻量回归使用实际文件、HTTP、队列、DNS 和业务实现，但替换 Android 平台边界；
不代表 WebView、DownloadManager、WorkManager、PiP、相机或原生播放已验证。

完整平台检查使用项目的 JDK 21、Android SDK 37 与 Gradle wrapper，在 CI 执行：

```powershell
./gradlew.bat testDebugUnitTest
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:lintDebug
```

有对应 Android 环境时也可本地执行；本地审计不安装重型 SDK。完整 CI 与签名产物状态以 GitHub Actions 对应提交/标签的运行结果为准。

app lint 仍是发布门禁。仅在 app 中临时停用 `FragmentLiveDataObserve`、
`FragmentBackPressedCallback`、`FragmentAddMenuProvider`：它们共用的 AndroidX 检测器在本项目超时，
[升级到 1.9.0 的试跑](https://github.com/U707T/olfact/actions/runs/45179024643/job/137707847110)也未在预算内结束。
新增 Fragment 视图相关的观察者、返回回调或菜单注册时，需人工确认使用 `viewLifecycleOwner`；
现有两处 Fragment 返回回调已核对。检测器修复后应移除此例外。
签名材料放仓库外，`keystore.properties` 已在 `.gitignore`；CI 使用仓库 secrets
（`OLFACT_KEYSTORE_B64` / `OLFACT_STORE_PASSWORD` / `OLFACT_KEY_ALIAS` / `OLFACT_KEY_PASSWORD`）。

## 发版

1. 改 `app/build.gradle.kts` 的 `versionName`（`versionCode` 递增）
2. `CHANGELOG.md` 顶部加同版本段落（CI 会把它贴进 Release）
3. 确认候选提交的完整 CI 通过后，创建并推送对应的新 tag，例如 `v1.0.0-rc16`；不要移动已有 tag
4. CI 跑单测 / lint / debug → 签 `assembleRelease` → 上传 APK 并创建 Release
   （`-rc` / `-beta` 自动标 prerelease）

## 文档

| 文件 | 内容 |
|---|---|
| [`docs/audit/2026-11-27-rc16-fixes.md`](docs/audit/2026-11-27-rc16-fixes.md) | **当前修复记录**：rc16 审计问题对照、本地验证与发布边界 |
| [`docs/audit/2026-11-21-full-audit.md`](docs/audit/2026-11-21-full-audit.md) | 上一轮六模块覆盖、修复与功能优化、本地验证和待 CI/设备项 |
| [`docs/audit/2026-11-05-post-rc11-fixes.md`](docs/audit/2026-11-05-post-rc11-fixes.md) | rc11 之后的历史修复与真机复核清单 |
| [`docs/audit/2026-11-01-rc11-status.md`](docs/audit/2026-11-01-rc11-status.md) | rc11 的逐屏对齐记录（历史对照） |
| [`docs/audit/archive/`](docs/audit/archive/) | 历史审计（rc4 / rc6 基线，不再更新） |
| [`docs/TECH-PLAN.md`](docs/TECH-PLAN.md) | 范围、选型、模块划分、DoH 设计 |
| [`docs/tasks/README.md`](docs/tasks/README.md) | 五批并行的切分、接口契约、交付说明（历史） |
| [`docs/refs/`](docs/refs/) | 原版截图参照 |
| [`core/adblock/INTEGRATION.md`](core/adblock/INTEGRATION.md) | 拦截 / 脚本 / 备份的对接说明 |

## 已知限制

- 当前 Windows 验证环境未配置 Android SDK/adb；视觉与原生集成需在设备补跑（见 `docs/tasks/B5-SMOKE.md`）。
- **资源嗅探只认带扩展名的网页请求**（`.m3u8/.mpd/.mp4/.m4v/.webm/.mkv/.flv/.mov/.m4a/.mp3/.aac/.ogg/.opus/.flac/.wav`，且必须是 GET）。
  无扩展名的媒体地址、`blob:`、DRM 内容与需要额外请求才能拿到播放列表的站点不保证能被发现；结果按「标签 + 页面」隔离，不跨页复用。
- **标签渲染缓存有界**：最多保留 3 个离屏渲染器，按最近使用顺序淘汰；可见页使用独立渲染器。
  缓存命中时保留网页状态，超过容量、内核进程退出或 Activity 重建时仍会重载。
  修改网页设置会丢弃离屏旧配置缓存，并刷新当前页。
- **无痕标签与普通标签共用进程默认 WebView profile 的 Cookie/缓存**：渲染器按标签固定无痕属性，
  不写历史、URL/标题不落盘；这不等于 Cookie 隔离，用完仍需手动清 Cookie。
- **HLS 外置音轨暂不支持合并**：遇到需要独立音轨的播放列表会明确拒绝下载，避免只保存视频却报告完成。
- **GM 原生桥尚未接入 WebView**：普通 UserScript 可用；`GM_getValue` 持久化、
  `GM_xmlhttpRequest` 等原生能力暂不可用，设置页已明确显示限制。
- 应用内默认 HTTP 客户端已接 DoH；系统 `DownloadManager` 与播放器的 Media3 HTTP 不经过此入口，
  WebView 的 QUIC 也不在本地 TCP 隧道覆盖范围。
- `libadblock_ffi.so` 默认由手动触发的 CI job 产出，缺失时降级 Kotlin 引擎（规则上限 2 万条）。
- 仅中文资源，未提供英文。
- 下载列表「文件管理」提供「复制目录路径 / 尝试打开系统文件管理器」；未接 SAF 目录选择器
  （下载目录在 `Android/data` 下，Android 11+ 的第三方文件管理器看不到）。

---

## 本地缓存

- Gradle、Android SDK、现有 JDK/Kotlin 工具和工作区 `.tools/olfact/lib/` 是可复用工具及依赖，勿整体删除。
- `web/build/pure-jvm-deps/` 是 Web 回归下载的 Maven 依赖；`*/build/` 下的测试输出可重建，
  但应用轻量集成验证需要先生成 core/AI 与 web 的产物，清理后需按顺序重跑。
- `.tools/olfact/build/core-ai/` 的 GUID 子目录隔离每次测试；`latest-success.txt` 标识最近通过的产物。
- 当前 Windows 的缓存大小、APK 大小和完整构建时长未重新测量；旧 Linux 沙箱数字不代表本机环境。
- 不清理签名材料或真实用户数据，也不为本地测试安装完整 Android/NDK 工具链。
