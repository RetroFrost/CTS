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
    "Poppins-Bold.ttf" to Pair(
        "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-Bold.ttf",
        "1982f38ab21303459aa1155265052ca599fa58d1",
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
    // Keep the 2.0.7 Kotlin source namespace during the staged migration so
    // the full studio remains buildable. The install package is already the
    // final Data Guys package and the source namespace will move once the
    // Python renderer bridge is completely gone.
    namespace = "io.github.retrofrost.cts.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.thedataguys.cc"
        minSdk = 26
        targetSdk = 36
        versionCode = 300001
        versionName = "3.0.0-native-alpha.1"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
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
}

// Temporary during the staged 2.0.7 -> native migration. The rewrite branch
// is removing Python feature-by-feature while preserving the complete studio.
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
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
