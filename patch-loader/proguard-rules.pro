-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-allowaccessmodification
-renamesourcefileattribute SourceFile

# Native code loads this entry by its original binary name and invokes onLoad().
-keep class top.nkbe.npatch.loader.LSPApplication {
    public static void onLoad();
}

# Gson serializes/deserializes these by field name across patcher, manager and loader.
-keep class top.nkbe.npatch.share.PatchConfig { *; }
-keep class top.nkbe.npatch.share.LSPConfig { *; }

# JNI registration uses literal bridge class names.
-keep class org.lsposed.lspd.nativebridge.** { *; }
-keep class org.matrix.vector.nativebridge.** { *; }

# Public Xposed/libxposed API names are part of the module ABI.
-keep class de.robv.android.xposed.** { *; }
-keep class android.app.AndroidAppHelper { *; }
-keep class android.content.res.** { *; }
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keep class org.lsposed.lspd.models.** { *; }
-keep class org.lsposed.lspd.service.** { *; }
-keep class org.matrix.vector.ipc.** { *; }
-keep class xposed.dummy.** { *; }

# Legacy resource initialization rewrites the classloader parent at runtime so
# XResources can resolve the generated xposed.dummy super classes.
-keep class org.matrix.vector.Startup { *; }
-keep class org.matrix.vector.legacy.** { *; }
-keep class org.matrix.vector.nativebridge.ResourcesHook { *; }

# Internal reflection points that still depend on stable names/members.
-keepclassmembers class org.matrix.vector.impl.core.VectorServiceClient {
    <fields>;
    <methods>;
}
-keep class org.matrix.vector.impl.core.VectorModuleManager$EmptyInjectedModuleService { *; }

-dontwarn android.content.res.Resources
-dontwarn android.content.res.Resources$Theme
-dontwarn android.content.res.AssetManager
-dontwarn android.content.res.TypedArray
-dontwarn android.app.**
-dontwarn android.content.**
-dontwarn android.os.**
-dontwarn android.view.**
-dontwarn com.android.internal.**
