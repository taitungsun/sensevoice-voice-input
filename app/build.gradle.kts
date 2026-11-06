plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "app.dsh.asr"
    compileSdk = 36

    defaultConfig {
        // Fork: own package ID so it can be installed next to upstream app.dsh.asr.
        applicationId = "io.github.taitungsun.sensevoice"
        minSdk = 26
        // targetSdk 28 —— 与 dsh-android 保持一致（sideload 分发策略）
        targetSdk = 28
        versionCode = 13
        versionName = "0.5.2-tw.1"

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
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Shizuku（v0.4.0）：用于「一键设为系统默认识别服务」——
    // 普通应用没有 WRITE_SECURE_SETTINGS，但 Shizuku 能以 adb(uid 2000) 身份
    // 执行 `settings put secure voice_recognition_service ...`，从而自动完成设置。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
