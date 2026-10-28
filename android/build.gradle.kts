// Kotlin is built into the Android Gradle plugin, which brings Kotlin 2.2.10.
// This keeps the newer Kotlin the app is built with.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
