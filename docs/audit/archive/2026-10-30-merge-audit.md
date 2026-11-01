# 首次合并 · 发版审计（2026-10-30）

> 范围：`feat/b2-web` … `feat/b5-ui` 四条分支合入 `master`（B1 = m0 基线），
> 版本 `1.0.0-rc1`。本文件记录**发现的问题、处理方式、验证证据**，以及仍欠的账。

## 0. 验证证据

| 项 | 命令 | 结果 |
|---|---|---|
| 全模块单测 | `./gradlew testDebugUnitTest` | ✅ core / web / player / download / ai / app 六个模块全绿 |
| Debug 出包 | `./gradlew :app:assembleDebug` | ✅ 12.6 MB/ABI（`app-arm64-v8a-debug.apk`） |
| Release 出包 | `./gradlew :app:assembleRelease` | ✅ 2.8 MB/ABI，R8 + 资源压缩 + ABI split |
| 签名 | `apksigner verify --print-certs` | ✅ V2，证书 `CN=olfact browser, OU=U707T` |
| 冲突残留 | `grep -rn "<<<<<<<"` | ✅ 0 处 |
| 明文密钥 | `grep -rIn "ghp_\|github_pat\|api_key=\|password=\"…"` | ✅ 0 处 |

## 1. 🔴 阻断性问题（已修）

| # | 问题 | 位置 / 证据 | 处理 |
|---|---|---|---|
| 1 | CI 上传路径与 ABI split 不匹配：`assembleDebug` 产出的是 `app-<abi>-debug.apk`，workflow 写死 `app-debug.apk` + `if-no-files-found: error`，第一次 push 必红 | `.github/workflows/ci.yml` vs `app/build.gradle.kts` 的 `splits.abi` | 上传路径改 `app/build/outputs/apk/debug/*.apk`，release job 同理 |
| 2 | 无发版链路：release job 整段被注释，仓库也没有签名材料 | 合并后的 `.github/workflows/ci.yml` | 生成 `/workspace/olfact-keys/olfact-release.jks`（仓库外，PKCS12/RSA4096），新增 tag 触发的签名 + 发布 job，keystore 走 4 个 secrets |
| 3 | B5 的接口桩覆盖了 B1 冻结契约：`MediaItem`（`kind/sizeBytes` vs `type/contentLength`）、`SettingsKey`（接口 `key/def` vs 类 `name/default`）三处对不上，直接合并会让 B2/B3/B4 全线编译失败 | `core/api/{Media,Settings,AdBlock,Injection,BrowserHost}.kt` 的 add/add 冲突 | 五个契约一律取 m0 定稿；B5 桩里 UI 需要的扩展（`BrowserSurface`/`AdBlockStats`/`AdBlockController`/`UserScript`/`ScriptStore`）拆到独立文件 `BrowserSurface.kt`、`AdBlockPanel.kt`、`Script.kt` |
| 4 | 设置双存储：B5 的 `PrefsSettings`（SharedPreferences）与 B1 的 `DataStoreSettingsRepository` 各存一份，备份、各模块、界面会读到不同值 | `app/data/AppSettings.kt` | 删除 `PrefsSettings`，统一 `Core.settings`；`K.*` 的 key 与备份侧同源 |

## 2. 🟡 建议修复（已修）

| # | 问题 | 处理 |
|---|---|---|
| 5 | `GmBridge.defaultClient()` 的返回类型是 `OkHttpClient`，但 core 用 `implementation` 引 okhttp，app 编译报「Cannot access class okhttp3.OkHttpClient」 | core 的 okhttp 依赖改 `api`（公开签名里出现的类型必须可传递） |
| 6 | 脚本编辑器只能存「脚本体」，B4 的 `ScriptStore` 只提供 `install(source)`（要求 `==UserScript==` 头），UI 新建的脚本存不进去 | `core/script/ScriptStore` 新增 `save(meta, code)`（元数据 + 本体会话式落盘，与 `install` 并存） |
| 7 | `document-end` / `document-idle` 脚本从未注入（B2 只做 `addDocumentStartJavaScript`，B4 的 `scriptsFor` 没人调用） | `WebBrowserSurface.injectLateScripts()` 在 `onPageFinished` 注入两阶段脚本 |
| 8 | 兜底的 `LocalProviderStore` 把 AI Key **明文**写进 SharedPreferences，一旦 ai 模块初始化失败就会静默降级到它 | 删除该兜底与其注册分支；密钥只走 `KeystoreSecretBox` |
| 9 | `ai` 用 `kotlinx-serialization-json:1.8.1`，`core` 用 `1.11.0`，同一类库双版本靠 Gradle 仲裁 | ai 对齐到 1.11.0 |
| 10 | `.gitignore` 未排除签名材料；且 `storeFile` 相对路径在 `app` 模块里解析，容易把 keystore 写进仓库 | `.gitignore` 补 `keystore.properties` / `*.jks` / `*.keystore`；`storeFile` 改 `rootProject.file(...)` |
| 11 | B2 自带 `core/api/MediaSniffer.kt`（`MediaType` 只有 4 个值、`MediaItem` 少字段），与 m0 的 `Media.kt` 同名冲突 | 删除重复文件，保留 `Media.kt`；B2 的调用点按 m0 字段（`type/pageUrl/pageTitle/headers`）本就兼容 |
| 12 | 仓库没有 README / CHANGELOG，发版无处取 Release 文案 | 新增 `README.md`、`CHANGELOG.md`（CI 从 CHANGELOG 抽当前版本段落当 Release 正文） |

