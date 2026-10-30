# olfact browser

用 Kotlin 重写「嗅觉浏览器 6.82」的核心能力：现代化、可维护、轻量。
原版（含规则引擎、迅雷 SDK、磁力、投屏等）只作为视觉与行为参照，范围见
[`docs/TECH-PLAN.md`](docs/TECH-PLAN.md)。

- **minSdk 24 / targetSdk 37**，Gradle 9.7.0 + AGP 9.0.1 + Kotlin 2.3.20，JDK 21
- 纯 View 体系（不引 Compose），release 开 R8 + 资源压缩 + ABI split
- 发版由 GitHub Actions 完成，本地只做 `assembleDebug` / 单测

## 模块

```
app       壳、导航、38 个界面、默认浏览器接管、模块装配（ModuleBackends）
core      日志/工具/设置(DataStore)/Room/加密 + adblock + script + backup + uikit
web       浏览内核（BrowserView/Client/TabManager）+ DoH（规则/编码/竞速/隧道）
player    Media3 播放页、PiP、倍速与跳片头
download  系统委派 + 分块 + M3U8（AES-128/remux）+ 队列/通知
ai        供应商管理、SSE 流式、网页总结、划词/整页翻译
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
3. push 打 tag：`git tag v1.0.0 && git push origin v1.0.0`
4. CI 跑单测 → 签 `assembleRelease` → 上传 APK 并创建 Release（`-rc`/`-beta` 自动标 prerelease）

## 文档

| 文件 | 内容 |
|---|---|
| `docs/TECH-PLAN.md` | 范围、选型、模块划分、DoH 设计（定稿 v1） |
| `docs/tasks/README.md` | 五批并行的切分、接口契约、文件所有权 |
| `docs/tasks/B*-*.md` | 各批次任务书与交付说明（B1-handoff / B5-DELIVERY / B5-SMOKE） |
| `docs/audit/2026-10-30-merge-audit.md` | 首次合并的发版审计记录 |
| `core/adblock/INTEGRATION.md` | 拦截/脚本/备份的对接说明 |

## 已知限制

- 沙箱无 KVM/adb，视觉与真机冒烟用例需在真机补跑（见 `docs/tasks/B5-SMOKE.md`）。
- 扫码解码（ZXing/MLKit）未接，当前是相机取景 + 手动输入。
- `libadblock_ffi.so` 默认由手动触发的 CI job 产出，缺失时降级 Kotlin 引擎。
