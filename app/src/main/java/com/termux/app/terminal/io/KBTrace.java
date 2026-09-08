package com.termux.app.terminal.io;

import android.util.Log;

import com.termux.BuildConfig;

/**
 * Debug-build keyboard/IME lifecycle tracer (compiled out of release).
 * All keyboard decision points log one line to tag "KBTrace" so a full
 * resume/session-switch trace can be captured with:
 *   adb logcat -s KBTrace TIPanelCmd
 */
public final class KBTrace {

    private static final String TAG = "KBTrace";
    /** Compile-time constant: javac constant-folds this through call sites, so a
     * guarded `if (ENABLED) KBTrace.i("x" + y)` compiles to NOTHING in release —
     * including the string concatenation that would otherwise allocate. */
    public static final boolean ENABLED = BuildConfig.DEBUG;

    private KBTrace() {}

    public static void i(String msg) {
        if (ENABLED) Log.i(TAG, msg);
    }
}
