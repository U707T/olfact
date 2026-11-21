# core / AI 审计与验证

基线：`main` `7796c71`。按个人自用浏览器的正常操作分批审计：先保护用户数据和恢复，再检查外部请求、拦截和 UI 契约。

## 已读清单

| 范围 | 实际阅读 |
| --- | --- |
| core 主源码，71 个 Kotlin 文件 | `Core`、`api`、`b4`、`backup`、`adblock`、`script`、`crypto`、`log`、`storage`（含 Room DAOs/entities/database）、`uikit`、`util`，全包逐文件。新增 `net/AppDns` 与 `network/HttpResponse`。 |
| core 测试，13 个原有 Kotlin 文件 | `adblock`、`backup`、`script` 的 12 个测试类和 `testutil/MiniHttpServer`；新增 AppDns、SettingsWrite 测试。 |
| AI 主源码，18 个 Kotlin 文件 | 根包和 `crypto`、`error`、`net`、`summarize`、`translate` 全包；另外检查 Android manifest 与两套字符串资源。 |
| AI 测试，5 个原有 Kotlin 文件 | ErrorMapper、OpenAiWire、ReadabilityExtractor、SseFramer、TextChunker；新增 ChatClient、ProviderStore 测试。 |
| 文档 / 构建 / 原生 | 项目 README、近期变更日志、CI，两个模块的 Gradle 文件、[B4 对接记录](adblock/INTEGRATION.md)、[Rust README](adblock/rust/README.md)、[Rust FFI](adblock/rust/src/lib.rs)、[Cargo 配置](adblock/rust/Cargo.toml)、[原生 CI 片段](adblock/ci-snippet.yml)、[JNI 产物说明](src/main/jniLibs/README.md)、[AI README](../ai/README.md)、Room v1 schema、两份内置拦截资产。 |
| 非本次逐文件范围 | core 的视觉 XML/PNG 资源未逐件审查；其他模块只核对备份、DoH、脚本、AI 的调用交界，不在此报告其独立源码覆盖。 |

## 已落地的修复

