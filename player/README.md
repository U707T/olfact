# player · 内置播放（Media3 1.9.0，无 session）

只改本目录。对外只有一个入口：`PlaybackIntents.play(context, PlaybackSource, PlaybackOptions)`。

## 类地图

| 文件 | 作用 |
|---|---|
| `PlaybackSource` | 播放入参（url/kind/headers/UA/Referer/断点 key）。**不依赖 core/api 与 web** |
| `PlaybackOptions` | 起播位置、倍速、跳片头/片尾、循环、自动 PiP、是否记忆 |
| `MediaItemFactory` | MediaItem + `DefaultHttpDataSource.Factory`（UA / Referer / 自定义头 / 跨协议重定向） |
| `VideoPlayerActivity` | 播放页：ExoPlayer、错误分级提示、断点续播、横竖屏、PiP |
| `ui/PlayerControlsView` | 自绘控制条：播放/暂停、进度、倍速、跳片头片尾、锁屏、PiP |
| `gesture/PlayerGestureController` | 只有三个手势：双击快进/快退、长按 2x、单击显隐控制条 |
| `PipController` | API 31+ `setAutoEnterEnabled`；26~30 手动 enter；更低版本 no-op |
| `external/FallbackPlayer` | 24 个外部播放器包名 + 系统 chooser 兜底 |
| `PlayerPrefs` | 倍速与断点（SharedPreferences，不碰 core 的 DataStore） |
| `PlayerMath` | 纯计算（时间格式、seek 夹取、跳片头片尾判定），单测覆盖 |

## 与 core/api 的对接（等 B1 落地后由挂载方写，不改本模块）

```kotlin
fun MediaItem.toPlaybackSource() = PlaybackSource(
    url = url,
    kind = MediaKind.fromWire(kind),   // "hls"/"dash"/"progressive"
    title = title,
    pageUrl = pageUrl,
    pageTitle = pageTitle,
    mimeType = mimeType,
    headers = headers,
    userAgent = userAgent,
    referer = referer,
    live = isLive,
    isPrivate = isPrivate,
)
```

嗅探列表点击时：

```kotlin
PlaybackIntents.play(context, item.toPlaybackSource(), PlaybackOptions(startPositionMs = item.positionMs))
```

## 验证

```bash
./gradlew :player:assembleDebug --offline
./gradlew :player:testDebugUnitTest --offline
```

## 已知边界

- 不支持后台播放（无 `media3-session`，方案已砍）。
- 亮度/音量/进度滑动手势按方案未实现。
- 外部播放器只在"内置播放失败"面板与长按菜单里出现，不抢默认。
- 内置网络播放仍使用 Media3 的 HTTP 数据源；core 的可选应用 DNS 目前只接入 OkHttp 链路，
  不将其等同于播放器已经使用 DoH。

## 2026-11-21 审计与本地验证

仓库根目录运行 `./tools/test-download-player.ps1`；首次缺依赖时加 `-ResolveDependencies`。
工具目录可通过 `OLFACT_JAVA_HOME`、`OLFACT_KOTLIN_HOME`、`OLFACT_TEST_LIB_DIR` 指定，
环境与替身范围详见 [download 的轻量验证说明](../download/README.md#windows-本地轻量验证2026-11-21)。
本次与 download 联合 **124 例通过**；播放器部分验证类型识别、MIME 优先级、时间/定位计算、
倍速校验与内存会话状态，不加载 Activity、PlayerView 或原生 ExoPlayer。

本轮修复：后台释放前保存内存会话快照，私密播放、不记忆进度和显式起播位置都能在回前台时
恢复本次实际位置；保留暂停/缓冲时的播放意图与本次未持久化倍速。释放前解绑控制条与取消
待触发长按，非法启动参数提前退出时不再访问未初始化 View。相同 URL 的新请求仍可更新
请求头、私密标记与播放选项；HLS/DASH 类型不会被 CDN 错报的通用 MIME 覆盖。
“跳片尾”跳到结束位置，缓冲时的暂停按钮按播放意图切换。

完整 Android 编译/lint、Activity 生命周期、长按释放、无痕返回、PiP 进入/退出、失败重试、
受保护视频和 TS/fMP4 真实播放均待 CI/设备验证。本轮未宣称轻量回归等于上述平台验收。

### 本轮已读清单

基线 34 个跟踪文件全部逐文件阅读：13 个主 Kotlin、2 个测试、16 个资源、README、构建脚本
与 manifest；并阅读本轮新增文件。模块内没有未读自有文件；第三方代码与构建产物不计入。

- 根：`README.md`、`build.gradle.kts`、`src/main/AndroidManifest.xml`。
- 主包：`MediaItemFactory`、`MediaKind`、`PipController`、`PlaybackIntents`、`PlaybackOptions`、
  `PlaybackSource`、`PlayerMath`、`PlayerPrefs`、`VideoPlayerActivity`、新增 `PlaybackSession`；
  `external/ExternalPlayerTarget`、`external/FallbackPlayer`、`gesture/PlayerGestureController`、
  `ui/PlayerControlsView`（均为 `.kt`）。
- 测试：`MediaKindTest`、`PlayerMathTest`、新增 `PlaybackSessionTest`、`PlaybackSourceTest`。
- 布局：`activity_video_player.xml`、`view_player_controls.xml`。
- Drawable：`ic_player_back`、`ic_player_fullscreen`、`ic_player_lock`、`ic_player_pause`、
  `ic_player_pip`、`ic_player_play`、`ic_player_skip_next`、`ic_player_skip_previous`、
  `ic_player_unlock`、`player_scrim_bottom`、`player_scrim_top`（均为 `.xml`）。
- Values：`values/strings.xml`、`values-en/strings.xml`、`values/themes.xml`。
