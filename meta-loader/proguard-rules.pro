-keep class top.nkbe.npatch.metaloader.** {
    *;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class * extends androidx.room.Entity {
    <fields>;
}
-keep interface * extends androidx.room.Dao {
    <methods>;
}

-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
