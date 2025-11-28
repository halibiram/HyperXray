# ============================================================================
# R8 Full Mode - Phase 5: Aggressive code shrinking with dynamic loading support
# ============================================================================

# Core service classes
-keep class com.hyperxray.an.service.TProxyService {
    @kotlin.jvm.JvmStatic *;
}

# ============================================================================
# Reflection: Keep classes accessed via Class.forName() and getMethod().invoke()
# ============================================================================

# ConfigFormatConverter uses Class.forName to load VlessLinkConverter dynamically
# CRITICAL: Keep VlessLinkConverter class, constructor, and all methods for reflection
# R8 Full Mode aggressively obfuscates, so we must explicitly preserve exact names and signatures

# STEP 1: Preserve class name for Class.forName("com.hyperxray.an.feature.vless.config.VlessLinkConverter")
# -keepnames ensures the class name string literal resolves correctly
-keepnames class com.hyperxray.an.feature.vless.config.VlessLinkConverter

# STEP 2: Keep the class itself (prevents removal) and preserve exact method signatures
# getMethod() requires exact method names and parameter types, so we must explicitly list them
-keep class com.hyperxray.an.feature.vless.config.VlessLinkConverter {
    # Preserve no-args constructor for getDeclaredConstructor().newInstance()
    <init>();
    
    # Preserve detect(String) method signature for getMethod("detect", String.class)
    # R8 Full Mode may rename methods even with <methods> wildcard, so explicit signature is required
    boolean detect(java.lang.String);
    
    # Preserve convert(Context, String) method signature for getMethod("convert", Context.class, String.class)
    # Return type must match exactly: Result<Pair<String, String>> (Kotlin compiles as kotlin.Result)
    # Note: Kotlin may mangle method names (e.g., convert-gIAlu-s), so we also keep by name pattern
    kotlin.Result convert(android.content.Context, java.lang.String);
    
    # Fallback: Keep all methods starting with "convert" (handles Kotlin name mangling)
    # The reflection code searches for methods starting with "convert" as fallback
    <methods>;
    
    # Keep all fields (may be needed for reflection)
    <fields>;
}

# STEP 3: Preserve method names (critical for getMethod() calls)
# getMethod() requires exact method names, so we must prevent R8 from renaming them
-keepclassmembernames class com.hyperxray.an.feature.vless.config.VlessLinkConverter {
    boolean detect(java.lang.String);
    kotlin.Result convert(android.content.Context, java.lang.String);
    <init>();
}

# STEP 4: Preserve Kotlin metadata for reflection compatibility
# Kotlin reflection and some JVM reflection features require metadata annotations
-keepclassmembers class com.hyperxray.an.feature.vless.config.VlessLinkConverter {
    @kotlin.Metadata <methods>;
}

# STEP 5: Keep the interface that VlessLinkConverter implements
# This ensures the interface methods are preserved and match the implementation
-keep interface com.hyperxray.an.feature.vless.config.ConfigFormatConverter {
    boolean detect(java.lang.String);
    kotlin.Result convert(android.content.Context, java.lang.String);
}

# STEP 6: Preserve DetectedConfig typealias (Pair<String, String>) for Result type
# The convert method returns Result<DetectedConfig> where DetectedConfig = Pair<String, String>
-keep class kotlin.Pair { *; }
-keep class kotlin.Result { *; }

# STEP 7: Keep the entire feature.vless.config package as fallback
# This ensures any related classes are also preserved
-keep class com.hyperxray.an.feature.vless.config.** { *; }
-keep interface com.hyperxray.an.feature.vless.config.** { *; }

# STEP 8: Preserve class names for Class.forName() string literals
# This ensures any Class.forName() calls in the feature module work correctly
-keepnames class com.hyperxray.an.feature.vless.** { *; }

# Keep ConfigFormatConverter in app module
-keep class com.hyperxray.an.common.configFormat.ConfigFormatConverter { *; }
-keep class com.hyperxray.an.common.configFormat.ConfigFormatConverter$* { *; }
-keep class com.hyperxray.an.common.configFormat.** { *; }

# Keep all classes that use Class.forName for dynamic loading
-keepclassmembers class * {
    static java.lang.Class forName(java.lang.String);
}

# AiOptimizerProfileManager uses reflection to access SystemProperties
-keep class com.hyperxray.an.telemetry.AiOptimizerProfileManager { *; }

# DeepPolicyModel uses reflection to call ONNX Runtime methods (addNnapi, addOrtOpenGL, addOrtOpenCL)
-keep class com.hyperxray.an.telemetry.DeepPolicyModel { *; }
-keepclassmembers class com.hyperxray.an.telemetry.DeepPolicyModel {
    *;
}

# Keep classes with @Keep annotation
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep classes that implement interfaces used via reflection
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================================
# JNI Bindings: Keep native methods and classes that use JNI
# ============================================================================

# Keep all classes with native methods (called from native code)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}


# HyperVpnService loads native library "hyperxray" and has native methods
# Go native library calls these JNI methods, so they must be kept
-keep class com.hyperxray.an.vpn.HyperVpnService {
    native <methods>;
    static <methods>;
    static void <clinit>();
}
-keep class com.hyperxray.an.vpn.HyperVpnHelper { *; }

# Keep native library loading methods
-keepclassmembers class * {
    static <fields>;
}

# ============================================================================
# Xray Core Runtime: Keep Xray runtime service classes
# ============================================================================

# Xray runtime service classes for stats and gRPC communication
-keep class com.hyperxray.an.xray.runtime.** { *; }
-keep interface com.hyperxray.an.xray.runtime.** { *; }
-keep class com.hyperxray.an.xray.runtime.stats.** { *; }
-keep class com.hyperxray.an.xray.runtime.stats.model.** { *; }

# gRPC generated classes for Xray stats API
-keep class com.xray.app.stats.command.** { *; }
-keep interface com.xray.app.stats.command.** { *; }

# ============================================================================
# ONNX Runtime: Keep ONNX Runtime classes for AI/ML inference
# ============================================================================

-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ONNX Runtime model classes
-keep class com.hyperxray.an.telemetry.DeepPolicyModel { *; }
-keep class com.hyperxray.an.telemetry.Scaler { *; }

# ============================================================================
# Conscrypt: Keep Conscrypt security provider classes
# ============================================================================

-keep class org.conscrypt.** { *; }
-keep interface org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Allow Conscrypt to access hidden APIs (for educational purposes)
# Note: This may not work on Android 10+ due to stricter restrictions
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclassmembers class java.net.InetAddress$InetAddressHolder {
    <methods>;
}

# ============================================================================
# Cronet: Keep Cronet classes for HTTP/3 networking
# ============================================================================

-keep class org.chromium.net.** { *; }
-keep interface org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

# ============================================================================
# TLS SNI Optimizer v5: Keep ML and runtime optimization classes
# ============================================================================

-keep class com.hyperxray.an.ml.TlsSniModel { *; }
-keep class com.hyperxray.an.runtime.FeedbackManager { *; }
-keep class com.hyperxray.an.runtime.BanditRouter { *; }
-keep class com.hyperxray.an.runtime.RealityAdvisor { *; }
-keep class com.hyperxray.an.workers.TlsRuntimeWorker { *; }

# ============================================================================
# R8 Full Mode Optimizations
# ============================================================================

# Allow aggressive optimizations: class merging, inlining, etc.
# These are enabled by default in R8 full mode (android.enableR8.fullMode=true)

# Keep entry points (Activities, Services, BroadcastReceivers, ContentProviders)
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

# Keep ViewModels and other Android architecture components
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModelStoreOwner { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

