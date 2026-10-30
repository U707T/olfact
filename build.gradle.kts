// 根构建：只声明插件版本，不应用到根项目（各模块自行 apply）。
// 版本锁定见 docs/TECH-PLAN.md §2，B1 独占本文件。
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

// AGP 9 + 外部 Kotlin 插件时，Java 目标是 17、Kotlin 却跟工具链（JDK 21）走，
// 校验会直接报 "Inconsistent JVM Target Compatibility"。这里统一到 17。
subprojects {
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
}
