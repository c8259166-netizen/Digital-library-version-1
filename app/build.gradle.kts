plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.launchdeck.app"
    compileSdk = 34

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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.flyfishxu:kadb:2.1.5")
}
