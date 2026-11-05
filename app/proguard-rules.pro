# ProGuard / R8 Rules for Medical Roster Extractor
# Private Hospital Pilot Release Configuration

# 1. Obfuscation Configuration
# Disable aggressive obfuscation to ensure seamless reflection, Room ORM mapping,
# JNI bindings for OpenCV native libraries, and ML Kit C++ runtime.
-dontobfuscate

# Preserve standard attributes for debugging, reflection, and annotations
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Preserve native method names and JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Room Database Rules
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# 3. OpenCV Native & Java Bindings
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# 4. Google ML Kit Text Recognition
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# 5. Application Models & Database Entities
-keep class com.example.rosterrebuild.data.** { *; }
-keep class com.example.rosterrebuild.domain.** { *; }
-keep class com.example.rosterrebuild.ocr.** { *; }
-keep class com.example.rosterrebuild.update.** { *; }

