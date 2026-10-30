# 五批并行工作说明（B1~B5）

> 主方案：`docs/TECH-PLAN.md`（定稿 v1）。本目录是把方案切成 5 个**可独立开窗口**的批次。
> 每个批次一份任务书，自包含（含环境约束、文件所有权、接口契约、验收），可直接粘到新会话里开工。

## 0. 为什么这样切

按"垂直切片 + 文件所有权"切，而不是按部门切。三条原则：

1. **一个批次只拥有一批文件**，跨批调用一律走 `core` 里的接口桩，不直接 import 别人的实现。
2. **批次之间不共享可变文件**（`settings.gradle.kts`、`app/src`、`core/src/main/res` 都只有一个主人）。
3. **每个批次都能独立验证**：有自己的 debug 入口或单元测试，不必等其他批次。

## 1. 批次总览

| 批次 | 主题 | 覆盖里程碑 | 工作量 | 主要目录 |
|---|---|---|---|---|
| **B1** | 地基与构建 | M0 + M9 构建部分 | 5 天 | 根构建、`.github/`、`core/`（基础）、`docs/` |
| **B2** | 浏览内核 + DoH | M1 + M8 | 12 天 | `web/` |
| **B3** | 播放 + 下载 + AI | M2 + M3 + M7 | 13 天 | `player/`、`download/`、`ai/` |
| **B4** | 拦截 + 脚本 + 数据 | M4 + M5 + 备份/隐私 | 10 天 | `core/adblock/`、`core/script/`、`core/backup/` |
| **B5** | 界面复刻 + 收尾 | M6 + 默认浏览器/提链 | 11 天 | `app/`、`core/uikit/` |

合计 51 人日（比串行 46 多约 10%，是拆分与合并的协调开销）。

## 2. 依赖与并行策略

```
B1 (骨架 + 接口桩，1~2 天先出)
 ├── B2 (web)  ──┐
 ├── B3 (player/download/ai)  ├──> B5 挂载界面
 └── B4 (adblock/script)  ─────┘
```

- **B1 必须先跑 1~2 天**，交出"能编译的空壳 + 接口桩 + 资源资产"，其余四批才能同时开。
- B2/B3/B4 **完全可并行**（互不 import）。
- B5 依赖 B2 的 `BrowserFragment`，建议在 B2 完成 60% 后开始；UI 的静态部分（设置页、列表页）可以更早开。
- 墙钟估算：五窗口并行约 **18~22 天**（受关键路径 B1→B2→B5 与合并返工限制）。

## 3. 跨批接口契约（由 B1 建立，任何批次不得修改签名）

```kotlin
// core/api/Sniffer.kt        —— B2 实现，B3 消费
interface MediaSniffer {
    fun onMediaDetected(item: MediaItem)          // m3u8/mp4/mpd
    fun currentPageTitle(): String
    fun currentPageUrl(): String
}

// core/api/AdBlock.kt        —— B4 实现，B2 调用
interface AdBlockHook {
    fun shouldBlock(url: String, pageHost: String): Boolean
    fun cosmeticCss(pageHost: String): String
    fun recordBlocked()
}

// core/api/Injection.kt      —— B4 提供脚本，B2 负责注入
interface ScriptSource {
    fun documentStartScripts(url: String): List<String>
}

// core/api/Settings.kt       —— B1 实现，所有人只读
interface SettingsRepository {
    fun <T> get(key: SettingsKey<T>): T
    fun <T> set(key: SettingsKey<T>, value: T)
    val changes: Flow<String>
}

// core/api/BrowserHost.kt    —— B2 实现，B5 通过它挂载界面
interface BrowserHost {
    fun newTab(url: String?)
    fun currentUrl(): String?
    fun openSettings()
}
```

**规则**：接口文件在 `core/src/main/java/cn/olfact/core/api/`，只有 B1 能改；实现类在各自模块。

## 4. 文件所有权（硬约束）

| 路径 | 主人 | 其他人 |
|---|---|---|
| `settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`gradle/` | B1 | 只读 |
| `.github/workflows/` | B1 | B4 只能提"需要什么 job"，由 B1 落地 |
| `core/src/main/java/cn/olfact/core/{api,log,util,storage,uikit?}/` | B1 / B5（uikit 归 B5） | 只读 |
| `core/src/main/res/{values/colors,dimens,styles}`（基础视觉资产） | B1 | 只读 |
| `web/**` | B2 | 只读 |
| `player/**`、`download/**`、`ai/**` | B3 | 只读 |
| `core/adblock/**`、`core/script/**`、`core/backup/**` | B4 | 只读 |
| `app/**`、`app/src/main/res/**` | B5 | 只读 |

**字符串资源**：各批次只在自己的模块 `res/values/strings.xml` 里加，**不共用 app 的 strings**，避免 merge 冲突。

## 5. 工作区隔离（重要：五个窗口共享同一文件系统）

五个会话共享 `/workspace`，**不能靠 `git checkout` 切分支**（会把别人的工作目录切走）。用 `git worktree` 一个批次一个目录：

```bash
cd /workspace/olfact
git init && git add -A && git commit -m "chore: m0 skeleton"   # B1 先落地
git worktree add ../olfact-b2 -b feat/b2-web
git worktree add ../olfact-b3 -b feat/b3-media
git worktree add ../olfact-b4 -b feat/b4-adblock
git worktree add ../olfact-b5 -b feat/b5-ui
```

- 各窗口 `cd /workspace/olfact-b2` … `-b5`，互不干扰。
- 合并顺序：**B1 → B2 → B3 → B4 → B5**（B2~B5 每轮 `git rebase main`）。
- B1 完成后打 tag `m0`，其余四批从 `m0` 起分支。
- 冲突处理：只允许"文件主人"解决；非主人遇到冲突一律放弃自己对该文件的改动。

### Gradle 并发注意

- 五个 worktree 共用 `~/.gradle`，**并发构建会排队**（文件锁），不是死锁但会变慢；建议各自 `--offline`，避免同时开跑。
- 每个 worktree 有自己的 `build/`，磁盘会翻倍；阶段性 `rm -rf */build` 清理。
- 不要用 `nohup`/后台构建，任务会随调用结束被杀。

## 6. 每个窗口开工前必读

1. `docs/TECH-PLAN.md`（定稿方案）
2. 本文件的 §3 接口契约与 §4 文件所有权
3. 自己的任务书

## 7. 环境约束（所有批次通用）

- Gradle **9.7.0** wrapper、AGP **9.0.1**、Kotlin **2.3.20**、JDK 21、compileSdk/targetSdk **37**、minSdk 24。
- 本地只跑 `./gradlew :<module>:assembleDebug`；首次需联网拉依赖，之后加 `--offline`。
- **不要本地出 release**，release 走 CI（签名材料在 `/workspace/olfact-keys`，仓库外）。
- 构建必须**前台一次跑完**（后台任务会被杀）。
- 不要改别人的模块；不要改 `docs/TECH-PLAN.md`（要改先在本批任务书里记"变更请求"）。
