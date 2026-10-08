@file:Suppress("SpellCheckingInspection")

/*
 * Operit 1:1 port module (library-ized from upstream app/build.gradle.kts @ f1bbcd80).
 *
 * Version policy (SYNC.md §4): shared libs follow the host catalog (Ktor/Room/Compose-BOM/
 * okhttp/material/appcompat…); upstream-only libs keep upstream versions as direct
 * coordinates so upstream build-file diffs map 1:1 onto the groups below. Deviations from
 * upstream (kapt→KSP, no applicationId/buildTypes/signing/native/ffmpeg/STT-download) are
 * recorded in SYNC.md and are NOT re-synced from upstream.
 */
plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Bundled inside the root-classpath KGP jar (no standalone artifact for Kotlin 2.3).
    id("org.jetbrains.kotlin.plugin.parcelize")
    // Room on KSP; ObjectBox on AGP's legacy-kapt (ObjectBox has NO KSP processor —
    // github.com/objectbox/objectbox-java issue #1075; legacy-kapt is AGP 9's official
    // kapt path under built-in Kotlin). Upstream source annotations unchanged (C2 note).
    id("com.google.devtools.ksp")
    id("com.android.legacy-kapt") version "9.0.1"
    // C3: ObjectBox plugin applied in-module only (published on Maven Central).
    id("io.objectbox") version "5.3.0"
}

