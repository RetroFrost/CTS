import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

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

fun gitBlobSha1(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${payload.size}\u0000".toByteArray(Charsets.US_ASCII))
    digest.update(payload)
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

val watchDataFonts = mapOf(
    "Poppins-ExtraBold.ttf" to Pair(
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-ExtraBold.ttf",
        "167667d203d98f5b27c3ff58d486eea9c5287fe4",
    ),
    "Poppins-SemiBold.ttf" to Pair(
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-SemiBold.ttf",
        "c30ad104723a0e6e00e54768626cb02c5fdf6aee",
    ),
    "Poppins-Medium.ttf" to Pair(
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Medium.ttf",
        "a590f5c3e4902a7cb10f4bbc5da0e65e667f7950",
    ),
)

val downloadWatchDataFonts = tasks.register("downloadWatchDataFonts") {
    group = "build setup"
    description = "Fetch and verify the official Poppins files used by the WatchData reference renderer"
    doLast {
        val fontDir = layout.projectDirectory.dir("src/main/python/ccengine/fonts").asFile
        fontDir.mkdirs()
        val license = fontDir.resolve("OFL.txt")
        check(license.isFile) { "Poppins OFL license notice is missing." }
        watchDataFonts.forEach { (filename, source) ->
            val destination = fontDir.resolve(filename)
            val expected = source.second
            if (destination.isFile && gitBlobSha1(destination.readBytes()) == expected) {
                return@forEach
            }
            val payload = URI(source.first).toURL().openStream().use { it.readBytes() }
            val actual = gitBlobSha1(payload)
            check(actual == expected) {
                "Official Poppins font verification failed for $filename: $actual"
            }
            destination.writeBytes(payload)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadWatchDataFonts)
}

android {
    namespace = "io.github.retrofrost.cts.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.retrofrost.cts.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 20004
        versionName = "2.0.4"
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
