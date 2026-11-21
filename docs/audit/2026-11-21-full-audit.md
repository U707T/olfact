# 2026-11-21 全量源码审计、修复与改进

## 基线与交付范围

- 仓库：`U707T/olfact`，2026-11-21 拉取 `main@7796c71334eecc65ffc667223dc5098146c61cd6`，初始工作树干净。
- 场景：个人使用的 Android 浏览器；本地浏览数据、可选 DoH/AI/WebDAV、系统下载与 Media3 播放。优先正常操作可靠、数据可恢复和维护简单。
- 基线规模：230 个自有 Kotlin 主源码文件、54 个 Kotlin 测试文件。未把依赖、缓存、构建产物和历史报告计为源码覆盖。
- 六模块原有 Kotlin 主源码与测试已逐文件阅读；按数据/备份、下载/播放、Web/DoH、应用集成/UI 分组审查并交叉复核。本轮不是只扫描文件名或 TODO。
- 本次完成代码修改、回归与文档整理；没有提交、推送、发 tag、触发 CI 或发布安装包。版本仍为 rc13，变更记录放在“未发布”。
- “全量”指上述自有源码范围，不表示第三方实现、所有图像/视觉资产、Android 全平台或所有联网服务均已验证。

## 初步分诊与处理结果

| 功能链 | 初步优先级 | 实际处理 |
|---|---|---|
| 下载与文件发布 | 🔴 | 保护成品、原子队列写入、取消状态机、分块/HLS/合并完整性 |
| 备份、设置与脚本存储 | 🔴 | 共享实际仓库、损坏包边界、写入回执、原子保存、路径和错误反馈 |
| 浏览器标签、返回与设置 | 🔴 | 独立标签渲染器、有界缓存、宿主重绑、无痕元数据与设置失效处理 |
| DoH 与网络链路 | 🟡 | 编解码、双族缓存、超时/取消、启停代际、连接释放和应用内接线 |
| AI、播放器与其界面 | 🟡 | 真实取消、SSE 完成语义、供应商保存、后台恢复与下载元数据 |
| 构建与交付配置 | 🟡 | CI tag 发版依赖检查结果；Windows 轻量验证和环境说明 |

## 已修复问题与新增改进

下表按实际后果归组；每行均为已修改代码。模块报告包含更细的位置、证据、用例与覆盖文件清单。

### 🔴 数据保护和核心结果

| 位置 | 原触发条件与影响 | 本轮修复及证据 |
|---|---|---|
| `download/.../queue/DownloadQueue.kt` | 系统下载已完成后“仅删除记录”仍调用 `DownloadManager.remove`，成品可能随记录删除；暂停/删除被迟到回调覆盖 | 区分记录与文件删除，清理等待 Job 退出，状态锁与 RUNNING 校验保护迟到写回；队列回归覆盖 |
| `download/.../queue/DownloadStore.kt`、`core/.../b4/StoreFiles.kt` | 删除旧文件后再改名，落盘失败或中断丢失原件，部分路径还吞掉错误 | 临时写入、发布前保留旧件、失败恢复并报告；内存缓存只在成功发布后更新 |
| `download/.../ChunkDownloader.kt`、`m3u8/*`、`util/Files.kt` | 短 206、错误 Content-Range、HLS 字节范围被忽略或续传缓存串段，可能生成错误文件却报成功；失败清理会碰同名成品 | 校验范围与长度、完成后发布、使用内容身份隔离分片、支持 BYTERANGE；不覆盖既有成品，失败保留可恢复源分片 |
| `download/.../m3u8/TsRemuxer.kt`、`SampleData.kt` | sample metadata 的 offset 没保留下一帧数据，封装关闭失败仍可能成功 | 按样本边界消费数据，保留尾部已读字节；没有样本/关闭失败明确失败。纯边界回归和真实 Media3 类型检查通过，真实媒体可播性待设备 |
| `core/.../backup/BackupReader.kt`、`BackupPipeline.kt` | ZIP 目录绕过解压计量、缺失脚本正文可能覆盖现有脚本；损坏/部分失败恢复缺乏准确结果 | 所有条目计入限额，恢复前校验；流式导入，缺正文不覆盖，分段写失败明确报告已经合并的部分 |
| `core/.../storage/Settings.kt` | 异步 set/reset 与快照回写乱序，恢复尚未落盘却返回成功，错误类型在泛型读取处崩溃 | 串行编辑队列、内存快照覆盖待写项、批量恢复等待 DataStore 回执，类型不兼容回退默认；写失败与顺序回归通过 |
| `app/.../ui/browser/WebBrowserSurface.kt`、`BrowserFragment.kt` | createView 的 host 形参被闭包捕获，detach/reattach 不生效；切无痕标签复用按普通模式创建的渲染器，仍可能写历史 | 回调读取动态宿主；按标签使用独立渲染器，创建前选择对应标签并固定其无痕属性，离屏暂停/回前台重绑 |

