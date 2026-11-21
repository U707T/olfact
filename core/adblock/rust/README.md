# adblock-ffi（adblock-rust 的 JNI / C ABI 壳）

产物：`libadblock_ffi.so`（arm64-v8a / armeabi-v7a），由 CI 交叉编译，**仓库不提交二进制**。

## 为什么分 C ABI + JNI 两层

- C ABI（`olfact_adblock_*`）不依赖 JVM，命令行/其他宿主也能 `dlopen`，方便本地压测；
- JNI（`Java_cn_olfact_core_adblock_*`）只是把 C ABI 包给 Kotlin，逻辑只有一份。

## Kotlin ↔ Rust 契约（改一边必须改另一边）

| Kotlin（`NativeAdBlockEngine`） | Rust 导出 |
|---|---|
| `nativeCreate(rulesText, enginePath): Long` | `Java_cn_olfact_core_adblock_NativeAdBlockEngine_nativeCreate` |
| `nativeCheck(handle, url, sourceUrl, typeCode): Boolean` | `..._nativeCheck` |
| `nativeCosmetic(handle, pageUrl): String` | `..._nativeCosmetic` |
| `nativeDestroy(handle)` | `..._nativeDestroy` |
| `AdBlockNativeLib.nativeVersion()` | `Java_cn_olfact_core_adblock_AdBlockNativeLib_nativeVersion` |

`typeCode` 对应 `ResourceType.code`（document=0 … other=11），映射表在 `lib.rs::request_type_name`。

句柄是 `Box<Engine>` 的裸指针；`destroy` 回收。C ABI 调用者必须串行使用句柄并负责其生命周期，
不可用已销毁句柄。Kotlin JNI 包装层串行化检查、样式查询和关闭。同一安装中的每次构建按当前规则文本生成，
旧缓存路径参数只保留 ABI 兼容，不读取 `engine.dat`。空参数的默认返回值不保证兼容任意版本的 `.so`。

## 构建

```bash
# 需要当前 Rust stable（传递依赖可能使用 edition 2024）和 Android NDK
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked

cd core/adblock/rust
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../../../core/src/main/jniLibs build --release
```

手工命令将产物落在 `core/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libadblock_ffi.so`，AGP 会自动打进 APK。
项目 CI 的可选 `build-adblock-rust` job 单独上传 `.so` 制品，不自动把它们合并到其他 job 的 APK。

依赖约束：`adblock 0.9.8 → rmp-serde 0.15.5` 仍调用 `rmp::decode::read_data_*`；
`rmp 0.8.15` 删除了这些兼容函数，首次 CI 因此报 E0425。`Cargo.toml` 直接精确约束
`rmp = "=0.8.14"`，该版本保留这些函数且默认启用 `std`；无需改 JNI 或升级引擎。
官方发布源码见 [rmp 0.8.14](https://docs.rs/crate/rmp/0.8.14/source/src/decode/mod.rs)
和 [rmp 0.8.15](https://docs.rs/crate/rmp/0.8.15/source/src/decode/mod.rs)。

> 依赖图已从 CI 的实际解析产物保存为 `Cargo.lock`，后续 CI 使用 `--locked`。
> 第二轮编译同时纠正 `Engine` 的导入为公开入口 `adblock::Engine`，双 ABI 构建待重跑确认。
> 本机未运行 Rust/NDK；带 `.so` 的 APK 仍需验证切换订阅、三方请求及页面样式。

## 降级

`.so` 缺失 / `System.loadLibrary` 失败 / `nativeCreate` 返回 0 时，
`AdBlockEngineFactory` 自动落回 `KotlinAdBlockEngine`（规则上限 2 万条），
状态写进 `AdBlockManager.state.nativeError`，设置页显示"Kotlin 降级引擎"。

## 已知取舍

- `rule_count` 没有从 Rust 暴露：adblock-rust 不提供规则计数，改由 Kotlin 侧按规则文本行数估算。
- `generichide` 未区分：`url_cosmetic_resources` 的结果里拿不到"通用 vs 站点"的标记，
  统一返回后由 Kotlin 侧按 800 条上限截断。
