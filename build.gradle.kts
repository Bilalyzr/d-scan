// AGP 9 compiles Kotlin through its built-in Kotlin support, so org.jetbrains.kotlin.android is never applied.
// AGP only guarantees its own minimum Kotlin Gradle plugin; this pins the version the whole project compiles with.
// Keep it in lockstep with the Compose compiler plugin (libs.plugins.kotlin.compose).
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
