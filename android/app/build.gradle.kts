import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ctsKeystorePath = providers.environmentVariable("CTS_ANDROID_KEYSTORE_PATH").orNull
val ctsStorePassword = providers.environmentVariable("CTS_ANDROID_KEYSTORE_PASSWORD").orNull
val ctsKeyAlias = providers.environmentVariable("CTS_ANDROID_KEY_ALIAS").orNull
val ctsKeyPassword = providers.environmentVariable("CTS_ANDROID_KEY_PASSWORD").orNull
val stableSigningReady = listOf(ctsKeystorePath, ctsStorePassword, ctsKeyAlias, ctsKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.retrofrost.cts.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.retrofrost.cts.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 200081
        versionName = "2.0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (stableSigningReady) {
            create("ctsStable") {
                storeFile = file(ctsKeystorePath!!)
                storePassword = ctsStorePassword
                keyAlias = ctsKeyAlias
                keyPassword = ctsKeyPassword
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".v208preview"
            versionNameSuffix = "-preview"
        }
        release {
            applicationIdSuffix = ".nativefork"
            isMinifyEnabled = false
            signingConfig = if (stableSigningReady) signingConfigs.getByName("ctsStable") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Stable Material 3 1.4.x: M3 components use the Material motion system
    // rather than the older component-local animation behaviour.
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    // These are true runtime smoke tests, not just JVM compilation checks.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}