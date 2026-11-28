# rc17 功能完善记录 · 2026-11-28

## 范围

在已发布的 `1.0.0-rc16`（`cab3f75`）上继续完善两项常用能力，并修掉收藏链路上的几处遗漏。本轮没有重跑全量源码审计，也没有改变 rc16 已确认的边界。

## 网页视频全屏

- `web/.../BrowserChromeClient.kt` 实现 `onShowCustomView` / `onHideCustomView`（含旧版带 `requestedOrientation` 的重载，保持用户方向策略不变）。新增 `CustomViewSession` 统一管理唯一所有权：重复请求、宿主拒绝或抛异常时立即完成 `onCustomViewHidden`，避免页面一直等回调导致播放器黑屏。
- `app/.../MainActivity.kt` 增加覆盖层宿主：只接受当前可见渲染器，退出时恢复系统栏（若菜单「全屏」仍开启则保持隐藏），Activity 销毁前先收尾。
- `app/.../WebBrowserSurface.kt` 在页面开始加载、前进后退、重新加载、宿主切换、渲染进程退出和销毁时统一收尾，避免把 A 标签的自定义视图挂到 B 标签。
- 返回键优先退出网页全屏，再按原优先级处理地址条、页内查找、网页后退。
- 离屏缓存或非当前页请求全屏会被拒绝，不把视频盖到别的标签上。

真实 WebView 行为（进入/退出、旋转、返回手势、系统栏恢复）仍待设备验收。

## 收藏补全与可靠性

- `FavoriteStore.saveOrEnrich` 区分新增、补全、已存在：重复收藏同一地址时补齐缺失的来源页、媒体类型与 MIME，保留原 ID、收藏时间、排序和已有有效标题；不再要求用户删除后重加。
- 备份恢复遇到同 URL 时也走补全路径，同批重复项不会互相覆盖；撤销删除若该地址已被重新收藏，只补全而不产生重复记录。
- 来源页只接受有效 HTTP(S)（拒绝 `ftp:`、`file:`、`intent:`、非法端口与空 host），持久化继续剔除账户信息、查询参数和片段；Cookie/Authorization 等认证头不落盘。
- `PlayerFragment` 从收藏下载时回退使用已保存的 MIME；私密媒体「回来源页」继续使用无痕标签。
- `FavoritesFragment` 返回时刷新列表，并在视图销毁时解绑 RecyclerView。

## 验证

| 入口 | 结果 | 说明 |
|---|---|---|
| `web/tools/test-web.ps1` | 119 项通过（原 112 + 全屏 7） | 含真实 `BrowserChromeClient` 的重复退出、拒绝、抛异常、旧版入口用例 |
| `app/tools/test-app-data.ps1` | 129 项通过（原 124 + 收藏 5） | 使用工作区专用 TEMP 后通过 |

`web/tools/test-web.ps1` 原先只执行 `PlatformBoundaryTest`，新增的 `web/tools/tests` 用例会静默不跑；本轮改为按包名自动发现该目录下的全部 `*Test.kt`。

Windows 系统 `%TEMP%` 曾导致 `AccessDeniedException`（脚本测试临时目录无法创建），属于环境问题；改用工作区 `.tmp/` 下的专用临时目录后全部通过，不是产品缺陷。

## 边界

- 未新增网页摄像头/麦克风/定位授权，未改 GM 原生桥、无痕 Cookie 隔离、HLS 外置音轨等既有边界。
- 本机没有 Android SDK/adb；完整 Gradle 编译、资源链接、lint、R8 与签名以 `v1.0.0-rc17` 的 CI 结果为准，交互仍需设备验收。
