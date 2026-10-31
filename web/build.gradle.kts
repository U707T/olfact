plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "cn.olfact.web"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    buildFeatures { buildConfig = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint { abortOnError = false }
}
dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
}

// 真联网的 DoH 验证默认关闭：./gradlew :web:testDebugUnitTest -Polfact.liveDoh=1 --tests '*LiveDohTest*'
tasks.withType<Test>().configureEach {
    systemProperty("olfact.liveDoh", (project.findProperty("olfact.liveDoh") ?: "0").toString())
}
