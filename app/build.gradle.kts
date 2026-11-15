import org.gradle.api.tasks.testing.Test
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.github.jk1.dependency-license-report") version "3.1.5"
}

val releaseStoreFile = providers.environmentVariable("RELYRA_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("RELYRA_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RELYRA_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RELYRA_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

dependencyLocking {
    lockAllConfigurations()
}

android {
    namespace = "org.relyra.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.relyra.app"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 10
        versionName = "0.9.0"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

// OpenMLS is compiled from native/mls and packaged as a real Android JNI library.
// The Variant API carries the generated task dependency into each Android variant.
abstract class BuildMlsNativeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val buildScript: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun buildNativeLibraries() {
        execOperations.exec {
            workingDir(project.rootDir)
            commandLine(
                "bash",
                buildScript.get().asFile.absolutePath,
                outputDirectory.get().asFile.absolutePath,
            )
        }
    }
}

val buildMlsNative = tasks.register<BuildMlsNativeTask>("buildMlsNative") {
    buildScript.set(rootProject.layout.projectDirectory.file("scripts/build_mls_android.sh"))
    outputDirectory.set(layout.buildDirectory.dir("generated/mls-jniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildMlsNative) { task ->
        task.outputDirectory
    }
}

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
    implementation("org.matrix.rustcomponents:sdk-android:26.11.05")

    // XMPP + XEP-0384 OMEMO (Signal provider is GPL-3.0; preserve its notices in distributions).
    implementation("org.igniterealtime.smack:smack-android:4.5.0")
    implementation("org.igniterealtime.smack:smack-tcp:4.5.0")
    implementation("org.igniterealtime.smack:smack-im:4.5.0")
    implementation("org.igniterealtime.smack:smack-extensions:4.5.0")
    implementation("org.igniterealtime.smack:smack-omemo:4.5.0")
    implementation("org.igniterealtime.smack:smack-omemo-signal:4.5.0")
    // Required directly because smack-omemo-signal exposes libsignal types in its public store API.
    implementation("org.whispersystems:signal-protocol-java:2.8.1")
    implementation(libs.secp256k1)
    implementation(libs.secp256k1.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.orgjson)
    testImplementation(libs.okhttp.mockwebserver)
}

// Smack 4.5 publishes both xpp3:xpp3_min and the CodeLibs-compatible
// org.codelibs:xpp3 runtime implementation. Both contain org.xmlpull classes;
// retain the CodeLibs implementation and exclude the duplicate legacy jar.
configurations.configureEach {
    exclude(group = "xpp3", module = "xpp3_min")
}

tasks.withType<Test>().configureEach {
    // Emit each test boundary into Actions logs so a stalled test is identifiable.
    testLogging {
        events("started", "passed", "failed", "skipped")
        showStandardStreams = true
    }
    maxParallelForks = 1
    forkEvery = 1
}