### 🟡 常用操作和跨模块一致性

| 位置 | 原问题与影响 | 本轮修复 |
|---|---|---|
| `download/.../DownloadFacade.kt`、队列调度 | 继续/重试只改状态不重新调度；同批取消可扩散；空闲并发位未及时接新任务 | 重新 WorkManager 调度、串行处理器协调并发、隔离子任务取消、补任务与状态回归 |
| `app/.../data/DownloadAdapter.kt` | 下载存储错误现在必须向上报告，原直传调用会在主线程写盘且无法提示 | 按调用顺序进入 Mutex，在 IO 委派暂停/恢复/删除/批量清理；失败 Toast 与日志，测试核对线程和顺序 |
| `app/.../data/AppBackup.kt` | 第二套订阅/白名单仓库导出陈旧数据；本地备份要求联网；读取整个 ZIP；临时文件混入可恢复列表 | 使用 `AdBlockManager` 共享 section，按目标设置网络约束，流式读取并仅列有效备份名；恢复后刷新模块状态 |
| `app/.../data/AppBackup.kt` | WebDAV 密文绑定旧设备，恢复后覆盖当前可用密码；设置目标变化还可能沿用错误凭据 | 导出/导入均排除密码，目标或用户名变化时清空本机密码；界面说明换设备重配凭据 |
| `app/.../data/BrowserSession.kt` | “不恢复”仍写会话；恢复不保留所选标签；冷启动外链与旧标签 ID 碰撞导致旧页丢失 | 不恢复模式清空持久会话，所选普通标签单独保存；合并保留当前页、碰撞重分配并记录映射，重复恢复幂等，无痕不吸收或落盘 |
| `app/.../ui/browser/SurfaceCache.kt`、`BrowserFragment.kt` | 单槽复用导致切标签丢状态，新增多槽后还需防旧配置继续生效 | 最多 3 个离屏渲染器的 LRU；关闭标签及时释放，Activity 销毁清理；网页设置变化丢弃离屏旧缓存并刷新当前页 |
| 主/兜底 BrowserSurface | 网页进程退出后只有空白或无效的 reload | 保存容器并显示错误，重试重建视图；宿主销毁、切换和缓存释放时清理引用。真实渲染进程恢复待设备 |
| `app/.../data/BackendInstall.kt`、`core/.../net/AppDns.kt` | 一个模块初始化失败挡住后续模块；L1 DoH 和会话粘性文档承诺存在但实际没有共享接线 | 分模块独立初始化；默认 HTTP 客户端动态委派 DoH，停用后回退系统 DNS；浏览渲染与 DoH 使用同一 SessionSticky |
| `web/.../doh/*` | ECS /16 地址族误判、CNAME 影响缓存类型、缓存命中丢 IPv6、切网后旧答案回填、过期代理回调重新启用 | 校验 DNS 响应与长度，按 A/AAAA 查询类型缓存，共用查询 deadline，缓存/生命周期代号防迟到提交，stop 跟踪并关闭连接 |
| `app/.../data/DohUpstreams.kt`、`DohAdapter.kt` | UI 接受本地 HTTP，但适配器退回内置上游；IPv6/端口解析不一致；选择预设也被放进无 bootstrap 的自定义池 | 同一 URI 校验和归一规则，支持合法内网 HTTP/IPv6，公网仍为 HTTPS，保留 path/query 大小写；预设保留自己的 bootstrap 配置 |
| `web/.../browser/BrowserClient.kt`、`BrowserView.kt` | 后台拦截读取 WebView UI API、广告规则的 pageHost 传成资源 host、夜间和拦截 CSS 互相覆盖 | UI 事件保存页面快照、透传请求头，使用真实页面 host，分离 CSS 槽位；iframe 不分派外部应用 |
| `app/.../ui/browser/WebBrowserSurface.kt` | 冷启动首个 BrowserClient 永久捕获尚未初始化的空拦截器；关闭脚本后旧 document-start 注册残留 | 动态读取拦截器；关闭脚本时移除注册，配置刷新时同步处理 |
| `ai/.../net/ChatClient.kt`、应用 AI 面板 | 取消只停读但连接不一定断开；SSE finish_reason 过早退出丢 usage，EOF 当成功；替换面板仍后台生成 | 请求生命周期覆盖响应体消费，取消断连；读取可靠结束标志和用量，区分截断；关闭/替换/取消面板取消所属任务 |
| AI ProviderStore、AiAdapter 与编辑页 | 禁用/删除供应商可能回退出现，保存失败被吞，编辑丢隐藏设置，本机无密钥服务被拦截 | 保存失败保留原配置并提示；保留 keyRef/生成参数/额外头；明确免密规则与禁用状态，不把展示空 key 覆盖已有 secret |
| `core/.../script`、`adblock` | 外部 ID 参与路径、脚本标识冲突、编辑丢授权/排除等元数据、拦截大小写/规则索引错误、JNI 关闭并发 | 路径与标识边界、原文件保护、编辑保留未展示元数据、修正规则匹配并保护原生句柄。Rust 错误 API 已按 crate 核对，未完成 Rust 构建 |
| `player/*` 与播放器详情 | 后台返回丢本次进度/暂停/倍速，非法启动后访问 lateinit；受保护媒体从详情下载缺请求头 | 单独保留本次内存播放状态并处理生命周期；详情下载透传 Referer/UA/Cookie/Authorization/MIME |
| `MainActivity.kt`、`ExternalIntents.kt` | 外部 VIEW 未限定网页 scheme；intent:// 直接当普通 URI，无法正确交系统 | 外部浏览入口只收合法 HTTP(S)；解析 intent URI 后固定 VIEW/BROWSABLE、去 component/selector 和授权 flags；离屏网页不拉起应用 |
| `.github/workflows/ci.yml` | release job 没有依赖测试和构建，可能并行发布；tag 的 lint 被 push 条件跳过 | tag 也运行 lint，release 等待 test/lint/build 全部成功；默认只读，发布 job 单独写权限。actionlint 通过，未远程执行 |

