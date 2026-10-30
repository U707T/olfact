# B4 · 拦截 + 脚本 + 数据（M4 + M5 + 备份/隐私，10 天）

> 分支：`feat/b4-adblock` ｜ 只改 `core/adblock/`、`core/script/`、`core/backup/`

## 目标

补齐"网页干净"与"可编程"两块：adblock-rust 引擎、UserScript 注入、备份/WebDAV/隐私清理。

## 交付物

### 1. adblock（`core/adblock/`）
- **JNI 壳**：`external fun` 对应 `adblock-ffi` 的 C ABI
  `engine_create(rules_path)` / `engine_check(url, source_url, req_type)` / `engine_cosmetic(url)` / `engine_destroy`
- **`.so` 由 CI 编**：沙箱无 rustup，本地编不出。命令：
  `cargo ndk -t arm64-v8a -t armeabi-v7a -o app/src/main/jniLibs build --release`
  产物 `libadblock_ffi.so`（约 1 MB）。B1 的 ci.yml 已预留 job，双方对接参数即可。
- **规则集**：`Engine` 序列化 `.dat` 放 `assets/`，首次 mmap 加载；支持从 URL 下载订阅并重建
- **实现 `AdBlockHook`**：`shouldBlock` / `cosmeticCss` / `recordBlocked`
- **订阅管理**：列表（内置 uBO Lite / ABP 常用清单）、启用停用、手动更新、上次更新时间
- **白名单**：域名级，优先于订阅
- **降级**：`.so` 缺失时切 Kotlin 匹配器（规则上限 2 万条），功能不中断

### 2. script（`core/script/`）
- `UserScriptParser`：解析 `// ==UserScript==` 元数据（@match/@include/@exclude/@run-at/@grant）
- `ScriptStore`：安装/启用/停用/删除/更新（本地文件 + Room 索引）
- `GmBridge`：`GM_getValue / GM_setValue / GM_xmlhttpRequest / GM_addStyle / GM_log`（通过 JsBridge 暴露，**不引 QuickJS**）
- **实现 `ScriptSource`**：`documentStartScripts(url)` 返回命中的脚本
- 从 GreasyFork 一键安装（可选，先做手动粘贴 URL）

### 3. 数据（`core/backup/`）
- `BackupWriter`：`zip + JSON`（settings / bookmarks / history / scripts / adblock 订阅），带版本号
- `BackupReader`：校验 + 合并导入（不覆盖式清空）
- `WebDavClient`：OkHttp 实现 PROPFIND/PUT/GET/DELETE（不引 sardine）
- `AutoBackupWorker`：WorkManager 定时（启动即备份 / 1h / 6h / 12h / 24h 五档）
- `PrivacyCleaner`：清缓存 / Cookie / 搜索记录 / 退出时清数据

## 验收

| 项 | 标准 |
|---|---|
| 拦截 | 订阅生效，典型广告位（3 个测试站）消失；计数与实际相符 |
| 元素隐藏 | `cosmeticCss` 注入后广告 div 不可见且不占位 |
| 脚本 | 三个常用油猴脚本（去广告/改样式/加按钮）可跑 |
| 备份 | 备份→清数据→恢复，书签/设置/脚本全回来 |
| WebDAV | 上传/下载/列表可用（用 nginx dav 或坚果云测） |
| 降级 | 删掉 `.so` 后 App 仍能启动、拦截降级为 Kotlin 匹配器 |

## 禁止

- 不引 QuickJS / Rhino / J2V8
- 不改 `web/`（钩子调用点由 B2 负责）
- 不实现"规则引擎"（`hiker://`、`body&&li`，已砍）

## 开局 prompt

```
读 /workspace/olfact/docs/TECH-PLAN.md（§5 拦截、§7 脚本/备份）和 docs/tasks/README.md，
然后按 docs/tasks/B4-adblock-script-data.md 执行。

分支 feat/b4-adblock，只改 core/adblock/、core/script/、core/backup/。
先做 adblock 的 JNI 壳与 Kotlin 封装（本地可用降级匹配器先跑通），
再把 CI 交叉编译流水线和 B1 对接，最后做 UserScript 与备份。
实现 AdBlockHook / ScriptSource 两个接口，供 B2 调用。
```
