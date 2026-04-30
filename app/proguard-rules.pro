# LiteRT / TensorFlow Lite native interop
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fitform.app.**$$serializer { *; }
-keepclassmembers class com.fitform.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.fitform.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
