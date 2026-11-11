buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 built-in Kotlin: use the same Kotlin/KSP toolchain selected in the version catalog.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.13")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
