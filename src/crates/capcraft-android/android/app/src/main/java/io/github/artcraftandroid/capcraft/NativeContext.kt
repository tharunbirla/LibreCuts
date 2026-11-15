package io.github.artcraftandroid.capcraft

import android.content.Context

/**
 * Hands the Java VM and the app's context to the engine (its sound device opens through them). The
 * bindings load the library through JNA, which skips `JNI_OnLoad`; loading it here once with
 * `System.loadLibrary` runs it.
 */
object NativeContext {
    @JvmStatic
    private external fun init(context: Context)

    fun start(context: Context) {
        System.loadLibrary("capcraft_android")
        init(context.applicationContext)
    }
}
