import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable 在 core 内可用（备份 / 脚本索引 / 订阅清单），B4 直接写代码即可，勿改本文件
    alias(libs.plugins.kotlin.serialization)
    // Room 注解处理（bookmark / history / download 三张表）
    alias(libs.plugins.ksp)
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
    api(libs.androidx.core.ktx)
    // api：core 提供 Theme.Olfact 与基础控件样式，app/appcompat 都必须能解析
    api(libs.androidx.appcompat)
    api(libs.androidx.material)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    api(libs.androidx.datastore.preferences)

    // ---- B4 报备的需求（订阅下载 / WebDAV / AutoBackupWorker），已由 B1 落在这里 ----
    // api：GmBridge.defaultClient() 等公开签名里出现 OkHttpClient，app 侧要能看见类型
    api(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    // ---- 单测（B4 的 adblock/backup 用例） ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
