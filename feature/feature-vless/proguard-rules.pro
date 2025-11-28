# ============================================================================
# ProGuard Rules for feature-vless module
# ============================================================================
# This module contains VlessLinkConverter which is loaded via reflection
# from the app module using Class.forName()

# Keep VlessLinkConverter and all its members for reflection access
-keep class com.hyperxray.an.feature.vless.config.VlessLinkConverter { *; }
-keepclassmembers class com.hyperxray.an.feature.vless.config.VlessLinkConverter {
    <init>();
    <methods>;
    <fields>;
}

# Keep the ConfigFormatConverter interface
-keep interface com.hyperxray.an.feature.vless.config.ConfigFormatConverter { *; }

# Keep all classes in the config package
-keep class com.hyperxray.an.feature.vless.config.** { *; }

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }











