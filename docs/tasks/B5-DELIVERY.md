# B5 交付说明（界面复刻 + 收尾）

> 分支 `feat/b5-ui` ｜ 目录 `/workspace/olfact-b5` ｜ 只改 `app/**`、`core/uikit` 与 `core/src/main/res` 的 uikit 资源

## 0. 一句话结论

界面壳子已经能编译、能跑通导航：`assembleDebug` 通过，release 单 ABI **1.3 MB**，
`LinkExtractor` 9 条单测全绿，lint 无 error。
**真机视觉验收未做**——沙箱内没有 `/dev/kvm`、adb 也起不来，出不了截图；配色/尺寸按原版资源逐项取值，
见 §3 对照表与 `B5-SMOKE.md` 的 20 条手工用例。

## 1. 交付内容

### 1.1 视觉基座（`core/uikit`）
纯 framework View，不引 Material，release 体积只涨 ~120 KB。

| 组件 | 说明 |
|---|---|
| `BottomBar` | 5 槽位；两种样式：`PILL`（胶囊「主页」）/ `ICON_ONLY`（原版 4 种收敛为 2 种） |
| `UrlBar` | 圆角描边输入条；左侧 🔒/🌐 表示 DNS 通道；底部 2dp 进度；`editable=false` 时整条可点 |
| `SettingItem` | 56dp 行，`NONE/CHEVRON/SWITCH/CHECKBOX` 四种尾部，点整行也能切换 |
| `SettingGroup` | 白色圆角卡 + 自动分隔线 |
| `ListRow` | 64dp 列表行（图标/标题/副标题/尾部/箭头），列表类页面统一用它 |
| `PageHeader` | 返回箭头 + 28sp 粗体大标题，右侧可挂动作 |
| `EmptyView` / `LoadingBar` / `Sheet` / `NineGrid` / `UiSwitch` | 空态、细进度、底部弹层、九宫格、开关 |

资源：`core/src/main/res/values/uikit_{colors,dimens,styles}.xml` + `values-night/`，
`uikit_*` 前缀避免与 B1 的基础色板冲突。

### 1.2 界面清单（38 个）

| 组 | 界面 |
|---|---|
| 主壳 | 首页、标签页管理、底部栏（2 样式）、主菜单九宫格 |
| 浏览 | 浏览器主界面、地址栏下拉（含提链候选）、长按菜单、页内查找条、网站配置、UA 管理 |
| 播放 | 播放页（控制条/倍速/跳片头/PiP）、嗅探结果列表、收藏 |
| 下载 | 下载列表（已下载/下载中 + 六项 ⋮ 菜单）、下载详情、添加下载弹层 |
| 设置 | 设置首页、基本设置、外观/手势、下载相关、嗅探和播放器、加密 DNS、广告拦截、脚本管理、AI 供应商、数据备份、隐私与安全、更多设置、关于与帮助、搜索引擎 |
| 其它 | 书签、历史、扫码、二维码分享、日志、协议（用户/隐私/许可） |

设置子页由声明式 `Spec`（`SettingsSpec.kt`）+ 一个渲染器驱动，13 个页面没有 13 份重复布局。

### 1.3 系统集成
- **默认浏览器**：manifest 四组 filter（VIEW http(s) / WEB_SEARCH / SEND / PROCESS_TEXT）+ `MAIN/CATEGORY_APP_BROWSER`；
  `MainActivity.requestDefaultBrowser()` 走 `RoleManager.ROLE_BROWSER`（API 29+），旧系统跳默认应用设置。
- **冷启动分发**：`handleIntent()` 处理 VIEW / WEB_SEARCH / SEND / PROCESS_TEXT；`singleTask + onNewIntent` 也走同一条路径。
- **文本提链**：`LinkExtractor`（纯 Kotlin，可单测）提取完整 URL + 裸域名，去重、去尾标点、最多 5 条；
  地址栏 200 ms debounce 出下拉；多链接可「全部打开」，每个链接一个标签页。
- **设置页装配**：DoH 面板读 `OlfactServices.doh?.status()`，拦截面板读 `adBlockController`，AI 面板读写 `ProviderStore`；
  未接入时显示「未接入」并提供本地配置存储，不伪造统计数字。

