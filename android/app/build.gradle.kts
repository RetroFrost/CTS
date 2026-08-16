import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
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
        versionCode = 20003
        versionName = "2.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (stableSigningReady) signingConfigs.getByName("ctsStable") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("Pillow==11.0.0")
            install("openpyxl==3.1.5")
        }
        pyc { src = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