## 2.1 CI 二次故障（发版当天才暴露，已修）

| # | 问题 | 证据 | 处理 |
|---|---|---|---|
| 12a | `android-actions/setup-android@v3` 在本环境直接失败：它先跑 `sdkmanager tools` 引导，而 `tools` 包已从仓库下架 | run #1 日志 `Warning: Failed to find package 'tools'` → `sdkmanager` exit 1 | 三个 job 全部去掉该 action，改用 runner 预装 SDK（只保留一次 `sdkmanager --licenses`） |
| 12b | `platforms;android-37` 不存在：新平台按 minor 版本命名（`android-37.0/37.1/37.2`），装机也带 `build-tools;37.0.0` | run #1 日志 `Warning: Failed to find package 'platforms;android-37'`；探测 run 列出可用包 | 无需安装；`compileSdk = 37` 由 AGP 解析到预装的 37.x 平台 |
| 12c | 临时探测 workflow 用完即删 | `sdk-probe.yml` | 已删除，不留在主干 |

修完后：main 的 `unit tests` + `assembleDebug` 全绿（run #5），打 tag 后 `assembleRelease + publish` 通过（run #6）。

## 2.2 发布结果

| 项 | 值 |
|---|---|
| Release | <https://github.com/U707T/olfact/releases/tag/v1.0.0-rc1>（prerelease） |
| 产物 | `app-arm64-v8a-release.apk` / `app-armeabi-v7a-release.apk`，各 2.8 MB |
| 签名 | CI 用 secrets 里的 keystore，`apksigner verify` 证书 SHA-256 `d04aad4e…07:97` 与本地一致 |
| Release 正文 | 由 CHANGELOG 的 `## [1.0.0-rc1]` 段落自动抽取 |

## 3. 🔵 可选优化（本轮不做，记录在案）

| # | 事项 | 说明 |
|---|---|---|
| 13 | 两份 `LinkExtractor` | `app/link/LinkExtractor.kt`（B5，9 条测试）与 `web/browser/LinkExtractor.kt`（B2，自带测试）能力重叠。当前 UI 用 app 版，B2 版给 `UrlBarController` 用；后续收敛为一份（放 core 或 web） |
| 14 | 契约与实现模型重复 | `core/api/{Doh,Ai,Download,Script}.kt` 与 `web.doh`/`ai`/`download`/`core.script` 内部模型是两套，靠 `ModuleBackends` 适配。业务量再涨时应把面板契约收进各模块，删除中间层 |
| 15 | 无真机 / Robolectric 冒烟 | B5 的 `UiSmokeTest` 在 aarch64 沙箱里 3/3 失败（Robolectric sandbox 起不来）后被删。`docs/tasks/B5-SMOKE.md` 的 20 条手工用例需在真机跑一遍 |
| 16 | 下载速度恒为 0 | B3 的 `DownloadRecord` 不记录瞬时速度，`DownloadTask.speedBytesPerSec` 只能填 0，详情页显示「0 B/s」 |
| 17 | `K.AD_COUNT_*` 冗余 | 拦截计数已由 B4 的 `BlockStats` 管理，`AppSettings` 里那三个 key 只在引擎未接入时兜底用，可删 |

## 4. 🟢 做得好的地方

- **依赖方向干净**：`app → web/player/download/ai → core`，没有反向 import；`core/api` 只有接口和纯数据。
- **测试密度**：合并后六个模块单测全绿（core 含 49 条 B4 用例，web 55 条 DoH/规则用例，app 新增 4 条集成映射用例）。
- **无技术债标记**：全仓 `TODO/FIXME/XXX/HACK` 为 0；最大文件 `ModuleBackends.kt` 465 行，其余 ≤417 行。
- **故障降级明确**：模块缺失时 `LocalBackends` 只在槽位为 `null` 时装兜底，且 debug 才塞示例下载（界面有「示例数据」角标）。
- **DoH 边界写清楚**：QUIC/SNI/ECH 不做、L2 只覆盖约 90% 流量，设置页有原文说明，不吹能力。

---

## 6. 第二轮：可达性审计（2026-10-31，rc2）

用户反馈「设置里面的功能点不进去」。顺着这条线把**每个入口是否真的接到实现**过了一遍，
又挖出三处「界面在、功能是壳」的问题。

### 6.1 逐屏点检

