# web · 浏览内核 + DoH（B2）

分支 `feat/b2-web`。只改 `web/**`（另有两处跨批必要改动，见文末“越界说明”）。

## 1. 这个模块给了什么

### 浏览内核 `cn.olfact.web.browser`

| 文件 | 职责 |
|---|---|
| `BrowserView` | `WebView` 子类：UA、缩放、无图、夜间（`setAlgorithmicDarkeningAllowed` + CSS 兜底）、长按命中、下载监听、document-start 脚本 |
| `BrowserClient` | 请求链路：`shouldInterceptRequest` 里做拦截 → 嗅探 → 粘性打点；页面生命周期回调 |
| `BrowserChromeClient` | 进度 / 标题 / 图标 / `target=_blank` |
| `TabManager` | 最多 8 个活 WebView，LRU 回收（保留 URL，切回重建），`saveState` / `restoreState` |
| `Tab`（内部类） | 标签状态：id / url / title / incognito / lastUsed |
| `CookieJar` | 普通 vs 无痕隔离（快照-清空-恢复，见 §4） |
| `UrlBarController` | 输入判定、200ms 防抖建议、文本提链、提交 |
| `SuggestionEngine` | 纯逻辑：URL 判定、协议补全、候选排序 |
| `LinkExtractor` | 纯逻辑：`http(s)://` / `www.` / 裸域名提取，去重，最多 5 条 |
| `PageTools` | 页内查找、无图、夜间、缩放、桌面模式 |
| `UaManager` | 移动 / 桌面 / 自定义 / 按域名 |
| `SiteConfigStore` | 按域名配置（JS/图片/夜间/桌面 UA/DoH），DataStore 存 JSON 映射 |
| `BrowserHostImpl` | 实现 `core/api/BrowserHost`，B5 的唯一入口 |
| `LongPressTarget` / `DownloadRequest` | 长按菜单与下载的跨模块数据 |
| `BrowserEvents` | 历史 / 标题 / 进度出口（无痕不触发 `onPageVisited`） |

### DoH `cn.olfact.web.doh`

| 文件 | 职责 |
|---|---|
| `DohManagerImpl` | 总装：L1 + L2 + 竞速 + 缓存 + 熔断 + 切网重建 + 看门狗 |
| `DohManager` | 接口（`start/stop/status/clearCache/rerace/isProxying` + `statusFlow`） |
| `DnsRules` / `DnsRule` | 精确 > 通配 > 正则；`re:` / `*.` / `+.` / 裸域名；txt 导入导出；内置 60+ 条 |
| `DomesticSites` | 国内域名判定（后缀 + 站点表，无需联网） |
| `DnsCache` | TTL（60s~1h）+ 负缓存 30s + `doh:` / `sys:` 前缀隔离 + 命中率 |
| `DnsMessage` | 自研 RFC1035/8484 编解码（A/AAAA + EDNS0 ECS），为了拿到 TTL |
| `DohTransport` | RFC8484 POST + bootstrap IP 表（打破 DoH 域名解析的鸡生蛋） |
| `UpstreamPool` | 5 路并发竞速，国内/国外各记最快，连续 3 次失败重竞速 |
| `DohDns` | `okhttp3.Dns` 实现：A/AAAA 并发、缓存、失败兜底系统 DNS |
| `RoutingDns` | 按规则分流 DoH / 系统，两条通道各自前缀缓存 |
| `SessionSticky` | 会话粘性：主域命中后页内所有 host 走 DoH（10 分钟 TTL） |
| `TunnelProxy` | 本地 `ServerSocket`，只处理 80/443 的 CONNECT 与明文 GET，**不解密 TLS** |
| `ProxyBridge` | `ProxyController.setProxyOverride` + 自检 + 连续 3 次失败熔断 |
| `NetworkWatcher` | `NetworkCallback` + 500ms 防抖 → 清缓存 / 重竞速 / 重建隧道 |
| `DohStats` | 平均耗时（EMA）/ 命中率 / 失败数 / 今日查询 |
| `DohPanelView` + `res/layout/view_doh_panel.xml` | 状态面板，B5 直接 `bind(status)` |

## 2. 接入方式

### app 侧（B5）

```kotlin
// 1) DoH 单例（Application 或 MainActivity 里）
val doh = DohManagerImpl(context)
doh.start(DohConfig(
    mode = DohMode.SMART,
    rules = DnsRules.builtin(DohMode.SMART),
    useProxy = true,
    ecsSubnet = "116.24.0.0/16"     // 可选，国内池带 ECS
))
doh.statusFlow.collect { status -> panelView.bind(status, doh::rerace, doh::clearCache) }

// 2) 标签页
val tabs = TabManager(
    context = activity,
    container = binding.webContainer,          // FrameLayout
    deps = BrowserDependencies(
        sniffer = mediaSniffer,                // B3 实现
        adblock = adBlockHook,                 // B4 实现
        scripts = scriptSource,                // B4 实现
        sticky = SessionSticky(),              // 或与 DohManagerImpl 共用一个
        siteConfigStore = siteConfigStore,
        uaManager = UaManager(UaManager.systemMobileUa(context)),
        configFor = { host -> /* 读设置仓库，合成本页 BrowserConfig */ BrowserConfig() },
        dohPredicate = doh::isDohHost,         // 供粘性判定
        events = object : BrowserEvents { ... },
        externalUrlHandler = { url -> /* 拉起外部 App */ true },
        downloadHandler = { req -> /* 交给 download 模块 */ }
    )
)

// 3) 对外入口
val host: BrowserHost = BrowserHostImpl(tabs) { startActivity(Intent(this, SettingsActivity::class.java)) }
```

