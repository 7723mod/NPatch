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
-keep class org.lsposed.npatch.patch.NPatch { *; }
-keepclassmembers class org.lsposed.npatch.patch.NPatch {
    @com.beust.jcommander.Parameter <fields>;
}

-keepclassmembers class org.lsposed.npatch.database.dao.** { *; }
-keep class org.lsposed.npatch.database.entity.** { *; }
-keep class org.lsposed.npatch.manager.ConfigProvider { *; }
-keep class org.lsposed.npatch.Patcher$Options { *; }
-keep class org.lsposed.npatch.share.LSPConfig { *; }
-keep class org.lsposed.npatch.share.PatchConfig { *; }
-keep class org.lsposed.lspd.nativebridge.** { *; }
-keep class org.lsposed.npatch.loader.SigBypass { *; }
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.squareup.moshi.**
-dontwarn retrofit2.**
-dontwarn okio.**