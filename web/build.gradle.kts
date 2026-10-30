plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
}

// 真联网的 DoH 验证默认关闭：./gradlew :web:testDebugUnitTest -Polfact.liveDoh=1 --tests '*LiveDohTest*'
tasks.withType<Test>().configureEach {
    systemProperty("olfact.liveDoh", (project.findProperty("olfact.liveDoh") ?: "0").toString())
}
