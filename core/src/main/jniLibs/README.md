# jniLibs

`libadblock_ffi.so` 由 CI 产出后放在这里（**不进 git**）：

```
core/src/main/jniLibs/
├── arm64-v8a/libadblock_ffi.so
└── armeabi-v7a/libadblock_ffi.so
```

本地没有 `.so` 时 App 照常启动，拦截自动降级为 `KotlinAdBlockEngine`（规则上限 2 万条）。
构建命令与契约见 `core/adblock/rust/README.md`，CI job 见 `core/adblock/ci-snippet.yml`。
