# ProGuard rules for FireAirPlay
# Keep the RAOP server and audio classes (they use reflection-like patterns)
-keep class com.fireairplay.receiver.server.** { *; }
-keep class com.fireairplay.receiver.audio.** { *; }
-keep class com.fireairplay.receiver.model.** { *; }

# Keep the custom view (referenced in XML layout)
-keep class com.fireairplay.receiver.ui.AnimatedGradientView { *; }
