# B1 · 地基与构建（M0 + M9 构建部分，5 天）

> 分支：`feat/b1-foundation` ｜ 合并后打 tag `m0`，其余四批基于它开工。

## 目标

交出一个**能编译、能安装、能启动的空壳**，以及全套跨批接口桩与视觉资产。B1 不做业务逻辑。

## 交付物

### 1. 构建骨架（已有雏形，补完）
- `gradlew` + `gradle/wrapper/gradle-wrapper.jar`（从 `/workspace/wxtool` 拷）
- `settings.gradle.kts`：`:app :core :web :player :download :ai`
- 根 `build.gradle.kts`：AGP 9.0.1 / Kotlin 2.3.20
- 各模块 `build.gradle.kts`（已建，核对 namespace 与依赖）
- 跑通：`./gradlew :app:assembleDebug`（首次联网，之后 `--offline`）

### 2. 跨批接口桩（`core/src/main/java/cn/olfact/core/api/`）
按 `tasks/README.md` §3 建立 5 个接口 + 数据类：`MediaSniffer`、`AdBlockHook`、`ScriptSource`、`SettingsRepository`、`BrowserHost`。
**只定义签名 + 空实现（返回默认值），保证全仓编译通过。**

### 3. core 基础件
- `log/`：统一日志（Timber 风格自研，20 行）
- `util/`：dp/sp 转换、Uri 工具、线程切换
- `storage/`：DataStore 封装（实现 `SettingsRepository`）+ Room 单库骨架（3 张表：bookmark / history / download）
- `crypto/`：Keystore + AES-GCM（约 30 行）
- `uikit/` 只建空目录（归 B5）

### 4. 视觉资产落地
从 `_archive/olfact-repack/decode/res/` 抽取基础资产进 `core/src/main/res/`：
- `values/colors.xml`（提取原版主色/背景/文字色）
- `values/dimens.xml`（底部栏高度、圆角、常用间距）
- `values/styles.xml`（`Theme.Olfact`，继承原版配色，不用 Material3 默认色）
- 常用 drawable（图标、9-patch）按需拷，**不要整包复制 2422 个文件**，只拷 B5 列出的清单。

### 5. CI（`.github/workflows/ci.yml`）
- push main / PR → `assembleDebug` → 上传 artifact
- 预留 job：`build-adblock-rust`（cargo ndk 交叉编译，B4 提供命令，B1 落地流水线）
- release job 模板留空（签名 secrets 由 B5 收尾时启用）

## 验收

| 项 | 标准 |
|---|---|
| 编译 | `./gradlew :app:assembleDebug --offline` 通过 |
| 安装 | debug APK 能装、能启动、显示占位页 |
| 接口 | 5 个接口全部有定义与空实现，全仓无编译错误 |
| CI | 推一次代码，Actions 出 artifact |
| 资产 | 主题色/尺寸与原版一致（抽 3 个界面比对） |

## 禁止

- 不实现任何浏览/播放/下载/拦截业务
- 不改 `docs/TECH-PLAN.md`
- 不引入未在方案里列出的依赖（尤其别引 Compose、Hilt、Retrofit）

## 开局 prompt（粘到新窗口）

```
读 /workspace/olfact/docs/TECH-PLAN.md 和 docs/tasks/README.md，
然后按 docs/tasks/B1-foundation.md 执行。

环境：Gradle 9.7.0 / AGP 9.0.1 / Kotlin 2.3.20 / JDK 21 / compileSdk 37 / minSdk 24。
只在本分支 feat/b1-foundation 工作，只改 §4 文件所有权里属于 B1 的路径。
先跑通 assembleDebug（首次联网），再补接口桩与 core 基础件。
构建必须前台一次跑完，不要 nohup。
```
