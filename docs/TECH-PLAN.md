# olfact browser · 技术方案（定稿 v1）

> 状态：范围锁定，可开工
> 目标：把 6.82 的能力用一个现代化、可维护、轻量的 Kotlin 工程重做；UI 尽量与原版视觉一致。
> 前身：复刻（repack）版见 `_archive/olfact-repack/`，仅作为 UI 资产与行为参照来源。

---

## 1. 范围（已锁定）

### 1.1 保留

| 组 | 内容 |
|---|---|
| 浏览 | 多标签/多窗口、地址栏+搜索建议、无痕、无图、页内查找、UA 管理、站点配置、长按菜单、桌面小部件、扫码、离线页、主页宫格 |
| 网络 | 自定义 DoH（三档分流）、浏览器代理替换、网络日志 |
| 嗅探播放 | 视频嗅探、嗅探提示、内置播放器（Media3）、倍速/跳片头、重力横屏/PiP |
| 下载 | 多线程下载、M3U8 下载+合并、外部下载器委派、队列/并发设置、自动转存、通知、断点续传 |
| 拦截 | 订阅式拦截（adblock-rust）、网址过滤、元素拦截、白名单、拦截计数 |
| 脚本 | UserScript（油猴）注入、JS 编辑器 |
| UI | 夜间/护眼、底部栏、手势、字号、启动恢复标签、返回不重载、强制新窗口、预测性返回 |
| 数据 | 本地备份/恢复、WebDAV、定时备份 |
| 隐私 | 隐私规则改写、退出清数据、清缓存/Cookie、权限管理、开发者模式 |
| 杂项 | 二维码、口令导入、通知、崩溃日志、协议页、语言切换 |

### 1.2 砍掉（及原因）

| 内容 | 原因 |
|---|---|
| 规则引擎（`hiker://`、`body&&li` 列表/节点规则、订阅、校验、导入） | 用户决定整块去掉；也是最大的一块历史包袱（-17 天） |
| 网页小程序、奇妙工具箱 | 依赖规则运行时；PWA 可替代 |
| 迅雷 SDK（C4） | 闭源 5.35 MB + 合规风险 |
| 磁力/BT（C5） | jlibtorrent 6.88 MB，与"轻量"冲突 |
| 投屏全套（DLNA/网页投屏/遥控投屏）、音乐播放、朗读 TTS | 使用频率低、周期长 |
| 悬浮嗅探、嗅探规则管理、M3U8 广告清除、隧道播放、极速播放(本地代理)、换肤 | 与"只留能播"的决定一致 |
| 视频手势（亮度/音量/进度）、后台播放 | 同上 |
| 隐藏空间、密码管理、表单记忆、文件解压 | 复杂度高、有系统/第三方替代 |
| 主题壁纸、悬浮球、自更新、友盟统计、X5 内核 | 非核心 / 合规风险 / 体积 |

### 1.3 新增（用户提出）

| 编号 | 需求 | 落点 |
|---|---|---|
| N1 | 多 DoH 供应商 + 按域名分流 | §4 |
| N2 | 作为系统默认浏览器 | manifest + `RoleManager.ROLE_BROWSER` |
| N3 | 地址栏从整段文本提取多个链接做候选 | 输入框 debounce + 正则提取，最多 5 条 |
| N4 | AI 供应商管理（OpenAI 兼容） | `:ai` 模块 |
| N5 | AI 网页总结 | Readability 抽正文 → 分块摘要 → 卡片 |
| N6 | AI 划词/整页翻译（Via 风格） | 划词浮层 + 双语对照注入 |

---

## 2. 技术选型（定稿）

| 项 | 版本 | 说明 |
|---|---|---|
| Gradle | **9.7.0**（wrapper） | 与工作区其它项目统一，避免多版本 cache |
| AGP | **9.0.1** | 与 wxtool / MetaRikka 一致 |
| Kotlin | **2.3.20** | 同上 |
| compileSdk / targetSdk / minSdk | **37 / 37 / 24** | SDK 平台已装（34~37） |
| JDK | 21 | 环境自带 |
| OkHttp | **5.5.1**（`okhttp` + `okhttp-dnsoverhttps` + `okhttp-sse`） | SSE 官方支持，DoH 内置 |
| Media3 | **1.9.0**（exoplayer / hls / ui / muxer） | **不引 media3-session**（后台播放已砍） |
| androidx.webkit | **1.18.0** | 注入、代理、暗色、渲染进程恢复 |
| Coil | **2.7.0**（`coil-base`） | 只做图片加载，不引 gif/svg |
| Material / AppCompat | 1.12.0 / 1.7.0 | View 体系 |
| 存储 | Room 单库 + DataStore + kotlinx.serialization | 不用 LitePal/fastjson |
| 密钥 | Keystore + AES-GCM 手写（~30 行） | `security-crypto` 已停止维护，不引 |

