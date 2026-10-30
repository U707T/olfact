# B5 · 界面复刻 + 收尾（M6 + 默认浏览器/提链，11 天）

> 分支：`feat/b5-ui` ｜ 只改 `app/**`、`core/uikit/**` ｜ 依赖 B2 的 `BrowserHost`

## 目标

把 `app` 做成"看起来就是它"的壳：30~40 个界面、主题配色、底部栏、设置体系、默认浏览器接管，最后做体积与发布收尾。

## 交付物

### 1. 主题与视觉基座（`core/uikit/` + `app/src/main/res/`）
- 复刻原版配色：从 `_archive/olfact-repack/decode/res/values/styles.xml` 提取
  `colorPrimary / windowBackground / textColor / hint / card / divider`
- 尺寸：底部栏高度、圆角、列表项高度、字号，全部取 `decode/res/values/dimens.xml`
- 图标：按界面清单从 `decode/res/drawable*` 拷（**只拷用到的**）
- 组件：`BottomBar`、`UrlBar`、`SettingItem`、`SettingGroup`、`EmptyView`、`LoadingBar`

### 2. 界面清单（30~40 个，按原版截图对齐）
| 组 | 界面 |
|---|---|
| 主壳 | 首页（宫格+最近访问）、标签页管理、底部栏（4 种样式收敛到 2 种） |
| 浏览 | 浏览器主界面、地址栏下拉（含**文本提链候选**）、长按菜单、页内查找条 |
| 播放 | 播放页（控制条/倍速/跳片头）、PiP、嗅探结果列表 |
| 下载 | 下载列表、下载详情、添加下载对话框 |
| 设置 | 设置首页、通用、外观/手势、搜索、UA、站点配置、**加密 DNS（DoH 面板）**、**广告拦截**、**脚本管理**、**AI 供应商**、备份、隐私、关于 |
| 其它 | 书签、历史、收藏、扫码、二维码分享、日志、协议页 |

### 3. 系统集成
- **默认浏览器**：manifest 四组 filter（已在骨架）＋ `RoleManager.createRequestRoleIntent(ROLE_BROWSER)` 申请入口 ＋ 冷启动 URL 分发
- **文本提链**：地址栏输入时下拉候选（调 B2 的 `LinkExtractor`），支持一次多链批量入标签
- **设置页装配**：DoH 面板读 B2 的 `DohStats`；拦截面板读 B4 的计数；AI 面板读写 B3 的 `ProviderStore`

### 4. 收尾
- 体积：`isMinifyEnabled` + `isShrinkResources` + `resConfigs("zh","en")` + ABI split，目标 release < 15 MB
- 启动时间：冷启 < 1.5 s（中端机），首屏不阻塞
- 签名与发布：`/workspace/olfact-keys`（仓库外）+ CI secrets，release 走 CI
- 冒烟清单：20 条手工用例（浏览/嗅探/播放/下载/拦截/脚本/DoH/备份/默认浏览器）

## 验收

| 项 | 标准 |
|---|---|
| 视觉 | 6 张关键界面与原版截图并排比对，布局/配色/字号一致（间距允许 ±2dp） |
| 默认浏览器 | 系统设置里能被选为默认；点外部链接直接进浏览器 |
| 提链 | 粘一段含 3 个链接的文本，下拉出 3 条候选且可批量打开 |
| 体积 | release APK < 15 MB |
| 冒烟 | 20 条用例全过 |

## 禁止

- 不改 `web/ player/ download/ ai/ core/adblock core/script`
- 不在 app 里重写业务逻辑（一律调各模块接口）

## 开局 prompt

```
读 /workspace/olfact/docs/TECH-PLAN.md（§6 UI 复刻）和 docs/tasks/README.md，
然后按 docs/tasks/B5-ui.md 执行。

分支 feat/b5-ui，只改 app/** 和 core/uikit/**。
先搭主题与 BottomBar/UrlBar/SettingItem 等基座组件，再按界面清单逐屏实现，
屏幕以 _archive/olfact-repack/decode/res/layout 为结构参考、以用户提供的截图为视觉基准。
业务一律调 web/player/download/ai 的接口，不要在 app 里写实现。
最后做体积优化与 release 冒烟。
```
