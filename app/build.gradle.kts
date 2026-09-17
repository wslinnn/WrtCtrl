import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    compileSdk = 35
    ndkVersion = ndkVersionUsed

    defaultConfig {
        applicationId = "dev.wrtctrl.app"
        minSdk = 29
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
}

// 经 cargo-ndk 构建 Rust core 为 cdylib，产物输出到 jniLibs
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Windows 非 ASCII 路径下测试 worker 需按 UTF-8 读路径
    jvmArgs("-Dfile.encoding=UTF-8")
}
