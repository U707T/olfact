import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cn.olfact.download"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        // WorkManager 的 SystemForegroundService 声明在 AAR 依赖里，库模块单独 lint 时
        // 看不到「依赖清单合并后」的结果，会误报缺 foregroundServiceType。
        // 实际覆盖写在 src/main/AndroidManifest.xml（tools:node="merge"），
        // 最终 app 的合并清单里已确认带 android:foregroundServiceType="dataSync"。
        disable += "SpecifyForegroundServiceType"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 队列
    implementation(libs.androidx.work.runtime.ktx)

    // TS → MP4 remux（不引 ffmpeg）
    implementation(libs.androidx.media3.muxer)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.common)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