android {
    namespace = "com.ai.assistance.operit"
    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin

        // BuildConfig parity with the upstream app module (upstream files reference these).
        // APPLICATION_ID is the host applicationId at runtime; VERSION_* keeps the upstream
        // Operit identity (ToolPkg/A2A compat checks are versioned against Operit releases).
        buildConfigField("String", "APPLICATION_ID", "\"com.xiaoyu.ai\"")
        buildConfigField("String", "VERSION_NAME", "\"1.12.2\"")
        buildConfigField("int", "VERSION_CODE", "51")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        aidl = true // IAccessibilityProvider AIDL stubs (C16 interface layer)
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            // Mirrors upstream packaging excludes (poi/pdfbox/document libs).
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-EPL-1.0.txt"
            excludes += "LICENSE-EPL-1.0.txt"
            excludes += "/META-INF/LICENSE-EDL-1.0.txt"
            excludes += "LICENSE-EDL-1.0.txt"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/module-info.class"
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
    // —— Operit QuickJS sandbox engine (upstream :quickjs, vendored CMake) ——
    implementation(project(":modules:operit-quickjs"))

    // —— Operit terminal (upstream :terminal submodule, restored 2026-10-08) ——
    // Replaces the HOSTCOMPAT stub package (terminal/*) deleted in the same change.
    implementation(project(":modules:operit-terminal"))

    // —— Operit streamnative (native markdown/XML/JSON streaming splitter, 2026-10-08) ——
    // Provides libstreamnative.so for util.streamnative.* (chat markdown streaming + XML parsing).
    implementation(project(":modules:operit-streamnative"))

    // —— Operit native ripgrep (Rust search backend, 2026-10-08) ——
    // Provides liboperit_ripgrep.so for util.ripgrep.NativeRipgrep (file search tools).
    implementation(project(":modules:operit-ripgrep"))

    // —— Operit toolpkgwasm (WAMR WASM runtime, 2026-10-08) ——
    // Provides libtoolpkgwasm.so for util.ToolPkgWasmNative (ToolPkg WASM plugins).
    implementation(project(":modules:operit-toolpkgwasm"))

    // —— Host-catalog shared libs (versions pinned once, see gradle/libs.versions.toml) ——
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.webkit)
    implementation(libs.gson)
    implementation(libs.zip4j)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.apache.commons.compress)
    implementation(libs.commons.io)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.work.runtime)
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.preferences.core)
    implementation(libs.mcp.sdk.client)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)

    coreLibraryDesugaring(libs.desugar)

    // Compose (BOM unified with bibi per C4)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.activity.compose)
    implementation("androidx.compose.ui:ui-text-android:1.10.4")
    implementation("androidx.compose.runtime:runtime-android:1.10.4")
    implementation("androidx.compose.animation:animation-android:1.10.4")
    implementation("androidx.compose.ui:ui-android:1.10.4")
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3-window-size-class:1.2.0")
    implementation("androidx.window:window:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")

    // —— Upstream-only libs (upstream versions, direct coordinates) ——
    // agent memory (vector index)
    implementation("com.github.jelmerk:hnswlib-core:1.2.1")
    implementation("com.github.jelmerk:hnswlib-utils:1.2.1")
    // gltf avatar (Filament, maven — avatar gltf impl kept)
    implementation("com.google.android.filament:filament-android:1.69.2")
    implementation("com.google.android.filament:gltfio-android:1.69.2")
    implementation("com.google.android.filament:filament-utils-android:1.69.2")
    // OCR / barcode
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.0")
    implementation("com.google.zxing:core:3.5.3")
    // APK tooling (install/patch flows)
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    // com.android.tools.build:apksig REMOVED (D-9, 2026-10-08): the host :modules:apk-signer
    // already vendors the full com.android.apksig sources under src/main/java — pulling the
    // Maven artifact here made the same FQCNs land in two dex archives and broke
    // :app:mergeDexAppRelease with "ApkSigner$1 defined multiple times". The module only uses
    // com.android.apksig.ApkSigner (sign/Builder), satisfied by the vendored sources.
    implementation(project(":modules:apk-signer"))
    // net.dongliu:apk-parser REMOVED (P3, 2026-10-08): superseded by the host's local
    // :modules:apk-parser (same upstream lib, kept for the AutoJs6 packaging pipeline);
    // the module has zero `net.dongliu` imports and the duplicate jar only produced an
    // `r_styles.ini` JavaRes merge clash. SYNC.md §4.
    // com.github.Sable:axml downgraded to compileOnly (D-9, 2026-10-08): that jar embeds the
    // full pxb.android sources which the HOST app ALSO vendors under app/src/main/java/pxb/android
    // — as a runtime `implementation` the same FQCNs landed in two dex archives and broke
    // :app:mergeDexAppRelease with "pxb.android.ResConst defined multiple times". compileOnly
    // gives the module the pxb.android.axml.{Axml,AxmlReader,AxmlWriter,AxmlVisitor} symbols it
    // compiles against (ApkReverseEngineer) WITHOUT packaging a second copy; at runtime the app's
    // own vendored pxb classes (API-identical) satisfy the references.
    compileOnly("com.github.Sable:axml:2.0.0")
    implementation("com.github.iyxan23:zipalign-java:1.2.1")
    // root / shell
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")
    // media / documents / rendering
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.28")
    implementation("com.vanniktech:android-image-cropper:4.5.0")
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
    implementation("com.itextpdf:itextg:5.5.10")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("io.coil-kt:coil:2.5.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("ru.noties:jlatexmath-android:0.2.0")
    implementation("com.github.tech-pw:RenderX:1.0.0")
    // misc utils
    implementation("com.benasher44:uuid:0.8.2")
    implementation("org.hjson:hjson:3.0.0")
    // com.huaban:jieba-analysis REMOVED (D-11, 2026-10-08): the HOST already ships its own
    // Kotlin jieba under :modules:jieba-analysis (same package com.huaban.analysis.jieba, DB-backed
    // API). At runtime the host's classes won the classpath, so the Maven 1.0.2 API operit compiled
    // against (static WordDictionary.getInstance(), loadUserDict(Path)) threw
    // NoSuchMethodError. Depend on the host module instead and use its Context-based API.
    implementation(project(":modules:jieba-analysis"))
    implementation("org.tensorflow:tensorflow-lite:2.10.0")
    implementation("com.google.mediapipe:tasks-text:0.10.11")
    // com.microsoft.onnxruntime:onnxruntime-android REMOVED (P3, 2026-10-08): the host's
    // libs/rapidocr already vendors libonnxruntime.so (RapidAI build) and the module has
    // zero direct onnx imports (its upstream consumer, the Silero VAD, was trimmed C15).
    // The OpenSourceLicenses entry stays — it is display-only upstream data. SYNC.md §4.
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("com.joaomgcd:taskerpluginlibrary:0.4.10")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.32.0")
    implementation("androidx.glance:glance-appwidget:1.0.0")
    implementation("androidx.glance:glance-material3:1.0.0")
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("org.apache.poi:poi-scratchpad:5.2.3")
    implementation("com.github.skydoves:colorpicker-compose:1.0.6")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")
    implementation("me.saket.swipe:swipe:1.2.0")

    // Upstream keeps bcprov-jdk15to18 out (conflicts with bcprov-jdk18on above).
    // Port extension (P3): pdfbox-android also drags bcpkix/bcutil-jdk15to18 whose classes
    // collide with the jdk18on family on the host's single-APK runtime classpath —
    // exclude the whole jdk15to18 line (classic APIs stay satisfied by jdk18on 1.78).
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
    }

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
}