### 🔵 已落实的体验改进

- 多链接按原文先后排序，域名大小写归一但保留路径和查询大小写，不再误去重两个不同资源。
- 地址建议更新时保留向上/向下位置；长按下载预填目标链接，模块未就绪不再提示添加成功。
- 收藏导出返回加锁的只读快照；负历史限制不会使裁剪崩溃；二维码无网址显示明确空态。
- 供应商表单保存失败不自动丢弃输入；AI 面板有真实取消入口。

## rc14 发布前独立复核

用户随后授权完整 CI 与小版本发布，版本候选为 `1.0.0-rc14` / versionCode 14。本节与前述初轮本地审计分开记录；平台状态以后续 Actions 运行结果为准。

- 独立检查 app/web/core-storage 的 52 份生产新增/改动，修复 `AiFragment` 编辑器在局部变量自身初始化表达式内引用该变量的编译错误；接收器标签最小修复已用实际 Kotlin 编译器验证。
- `AiSheet.attach` 不再将先完成后绑定的任务重标为“已取消”。实际面板源码配窄平台形状替身验证完成、失败和取消三种状态保留，真实 Main/WebView 生命周期仍需平台验收。
- 独立复核下载/播放、备份/网络和 Rust 调用边界。新增受控消费者测试证明 HTTP 取消前旧实现的 Job 已结束而分片消费者还在写；改为共享响应生命周期，并保留 pause/cancel 的 Job 句柄供删除等待。共享网络层将取消期间的 socket 异常恢复为 CancellationException 语义。下载/播放回归由 124 增至 126。
- Rust job 的 NDK 路径改由实际 shell SDK 环境计算，不再依赖未显式注入的 Actions env 表达式；显式请求原生构建时，失败不再被 continue-on-error 掩盖。
- 这次复核没有改变已记录的 GM 原生桥、无痕 profile 隔离、设备验收和原生 APK 打包边界。

## 覆盖证据

| 范围 | 基线主源码 / 测试文件 | 阅读与定位明细 |
|---|---:|---|
| app | 65 / 9 | 33 个设置/工具/AI/列表 UI 文件见 [应用 UI 审计](../../app/AUDIT-UI-2026-11-21.md)；其余 32 个数据、启动、导航、首页/标签和浏览渲染文件在本次主审逐文件读完。应用测试的集成范围见[轻量边界](../../app/tools/support-data/README.md) |
| core | 71 / 13 | [核心与 AI 报告](../../core/AUDIT.md)，含存储、备份、脚本、规则、契约、UI kit、Rust 和 Room schema |
| web | 33 / 10 | [Web 报告与逐文件清单](../../web/AUDIT-2026-11-21.md) |
| download | 30 / 15 | [下载报告、修复与完整已读清单](../../download/README.md) |
| player | 13 / 2 | [播放报告、修复与完整已读清单](../../player/README.md) |
| ai | 18 / 5 | [核心与 AI 报告](../../core/AUDIT.md)、[模块说明](../../ai/README.md) |

主审 app 的 32 个文件：`MainActivity.kt`、`OlfactApp.kt`、`data/` 全部 19 个文件、`link/LinkExtractor.kt`、`nav/Nav.kt`、`ui/BottomBarSlots.kt`、`ui/HomeFragment.kt`、`ui/TabsFragment.kt`、`ui/browser/` 全部 6 个文件。另核对根构建、version catalog、Gradle 属性/wrapper、app 构建/manifest、CI 与 README/变更记录。图片和全部视觉布局未做逐屏审美评判。

