# Wyltek Wallet ProGuard Rules

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.wyltek.wallet.core.model.**$$serializer { *; }
-keepclassmembers class com.wyltek.wallet.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.wyltek.wallet.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
