import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // @Serializable 在 core 内可用（备份 / 脚本索引 / 订阅清单），B4 直接写代码即可，勿改本文件
    id("org.jetbrains.kotlin.plugin.serialization")
    // Room 注解处理（bookmark / history / download 三张表）
    id("com.google.devtools.ksp")
}

android {
    namespace = "cn.olfact.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // 纯 JVM 单测会碰到 android.jar 桩（Log / TextUtils / Base64），返回默认值而不是抛
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api：接口契约里出现 Flow / Context，下游模块要能直接看到
    api("androidx.core:core-ktx:1.13.1")
    // api：core 提供 Theme.Olfact 与基础控件样式，app/appcompat 都必须能解析
    api("androidx.appcompat:appcompat:1.7.0")
    api("com.google.android.material:material:1.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    api("androidx.room:room-runtime:2.8.5")
    api("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    api("androidx.datastore:datastore-preferences:1.1.7")

    // ---- B4 报备的需求（订阅下载 / WebDAV / AutoBackupWorker），已由 B1 落在这里 ----
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // ---- 单测（B4 的 adblock/backup 用例） ----
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
