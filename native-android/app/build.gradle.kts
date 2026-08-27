plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.infinitycomparison.cc"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.infinitycomparison.cc"
        minSdk = 23
        targetSdk = 35
        versionCode = 300000
        versionName = "3.0.0-native-alpha"
    }
}
