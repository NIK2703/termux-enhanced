package com.termux.app.terminal.io;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

/**
 * Reliable soft-keyboard show helper: showSoftInput() silently fails when the window is not
 * focused yet, the target view has no focus, or the IME service is still settling after a
 * resume/recreate. Retries with a short delay and STOPS as soon as the probe reports the IME is
 * actually visible, so it never hammers the IME longer than necessary.
 *
 * <p>Single funnel for every <em>automatic</em> IME show in the app (user-driven paths — a tap on
 * the terminal, the KEYBOARD toggle — call {@code KeyboardUtils.showSoftKeyboard} directly),
 * which is why {@link #logCaller} can identify the caller: the call sites are otherwise
 * indistinguishable in the {@code InputMethodManager} log line.
 */
public final class SoftKeyboardRestore {

    private static final String LOG_TAG = "SoftKeyboardRestore";

    /** Probe for the REAL current IME visibility (insets OR visible-frame). */
    public interface ImeVisibilityProbe {
        boolean isImeVisible();
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 120;

    private SoftKeyboardRestore() {}

    /**
     * Report which call site is about to request the IME — only the first frame outside this
     * class, so the line stays short.
     */
    private static void logCaller() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        for (StackTraceElement frame : frames) {
            if (!SoftKeyboardRestore.class.getName().equals(frame.getClassName())) {
                Logger.logInfo(LOG_TAG, "showSoftInput requested by " + frame.getClassName() + "."
                        + frame.getMethodName() + ":" + frame.getLineNumber());
                return;
            }
        }
    }

    public static void showWithRetry(@NonNull View target, @NonNull ImeVisibilityProbe probe) {
        showWithRetry(target, probe, null);
    }

    /**
     * @param onSettled optional one-shot callback, run exactly once when the helper stops
     *                  trying (IME up, attempts exhausted, or view never servable). Callers
     *                  release their "restore in flight" latch here instead of a blind
     *                  4 × 120 ms timer — while it is up the IME-visibility handler ignores
     *                  the user's own keyboard intent, so a long latch swallows a real keypress.
     */
    public static void showWithRetry(@NonNull View target,
                                     @NonNull ImeVisibilityProbe probe,
                                     @Nullable Runnable onSettled) {
        attempt(target, probe, 0, 0, new Once(onSettled));
    }

    private static void attempt(@NonNull final View target,
                                @NonNull final ImeVisibilityProbe probe,
                                final int n, final int wait,
                                @NonNull final Once settled) {
        if (probe.isImeVisible()) { settled.run(); return; }     // already there
        // Not attached OR zero-sized → the IME never serves such a view
        // ("Ignoring showSoftInput ... is not served"). Wait a frame, but boundedly.
        if (!target.isAttachedToWindow() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            if (wait < 10) target.post(() -> attempt(target, probe, n, wait + 1, settled));
            else settled.run();          // gave up on ever being servable — report it
            return;
        }
        if (!target.hasFocus()) target.requestFocus();

        logCaller();
        Context context = target.getContext();
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        }

        // Some IMEs take the request synchronously; settle right away instead of waiting a whole
        // retry interval for the probe to notice.
        if (probe.isImeVisible()) { settled.run(); return; }

        if (n + 1 < MAX_ATTEMPTS) {
            target.postDelayed(() -> {
                if (!probe.isImeVisible()) attempt(target, probe, n + 1, 0, settled);
                else settled.run();
            }, RETRY_DELAY_MS);
        } else {
            settled.run();
        }
    }

    /** Runs the wrapped action at most once. Every callback here arrives on the main thread. */
    private static final class Once implements Runnable {
        @Nullable private Runnable mDelegate;

        Once(@Nullable Runnable delegate) {
            mDelegate = delegate;
        }

        @Override
        public void run() {
            Runnable delegate = mDelegate;
            if (delegate == null) return;
            mDelegate = null;
            delegate.run();
        }
    }
}