### 1.4 收尾
- `isMinifyEnabled + isShrinkResources + resConfigs("zh","en") + ABI split`；`proguard-rules.pro` 保留 JS 桥/数据类/ZXing。
- release 实测：`app-arm64-v8a-release-unsigned.apk` **1,315,159 B**（1.3 MB），远低于 15 MB 目标。
- 冷启动：首屏只有一次 `commit()`，首页不加载网络图片（插画是本地 vector + 一张 157 KB webp）。

## 2. 与其它批次的接口

`core/api` 在 B1 尚未落地时由 B5 **按 `docs/tasks/README.md` §3 原文补齐**（签名逐字一致），并补了 UI 需要的
只读契约（`DohStatus`、`AdBlockStats`、`AiProvider`、`DownloadTask`、`BrowserSurface`）。
B1 合并时可直接采用或覆盖，差异集中在一个文件（`Services.kt` 的注册表）。

`app` 侧的降级顺序：
1. `OlfactServices.browserSurface`（B2）→ 2. `DefaultBrowserSurface`（app 内最小 WebView）
3. `OlfactServices.downloads`（B3）→ `LocalDownloadCenter`（debug 塞 3 条示例，release 不塞）
4. `scriptStore` / `aiProviders` → 本地 SharedPreferences 实现。

> 这些兜底只在接口为 null 时注册（`LocalBackends.install`），不会覆盖真实模块。

## 3. 视觉对照（取原版 `_archive/olfact-repack/decode/res/values`）

| 项 | 原版 | 本项目 |
|---|---|---|
| 主色 `colorAccent` | `#109D58` | `brand = #109D58` |
| 主文本 | `#212121` | `text_primary = #212121` |
| 次文本 | `#757575` | `text_secondary = #757575` |
| 卡片圆角 | `dialog_radius = 16dp` | `card_radius = 16dp` |
| 页面底色 | `#F2F2F2`（截图取样） | `page_bg = #F2F2F2` |
| 设置行高 | 截图 56dp | `uikit_item_height = 56dp` |
| 大标题 | 截图 28sp Bold | `uikit_text_page_title = 28sp` |
| 勾选框 | 紫色 `#B39DDB` | `uikit_check = #B39DDB` |
| 首页圆底图标 | `home_bookmark2/home_download/home_history2/home_setting`（#62A6FB/#2FC99D/#B7ACF8/#FFA19C） | 原样拷贝 |
| 首屏插画 | 原版 assets 里是 HTML 页 | 自绘 `img_bird.xml` + `bg_home_hero`（风格近似，非像素级） |

## 4. 已知差异 / 待接入

1. **浏览器渲染**：B2 未合入时用最小 WebView 兜底；UA、DoH 代理、拦截、脚本注入、嗅探都在 B2，ab 面板会显示「未接入」。
2. **播放**：没有 player 模块时控制条只做 UI 反馈，不伪造进度；PiP 走 Activity 原生能力。
3. **扫码**：相机权限 + 取景框 + 手动输入；解码组件（ZXing decode/MLKit）未接。
4. **小程序 / 阅读模式 / 投屏 / 视频手势**：按 TECH-PLAN §1.2 已裁掉，菜单里不再出现或明确提示。
5. **真机截图对比**：沙箱无 KVM，`adb` 不可用；6 张关键界面需要在一台真机/云真机上补拍并归档到 `docs/refs/b5/`。

## 5. 验证命令

```bash
cd /workspace/olfact-b5
./gradlew :app:assembleDebug --offline        # 通过
./gradlew :app:testDebugUnitTest --offline    # LinkExtractor 9/9
./gradlew :app:lintDebug --offline            # 无 error
./gradlew :app:assembleRelease --offline      # 1.3 MB / ABI（未签名，CI 签名）
```

## 6. 合并提示（给 B1）

- 新增：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`（原骨架缺，构建必需）。
- 改动别人文件仅两处：
  - `core/build.gradle.kts`：加 `kotlin { compilerOptions { jvmTarget = JVM_17 } }`（否则 core 有 Kotlin 源码就编不过）。
  - `app/build.gradle.kts`：依赖与打包配置（app 属于 B5，无需协调）。
- `docs/refs/` 是未跟踪的用户截图，未提交。
