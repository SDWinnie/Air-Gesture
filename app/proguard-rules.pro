# Preserve MediaPipe JNI & Protobuf model wrappers
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# Strip any debug/verbose Android Log calls if minification/shrinking is enabled
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
