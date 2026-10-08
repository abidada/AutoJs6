@file:Suppress("SpellCheckingInspection")

/*
 * OperitTerminalCore 1:1 port (upstream submodule :terminal @ 04586898).
 *
 * Replaces the HOSTCOMPAT stubs that lived under modules-operit-terminal-stub
 * (trimmed in P1.3, restored 2026-10-08). Build-file deviations from upstream:
 * plugin aliases -> host plugin ids, catalog aliases -> host catalog/direct
 * coordinates (same policy as modules/operit). Sources under src/ are untouched.
 * NOTE: never write a slash-star sequence inside this block comment — Kotlin
 * block comments NEST and would silently comment out the whole script.
 */
plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Bundled inside the root-classpath KGP jar (same as modules/operit).
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.ai.assistance.operit.terminal"
    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Upstream ships arm64-v8a only (proot/bash/busybox prebuilts).
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    defaultConfig {
        // Consumer rules ship in the AAR and apply to the HOST app's R8 pass (Q1 / D-9).
        consumerProguardFiles("proguard-rules.pro")
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            useLegacyPackaging = true
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
    // —— Host-catalog shared libs ——
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation("androidx.compose.animation:animation-core")
    implementation(libs.navigation.compose)
    implementation("androidx.compose.ui:ui-android:1.10.4")
    implementation("androidx.compose.ui:ui-graphics-android:1.10.4")
    implementation("androidx.compose.ui:ui-text-android:1.10.4")
    implementation("androidx.compose.runtime:runtime-android:1.10.4")
    implementation("androidx.compose.animation:animation-android:1.10.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // SSH (upstream coordinates)
    implementation("com.jcraft:jsch:0.1.55")

    // FTP server
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.ftpserver:ftplet-api:1.2.0")

    // SSHD server
    implementation("org.apache.sshd:sshd-core:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    // BouncyCastle for SSHD on Android (avoids JMX issues); unified with host jdk18on 1.78.
    implementation(libs.bcprov.jdk18on)
}
