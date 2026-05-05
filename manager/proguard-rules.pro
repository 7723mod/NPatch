-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
 public static void check*(...);
 public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.beust.jcommander.** { *; }
-keep interface com.beust.jcommander.** { *; }
-keep class top.nkbe.npatch.patch.NPatch { *; }
-keepclassmembers class top.nkbe.npatch.patch.NPatch {
    @com.beust.jcommander.Parameter <fields>;
}

-keepclassmembers class top.nkbe.npatch.database.dao.** { *; }
-keep class top.nkbe.npatch.database.entity.** { *; }
-keep class top.nkbe.npatch.manager.ConfigProvider { *; }
-keep class top.nkbe.npatch.Patcher$Options { *; }
-keep class top.nkbe.npatch.share.LSPConfig { *; }
-keep class top.nkbe.npatch.share.PatchConfig { *; }
-keep class org.lsposed.lspd.nativebridge.** { *; }
-keep class top.nkbe.npatch.loader.SigBypass { *; }
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.squareup.moshi.**
-dontwarn retrofit2.**
-dontwarn okio.**