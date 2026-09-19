// AGP 9 内置 Kotlin（不再 apply org.jetbrains.kotlin.android）；KGP 经 classpath 升到 2.4.20
// （高于内置默认 2.2.10），compose 编译器插件版本须与 KGP 一致（见 libs.versions.toml 的 kotlin）
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
}
