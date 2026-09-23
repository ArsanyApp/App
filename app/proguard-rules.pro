# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.choice.autotap.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