| 界面 | 入口 | 结论 | 备注 |
|---|---|---|---|
| 设置首页 | 14 个图标行 | ❌→✅ | `SettingItem` 只在 SWITCH/CHECKBOX 注册监听，CHEVRON 行没有 listener |
| 设置子页 ×9 | 开关 / 单选 / 动作 / 跳转 | ❌→✅ | 同上，全部走 `onClickAction` |
| 加密 DNS | 开关、三档策略、上游、重测、清缓存 | ⚠️→✅ | 开关过去只在冷启动生效，现在即时 start/stop |
| 广告拦截 | 开关、订阅、白名单、立即更新 | ✅ | 走 B4 `AdBlockManager` 适配层 |
| 脚本管理 | 列表、开关、编辑保存 | ✅ | 走 B4 `ScriptStore`（新增 `save(meta, code)`） |
| AI 供应商 | 增删改、设为当前 | ✅ | Key 走 Keystore，面板只显示掩码 |
| 数据备份 | 备份、恢复、WebDAV、定时 | ❌→✅ | 见 6.3，之前备份出来是空包 |
| 关于与帮助 | 默认浏览器、模块状态、协议 | ✅ | |
| 首页 | 5 个快捷入口 / 3 条统计 / 最近访问 / 语录 | ✅ | |
| 播放页 | 播放、收藏、下载、嗅探 | ⚠️→✅ | 之前只弹「player 模块接入后由此起播」，现在拉起 Media3 |
| 嗅探弹层 | 播放 / 下载 | ✅ | 播放走上面的真播放器 |
| 下载列表 / 详情 | 暂停、继续、删除、清空 | ✅ | 暂停系统下载按取消处理（DownloadManager 无暂停语义） |
| 浏览器 | 地址栏、建议、长按菜单、页内查找 | ✅ | |
| 标签页 / 书签 / 历史 / 收藏 | 点击进入 | ✅ | |
| 扫码 | 相机、手输 | ⚠️ | 解码组件未接，见 §7 |

### 6.2 根因：`SettingItem` 的点击入口只有一个

```kotlin
// 旧：只有 SWITCH/CHECKBOX 分支注册 listener
if (mode == Mode.SWITCH) setOnClickListener { switchView.toggle() }
// onClickAction 只在被重写的 setOnClickListener 里调用 → CHEVRON 行永远不触发
```

修法：`init` 里注册**唯一**监听，内部按 `internalClick`（开关/勾选）+ `onClickAction`（跳转）
叠加执行；`onClickAction` 的 setter 同步 `isClickable`，没有动作的行既不吃点击也不点亮 ripple。

回归用例 `app/src/test/java/cn/olfact/app/ui/settings/SettingItemClickTest.kt`（4 条）。
注意：Robolectric 在本沙箱（aarch64 proot）起不来（`UnsatisfiedLinkError`），
所以这 4 条只在 GitHub Actions 的 x86_64 runner 上执行。

### 6.3 另外三处「界面在、功能是壳」

| 问题 | 实际后果 | 处理 |
|---|---|---|
| 备份读的是没人写的 SharedPreferences | 「立即备份」成功，但设置段永远是空表；WebDAV、定时备份完全没接 | 接 `BackupPipeline`：6 个数据段 + zip/manifest + 本地/WebDAV + 保留 5 份；恢复整包校验后合并且 `manager.reload()` |
| 播放页只是控制条 UI | 点播放只弹 toast，视频永远不播 | `PlayerFragment` 进页面即 `PlaybackIntents.play()`，播放交给 `VideoPlayerActivity` |
| 「退出时清除数据」没有调用方 | 开关是装饰 | `MainActivity.onDestroy()` 且 `isFinishing` 时走 `PrivacyCleaner`（清缓存/Cookie/WebStorage/表单/历史） |

### 6.4 资源：能用原版就别手绘

| 资源 | 之前 | 现在 |
|---|---|---|
| 首页插画 | `img_bird.xml` 手绘 vector（形状差得远） | 安装包 `res/kI.png`（2000×500，裁掉透明留白成 684×324），WebP 两档 |
| 首页插画底色 | `bg_home_hero.xml` 渐变卡片（原版没有） | 删除，直接浮在白底上，与原版一致 |
| 首页风景卡 | 已是原版 `res/kM.webp` | 保持不变（与安装包 MD5 一致） |

> 安装包里另外两张成图 `res/uY.png`（绿底树林，729×1018）与 `res/Iy.jpg`（沙丘，1000×1500）
> 没有明确的归属界面（APK 资源名被混淆，资源表取不到名字），暂不接入，避免瞎猜乱用。

### 6.5 本轮未做

| 事项 | 原因 |
|---|---|
| 扫码解码（ZXing decode / MLKit） | 需要额外解码依赖与真机相机验证，留到下一版 |
| 下载瞬时速度 | B3 的 `DownloadRecord` 不记录速度，面板显示 0 B/s |
| 真机跑 `B5-SMOKE` 20 条 | 沙箱无 KVM/adb |
| 两份 `LinkExtractor` 合并 | 行为已一致，纯结构债 |
