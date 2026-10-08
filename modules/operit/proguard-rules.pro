# =============================================================================
# Operit module consumer ProGuard rules (Q1 / D-9).
#
# Applied to the HOST app release build (consumer rules ship inside the AAR and
# are picked up by the app's R8 pass; the module's own isMinifyEnabled=false is a
# no-op for AAR). Cover every reflection / serialization / native-loading point
# that R8's static analysis cannot see.
# =============================================================================

# ---- 1. OperitLibrary reflective bootstrap (hostcompat) -----------------------
# OperitLibrary reflectively boots the upstream Application in library mode:
#   Class.forName("...core.application.OperitApplication")
#     .getMethod("getInstance")            -> companion @JvmStatic? no: companion
#     lateinit var instance -> outer static getter getInstance()
#     .getMethod("initializeMainApplication").invoke(instance)
#     .getDeclaredMethod("attachBaseContext", Context) on ContextWrapper
#     .getMethod("onCreate").invoke(application)
# Keep the application class, its companion holder and the invoked members.
-keep class com.ai.assistance.operit.core.application.OperitApplication {
    <init>();
    <methods>;
}
-keep class com.ai.assistance.operit.core.application.OperitApplication$Companion {
    <methods>;
}
# ContextWrapper.attachBaseContext is framework-side; kept implicitly, but guard
# the reflective lookup target so R8 does not strip the method from a shrunken
# classpath view.
-keepclassmembers class android.content.ContextWrapper {
    void attachBaseContext(android.content.Context);
}
-keep class com.ai.assistance.operit.hostcompat.OperitLibrary { *; }

# ---- 2. Material icon name resolver (reflection over compose icons) -----------
# MaterialIconNameResolver does
#   Class.forName("androidx.compose.material.icons.filled.${Name}Kt")
#     .getMethod("get${Name}", Icons.Default)
# Keep the generated *Kt accessor classes + their static getters for the filled
# icon set (the resolver only queries material.icons.filled).
-keep class androidx.compose.material.icons.filled.** {
    public static *** get*(androidx.compose.material.icons.Icons$Default);
}

# ---- 3. JS <-> Java bridge (dynamic class loading by arbitrary name) ----------
# JsJavaBridgeDelegates resolves class names supplied at runtime from JS, so the
# TARGET classes cannot be enumerated. Keep the bridge entry points themselves;
# callers must add their own -keep for any app class they expose to JS.
-keep class com.ai.assistance.operit.core.tools.javascript.** { *; }

# ---- 4. JLatexMath compatibility (reflects TeXParser.pos) ---------------------
# JLatexMathCompatibility reads/writes the private int field `pos` on TeXParser,
# and LatexCache reflects builder fields. Keep the jlatexmath model fields.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keepclasseswithmembers class ru.noties.jlatexmath.** { *; }

# ---- 5. FloatingWindowManager (reflects WindowManager.LayoutParams) -----------
# setPrivateFlag reads WindowManager.LayoutParams.privateFlags (framework field,
# present via getField). Framework classes are not shrunk, but keep the manager
# so its reflective call site is retained.
-keep class com.ai.assistance.operit.services.floating.FloatingWindowManager { *; }

# ---- 6. Room -------------------------------------------------------------------
-keep class androidx.room.RoomDatabase { *; }
-keepclasseswithmembers class * {
    @androidx.room.** <methods>;
}
-keep @androidx.room.Entity class * { *; }
-keep interface * extends androidx.room.Dao { *; }

# ---- 7. ObjectBox ----------------------------------------------------------------
-keep class io.objectbox.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
# Generated cursor/factory classes (MyObjectBox, *_ , *Cursor) referenced reflectively.
-keep class com.ai.assistance.operit.data.model.**_ { *; }
-keep class com.ai.assistance.operit.data.model.MyObjectBox { *; }
-keep class com.ai.assistance.operit.data.model.*Cursor { *; }

# ---- 8. kotlinx.serialization ---------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
# Keep all @Serializable classes and their serializers in the module.
-keepclasseswithmembers class com.ai.assistance.operit.** {
    @kotlinx.serialization.Serializable <init>(...);
}
-keep @kotlinx.serialization.Serializable class com.ai.assistance.operit.** { *; }
-keepclassmembers class com.ai.assistance.operit.** {
    public static *** serializer(...);
    public static *** serializer();
}
# Generic serializer() lookup + companion $serializer holders.
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class com.ai.assistance.operit.**$$serializer { *; }
-keepclassmembers class com.ai.assistance.operit.** {
    *** Companion;
}
-keepclasseswithmembers class com.ai.assistance.operit.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 9. ToolPkg plugin loading (dynamic feature/plugin classes) -----------------
-keep class com.ai.assistance.operit.plugins.** { *; }
-keep class com.ai.assistance.operit.core.tools.packTool.** { *; }

# ---- 10. Data model / Parcelable / gson-mapped POJOs ----------------------------
# @Parcelize + gson reflection over model classes.
-keep class com.ai.assistance.operit.data.model.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final *** CREATOR;
    public *** CREATOR;
    <init>(android.os.Parcel);
}
-keepattributes Signature, *Annotation*
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---- 11. AIDL / service / receiver / provider components -------------------------
# Manifest-declared components are instantiated by the framework by name.
-keep class com.ai.assistance.operit.**Service { *; }
-keep class * extends android.app.Service { <init>(); }
-keep class * extends android.content.BroadcastReceiver { <init>(); }
-keep class * extends android.content.ContentProvider { <init>(); }
-keep class * extends android.app.Activity { <init>(); }

# ---- 12. Native (JNI) in any embedded helper --------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---- 13. Keep names needed by kotlin-reflect over module metadata ------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
