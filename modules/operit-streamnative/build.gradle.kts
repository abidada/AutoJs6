@file:Suppress("SpellCheckingInspection")

/*
 * Operit streamnative module (native port, 2026-10-08).
 *
 * 1:1 port of upstream app/src/main/cpp (streamnative target). Provides libstreamnative.so —
 * the streaming markdown/XML/JSON splitter backing com.ai.assistance.operit.util.streamnative.*
 * Without it, chat markdown streaming and structured-content parsing throw UnsatisfiedLinkError.
 *
 * Differences from upstream (kept minimal):
 *  1. Split into its own module (upstream built it inside the app module's externalNativeBuild).
 *  2. ABI filters: 4 ABIs aligned with :modules:operit-quickjs / bibi (upstream shipped arm64-v8a
 *     only). The sources are pure C++17 with no third-party deps, so all 4 build natively.
 *  3. Only the streamnative target is built here; toolpkgwasm (WAMR) is a separate phase and
 *     sherpa-ncnn (STT) was trimmed per C15.
 */
plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
}

android {
    namespace = "com.ai.assistance.operit.streamnative"

    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        ndkVersion = "26.3.11579264"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Self-contained: only the C++ STL and liblog.
}
