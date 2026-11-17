plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gamesub.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gamesub.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ""
            isDebuggable = true
        }
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
    // AndroidX Core & TV Support
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.leanback:leanback:1.0.0")

    // Kotlin Coroutines for Non-Blocking OCR & Translation Pipeline
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // 100% Free On-Device Google ML Kit Text Recognition v2 (Offline OCR)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Embedded HTTP Server for Phone QR Remote Control Panel (NanoHTTPD)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Persistent Keep-Alive HTTP Client for Fast Translation (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR Code Generator on TV Screen (ZXing Core)
    implementation("com.google.zxing:core:3.5.3")
}
