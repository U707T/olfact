// 根构建：只声明插件版本，不应用到根项目（各模块自行 apply）。
// 版本锁定见 docs/TECH-PLAN.md §2，B1 独占本文件。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
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
