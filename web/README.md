# web · 浏览内核与 DoH

本模块提供 WebView 请求链、页面设置和 DNS 分流。当前浏览器集成在 `app/ui/browser/WebBrowserSurface`；标签页与 surface 缓存由 app 管理。旧 B2 文档提到的 `TabManager`、`BrowserHostImpl`、`UrlBarController`、`PageTools` 已不在本模块，请以当前代码为准。

## 浏览器接入

| 组件 | 当前职责 |
|---|---|
| `BrowserView` | UA、缩放、无图、第三方 Cookie、夜间模式、长按/下载、document-start 脚本注册与撤销 |
| `BrowserClient` | 广告拦截、媒体嗅探、会话粘性、页面/错误/渲染进程回调、外部协议分派 |
| `BrowserChromeClient` | 进度、标题、图标；当前 WebView 不启用多窗口，链接默认在当前视图处理 |
| `BrowserConfig` / `UaManager` | 页面配置及移动/桌面/自定义 UA |
| `DownloadNames` / `LongPressTarget` / `MediaTypes` | 下载文件名、长按命中和媒体 URL 分类 |
| `SuggestionEngine` / `LinkExtractor` | 纯逻辑输入识别、候选与文本提链 |
| `SiteConfig` / `SiteConfigStore` | 可复用站点覆盖及 DataStore 存储辅助；不代表 app 已接入所有字段 |
| `CookieJar` | 未接入当前 app 的历史快照辅助，不提供真正的 WebView profile 隔离 |

`BrowserClient.shouldInterceptRequest` 在 WebView 工作线程运行。媒体 Referer/UA 从主线程页面事件保存的快照读取，请求实际携带的头一并透传；广告钩子的第二参数是**页面 host**，用于 `$domain` 与白名单匹配。自定义协议只对主框架调用外部处理器，子框架请求直接消费；处理器拒绝或失败时也消费该 URL，避免把它作为 WebView 网络错误再次加载。

页面导航开始前调用 `BrowserView.applyDocumentStartScripts`，包括关闭脚本时传空列表撤销旧注册。脚本匹配、document-end/idle 调度由 app/core 负责。旧 WebView 不支持 `DOCUMENT_START_SCRIPT` 时没有伪造 document-start 兜底。夜间 CSS 与广告隐藏 CSS 使用独立 DOM 槽，关闭夜间不会删除隐藏规则。

## DoH 请求链

| 组件 | 当前职责 |
|---|---|
| `DohManagerImpl` | L1 分流、L2 隧道、启停、切网、缓存失效、竞速与看门狗 |
| `DnsRules` / `DomesticSites` | 精确/通配/正则规则、智能/仅指定/仅排除三档模式、国内站点判断 |
| `DnsMessage` | DNS A/AAAA、CNAME TTL、压缩名称及 EDNS0 ECS；国际化域名转 ASCII；IPv4/IPv6 ECS 保留原地址族 |
| `DohTransport` / `BootstrapDns` | RFC 8484 POST、独立 bootstrap 解析、单次 call 超时；拒绝截断/超长/格式异常响应 |
| `DohDns` | A/AAAA 并发；按查询类型合并和缓存，两个地址族共用一次等待期限，失败回落系统 DNS |
| `DnsCache` | 正缓存 60 秒至 1 小时、负缓存 30 秒、LRU 上限、`doh:`/`sys:` 隔离、失效代号防迟到写入 |
| `RoutingDns` / `SessionSticky` | 分流与会话登记；浏览器与 manager 必须注入同一个 sticky 实例，销毁标签时 `closeTab` |
| `UpstreamPool` / `DohUpstream` | 五路后台竞速、国内/国外池、用户首选与自定义上游；首选必须属于本次配置集合 |
| `TunnelProxy` | 本地 80/443 CONNECT 与明文 HTTP 转发，不解密 TLS；停止时关闭处理、排队及连接中的 socket |
| `ProxyBridge` / `NetworkWatcher` | 自检后挂代理、迟到回调隔离、熔断、默认网络变化防抖 |
| `DohStats` / `DohStatus` / `DohPanelView` | 统计快照与可复用状态面板 |

