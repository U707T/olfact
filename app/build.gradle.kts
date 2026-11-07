import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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
        versionCode = 13
        versionName = "1.0.0-rc13"
        resourceConfigurations += listOf("zh")
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

    lint {
        // :app:lintDebug 默认会把 core/web/player/ai/download 也分析一遍（checkDependencies），
        // 冷缓存下 90 分钟都跑不完（CI 上实测直接超时）。关掉它：
        // 各模块用自己的 lintDebug 独立跑，CI 里按模块并行，单模块十几分钟就能出报告。
        checkDependencies = false
        // 只分析 debug 变体：release 的差异（R8/资源压缩）由 assembleRelease 覆盖，不必再分析一遍。
        checkReleaseBuilds = false
        // error 必须拦；warning 只存档。
        abortOnError = true
        warningsAsErrors = false
        // 测试源码不参与分析：单元测试/Instrumentation 各跑一遍分析，
        // 在冷缓存 runner 上等于把时长翻倍，收益极低（测试里的 lint 问题不影响发版）。
        ignoreTestSources = true
    }

    testOptions {
        // Robolectric 需要真实资源表（SettingItem 建视图时会取 dimen / drawable）
        unitTests.isIncludeAndroidResources = true
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.zxing.core)

    // 扫码：CameraX 负责取景与帧回调，解码复用上面的 zxing core（不再引第三方扫码库）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
