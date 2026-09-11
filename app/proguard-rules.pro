# Rules for com.anthropic:anthropic-java, verified on device in issue #4.
# Inert until the SDK is added at build-order step 4; kept here so the rule set
# is not lost. Each was earned against a distinct failure -- none is guessable.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# The SDK's custom Jackson serializers are nested classes under core
# (e.g. JsonMissing$Serializer). Obfuscating them makes Jackson fall back to
# bean serialization, which fails on the JsonMissing sentinel.
-keep class com.anthropic.core.** { *; }

# Jackson reflectively instantiates serializers; obfuscation strips their
# no-arg constructors.
-keep class com.fasterxml.jackson.** { *; }

# The SDK bundles victools JSON-schema generation, which references
# java.lang.reflect.AnnotatedType -- an API Android does not have. R8 strips it
# as unreachable. Do NOT add a broad "-keep class * extends JsonSerializer"
# rule: it drags victools back in and breaks the build.
-dontwarn com.github.victools.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType

# Jackson's Java7SupportImpl references java.beans.*, absent on Android.
-dontwarn java.beans.**