```kotlin
val sticky = SessionSticky()
val manager = DohManagerImpl(context, sticky = sticky)
manager.start(DohConfig(mode = DohMode.SMART, useProxy = true))
// 将同一个 sticky 交给 BrowserClient；由 app 将 statusFlow 绑定到 UI。
// 一次性包装现有 OkHttpClient：
val client = manager.wrap(existingClient)
// 长生命周期客户端应动态读取 manager.appDns()；未启动时返回 null，使用系统 DNS。
```

L1 只有实际使用分流 DNS 的客户端才生效。DoH 传输本身使用独立 `BootstrapDns`，不要给它再接入应用级 DNS 代理，否则会递归。

启停与隧道重建使用代号隔离旧任务。自检在后台执行，新隧道在自检完成前已登记，关闭 DoH 或再次切网会关闭它。`ProxyBridge` 忽略旧 attach/clear 回调；缓存也拒绝清除之前启动的解析结果写回。应用保留的旧 `wrap` 客户端在解析器停用后回落系统 DNS。

## 本地轻量验证与 CI

在仓库根目录运行：

```powershell
# 首次下载轻量测试的三个依赖；缓存位于已忽略的 web/build/pure-jvm-deps。
python web/tools/fetch-test-deps.py
pwsh -File web/tools/test-web.ps1
# 可覆盖工具目录、java.exe 和依赖目录：
pwsh -File web/tools/test-web.ps1 -ToolRoot <JVM工具目录> -Java <java.exe> -DependencyDirectory <依赖目录>
```

工具目录布局为 `kotlin/kotlinc/lib`、`lib`、`jdk21/jdk-21.0.13.1+2/bin/java.exe`；`lib` 需 JUnit 4.13.2、Hamcrest 1.3、coroutines-core/test JVM 1.11.0。默认复用工作区 `.panelfm-tools`，不修改全局 PATH，不安装 Android SDK。下载脚本固定使用项目当前 OkHttp 5.5.1 对应的 okhttp-jvm 与 Okio 3.18.1；JSON 20240303 只用于轻量替身环境。

2026-11-21 本次实跑：JDK 21、Kotlin 2.4.10、协程 1.11.0、真实 OkHttp/Okio，105 个 JUnit 用例无失败，其中 2 个 LiveDoH 用例按假设检查默认跳过。轻量编译包含 31 个实际模块主源码，排除 `SiteConfigStore` 的 DataStore 和 `DohPanelView` 的 Android 布局；测试使用 13 个模块测试文件，另有 `tools/tests/PlatformBoundaryTest`。

`tools/support` 是明确的 Android/WebKit 替身：校验代理迟到回调、媒体线程边界、样式槽和外部协议分派，不模拟真实 WebView 渲染、Cookie 持久化、Android 网络回调或 ProxyController 底层行为。它也不替代正式版本 catalog（Kotlin 2.3.20、协程 1.9.0）的 Gradle 编译。完整 Android 编译、Gradle 单测与 lint 由项目 CI 执行：

```powershell
.\gradlew.bat :web:testDebugUnitTest
.\gradlew.bat :web:lintDebug
# 显式联网验证，仅在可联网且具备 Android Gradle 环境时运行：
.\gradlew.bat :web:testDebugUnitTest -Polfact.liveDoh=1 --tests '*LiveDohTest*'
```

本轮未运行真实 DoH 或 Android 构建，不把历史联网日志作为本轮验证。逐文件覆盖与修复依据见 [本轮审计记录](AUDIT-2026-11-21.md)。

## 运行边界

- 普通与无痕 WebView 共享进程 Cookie/缓存；无痕历史与标签持久化由 app 控制，不能宣传为独立 profile。`CookieJar` 的旧快照方案未接入，也不能完整保存 Cookie 属性。
- L2 不做 MITM，不装 CA，不解密 TLS；HTTP/3/QUIC 不由 TCP 隧道处理。不承诺 HTTP/3 覆盖比例。
- DoH 防 DNS 污染，不解决 IP/SNI 阻断；DoH 失败会回落系统 DNS。未开启加密的通道仍按规则使用系统解析。
- 代理自检默认 CONNECT `www.gstatic.com:443`，依赖真实网络，自检失败退回仅应用内 DoH。
- 看门狗每秒检查监听状态，连续 3 次异常清代理；是否真实恢复直连需设备验收。WebView 代理能力还取决于系统与 WebView provider。