**构建纪律**：本地只跑 `assembleDebug --offline`；release 由 CI 出（签名材料在 `/workspace/olfact-keys`，仓库外）。

---

## 3. 模块结构（6 个）

```
olfact/
├─ app/        壳、导航、设置装配、manifest（默认浏览器接管）
├─ core/       工具、常量、事件、Room/DataStore、备份、adblock 引擎、UserScript 注入
├─ web/        WebView 封装、嗅探、UA、站点配置、注入、ProxyController 接入
├─ player/     Media3 封装、PiP、播放器控制条
├─ download/   下载（DownloadManager 优先 + 分块/M3U8 自研）、Transformer/Muxer
└─ ai/         供应商管理、总结、翻译、SSE 流式
```

依赖方向：`app → web/player/download/ai → core`，禁止反向依赖。

---

## 4. DoH 设计（核心章节）

### 4.1 三条通道

| 层 | 覆盖 | 实现 | 成本 |
|---|---|---|---|
| **L1 应用内** | 下载、嗅探探测、AI、备份、订阅、更新 | `OkHttpClient.dns(RoutingDns)` | 0.5 天 |
| **L2 WebView** | 页面全部 TCP 流量（约 90%） | 本地 CONNECT 隧道 + `ProxyController` | 2 天 |
| **兜底** | 系统级 | 设置页跳 `ACTION_PRIVATE_DNS_SETTINGS` | 0.1 天 |

不做 L3（VpnService 全局）：权限、常驻通知、耗电，与定位冲突。

### 4.2 三档策略（同一套规则表）

| 模式 | 默认行为 |
|---|---|
| **智能分流（默认）** | 国内域名 → 系统 DNS；国外/清单内 → DoH |
| **仅指定域名** | 默认系统 DNS，清单内走 DoH |
| **仅排除域名** | 默认 DoH，清单内走系统 |

### 4.3 规则语法

```
example.com          # 精确
*.google.com         # 子域通配（不含自身）
+.blogspot.com       # 含自身 + 所有子域
re:^ads?\..*\.cn$    # 正则（高级）
```
优先级：精确 > 通配 > 正则 > 默认策略。列表支持 txt 一行一条导入/导出。
内置一份常用清单（被污染概率高的站点），开箱可用。

### 4.4 接口约定

```kotlin
interface DohManager {
    fun start(cfg: DohConfig)
    fun stop()
    fun status(): DohStatus          // 当前上游、平均耗时、命中率、失败数
    fun clearCache()
    fun rerace()                     // 重新竞速
    fun isProxying(): Boolean        // L2 是否生效
}

class RoutingDns(
    private val rules: DnsRules,
    private val doh: Dns,
    private val system: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        if (rules.useDoh(hostname)) doh.lookup(hostname) else system.lookup(hostname)
}
```

### 4.5 实现要点（"好用"的必要条件）

1. **缓存**：`host → (ips, expireAt)`，TTL 取应答最小值，下限 60 s / 上限 1 h；NXDOMAIN 负缓存 30 s。
2. **缓存隔离**：key 加前缀 `doh:` / `sys:`，改策略时清对应缓存，避免串味。
3. **上游竞速**：启动与切网时并发探测 5 个上游取最快并记忆；连续 3 次失败重新竞速。
4. **分域 + ECS**：国内池（阿里/腾讯，带 ECS）与国外池（Cloudflare/Quad9，不带）分开。
5. **切网重建**：`NetworkCallback` → 清缓存 → 重新竞速 → 重建隧道；单次查询超时 2.5 s。
6. **熔断**：代理自检失败不 `setProxyOverride`；连续失败自动 `clearProxyOverride()` + 提示。
7. **会话粘性**：某标签页主域命中规则时，该页内所有 host 都走 DoH（覆盖子资源，拦截率 60%→90%）。
8. **可观测**：设置页显示当前上游 / 平均解析耗时 / 缓存命中率 / 失败次数 / 今日查询数 + 一键重竞速。
9. **地址栏图标**：当前页是否走 DoH（🔒 / 🌐），长按可"本站启用加密 DNS"一键加规则。

