# ==========================================
# Consumer rules for :modules:bibi
# Applied to the consuming application (AutoJs6) when minification is enabled.
# zh-CN: 供宿主应用 (:app) 开启 minify 时使用.
# ==========================================

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Parcelable
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ==========================================
# kotlinx.serialization
# ==========================================

-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.brycewg.asrkb.**$$serializer { *; }
-keepclassmembers class com.brycewg.asrkb.** {
    *** Companion;
}
-keepclasseswithmembers class com.brycewg.asrkb.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Serialized data classes
-keep class com.brycewg.asrkb.store.PromptPreset { *; }
-keep class com.brycewg.asrkb.store.SpeechPreset { *; }
-keep class com.brycewg.asrkb.store.AsrHistoryStore$* { *; }

# ==========================================
# Libraries
# ==========================================

# Sherpa-ONNX JNI (accessed both directly and via reflection in SherpaReflectionSupport)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# DashScope SDK
-keep class com.alibaba.dashscope.** { *; }
-dontwarn com.alibaba.dashscope.**

# Lombok
-dontwarn lombok.**
-dontwarn org.projectlombok.**

# OkHttp & WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin coroutines / metadata
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.Metadata *;
}

# ==========================================
# Project entry points
# ==========================================

# Manifest components (services/activities/receivers) are kept automatically by AAPT rules,
# the explicit keeps below only document the reflective/reflection-sensitive entries.

# Shizuku: PrivilegedKeepAliveStarter calls Shizuku.newProcess via reflection
-keepclassmembers class rikka.shizuku.Shizuku {
    *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
