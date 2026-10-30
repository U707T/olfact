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
./gradlew :player:testDebugUnitTest --offline    # 15 个用例
```

## 已知边界

- 不支持后台播放（无 `media3-session`，方案已砍）。
- 亮度/音量/进度滑动手势按方案未实现。
- 外部播放器只在"内置播放失败"面板与长按菜单里出现，不抢默认。
