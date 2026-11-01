package com.boxxy

import android.app.Application

class XemuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        /*
         * The emulator (libxemu.so, ~55 MB with hundreds of static
         * constructors) is only needed where games run.  Loading it in the
         * library process too cost every cold launch its mapping, relocation
         * and constructors before the first frame.
         */
        if (getProcessName().endsWith(":EmulationProcess")) {
            try {
                System.loadLibrary("slirp")
                System.loadLibrary("xemu")
                nativeLoaded = true
            } catch (e: Throwable) {
                android.util.Log.e("xemu-app", "Failed to load native libraries", e)
            }
        }
    }

    companion object {
        /** True in the emulation process, where NativeInterface may be called. */
        @Volatile
        var nativeLoaded = false
            private set
    }
}
