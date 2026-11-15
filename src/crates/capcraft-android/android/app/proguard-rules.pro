# UniFFI's Kotlin bindings reach the Rust library through JNA, which finds the bindings' native
# functions and structures by reflection: keep both.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class io.github.artcraftandroid.capcraft.core.** { *; }
# The JNI entry point the native library names: Java_io_github_artcraftandroid_capcraft_NativeContext_init
-keep class io.github.artcraftandroid.capcraft.NativeContext { native <methods>; }
-dontwarn java.awt.**