### L1：让下载/嗅探/AI 也走 DoH

```kotlin
val client = doh.wrap(OkHttpClient.Builder().build())   // dns 被换成 RoutingDns
```

## 3. 关键设计决定

1. **自研 DNS 报文编解码**（`DnsMessage`）：OkHttp 的 `DnsOverHttps` 不暴露 TTL，无法做 TTL 感知缓存与负缓存，所以按 RFC 8484 直接 POST `application/dns-message`，自己解析 A/AAAA/TTL/rcode。
2. **bootstrap IP 表**：DoH 服务器域名本身也要解析，用硬编码 IP 建连（`BootstrapDns`），避免“用被污染的 DNS 去解析 DoH 域名”。
3. **缓存前缀隔离**：`doh:` / `sys:` 分开，切换模式或切网时按前缀清，避免同一域名换通道后拿到旧结果。
4. **失败兜底**：DoH 全失败时 `DohDns` 回落到系统 DNS，宁可退化成明文也不白屏。
5. **L2 不做 MITM**：`TunnelProxy` 只搬字节，不解 TLS、不装 CA；代价是 HTTP/3（UDP 443）绕行，L2 覆盖约 90%。
6. **粘性会话**：主域命中后，页内第三方 host 逐个登记（10 分钟 TTL），把拦截率从 ~60% 拉到 ~90%。

## 4. 已知边界（写进设置页说明）

- **无痕 Cookie 隔离是“快照”方案**：Android 的 `CookieManager` 是进程级单例，没有双 profile。进入无痕时把普通模式访问过的域名 Cookie 存内存并清空全局库，退出时写回。进程被杀会丢快照（表现为普通模式需重登）。
- **HTTP/3 不走代理**：`ProxyController` 只接管 TCP，QUIC 直接绕过。
- **DoH 不解决封锁**：只防 DNS 污染/劫持；IP 封锁、SNI 阻断不在范围。
- **代理自检依赖网络**：`DohManagerImpl.selfCheck` 会真的 CONNECT 一次 `www.gstatic.com:443`，网络差时会退回“仅应用内 DoH”而不是白屏。
- **看门狗周期 1s**：隧道监听线程死掉后约 3s 触发 `clearProxyOverride`。

## 5. 验收对照

| 项 | 证据 |
|---|---|
| 多标签 / 后台不丢 / 无痕不落历史 | `TabManager` LRU + `saveState`/`restoreState`；`BrowserClient` 对 incognito 不调 `onPageVisited` |
| 无图 / 页内查找 / 夜间 | `BrowserView.setImagesEnabled` / `PageTools.find` / `setNightMode` |
| 嗅探 m3u8 回调 `MediaSniffer` | `BrowserClient.shouldInterceptRequest` → `MediaTypes.of` → `sniffer.onMediaDetected` |
| DoH 五项指标 | 缓存 + 竞速 + 切网重建 + 熔断 + 统计，见 §1；联网实测见 §6 |
| 熔断 3 秒内恢复直连 | `DohManagerImpl.startWatchdog`（1s × 3）+ `ProxyBridge.detach()` |

## 6. 测试

```bash
./gradlew :web:testDebugUnitTest            # 46+ 纯 JVM 单测，不联网
./gradlew :web:testDebugUnitTest -Polfact.liveDoh=1 --tests '*LiveDohTest*'   # 真联网
```

真联网实测（本机一次结果）：

```
selection=Selection(domestic=阿里 DoH, foreign=Cloudflare, latencies={cloudflare=1628, tencent=1026, ali=641})
www.example.com -> [172.66.147.243, 104.20.23.154, 2606:4700:10:0:0:0:ac42:93f3, ...]
stats=Snapshot(avgLatencyMs=269, cacheHitRate=0.5, failures=0)
CONNECT status=HTTP/1.1 200 Connection Established
```

覆盖：`DnsRules` 匹配优先级/三档模式/导入导出/ECS 截断、`DnsCache` TTL 与负缓存/前缀隔离、`DnsMessage` 编解码与压缩指针、`LinkExtractor` 提链边界、`SuggestionEngine` 判定与排序、`SessionSticky` 粘性与过期、`RoutingDns` 分流与缓存、`TunnelProxy` 端到端（CONNECT 透传 / 端口拒绝 / 明文 GET 改写）。

## 7. 越界说明（需 B1 认领）

B2 开工时 `core/api` 还是空目录，`web` 无法编译。为了让本批次可独立验证，做了两处**最小跨批改动**：

1. `core/src/main/java/cn/olfact/core/api/`：按 `docs/tasks/README.md` §3 原样落了 5 个接口 + `MediaItem` / `MediaType` / `settingsKey`。
   B1 落地时若签名一致，可直接 `git checkout master -- core/src/main/java/cn/olfact/core/api` 覆盖；若不一致，以 B1 版本为准，`web` 侧只依赖这几个签名。
2. 根 `build.gradle.kts`：加了 `subprojects { plugins.withId("org.jetbrains.kotlin.android") { ... jvmTarget = JVM_17 } }`。
   原因：AGP 9 + 外部 KGP 时 Java 目标 17、Kotlin 跟工具链 21，`:core:compileDebugKotlin` 直接报 `Inconsistent JVM Target Compatibility`。不修则任何模块都编不过。

`web/build.gradle.kts` 另加了 `androidx.datastore:datastore-preferences`（站点配置）与 `junit`（单测），均在方案允许范围内（未引入 Compose / Hilt / Retrofit）。