### 4.6 边界（写进设置页说明）

- **HTTP/3（QUIC）不走代理**：ProxyController 只处理 TCP，UDP 443 会绕过 → L2 覆盖约 90%，100% 需 VpnService。
- **DoH 不解决封锁**：只防 DNS 污染/劫持；IP 层封锁、SNI 阻断（需 ECH+MITM）不在范围。
- **不做 MITM/ECH**：需要内嵌 CA + 让系统信任用户证书，安全面变大，收益不匹配。

### 4.7 验收指标

| 指标 | 目标 |
|---|---|
| 首字节 vs 不开 DoH | 劣化 ≤ 10% |
| 同站点二次访问缓存命中率 | ≥ 80% |
| 切网后恢复 | ≤ 3 s |
| 上游故障自动切换 | ≤ 5 s |
| 页面白屏率 | 0（熔断生效） |

不达标不算"好用"。

---

## 5. 广告拦截（adblock-rust）

- 引擎：Brave `adblock-rust` 的 `adblock-ffi`（C ABI）→ JNI 壳 → Kotlin 高层 API。
- **编译只能在 CI**：沙箱内 rustc/cargo 1.75 无 rustup、无 cargo-ndk，本地编不出 Android `.so`。
  CI 步骤：`cargo ndk -t arm64-v8a -t armeabi-v7a -o app/src/main/jniLibs build --release`，产物 `libadblock_ffi.so`（约 1 MB）。
- 规则集：`Engine` 序列化格式（`.dat`）放 assets，首次 mmap 加载，避免冷启解析十万条。
- 能力：网络请求拦截（uBO/ABP 语法）、cosmetic filter（元素隐藏 CSS）、白名单、计数。
- 接入点：`WebViewClient.shouldInterceptRequest`（L1）+ 页面注入 CSS（cosmetic）。
- 兜底：Rust 编译失败时降级为 Kotlin 匹配器（规则上限 2 万条），保证功能不阻塞。

---

## 6. UI 复刻（视觉一致）

原则：**不自由设计，拿原版资源当设计稿**。

| 资产 | 来源 | 处理 |
|---|---|---|
| drawable（含 9-patch）、mipmap、color、dimen、style、font | `_archive/olfact-repack/decode/res/` | 直接复用 |
| 布局结构与 id 名 | `decode/res/layout/*.xml` | 逐屏翻译成 ViewBinding，id 保持一致、dp/sp 照抄 |
| 主题配色 | `decode/res/values/styles.xml` | 提取 colorPrimary / windowBackground / textColor 建成自有主题，不套 Material3 默认色 |

- 目标界面数量：30~40 个（不是 507 个）。
- 验收：结构/尺寸/颜色逐项对齐；间距微调需要**关键界面截图**（首页、底部栏、主菜单、设置页、播放页、下载页）。

---

## 7. 其它子系统

| 子系统 | 方案 |
|---|---|
| 下载 | 单文件走系统 `DownloadManager`；M3U8/多线程分块自研；合并用 `media3-muxer`（Mp4Muxer）做 TS→MP4 remux，失败降级"仅拼接 .ts" |
| 脚本 | **不做 QuickJS**：UserScript 通过 `addDocumentStartJavaScript` 注入页面，GM_* 走 JsBridge |
| 嗅探 | `shouldInterceptRequest` 识别 m3u8/mp4/mpd → 列表 → Media3 起播 |
| 存储 | DataStore（设置）+ Room 单库（书签/历史/下载）+ Keystore AES-GCM（密钥） |
| 备份 | 自定义 `zip + JSON`（不再兼容 `.hiker`） |
| 默认浏览器 | manifest 四组 filter（VIEW http(s) / WEB_SEARCH / SEND / PROCESS_TEXT）+ `RoleManager.ROLE_BROWSER` |
| 文本提链 | 输入 debounce 200 ms，正则提取 `http(s)://…`、`www.…`、`域名/路径`，去重取前 5 |
| AI | OpenAI 兼容（baseUrl + key + model，多供应商）；`okhttp-sse` 流式；总结用 Readability；翻译做划词浮层 + 双语对照 |

