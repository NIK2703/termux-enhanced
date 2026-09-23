package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;

import com.termux.shared.logger.Logger;

/**
 * The root view of the {@link TermuxActivity}.
 * <p>
 * The activity asks for {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_ADJUST_RESIZE}
 * (see {@link com.termux.shared.view.KeyboardUtils#setSoftInputModeAdjustResize}), so the window is
 * normally resized by the system when the soft keyboard opens and the extra keys / terminal are
 * pushed up on their own. {@code android:fitsSystemWindows="true"} additionally makes the platform
 * turn the system-bar insets into padding on this view.
 * <p>
 * That platform path is not always complete: a floating/undocked keyboard, a ROM that ignores
 * {@code ADJUST_RESIZE} or an IME that reports a smaller height than it actually occupies (Gboard's
 * candidates row) can leave the bottom of this view underneath the keyboard. Rather than measuring
 * the geometry by hand, this view tops its own bottom padding up to the IME inset the window
 * reports, which is the authoritative answer to "how much of me does the keyboard cover":
 *
 * <pre>
 *   want = max(systemBars.bottom, ime.bottom)
 *   if (paddingBottom &lt; want) paddingBottom = want
 * </pre>
 *
 * The rule is monotone — it only ever gives the content more room, never less — so it cannot fight
 * the platform resize and cannot oscillate: once the padding covers the keyboard the condition is
 * false and nothing further happens. When the platform already reserved the space, the IME inset is
 * 0 (or the padding already covers it) and the whole thing is a no-op, so the ordinary
 * {@code ADJUST_RESIZE} case behaves exactly like the platform default.
 */
public class TermuxActivityRootView extends LinearLayout {

    private boolean ROOT_VIEW_LOGGING_ENABLED = false;

    private static final String LOG_TAG = "TermuxActivityRootView";

    public TermuxActivityRootView(Context context) {
        super(context);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Sets whether root view logging is enabled or not.
     *
     * @param value whether logging is enabled.
     */
    public void setIsRootViewLoggingEnabled(boolean value) {
        ROOT_VIEW_LOGGING_ENABLED = value;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Let the platform apply its own system-bar padding (fitsSystemWindows) first, then top the
        // bottom up so the keyboard cannot cover the extra keys / terminal either.
        WindowInsets result = super.onApplyWindowInsets(insets);
        applyImeBottomPadding(insets);
        return result;
    }

    /** Top up {@code paddingBottom} to the IME inset; never shrinks — see class doc. */
    private void applyImeBottomPadding(WindowInsets insets) {
        if (insets == null) return;

        WindowInsetsCompat compat = WindowInsetsCompat.toWindowInsetsCompat(insets);

        int imeBottom = compat.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        if (imeBottom <= 0) return;

        int systemBarsBottom = compat.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
        int want = Math.max(imeBottom, systemBarsBottom);

        int current = getPaddingBottom();
        if (current >= want) return;

        if (ROOT_VIEW_LOGGING_ENABLED)
            Logger.logVerbose(LOG_TAG, "Raising bottom padding " + current + " -> " + want +
                " (ime " + imeBottom + ", systemBars " + systemBarsBottom + ")");

        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), want);
    }

}
