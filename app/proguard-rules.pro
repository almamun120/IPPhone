# Keep WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# Keep Protocol Messages
-keep class com.ipphone.network.protocol.** { *; }

# Keep Room entities
-keep class com.ipphone.data.local.database.entity.** { *; }

# Keep Hilt
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# Defensive: optional/unused transitive libraries must not fail the release build
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn java.lang.invoke.StringConcatFactory
