@file:Suppress("SpellCheckingInspection")

/*
 * Operit QuickJS module (P0.2 skeleton, ported 1:1 from upstream Operit :quickjs).
 *
 * Differences from upstream (kept minimal, recorded here instead of a separate ledger):
 *  1. QuickJS C sources are vendored under src/main/cpp/vendor/quickjs (pin recorded in
 *     VENDOR.md) — upstream fetched them from bellard/quickjs@master at configure time via
 *     cmake/operit_git_source.cmake; vendoring removes the network dependency (plan C6).
 *  2. ABI filters: 4 ABIs aligned with :modules:bibi / the host (upstream shipped arm64-v8a only).
 */
plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
}

android {
    namespace = "com.ai.assistance.quickjs"
    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

        ndk {
            // Aligned with :modules:bibi (4 ABIs); QuickJS is pure C99 so all build natively.
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
    // Upstream declared kotlinx-serialization but the sources only use org.json (Android SDK);
    // kept off until P1 proves a real need, so the skeleton stays dependency-clean.
}
