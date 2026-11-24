
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.launchdeck.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.launchdeck.app"
        minSdk = 29
        targetSdk = 32
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
