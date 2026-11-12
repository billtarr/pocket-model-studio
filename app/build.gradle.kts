plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.pocketmodel.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pocketmodel.studio"
        minSdk = 26
        targetSdk = 35   // 35+ 才落到 untrusted_app 域：ROM 只给这个域放行 DSP（28→_27、34→_34 都被拦）
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        getByName("debug") {
            // Android 16 (API 36) 对签名方案更严格；debug 默认只签 v2，
            // 这里显式开 v1/v2/v3，避免某些 ROM 的安装校验（ColorOS 报 -99）
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // 关键：原生可执行文件必须以 .so 形式随 apk 解压到 nativeLibraryDir，
    // 否则 Android 10+ 的 W^X 策略不允许 exec（详见 docs/架构.md）。
    // 必须用 useLegacyPackaging（而不是 manifest 的 extractNativeLibs），两者混用会让
    // APK 结构不一致，安装时报 INSTALL_PARSE_FAILED_NOT_APK: Failed to load asset path。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // AGP 默认会 strip jniLibs 里的 .so。skel 被 strip 后大小不变但内容变了，
            // DSP 校验不过就报 4000 —— 而且从目录列表完全看不出来。只认 md5。
            keepDebugSymbols += "**/libQnn*.so"
            keepDebugSymbols += "**/liblayerrun.so"
            // llama-split 是「改名成 .so 的可执行文件」，strip 一遍可能改坏 PIE header；
            // DSP applet 被 strip 后大小不变但内容变了，DSP 校验不过（4000）。只认 md5。
            keepDebugSymbols += "**/libllama_split.so"
            keepDebugSymbols += "**/libggml-htp-*.so"
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
