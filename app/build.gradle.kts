import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名材料在仓库外（/workspace/olfact-keys），CI 通过环境变量注入；本地缺省则跳过签名。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "cn.olfact.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.olfact.browser"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0-rc2"
        resourceConfigurations += listOf("zh", "en")
        vectorDrawables.useSupportLibrary = false
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "**/*.proto",
            "DebugProbesKt.bin",
            "kotlin/**"
        )
    }

    // ABI split：等 B4 的 libadblock_ffi.so 进来后按架构拆包（release < 15MB 的关键之一）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Robolectric 依赖 x86_64 的 native 组件：开发用的 aarch64 沙箱加载不了（UnsatisfiedLinkError），
// 本地按架构跳过 UI 用例，CI（x86_64 runner）照常执行。
tasks.withType<Test>().configureEach {
    val arch = System.getProperty("os.arch").orEmpty()
    if (!arch.contains("x86_64") && !arch.contains("amd64")) {
        exclude("**/SettingItemClickTest*")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":web"))
    implementation(project(":player"))
    implementation(project(":download"))
    implementation(project(":ai"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
