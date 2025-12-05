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

-keep class org.lsposed.npatch.database.** { *; }
-keep class org.lsposed.npatch.Patcher$Options { *; }
-keep class org.lsposed.npatch.share.LSPConfig { *; }
-keep class org.lsposed.npatch.share.PatchConfig { *; }
-keepclassmembers class org.lsposed.patch.NPatch { private <fields>;}
-keep class com.beust.jcommander.** { *; }-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keep class retrofit2.** { *; }

-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.squareup.moshi.**
-dontwarn retrofit2.**
-dontwarn okio.**