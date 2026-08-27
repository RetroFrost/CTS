plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.thedataguys.cc"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.thedataguys.cc"
        minSdk = 23
        targetSdk = 35
        versionCode = 300003
        versionName = "3.0.0-native-alpha.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
