# B2 · 浏览内核 + DoH（M1 + M8，12 天）

> 分支：`feat/b2-web` ｜ 只改 `web/**` ｜ 依赖 B1 的接口桩

## 目标

一个能用的 WebView 浏览器壳（多标签/地址栏/无痕/无图/UA/站点配置/夜间），加上整套 DoH（应用内 + WebView 隧道 + 三档分流）。

## 交付物

### 1. 浏览内核（`web/browser/`）
- `BrowserView`：`android.webkit.WebView` 子类，封装 UA、缩放、无图、夜间、长按、下载监听
- `TabManager`：最多 8 个 WebView，LRU 回收，`saveState/restoreState` 持久化
- `CookieJar`：普通模式与无痕模式隔离（无痕用 `CookieManager` 独立实例 + 不写历史）
- `UrlBarController`：输入、搜索建议（debounce 200ms）、**文本提链**（正则提取，最多 5 条候选）
- `PageTools`：页内查找、无图切换、夜间（`WebSettingsCompat.setAlgorithmicDarkeningAllowed`）
- `UaManager`：移动/桌面/自定义/按域名
- `SiteConfigStore`：按域名存 JS/图片/UA/DoH 开关（Room 或 DataStore）
- `LinkExtractor`：`http(s)://…`、`www.…`、`域名/路径` 正则提取 + 去重

### 2. 嗅探与钩子
- `shouldInterceptRequest` 里识别 `m3u8/mp4/mpd` → 回调 `MediaSniffer`
- 广告拦截调用点：命中即 `AdBlockHook.shouldBlock()`，并注入 `cosmeticCss()`
- 脚本注入：`ScriptSource.documentStartScripts(url)` → `addDocumentStartJavaScript`
- 这三个钩子只用 B1 的接口，**不要 import B4 的实现**

### 3. DoH（`web/doh/`，重点）
- `DohManager`：实现 `start/stop/status/clearCache/rerace/isProxying`
- `RoutingDns`：按 `DnsRules` 决定走 DoH 还是系统（见 TECH-PLAN §4.4）
- `DnsRules`：精确/通配/正则三级匹配，支持 txt 导入导出，内置常用清单
- `UpstreamPool`：国内池（阿里/腾讯，带 ECS）、国外池（Cloudflare/Quad9），启动与切网时 5 路竞速取最快并记忆，连续 3 次失败重竞速
- `DnsCache`：TTL 感知 + 负缓存，key 前缀 `doh:`/`sys:` 隔离
- `TunnelProxy`：本地 `ServerSocket`，只处理 80/443 的 `CONNECT` 与明文 GET，用 DoH 解析后建隧道；**不解密 TLS，不做 MITM**
- `ProxyBridge`：`androidx.webkit.ProxyController.setProxyOverride/clearProxyOverride`，起代理前先自检，连续失败熔断
- `NetworkWatcher`：`NetworkCallback` → 清缓存 → 重竞速 → 重建隧道
- `SessionSticky`：页面主域命中规则时，本标签页所有 host 走 DoH
- `DohStats`：当前上游/平均耗时/命中率/失败数/今日查询数（供 B5 渲染）
- 面板 UI 放在 `web/src/main/res/`，由 B5 调用

### 4. 单元测试
- `DnsRules` 匹配优先级、`LinkExtractor` 提取、`DnsCache` TTL 过期

## 验收

| 项 | 标准 |
|---|---|
| 浏览 | 能开多标签、切后台不丢、无痕不落历史 |
| 页内 | 无图/页内查找/夜间均生效 |
| 嗅探 | 抓到 m3u8 能回调到 `MediaSniffer` |
| DoH | TECH-PLAN §4.7 五项指标全达标 |
| 熔断 | 手动杀掉隧道进程，3 秒内自动 `clearProxyOverride` 且页面可继续访问 |

## 禁止

- 不碰 `app/`、`player/`、`download/`、`ai/`、`core/adblock`、`core/script`
- 不做 MITM/ECH、不引 VpnService
- 不在 `web` 里写设置页 UI（交给 B5）

## 开局 prompt

```
读 /workspace/olfact/docs/TECH-PLAN.md（重点 §4 DoH）和 docs/tasks/README.md，
然后按 docs/tasks/B2-browser-doh.md 执行。

分支 feat/b2-web，只改 web/**。接口只用 core/api 里的桩，不要 import 其他模块实现。
先做浏览器壳（BrowserView + TabManager + UrlBar），再做 DoH 的 L1（RoutingDns），
最后做 L2（TunnelProxy + ProxyController）与分流规则、缓存、竞速、熔断。
每一步跑 ./gradlew :web:assembleDebug --offline 验证。
```
