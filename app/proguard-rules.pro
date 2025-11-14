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
-keep class com.hyperxray.an.feature.vless.config.VlessLinkConverter { *; }
-keep class com.hyperxray.an.common.configFormat.ConfigFormatConverter { *; }
-keep class com.hyperxray.an.common.configFormat.ConfigFormatConverter$* { *; }

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

# TProxyService loads native library "hev-socks5-tunnel" and has native methods
-keep class com.hyperxray.an.service.TProxyService {
    native <methods>;
    static <methods>;
}

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