| 链路与触发条件 | 修复 |
| --- | --- |
| JSON、供应商与 zip 覆盖写时磁盘失败或进程被杀：旧文件已先删，改名失败可留下半份文件。 | [StoreFiles](src/main/java/cn/olfact/core/b4/StoreFiles.kt#L30-L51) 先同步独立同目录临时稿，再原子替换；失败不删旧文件、不回退到直接覆盖复制。 |
| 老备份的脚本条目缺正文；ZIP 目录携带非空内容；分段写入中途失败。 | [BackupReader](src/main/java/cn/olfact/core/backup/BackupReader.kt#L30-L112) 限制所有条目的解压量，拒绝重复名/新版缺摘要/非法订阅 id，缺正文的旧脚本跳过；新备份显式存空正文。[BackupPipeline](src/main/java/cn/olfact/core/backup/BackupPipeline.kt#L127-L225) 流式输入、分段错误与已完成报告，只清理规范命名的成品，文件名细化到毫秒；[Worker](src/main/java/cn/olfact/core/backup/AutoBackupWorker.kt#L28-L47) 未装配时有限重试，保留取消语义。 |
| 周期/手动备份与拦截界面持有不同清单，备份可能导出过时配置并在恢复时覆盖变更。 | [AdBlockManager](src/main/java/cn/olfact/core/adblock/AdBlockManager.kt#L244-L246) 提供共享的备份 section；[BackupSections](src/main/java/cn/olfact/core/backup/BackupSections.kt#L44-L136) 同存储批量合并，不把未备份的规则正文说成已恢复；无法读取的脚本正文会报告失败。 |
| 连续设置、清空、导入时后台写乱序；恢复在写入完成前报告成功；外部类型与 getter 约定不一致。 | [设置](src/main/java/cn/olfact/core/storage/Settings.kt#L49-L207) 单队列保存、即时快照不回退，恢复成一个写事务，真正等待结果；未知或不合法的类型格式丢弃，类型不符读取回退默认。Room [打开数据库](src/main/java/cn/olfact/core/storage/db/OlfactDatabase.kt#L28-L35) 不再允许缺失迁移时静默删库，当前 schema 仍为 v1。 |
| WebDAV 404 被当作连接成功，外目录响应可能污染列表/保留策略，大响应会被无界读取。 | [WebDAV](src/main/java/cn/olfact/core/backup/WebDavClient.kt#L51-L189) 检查真实 DAV 根、只接纳对应源和请求目录下的条目，保留空格/加号等路径语义，并限制解压后的响应、拒绝外部 XML 实体。共享 [HTTP 边界](src/main/java/cn/olfact/core/network/HttpResponse.kt) 使取消覆盖响应体读取。 |
| 订阅配置含不合法 id、下载缓存缺失却使用旧 ETag、错误地址或 HTML 覆盖可用规则、软件拦截漏匹配。 | [订阅存储](src/main/java/cn/olfact/core/adblock/SubscriptionStore.kt#L55-L113) 校验文件名，保留用户删空结果；[更新器](src/main/java/cn/olfact/core/adblock/SubscriptionUpdater.kt#L70-L145) 限制读取、处理坏 URL/HTML，确认行仍存在且来源仍相同才写入。[Kotlin 引擎](src/main/java/cn/olfact/core/adblock/KotlinAdBlockEngine.kt#L78-L133) 保留匹配大小写、正确考虑子串/正则替代与元素排除域。 |
| 启用原生引擎时缓存接口无法按当前 crate 编译，旧缓存不随规则变化；浏览器线程可能与关闭抢同一裸句柄、传入的站点是 host 而非 URL。 | [Rust FFI](adblock/rust/src/lib.rs#L57-L69) 对照 `adblock 0.9.8` 去掉陈旧缓存路径、修正选择器集合；[JNI 包装](src/main/java/cn/olfact/core/adblock/NativeAdBlockEngine.kt#L22-L50) 串行保护句柄、补齐站点 URL。未改变 Kotlin 降级规则上限。 |
| 两个脚本 id 清洗到同文件会覆盖正文；脚本下载先读全量响应，HTML 可能被当成裸脚本。 | [脚本仓库](src/main/java/cn/olfact/core/script/ScriptStore.kt#L69-L149) 拒绝文件名碰撞，下载支持取消、有限体积、拒绝明显 HTML；删除先成功保存索引再清正文；未为索引与正文建立跨文件事务。 |
| 应用内客户端忽略运行着的 DoH。 | 新增 [AppDns](src/main/java/cn/olfact/core/net/AppDns.kt)，默认 AI、WebDAV、脚本/GM、广告订阅的 OkHttp 客户端动态读取应用解析器，未启用时采用系统 DNS；DoH 上游自身不能用此解析器，否则会递归。 |
| AI 读体阻塞时取消连接仍不动；SSE 结束帧后面的 usage 丢失，异常 EOF、截断或空输出被误判成功。 | [ChatClient](../ai/src/main/java/cn/olfact/ai/net/ChatClient.kt#L35-L159) 用共享 HTTP 边界和背压通道，保留可靠终标、usage 与错误。只在尚未输出文本时重试；[协议解析](../ai/src/main/java/cn/olfact/ai/net/OpenAiWire.kt#L48-L90) 接受 null/缺字段。 |
| 禁用/删除所有 AI 供应商后回落，保存失败假成功；本地免认证服务都被空密钥挡住。 | [ProviderStore](../ai/src/main/java/cn/olfact/ai/ProviderStore.kt#L32-L91) 原子落盘，失败时不再继续存新密钥，不再启用/复活已删项；[AiProvider](../ai/src/main/java/cn/olfact/ai/AiProvider.kt#L23-L40) 正确拼接带查询的 URL，并允许本机回环免密。 |
| 段落过长时重叠突破块预算、emoji 被拆、抽取正文把原段落重排序；关闭翻译弹层时连接或迟到输出未清。 | [分块](../ai/src/main/java/cn/olfact/ai/summarize/TextChunker.kt)、[抽取](../ai/src/main/java/cn/olfact/ai/summarize/ReadabilityExtractor.kt#L61-L99) 保证预算、Unicode 和保留段落的原序；[划词弹层](../ai/src/main/java/cn/olfact/ai/translate/SelectionTranslator.kt#L50-L96) 消失即取消、只更新对应弹层。[地址组件](src/main/java/cn/olfact/core/uikit/UrlBar.kt#L79-L113) 避免多发点击与清空通知。 |

## Windows 轻量回归

从项目根目录执行：

```powershell
pwsh -File core/tools/test-core-ai.ps1
```

入口复用工作区 `.panelfm-tools` 的 JDK 21、Kotlin 编译器及 serialization 插件、JUnit 4.13.2、Hamcrest 1.3、coroutines 1.11.0；独立缓存 `.tools/olfact/lib` 需要 `okhttp-jvm:5.5.1`、`okio-jvm:3.18.1`、`kotlinx-serialization-core-jvm:1.11.0` 与 `kotlinx-serialization-json-jvm:1.11.0`。可用 `-ToolRoot`、`-DependencyRoot` 指定既有目录，脚本本身不下载依赖；产物写工作区 `.tools/olfact/build/core-ai`。

本轮实际结果：**114 例通过**（包括两个真实 loopback 套接字的 AI 读体取消）。JVM 仅使用 Context、日志、Keystore、DataStore 形状桩；设置状态与入队/提交用内存 fake，网络协议、ZIP、文件持久化和 Kotlin 匹配器使用真实 JVM 实现。未验证 UI、实际磁盘 DataStore/Keystore、Room KSP/迁移、WorkManager、真 WebDAV/AI 服务、Android APK/lint、Rust 构建/NDK、带原生 `.so` 的设备行为。完整验证走项目 CI，保留原生降级。

## 仍有的边界

- 普通 UserScript 可注入；GM 文件存储/原生 HTTP 尚未安全接入 WebView，不把 JVM bridge 测试当真实页面验证。避免对任意页面直接挂载未隔离的原生接口。
- WebDAV 服务端仍是直传 `PUT`。对断线事务不完整的服务器，需服务器兼容性与真实服务验证后再设计暂存资源 + `MOVE`；本次只保证本地成品原子发布。
- 跨数据段恢复不能承诺全局事务：格式错误不写任何段；写入错误会明确提示可能部分合并。旧备份缺正文的空脚本跳过是为了不覆盖目前存在的正文；新版能显式保存空脚本。
- 恢复的远端广告订阅不含规则正文，按需在广告拦截页更新，内置规则仍能从 APK 资产读取。Android WebView 清 Cookie/缓存的实际完成时序、显示和设备兼容性待真机复核。