---

## 8. 里程碑（46 个工作日）

| 阶段 | 内容 | 天 | 验收 |
|---|---|---|---|
| M0 | 骨架、wrapper、CI、空壳可装 | 2 | `assembleDebug --offline` 通过；CI 出 debug artifact |
| M1 | 浏览内核（多标签/地址栏/建议/无痕/无图/查找/UA/站点配置/夜间/手势/恢复标签） | 6 | 能正常浏览、切页不丢状态 |
| M2 | 嗅探 + Media3（PiP/倍速） | 4 | 抓到 m3u8 能起播、PiP 可用 |
| M3 | 下载（DownloadManager + 分块 + M3U8 + muxer 合并） | 5 | m3u8 下完能播 |
| M4 | 拦截（adblock-rust + CI 编 .so + 元素隐藏） | 5 | 订阅生效、拦截计数准确 |
| M5 | UserScript 注入 + JS 编辑器 | 2 | 常用油猴脚本可跑 |
| M6 | UI 复刻（30~40 界面） | 9 | 与原版截图对照通过 |
| M7 | AI（供应商/总结/翻译） | 4 | 流式输出、可取消 |
| M8 | DoH（L1+L2+三档分流+缓存/竞速/切网/面板） | 6 | 达标 §4.7 |
| M9 | 备份/隐私/默认浏览器/提链/体积收尾 | 3 | release < 15 MB |

---

## 9. 体积预算（release，R8 + ABI split）

| 组成 | 估算 |
|---|---|
| Media3（无 session） | 2.2 MB |
| androidx（webkit/appcompat/material） | 1.8 MB |
| OkHttp + serialization + DataStore + Room | 1.8 MB |
| Coil-base | 0.5 MB |
| `libadblock_ffi.so` | 1.0 MB |
| 自身代码 + 资源 | 2.0 MB |
| **合计** | **约 9~11 MB** |

---

## 10. 被否掉的方案（决策记录）

| 方案 | 否掉原因 |
|---|---|
| X5/TBS 内核 | 闭源、运行时再下几十 MB、版本不可控 |
| libadblockplus 15.6 MB | 停更、体积大、C++ JNI |
| 纯 Kotlin 拦截器（不做 Rust） | 十万条规则正则跑不动，首屏掉帧 |
| QuickJS 沙箱 | 规则引擎已砍，没有"脱离页面执行"的需求 |
| media3-session + 前台服务 | 后台播放已砍 |
| ffmpeg 模块 | `media3-muxer` 可替代，且 ffmpeg-kit 已停维护 |
| MITM + ECH（cfbrowser 做法） | 需内嵌 CA 并让系统信任用户证书，安全面过大 |
| VpnService 全局 DoH | 权限/通知/耗电与定位冲突 |
| 保留 `.hiker` 备份格式 | 规则引擎已砍，无兼容对象 |
| 507 个 layout 全复刻 | 工期不现实，改为 30~40 个核心界面 + 视觉对齐 |

---

## 11. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 本地无 rustup，adblock-rust 只能 CI 编 | 阻塞 M4 | M0 阶段先打通 CI 交叉编译流水线；失败则降级 Kotlin 匹配器 |
| HTTP/3 绕过 L2 代理 | 约 10% 流量仍用系统 DNS | 文档说明；不承诺 100% |
| 视觉还原缺截图 | M6 验收主观 | 用户提供 6 张关键界面截图 |
| 部分站点依赖 X5 特性 | 兼容性差异 | 外链交给系统浏览器兜底 |
| M3U8 转封装失败 | 个别编码无法合并 | 降级"仅拼接分片" |
| proot 构建慢、缓存膨胀 | 迭代效率 | 只 debug 构建、`--offline`、CI 出包 |

---

## 12. 待办（按顺序）

1. 拷贝 `gradlew` + `gradle-wrapper.jar`（从 wxtool），`assembleDebug --offline` 跑通 M0。
2. 建 GitHub 仓库 `U707T/olfact`，推送，验证 CI 出 artifact。
3. CI 打通 adblock-rust 交叉编译（产出 `.so` 入库或走 artifact）。
4. M1 开始：WebView 壳 + 多标签 + 地址栏。
5. 需要用户提供：6 张关键界面截图（首页/底部栏/主菜单/设置页/播放页/下载页）。