## 本次实际验证

日期均为 2026-11-21。默认使用现有 JDK 21、Kotlin 2.4.10；core/web/app 的轻量协程为 1.11.0，生产 catalog 仍为 Kotlin 2.3.20 / 协程 1.9.0。没有顺手升级生产依赖。

| 命令（仓库根目录） | 本地结果 | 证明范围 |
|---|---|---|
| `./core/tools/test-core-ai.ps1` | 114 例，无失败 | 实际文件、ZIP、设置队列/回执、供应商、HTTP/本机 socket 取消、SSE、规则等；替身范围见模块报告 |
| `./web/tools/test-web.ps1` | 105 例，无失败；含默认跳过的 2 个真联网 DoH 用例 | 实际 DNS/缓存/隧道/本机连接和 HTTP 拦截器，Android/WebKit 边界替身 |
| `./tools/test-download-player.ps1 -CheckRemuxTypes` | 发布前独立复核后为 126 例，无失败；真实 Media3 1.9.0 类型检查通过 | 下载文件/队列/HTTP/HLS 与纯播放状态；不执行原生媒体运行时 |
| `./app/tools/test-app-data.ps1` | 104 例，无失败 | 真实应用数据与适配器、保存/恢复、LRU 合约、请求句柄等；不含真实 UI 视图挂载 |
| `./app/tools/test-ui-pure.ps1` | 5 例，无失败；这 5 例已包含于应用集成组，不重复计数 | AI 请求句柄与媒体下载上下文 |
| Kotlin PSI、XML、TOML/Python/PowerShell 静态检查 | 307 个生产/测试 Kotlin 文件；273 个 XML、1 份 version catalog、2 个 Python、5 个 PowerShell 脚本解析通过 | 语法/结构，不代表 Android 类型编译或资源链接 |
| `actionlint`、`git diff --check` | 通过 | workflow 语法和差异空白检查，不代表 CI job 已运行 |

core/AI 每次生成独立 GUID 目录，只有测试成功后更新 latest-success 标记；应用入口拒绝陈旧核心源码产物。依赖与输出留在忽略目录，不把本机工具或凭据放入仓库。

早期环境问题已排除：PowerShell/curl 的 Schannel 凭据失败不等于 Maven 不可达，依赖改用正常验证 TLS 的 Python 下载；没有关闭证书检查或换未知镜像。一次全仓 PSI 调用误用了另一个解析器的主类名，改为实际主类后重跑通过，属于检查命令问题。Web 的最初冒烟曾调度默认上游探测，后改为不调度竞速并取消 scope；最终离线/本机回归不依赖外部 DoH 服务。

## 保留限制与待完成的验收

1. **GM 原生桥未安全接入。** 本次没有建立可验证的脚本授权、文档/来源隔离和回包归属，因此没有直接对所有页面开放 addJavascriptInterface；普通 UserScript 仍可执行，原生 GM 存储/跨域请求明确暂不可用。后续需独立设计与验证，不属于已修成功能。
2. **无痕不等于 profile 隔离。** 多个 WebView 仍共用 Cookie/缓存；本次保证标签无痕属性、历史与持久会话元数据边界，没有承诺全隔离。
3. **完整 Android 验证待 CI。** 未运行生产 Gradle 类型编译、资源链接、Room KSP、完整 Robolectric、lint、APK/R8/签名；Rust/NDK 编译也未完成。Media3 类型检查只证明 API 匹配。
4. **设备验收待做。** 重点是 WebView 切标签/滚动表单保留、主/兜底 renderer-gone 重试、设置返回与后台暂停、Cookie、ProxyController 竞态/切网、DownloadManager 保留成品、WorkManager 约束、通知、PiP/CameraX 和真实 TS/fMP4 播放。
5. **下载恢复边界。** 相同 URL/长度的远端资源原地更新仍未做 ETag/Last-Modified 一致性保证；需删任务重下。HLS 中途切换初始化 map 目前明确失败，不生成假成功文件。
6. **联网边界。** 应用默认 HTTP 走共享 DNS；系统下载器与 Media3 HTTP 不经过该入口，QUIC 不走本地 TCP 隧道。真实 AI、WebDAV、各种 DoH 服务的连通性/兼容性待环境验证。
7. **备份不是全库事务。** 损坏包整体校验在写入前完成；后续段失败时保留已合并段并如实报告，没有跨全部存储回滚。WebDAV 使用 PUT，未宣称所有服务端上传都具备原子发布。

没有安装大型工具链、改系统 PATH、访问签名备份或真实业务数据。用户可以从当前工作树审阅全部源码/测试/文档；下一阶段平台验收应使用项目现有 CI 和设备执行上述清单。
