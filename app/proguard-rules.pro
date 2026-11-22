# Aggressive ProGuard & R8 Obfuscation Rules for Open FS

# 1. Repackage all classes into a single root obfuscated package
-repackageclasses ''
-allowaccessmodification

# 2. Obfuscate class, method, field, and variable names aggressively
-optimizationpasses 5
-overloadaggressively
-useuniqueclassmembernames

# 3. Strip all source file names, line numbers, and variable name debug attributes
-renamesourcefileattribute ""
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,!RuntimeVisibleAnnotations,!RuntimeInvisibleAnnotations

# 4. Strip Kotlin metadata annotations & Android Log calls from Release binary
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
}

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# 5. Keep only required Android entry points
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 6. Room database entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.Entity *;
}
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.Dao *;
}

-dontwarn **
