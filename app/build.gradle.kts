import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sza.androidfolderexplorer"
    compileSdk = 35

    // Version auto-generated from build timestamp: Y.YMMD.Dhhm
    // Each letter = one digit. Example 2026-03-10 23:37 → "2.6031.0233"
    val buildTime: LocalDateTime = LocalDateTime.now()
    val yy  = buildTime.format(DateTimeFormatter.ofPattern("yy"))   // "26"
    val mon = buildTime.format(DateTimeFormatter.ofPattern("MM"))   // "03"
    val dd  = buildTime.format(DateTimeFormatter.ofPattern("dd"))   // "10"
    val hh  = buildTime.format(DateTimeFormatter.ofPattern("HH"))   // "23"
    val min = buildTime.format(DateTimeFormatter.ofPattern("mm"))   // "37"
    // Y  .  Y  M  M  D  .  D  h  h  m
    // y1 . y2 M  M  d1 . d2 H  H  m1
    val autoVersionName = "${yy[0]}.${yy[1]}${mon}${dd[0]}.${dd[1]}${hh}${min[0]}"
    // versionCode: grows every 6 minutes, fits in Int through year 2999
    val autoVersionCode = (yy.toLong() * 10_000_000L +
                           mon.toLong() * 100_000L +
                           dd.toLong()  * 1_000L +
                           hh.toLong()  * 10L +
                           min.toLong() / 6L).toInt()

    defaultConfig {
        applicationId = "com.sza.androidfolderexplorer"
        minSdk = 28
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = autoVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Native LZMA2 decoder used for multi-core 7z extraction (src/main/cpp).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.rootDir}/keys/release.keystore")
            storePassword = "REMOVED-PASSWORD"
            keyAlias = "AndroidFolderExplorer"
            keyPassword = "REMOVED-PASSWORD"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "AndroidFolderExplorer_${variant.versionName}-${variant.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/BCKEY.DSA", "META-INF/BCKEY.SF"
            )
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Activity
    implementation(libs.activity.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Core
    implementation(libs.core.ktx)

    // Shizuku — privileged shell access for Android/data on API 34+
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Archive extraction: zip/7z/tar via commons-compress; xz needed for LZMA2 (7z) and .tar.xz
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Media: video/audio player with background playback (Media3 / ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // Photos: EXIF read/strip/date edit
    implementation(libs.exifinterface)
    // Vault unlock
    implementation(libs.biometric)
    // Scheduled folder sync and auto-sort rules
    implementation(libs.work.runtime)
    // Fold posture (tabletop / book) and hinge position
    implementation(libs.window)
    // Password-protected zip create/extract and integrity test
    implementation(libs.zip4j)
    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")
    // Network storage: SFTP, FTP, SMB, WebDAV
    implementation(libs.jsch)
    implementation(libs.commons.net)
    implementation(libs.smbj)
    implementation(libs.okhttp)
    // LAN file server and its QR code
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
}
