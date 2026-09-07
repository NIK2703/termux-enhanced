package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Debug-build-only command entry point (source set src/debug). Exists so adb can inject
 * commands even when MIUI blocks background broadcast receivers:
 *   adb shell am start -n com.termux.debug/com.termux.app.TermuxDebugActivity --es cmd status
 * Runs the command via {@link TermuxDebugCommandReceiver#execute}, reports the result in
 * logcat (tag TIPanelCmd), then finishes without ever becoming visible to the user.
 */
public class TermuxDebugActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TermuxDebugCommandReceiver.execute(TermuxActivity.getInstance(), getIntent());
        finish();
    }

    /**
     * Entry point used by test scripts so multi-word commands can carry an argument:
     *   adb shell am start -n com.termux.debug/com.termux.app.TermuxDebugActivity \
     *       --es cmd "ti text" --es arg "hello world"
     */
    public static void start(android.content.Context context, String cmd, String arg) {
        Intent i = new Intent(context, TermuxDebugActivity.class)
                .putExtra("cmd", cmd)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (arg != null) i.putExtra("arg", arg);
        context.startActivity(i);
    }
}
