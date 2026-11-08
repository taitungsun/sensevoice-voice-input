plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 建置目標 ABI 可用 -Pabi=x86_64 覆寫（模擬器測試用），預設為實機 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "app.dsh.asr"
    compileSdk = 36

    defaultConfig {
        // Fork: own package ID so it can be installed next to upstream app.dsh.asr.
        applicationId = "io.github.taitungsun.sensevoice"
        minSdk = 26
        // targetSdk 28 —— 與 dsh-android 保持一致（側載發布策略）
        targetSdk = 28
        versionCode = 17
        versionName = "0.5.2-tw.5"

        ndk {
            abiFilters += listOf(targetAbi)
        }
    }

    // Release signing: keystore and passwords come from Gradle properties or
    // environment variables (SV_KS_FILE, SV_KS_PASS, SV_KEY_ALIAS, SV_KEY_PASS).
    // Nothing secret is stored in the repository.
    fun secret(prop: String, env: String): String? =
        (project.findProperty(prop) as String?) ?: System.getenv(env)
    val ksFile = secret("ksFile", "SV_KS_FILE")
    signingConfigs {
        create("release") {
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = secret("ksPass", "SV_KS_PASS")
                keyAlias = secret("keyAlias", "SV_KEY_ALIAS") ?: "sensevoice"
                keyPassword = secret("keyPass", "SV_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }

    // sherpa-onnx 的 C / C++ API 函式庫沒有被用到（Kotlin 走 libsherpa-onnx-jni.so，
    // 它只依賴 libonnxruntime.so）。拿掉約 4.9 MB，讓 APK 低於 IzzyOnDroid 的 30 MB 上限。
    packaging {
        jniLibs {
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
