-keep class com.hyperxray.an.service.TProxyService {
    @kotlin.jvm.JvmStatic *;
}

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keep class com.hyperxray.an.telemetry.DeepPolicyModel { *; }
-keep class com.hyperxray.an.telemetry.Scaler { *; }

# TLS SNI Optimizer v5
-keep class com.hyperxray.an.ml.TlsSniModel { *; }
-keep class com.hyperxray.an.runtime.FeedbackManager { *; }
-keep class com.hyperxray.an.runtime.BanditRouter { *; }
-keep class com.hyperxray.an.runtime.RealityAdvisor { *; }
-keep class com.hyperxray.an.workers.TlsRuntimeWorker { *; }