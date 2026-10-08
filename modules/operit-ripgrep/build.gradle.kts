@file:Suppress("SpellCheckingInspection")

/*
 * Operit native ripgrep module (2026-10-08).
 *
 * Packages liboperit_ripgrep.so — the Rust search backend consumed by
 * com.ai.assistance.operit.util.ripgrep.NativeRipgrep (file search tools).
 *
 * The .so is built OUTSIDE Gradle (Rust toolchain) and committed under src/main/jniLibs:
 * the upstream crate lives in ./rust (copied 1:1 from upstream tools/native_ripgrep) and is
 * built for the 4 ABIs by ./build_ripgrep.sh. Rebuild it whenever rust/ changes.
 */
plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
}

android {
    namespace = "com.ai.assistance.operit.ripgrep"

    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // jniLibs are the only payload; nothing else to compile.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Native-only module: no Kotlin/Java dependencies.
}
