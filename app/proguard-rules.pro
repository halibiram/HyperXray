-keep class com.hyperxray.an.service.TProxyService {
    @kotlin.jvm.JvmStatic *;
}

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keep class com.hyperxray.an.telemetry.DeepPolicyModel { *; }
-keep class com.hyperxray.an.telemetry.Scaler { *; }