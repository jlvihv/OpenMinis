# Tink references annotations absent at runtime
-dontwarn com.google.errorprone.annotations.**

# JNI callback and entrypoints must retain their names in release builds.
-keep class com.openminis.app.tools.CodemodeNative { *; }
