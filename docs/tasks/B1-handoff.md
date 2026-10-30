# B1 交付说明（m0 基线）

> 给 B2~B5 四个窗口看的"接班须知"。m0 已通过 `./gradlew :app:assembleDebug --offline`，
> 产出 `app/build/outputs/apk/debug/app-debug.apk`（debug 11.5 MB）。
> 分支 `master`，tag `m0`；四个 worktree 里执行 `git rebase master` 即可拿到全部地基。

## 1. 现在有什么

| 交付物 | 位置 | 说明 |
|---|---|---|
| 构建骨架 | 根 `build.gradle.kts`、`settings.gradle.kts`、`gradle/wrapper/` | Gradle 9.7.0 / AGP 9.0.1 / Kotlin 2.3.20 / JDK 21 / compileSdk 37 / minSdk 24；各模块 JDK target 统一 17 |
| 5 个接口桩 | `core/src/main/java/cn/olfact/core/api/` | `MediaSniffer`(+`MediaItem`/`MediaType`)、`AdBlockHook`、`ScriptSource`、`SettingsRepository`(+`SettingsKey`)、`BrowserHost`，另附 `Stubs.kt` 空实现 |
| 日志 | `core/.../log/Log.kt` | `Log.d/i/w/e`，release 自动只留 warn+，长日志自动分片 |
| 工具 | `core/.../util/` | `Dimens`（dp/sp）、`UriUtils`（host/registrableDomain/地址栏归一化）、`Threading` |
| 设置 | `core/.../storage/Settings.kt` | `DataStoreSettingsRepository`：内存快照同步读 + 异步写 + `changes` Flow |
| 数据库 | `core/.../storage/db/` | Room 单库 `olfact.db` v1，三张表 `bookmark` / `history` / `download`，DAO 已备基础增删改查；schema 导出在 `core/schemas/` |
| 加密 | `core/.../crypto/KeystoreCrypto.kt` | Keystore AES-GCM，`encryptString/decryptString`，用于 AI Key / WebDAV 密码 |
| 进程入口 | `core/.../Core.kt` + `app/.../OlfactApp.kt` | `Core.init(app)` → `Core.settings` / `Core.database` 单例 |
| 视觉基座 | `core/src/main/res/` | `Theme.Olfact`（原版绿 #109D58 + 浅色配色）、`colors.xml`/`dimens.xml`/`styles.xml`、夜间色、启动图标、底部栏/播放器常用图标 |
| CI | `.github/workflows/ci.yml` | push/PR → `assembleDebug` → artifact；手动触发的 adblock-rust 交叉编译 job；release job 以注释形式留模板 |

## 2. 必须知道的 6 条约束

1. **R 类是非传递的（AGP 9 默认）**。app 里引用 core 的资源要写 `cn.olfact.core.R`（代码里）或直接 `@color/...`（XML 里）。B5 写 UI 时建议 `import cn.olfact.core.R as CoreR`。
2. **接口文件只有 B1 能改**。要加字段，提到 `MediaItem.extras` / 新开 key，不要动 `core/api` 的签名。
3. **`Core.init()` 已在 `OlfactApp` 调过**，其他模块不要再建 Application；需要 Context 用 `Core.context`。
4. **设置项**：`SettingsKey<T>` 支持 Boolean / Int / Long / Float / String / Set<String>；其它类型会被忽略并记一条 warn。`get()` 读内存快照，进程刚起可能拿到默认值，要真值先 `(repo as DataStoreSettingsRepository).awaitLoaded()`。
5. **Room 表结构**：只加 DAO 查询，不加表/字段。确需改，先升 `version` 并写 Migration（破坏性升级只允许 1.0 之前）。
6. **资源命名**：core 的色值统一 `olfact_` 前缀；`white` / `grey_black` / `ic_launcher_background` 是原版 drawable 的兼容别名，**别在别的模块重复定义**（会盖掉）。

## 3. 构建纪律（沙箱特供）

```bash
cd /workspace/olfact-<bN>
./gradlew :app:assembleDebug --offline     # 首次要联网，之后加 --offline
```

- 五个 worktree 共用 `~/.gradle`，**别同时开跑**；同时构建不会死锁，但会排队变慢。
- 构建必须**前台一次跑完**，`nohup`/后台任务会被杀。
- 本机是 aarch64 + proot：`aapt2` 走 `~/.gradle/gradle.properties` 里的 qemu 包装（`android.aapt2FromMavenOverride`），**不要删那行**。
- Kotlin 编译守护进程在 proot 下 RMI 会失败，已设 `kotlin.compiler.execution.strategy=in-process`；单次编译 40 s 左右属正常。
- 本机 `llvm-objcopy` 报 `unknown argument '-o'` 是 NDK 打包 native 库时的噪音，不影响 APK。

## 4. 各自从哪开始

