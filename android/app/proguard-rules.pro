# Add project specific ProGuard rules here.
# Keep kotlinx.serialization generated serializers (not minifying for v1, but harmless to declare).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.dsh.android.**$$serializer { *; }
-keepclassmembers class com.dsh.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.dsh.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
