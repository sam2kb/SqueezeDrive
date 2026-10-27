# SqueezeDrive ProGuard rules.
#
# The Lyrion protocol layer uses kotlinx.serialization with @Serializable classes;
# keep their generated serializers and the models they reference.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class de.maniac103.squeezeclient.model.** {
    *** Companion;
}
-keepclasseswithmembers class de.maniac103.squeezeclient.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
