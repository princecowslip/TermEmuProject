# proguard-rules.pro
# R8 rules preserving JNI bridges, Oboe structures, and FFmpeg handles.

# ── JNI Bridge ──────────────────────────────────────────────────────────────
# Keep all native method declarations so R8 does not strip JNI signatures.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve the JniBridge class and all its members in full.
-keep class com.yourname.termemu.JniBridge { *; }

# ── Terminal Session / PTY ──────────────────────────────────────────────────
-keep class com.yourname.termemu.TerminalSession { *; }
-keepclassmembers class com.yourname.termemu.TerminalSession {
    public void onDataAvailable(byte[]);
    public void onSessionFinished(int);
}

# ── Audio Renderer ──────────────────────────────────────────────────────────
# Oboe callbacks are invoked from native threads; prevent stripping.
-keep class com.yourname.termemu.AudioRenderer { *; }
-keepclassmembers class com.yourname.termemu.AudioRenderer {
    public void onAudioData(byte[]);
    public void onAudioData(short[]);
}

# ── Media Overlay ───────────────────────────────────────────────────────────
-keep class com.yourname.termemu.MediaOverlayView { *; }

# ── FFmpeg / libswscale handles ─────────────────────────────────────────────
# These are accessed via reflection from the native layer.
-keepnames class com.yourname.termemu.** { *; }

# ── AndroidX / Kotlin runtime ───────────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# ── General safety net ──────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
