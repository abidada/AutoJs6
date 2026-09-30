@file:Suppress("SpellCheckingInspection")

plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
    // Kotlin sub-plugins are resolved from the root buildscript classpath (see settings.gradle.kts),
    // so no plugin version is declared here (they must stay in lockstep with the Kotlin Gradle Plugin).
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.brycewg.asrkb"
    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin

        ndk {
            // All 4 ABIs shipped by decision D4 (sherpa-onnx AAR covers them all).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        // Free-tier builtin key is intentionally empty in the library build; users fill their own key in settings.
        buildConfigField(
            "String",
            "SF_FREE_API_KEY",
            "\"\""
        )

        // Only consumed by AnalyticsManager which is excluded from the library build in a later phase.
        resValue("string", "pocketbase_base_url", "")
    }

    buildTypes {
        release {
            // Minification is decided by the consuming application (AutoJs6 keeps it off).
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
        viewBinding = true
        buildConfig = true
        compose = true
        resValues = true
    }

    packaging {
        jniLibs {
            excludes += listOf(
                "**/libonnxruntime4j_jni.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so"
            )
        }
        resources {
            excludes += listOf(
                "META-INF/services/lombok.*",
                "README.md",
                "META-INF/README.md"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
                )
            }
        }
    }

    lint {
        targetSdk = versions.sdkVersionTarget
        abortOnError = false
    }
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // Aligned with the host's material version (1.13.0 per gradle/libs.versions.toml).
    // 1.14.0 pulls in Material3Expressive styles whose buttonGravity=center_vertical breaks
    // resource linking against the host's vendored appcompat-1.0.2 (flags lack center_vertical).
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("com.alibaba:dashscope-sdk-java:2.23.1")

    // 汉字→带调拼音（自定义唤醒词生成），LGPL
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")

    // Shizuku: shell-level privileged operations when authorized (keep-alive enhancements)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // sherpa-onnx Kotlin API AAR, published by :libs:sherpa-onnx-1_13_4
    // (direct local .aar file dependencies are not allowed in library modules)
    implementation(project(":libs:sherpa-onnx-1_13_4"))
}
