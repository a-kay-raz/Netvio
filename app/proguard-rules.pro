# Proguard / R8 rules for :app & :core modules
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable

# Native P2P / QuickJS engines
-keep class com.nuvio.engine.** { *; }
-keep class com.quickjs.** { *; }
-keep class app.cash.quickjs.** { *; }

# Kotlinx Serialization (@Serializable models across app & core)
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.euthopiar.** { *; }
-keep class com.euthopiar.**$Companion { *; }
-keep class com.euthopiar.**$$serializer { *; }

# Keep Room Entities & DAOs
-keep @androidx.room.Entity class com.euthopiar.** { *; }
-keep @androidx.room.Dao interface com.euthopiar.** { *; }

# Suppress benign OkHttp / Conscrypt /BouncyCastle / JSR-305 warnings
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
