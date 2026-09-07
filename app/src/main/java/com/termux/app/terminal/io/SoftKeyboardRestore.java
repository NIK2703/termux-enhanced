package com.termux.app.terminal.io;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

/**
 * Reliable soft-keyboard show helper.
 *
 * showSoftInput() silently fails when the window is not focused yet, the target
 * view has no focus, or the IME service is still settling after a resume/recreate.
 * This helper retries with a short delay and STOPS as soon as the probe reports
 * the IME is actually visible (the activity's combined insets + visible-frame
 * detection), so it never hammers the IME longer than necessary.
 */
public final class SoftKeyboardRestore {

    /** Probe for the REAL current IME visibility (insets OR visible-frame). */
    public interface ImeVisibilityProbe {
        boolean isImeVisible();
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 120;

    private SoftKeyboardRestore() {}

    public static void showWithRetry(@NonNull View target, @NonNull ImeVisibilityProbe probe) {
        attempt(target, probe, 0, 0);
    }

    private static void attempt(@NonNull final View target,
                                @NonNull final ImeVisibilityProbe probe,
                                final int n, final int wait) {
        if (probe.isImeVisible()) return;                    // already there
        // Not attached OR zero-sized → the IME never serves such a view
        // ("Ignoring showSoftInput ... is not served"). Wait a frame, but boundedly.
        if (!target.isAttachedToWindow() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            if (wait < 10) target.post(() -> attempt(target, probe, n, wait + 1));
            return;
        }
        if (!target.hasFocus()) target.requestFocus();

        Context context = target.getContext();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        }

        if (n + 1 < MAX_ATTEMPTS) {
            target.postDelayed(() -> {
                if (!probe.isImeVisible()) attempt(target, probe, n + 1, 0);
            }, RETRY_DELAY_MS);
        }
    }
}
