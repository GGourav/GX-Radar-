# GX Radar ProGuard / R8 Rules

-keep class com.gxradar.GXRadarApplication { *; }
-keep class com.gxradar.ui.MainActivity { *; }
-keep class com.gxradar.network.** { *; }
-keep class com.gxradar.overlay.** { *; }
-keep class com.gxradar.data.model.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepattributes *Annotation*
-keepattributes Kotlin*
-keepattributes SourceFile,LineNumberTable
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class android.net.VpnService { *; }
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn org.jetbrains.annotations.**
