# olfact browser

用 Kotlin 重写「嗅觉浏览器 6.82」的核心能力：现代化、可维护、轻量。
原版（含规则引擎、迅雷 SDK、磁力、投屏等）只作为视觉与行为参照，范围见
[`docs/TECH-PLAN.md`](docs/TECH-PLAN.md)。

**当前版本：`1.0.0-rc11`（versionCode 11）** ·
[Release](https://github.com/U707T/olfact/releases/tag/v1.0.0-rc11)

- **minSdk 24 / targetSdk 37**，Gradle 9.7.0 + AGP 9.0.1 + Kotlin 2.3.20，JDK 21
- 纯 View 体系（不引 Compose），release 开 R8 + 资源压缩 + ABI split
- 发版由 GitHub Actions 完成，本地只做 `assembleDebug` / 单测

## 功能

- **浏览**：WebView 内核、多标签、默认浏览器接管、DoH（内置/自定义上游）、
  广告拦截、UserScript + GM 桥、页内查找、长按菜单（锚定浮层）、错误页。
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
web       浏览内核（BrowserView/Client/TabManager）+ DoH（规则/编码/竞速/隧道）
player    Media3 播放页、PiP、倍速与跳片头
download  系统委派 + 分块 + M3U8（AES-128/remux）+ 队列/通知
ai        供应商管理、SSE 流式、网页总结、整页翻译
```

依赖方向固定：`app → web/player/download/ai → core`，反向依赖会被编译期挡住
（`core/api` 里只有接口与纯数据契约）。

## 构建

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
./gradlew :app:assembleDebug            # 产物：app/build/outputs/apk/debug/*.apk
./gradlew testDebugUnitTest             # 六个模块的 JVM 单测
./gradlew :app:assembleRelease          # 需仓库根的 keystore.properties（见下）
```

签名材料不进仓库：本地放 `/workspace/olfact-keys/olfact-release.jks`，
`keystore.properties` 已在 `.gitignore`；CI 用仓库 secrets
（`OLFACT_KEYSTORE_B64` / `OLFACT_STORE_PASSWORD` / `OLFACT_KEY_ALIAS` / `OLFACT_KEY_PASSWORD`）。

## 发版

1. 改 `app/build.gradle.kts` 的 `versionName`（`versionCode` 递增）
2. `CHANGELOG.md` 顶部加同版本段落（CI 会把它贴进 Release）
3. push 打 tag：`git tag v1.0.0-rc11 && git push origin v1.0.0-rc11`
4. CI 跑单测 / lint / debug → 签 `assembleRelease` → 上传 APK 并创建 Release
   （`-rc` / `-beta` 自动标 prerelease）

## 文档

| 文件 | 内容 |
|---|---|
| [`docs/audit/2026-11-05-post-rc11-fixes.md`](docs/audit/2026-11-05-post-rc11-fixes.md) | **当前状态**：rc11 之后的修复轮（下载误删 / 备份顺序 / 设置不生效 / 启动默认值）+ 真机复核清单 |
| [`docs/audit/2026-11-01-rc11-status.md`](docs/audit/2026-11-01-rc11-status.md) | rc11 的逐屏对齐记录（历史对照） |
| [`docs/audit/archive/`](docs/audit/archive/) | 历史审计（rc4 / rc6 基线，不再更新） |
| [`docs/TECH-PLAN.md`](docs/TECH-PLAN.md) | 范围、选型、模块划分、DoH 设计 |
| [`docs/tasks/README.md`](docs/tasks/README.md) | 五批并行的切分、接口契约、交付说明（历史） |
| [`docs/refs/`](docs/refs/) | 原版截图参照 |
| [`core/adblock/INTEGRATION.md`](core/adblock/INTEGRATION.md) | 拦截 / 脚本 / 备份的对接说明 |

## 已知限制

- 沙箱无 KVM/adb，视觉与真机冒烟用例需在真机补跑（见 `docs/tasks/B5-SMOKE.md`）。
- **标签切换复用同一个 `BrowserFragment`，但 WebView 会换页**：per-tab surface 池 + host
  重绑未做，网页内的滚动/表单/视频进度在切标签时仍会丢；Fragment 与返回栈、首页不再被重建。
- `libadblock_ffi.so` 默认由手动触发的 CI job 产出，缺失时降级 Kotlin 引擎（规则上限 2 万条）。
- 仅中文资源，未提供英文。
- 下载列表「文件管理」只尝试拉起系统文件管理器，未接 SAF 目录选择器（失败时提示下载目录路径）。

---

## 本地构建缓存（沙箱）

> 沙箱里 `dl.google.com` / `maven.google.com` 基本不通，**Gradle 依赖重下不回来**。删任何缓存前先看 `/workspace/AI通用说明.md` §2。

| 路径 | 现状（2026-11-04） | 怎么办 |
|---|---|---|
| `app/build/intermediates` | 约 200 MB | **别删**（增量编译的底子；删了下次 2 min 全量） |
| 根目录 `build/` | 147 KB | 空壳，可删 |
| `~/.gradle/caches/modules-2` | 全局 3.4 GB | **别删**，全项目共享 |
| `~/.gradle/caches/9.7.0` | 全局，数 GB | **别删**（本项目用的就是这个版本） |
| `~/.gradle/daemon/9.7.0/*.log` | 每次构建一份 DEBUG 日志 | 可删，只留最近 3 个 |

- 本地节奏：`:app:assembleDebug` 增量约 30 s；`testDebugUnitTest`（六模块）约 20 s。
- debug APK 正常 **14.7 MB**（arm64 单 ABI）。发版仍交 CI，别在本地 `assembleRelease`。
