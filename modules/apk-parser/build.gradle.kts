plugins {
    id("org.autojs.build.versions")
    id("org.autojs.build.jvm-convention")
    id("com.android.library")
}

android {
    namespace = "net.dongliu.apk.parser"
    version = "6"
    compileSdk = versions.sdkVersionCompile

    defaultConfig {
        minSdk = versions.sdkVersionMin
        consumerProguardFiles("consumer-rules.pro")
        multiDexEnabled = true
    }

    lint {
        targetSdk = versions.sdkVersionTarget
    }
}

dependencies {
    // Operit port (P3, 2026-10-08): BouncyCastle unified onto the jdk18on family —
    // apk-parser/pdfbox-android/operit share one APK runtime classpath; the classic
    // API surface (X509CertificateHolder/CMSSignedData/BouncyCastleProvider) is kept
    // by 1.78. SYNC.md §4 deviation table.
    implementation(libs.bcprov.jdk18on)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.annotation)
}
