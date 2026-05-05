-dontobfuscate
-keep class com.beust.jcommander.** { *; }
-keep class top.nkbe.npatch.Patcher$Options { *; }
-keep class top.nkbe.npatch.share.LSPConfig { *; }
-keep class top.nkbe.npatch.share.PatchConfig { *; }
-keep class org.lsposed.lspd.nativebridge.** { *; }
-keep class top.nkbe.npatch.loader.SigBypass { *; }
-keepclassmembers class org.lsposed.patch.NPatch {
    private <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
