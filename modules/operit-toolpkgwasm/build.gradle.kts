@file:Suppress("SpellCheckingInspection")

/*
 * Operit toolpkgwasm module (native port, 2026-10-08).
 *
 * 1:1 port of upstream app/src/main/cpp (toolpkgwasm target). Provides libtoolpkgwasm.so — the
 * WAMR-backed WebAssembly runtime consumed by com.ai.assistance.operit.util.ToolPkgWasmNative
 * (ToolPkg WASM plugins). Without it, WASM-backed ToolPkgs throw UnsatisfiedLinkError.
 *
 * Deviation from upstream: WAMR is vendored under src/main/cpp/vendor/wamr (see VENDOR.md)
 * instead of git-fetched at configure time. ABI filters: 4 ABIs (upstream arm64 only).
 */
plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
}

android {
    namespace = "com.ai.assistance.operit.toolpkgwasm"

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
    // Self-contained: WAMR is vendored; only liblog/android/m/dl are linked.
}