- **B2**：`core/api/BrowserHost` 用它实现；`web` 已 `api(project(":core"))`，并已带 `okhttp-dnsoverhttps`。
- **B3**：`MediaItem.headers` 记得透传 UA/Referer；下库落 `DownloadEntity`，别自己建表。
- **B4**：`AdBlockHook`/`ScriptSource` 实现后，在装配处替换 `NoopAdBlockHook`/`EmptyScriptSource`；core 已开 `kotlinx.serialization` 插件与 Room。
- **B5**：`app` 已实现 `BrowserHost` 的装配位（占位页）；主题直接用 `@style/Theme.Olfact`，尺寸用 `@dimen/olfact_*`。

## 5. 已知欠账（交给后续批次，不属于 B1）

- 首页/设置页等 30~40 个界面（B5）。
- 底部栏组件、UrlBar 组件（B5）。
- 全量图标清单：现在只拷了壳能用的 ~20 个，B5 按界面增量补。
- release 签名与 R8 规则细化（B5）。

## 6. 与四个在途 worktree 的对账（重要）

> 现场：B2~B5 四个目录里已经有在途代码（未提交），而且各自抄了一份 `core/api` 桩。
> m0 把 `core/api` 收归 B1 后，直接 `git rebase master` 会被未跟踪文件挡住。按下面的顺序做。

### 6.1 通用步骤（每个批次都先做这个）

```bash
cd /workspace/olfact-bN
git add -A && git commit -m "wip: bN 在途工作"      # 先把自己的改动落到自己分支，别丢
git rebase master                                    # 冲突见下表
```

冲突时：**B1 拥有的文件一律取 m0 的版本**（rebase 期间 `--ours` 指 master，`--theirs` 指你自己的提交）：

```bash
git checkout --ours build.gradle.kts core/build.gradle.kts gradle.properties settings.gradle.kts
git checkout --ours core/src/main/java/cn/olfact/core/api      # 你抄的桩 vs m0 的定稿，取 m0
git add -A && git rebase --continue
```

注意：是"取 m0 的内容"，不是删掉这些文件——删了 core 就编不过了。
只有 m0 里**不存在**的文件（B5 的 `Doh.kt`/`Ai.kt`/`Download.kt`/`Services.kt`）会原样保留，不冲突。

`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar` / `docs/refs/` 在 m0 里已是跟踪文件：
你目录里那份未跟踪副本会被 `git add` 收进你的 wip 提交，rebase 时以 m0 为准即可（`git checkout --ours` 后 `git add`）。

### 6.2 各批次的差异点

| 批次 | 必须删的本地桩 | 需要改的调用点 | 备注 |
|---|---|---|---|
| **B2** | `core/api/` 下 5 个文件 | **无**。`BrowserClient.kt` 里唯一一处 `MediaItem(url,type,pageUrl,pageTitle,headers)` 与 m0 完全兼容 | `MediaTypes.of()` 用的 `HLS/DASH/MP4/OTHER` 都在 m0 的 `MediaType` 里；`web/build.gradle.kts` 是你自己的文件，保留你的依赖（datastore 已由 core 传递） |
| **B3** | 无（没抄桩） | 无 | m0 已给 `player/download/ai` 加 `api(project(":core"))`；你 build 文件里新增的 media3-extractor / work 2.12.0 / mockwebserver 属于你的模块，保留 |
| **B4** | `core/api/AdBlock.kt`、`core/api/Injection.kt` | 无 | 你的 `AdBlockManager`/`ScriptSourceImpl` 实现的方法与 m0 逐字一致；`core/build.gradle.kts` 你报备的 okhttp / work 2.12.0 / serialization 1.11.0 / junit / coroutines-test **B1 已落进 m0**，冲突时直接取 m0 |
| **B5** | `core/api/` 下 `Media/Settings/BrowserHost/AdBlock/Injection` 五个 | 无 | `Doh.kt` / `Ai.kt` / `Download.kt` / `Services.kt` 是**新增**契约（m0 没有同名文件），可以留着继续用，但请记住它们现在归 `core/api`，合并前跟 B1 打个招呼；`core/build.gradle.kts` 的 jvmTarget 改动 m0 已有，取 m0 |

### 6.3 接口对不上的地方（只有一处）

`SettingsKey`：B2 的本地桩写成了 `interface SettingsKey { val key; val def }` + `settingsKey()` 工厂；
m0 定稿是 `class SettingsKey<T>(val name: String, val default: T)`。
四个目录里**目前没有任何代码调用它**，删掉本地桩即可，不用改业务。

`MediaItem`：m0 的字段是 `url / type / title / pageUrl / pageTitle / mimeType / contentLength /
durationMs / headers / isEncrypted / extras`，`pageUrl`、`pageTitle` 可空，大小字段叫 `contentLength`（未知为 null）。
B2 现有的唯一调用点不用改。
