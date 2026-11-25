import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.letdown2491.noscat"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.letdown2491.noscat"
        minSdk = 26
        targetSdk = 35
        // Lockstep with noscat: versionName is the noscat release, and
        // versionCode goes up with every release.
        versionCode = 5
        versionName = "0.5.0"
    }

    // Release signing: keystore.properties at the repo root (never
    // committed) names the keystore and its passwords:
    //   storeFile=/home/you/.android/noscat-release.jks
    //   storePassword=...
    //   keyAlias=noscat
    //   keyPassword=...
    // Without it, assembleRelease builds an unsigned APK.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            // No R8: JNA (the uniffi bindings) finds its classes by
            // reflection, and the app is small enough without shrinking.
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // uniffi-generated Kotlin bindings are JNA-based; the @aar classifier is
    // the Android artifact (classes + native .so libs for jna).
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // QR generation for pairing (scanning is handled separately).
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR: core encodes the npub to a bitmap; embedded provides the scan activity.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0@aar")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
