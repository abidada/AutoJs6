# =============================================================================
# Operit terminal module consumer ProGuard rules (Q1 / D-9).
# Covers the JNI (pty) layer and the FileDescriptor reflection used to hand the
# pty master/slave fds across the native boundary.
# =============================================================================

# JNI entry points (Pty.kt loads libpty.so and declares native methods).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.ai.assistance.operit.terminal.Pty { *; }
-keep class com.ai.assistance.operit.terminal.** { *; }

# FileDescriptor.descriptor is a framework field read reflectively to recover the
# raw fd int; framework classes are not shrunk, but keep the member lookup stable.
-keepclassmembers class java.io.FileDescriptor {
    private int descriptor;
}

# kotlinx.serialization used by terminal session/config models.
-keep @kotlinx.serialization.Serializable class com.ai.assistance.operit.terminal.** { *; }
-keepclasseswithmembers class com.ai.assistance.operit.terminal.** {
    public static *** serializer(...);
    public static *** serializer();
}

# Manifest-declared terminal service + documents provider.
-keep class com.ai.assistance.operit.terminal.service.TerminalService { <init>(); }
-keep class com.ai.assistance.operit.terminal.provider.UbuntuDocumentsProvider { <init>(); }
