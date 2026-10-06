import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// APK and AAB always use the same version, including builds from Android Studio.
val releaseVersionCode = System.getenv("RELEASE_VERSION_CODE")?.let { value ->
    requireNotNull(value.toIntOrNull()?.takeIf { it in 9..2100000000 }) {
        "RELEASE_VERSION_CODE must be an integer from 9 to 2100000000 (the v1.0.6 APK uses 8)."
    }
} ?: 9
val releaseVersionName = System.getenv("RELEASE_VERSION_NAME") ?: "1.0.7"
val defaultRelayServerUrl = System.getenv("DEFAULT_RELAY_SERVER_URL") ?: "wss://relay.remote-desktop.co/ws"
val escapedDefaultRelayServerUrl = defaultRelayServerUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "co.remotedesktop"
    compileSdk = 36

    defaultConfig {
        applicationId = "co.remotedesktop"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        buildConfigField("String", "DEFAULT_RELAY_SERVER_URL", "\"$escapedDefaultRelayServerUrl\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE_FILE") ?: "release-keystore-not-configured.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Material 3 widgets (cards, switches, toolbar) for the main UI screens.
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR scanning for one-tap pairing with the desktop app.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
