import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin：不再 apply org.jetbrains.kotlin.android；Kotlin 版本由根项目 classpath 决定
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

// Rust 构建需要定位 SDK/NDK：优先 ANDROID_HOME 环境变量，其次 local.properties 的 sdk.dir
val sdkDir: String = System.getenv("ANDROID_HOME")
    ?: file("${rootDir}/local.properties").takeIf { it.exists() }?.let {
        Properties().apply { it.inputStream().use { stream -> load(stream) } }.getProperty("sdk.dir")
    }
    ?: error("未找到 Android SDK：请设置 ANDROID_HOME 或 local.properties 的 sdk.dir")
val ndkVersionUsed = "27.2.12479018"

android {
    namespace = "dev.wrtctrl"
    compileSdk = 37
    ndkVersion = ndkVersionUsed

    defaultConfig {
        applicationId = "dev.wrtctrl.app"
        minSdk = 29
        // Android 16 LNP：targetSdk ≥36 访问私网需本地网络权限；部分 ROM 强制但本地网络权限形态不同，不认
        // NEARBY_WIFI_DEVICES 授权，故钉在豁免边界 35。
        // 切回 37 的条件：ROM 提供本地网络开关或 Android 专属权限落地（切回前需逐项确认）
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Release 签名来自环境变量（CI 注入），仓库内不存密码；缺省回退 debug 签名
        val keystoreFile = file(System.getenv("SIGNING_KEYSTORE_FILE") ?: "${rootDir}/release.keystore")
        val storePass = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAliasName = System.getenv("SIGNING_KEY_ALIAS")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")
        if (keystoreFile.exists() && storePass != null && keyAliasName != null && keyPass != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
            // debug 也开 R8 裁剪：material-icons-extended 全量图标在 debug 下不裁剪会占
            // 40MB+ dex（APK 74MB→~25MB），高频装包体积优先于构建速度（+约1min）。
            // dontobfuscate 保留类名/行号——last_crash.txt 崩溃堆栈必须可读（排障依赖）。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-rules-debug.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // release 仅 arm64（x86_64 供模拟器调试，debug 双 ABI 都在 jniLibs）
            ndk {
                abiFilters += "arm64-v8a"
            }
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // R8 下 lint 无法解析 androidx 超类链导致的误报
        disable += "Instantiatable"
    }

    detekt {
        // 规则基线在仓库内 config/detekt/detekt.yml，放宽项均带理由注释
        buildUponDefaultConfig = true
        config.setFrom(files("${rootDir}/config/detekt/detekt.yml"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// 经 cargo-ndk 构建 Rust core 为 cdylib，产物输出到 jniLibs。
// 声明 inputs/outputs 让 Gradle 增量判断生效：crate 源未变时跳过 cargo-ndk 调用
// （此前每次 preBuild 都无条件 spawn）
tasks.register<Exec>("buildRustLibs") {
    workingDir = rootDir
    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME") ?: "${sdkDir}/ndk/${ndkVersionUsed}")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "${projectDir}/src/main/jniLibs",
        "build", "--release", "-p", "wrtctrl-jni",
    )
    inputs.dir("${rootDir}/crates")
    inputs.file("${rootDir}/Cargo.toml")
    if (file("${rootDir}/Cargo.lock").exists()) {
        inputs.file("${rootDir}/Cargo.lock")
    }
    outputs.dir("${projectDir}/src/main/jniLibs")
}

tasks.named("preBuild") { dependsOn("buildRustLibs") }

dependencies {
    implementation(libs.androidx.core.ktx)
    // AppCompatActivity 基类依赖（per-app language 切换的硬性要求）
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.vico.compose)
    implementation(libs.reorderable)
    implementation(libs.zxing.core)
    implementation(libs.material.kolor)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    // Windows 非 ASCII 路径下测试 worker 需按 UTF-8 读路径
    jvmArgs("-Dfile.encoding=UTF-8")
}
