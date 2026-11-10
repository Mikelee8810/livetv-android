# Live TV release rules.
# The app is small and self-contained: keep every app class so R8 cannot
# strip components the manifest, the TV input framework, or NanoHTTPD
# reach reflectively.
-keep class com.livetv.app.** { *; }
-keep class fi.iki.elonen.** { *; }

# Glide generated API and OkHttp platform plumbing.
-keep class com.bumptech.glide.integration.** { *; }
-dontwarn com.bumptech.glide.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# libVLC is called from native code via JNI.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**
