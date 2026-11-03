# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.ca1nm.droidfileexplorer.**$$serializer { *; }
-keepclassmembers class com.ca1nm.droidfileexplorer.** {
    *** Companion;
}
-keepclasseswithmembers class com.ca1nm.droidfileexplorer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Network / server libraries reference desktop-only classes
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.**
-dontwarn org.apache.logging.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.**
-dontwarn com.jcraft.jsch.**
-keep class com.jcraft.jsch.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**

# 7-Zip-JBinding (RAR and 7z): its native code finds these classes and their callback methods by name,
# so minification must not rename or strip them. Small library; the app's own RarSupport implements its callbacks.
-keep class net.sf.sevenzipjbinding.** { *; }
-dontwarn net.sf.sevenzipjbinding.**
