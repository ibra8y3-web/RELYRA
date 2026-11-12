import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.relyra.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.relyra.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.work)
    implementation(libs.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation("net.zetetic:sqlcipher-android:${libs.versions.sqlcipher.get()}@aar")

    implementation(libs.tink)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    implementation(libs.secp256k1)
    implementation(libs.secp256k1.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.orgjson)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    // Emit each test boundary into Actions logs so a stalled test is identifiable.
    testLogging {
        events("started", "passed", "failed", "skipped")
        showStandardStreams = true
    }
    maxParallelForks = 2
    forkEvery = 1
}
