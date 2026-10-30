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

句柄是 `Box<Engine>` 的裸指针；`destroy` 回收。所有函数对空指针返回安全默认值（false / 0 / 空串），
所以即使 `.so` 版本不匹配，也只会"拦截失效"，不会把浏览器带崩。

## 构建

```bash
# 需要 rust stable（≥1.82，adblock 的传递依赖已用 edition 2024）+ Android NDK
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked

cd core/adblock/rust
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../../../core/src/main/jniLibs build --release
```

产物落在 `core/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libadblock_ffi.so`，AGP 会自动打进 APK。

> `Cargo.lock` 故意不入库：沙箱里的 cargo 1.75 解析不了 edition 2024 的传递依赖，
> 生成的锁文件是半成品。CI 首次构建通过后，把 CI 产出的 `Cargo.lock` 提交进来即可锁定版本。

> 沙箱内只有 cargo 1.75 且无 Android target，**本地编不出来**（与 TECH-PLAN §5 的结论一致），
> 只能走 CI。见 `../ci-snippet.yml`。

## 降级

`.so` 缺失 / `System.loadLibrary` 失败 / `nativeCreate` 返回 0 时，
`AdBlockEngineFactory` 自动落回 `KotlinAdBlockEngine`（规则上限 2 万条），
状态写进 `AdBlockManager.state.nativeError`，设置页显示"Kotlin 降级引擎"。

## 已知取舍

- `rule_count` 没有从 Rust 暴露：adblock-rust 不提供规则计数，改由 Kotlin 侧按规则文本行数估算。
- `generichide` 未区分：`url_cosmetic_resources` 的结果里拿不到"通用 vs 站点"的标记，
  统一返回后由 Kotlin 侧按 800 条上限截断。
