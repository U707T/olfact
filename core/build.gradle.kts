plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // [B4] 备份格式、脚本索引、订阅清单都用 kotlinx.serialization
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "cn.olfact.core"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // 纯 JVM 单测里会加载到 android.jar 桩（Log / TextUtils 等），返回默认值而不是抛
        unitTests.isReturnDefaultValues = true
    }
}

// [B4] 基线缺这条：Java 17 + Kotlin 默认 21 会直接编译失败（app/web/player 同样需要）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- [B4] 本批新增依赖（B1 合并 core/build.gradle.kts 时请保留这一段）----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") // 订阅清单 / 脚本索引 / 备份 JSON
    implementation("com.squareup.okhttp3:okhttp:5.5.1")                      // 订阅下载 + WebDAV
    implementation("androidx.work:work-runtime-ktx:2.12.0")                  // AutoBackupWorker

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
