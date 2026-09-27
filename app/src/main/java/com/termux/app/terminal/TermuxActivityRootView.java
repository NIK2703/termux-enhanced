package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.termux.shared.view.DisplayCutoutUtils;

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

    /** Set while the terminal content, not just the window, may be laid out in the display cutout. */
    private boolean mExtendIntoDisplayCutout;

    @Nullable private WindowInsets mLastInsets;

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
     * Whether the terminal content may be laid out in the display cutout, as opposed to being held
     * clear of it by the platform padding.
     *
     * <p>Re-dispatches the last insets, since switching back off has to put that padding back and
     * the platform only recomputes it on a dispatch.
     */
    public void setExtendIntoDisplayCutout(boolean extend) {
        if (mExtendIntoDisplayCutout == extend) return;
        mExtendIntoDisplayCutout = extend;
        if (mLastInsets != null) dispatchApplyWindowInsets(mLastInsets);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mLastInsets = insets;
        // Let the platform apply its own system-bar padding (fitsSystemWindows) first, then top the
        // bottom up so the keyboard cannot cover the extra keys / terminal either.
        WindowInsets result = super.onApplyWindowInsets(insets);
        applyImeBottomPadding(insets);
        applyDisplayCutoutPadding(insets);
        return result;
    }

    /**
     * Take the display cutout back out of the padding while the content may use it.
     *
     * <p>{@code fitsSystemWindows} turns the system window insets into padding, and with the window
     * overlapping the cutout that padding covers the cutout too — so the terminal starts below the
     * notch and the strip the window gained sits there empty. Only the part the cutout adds on its
     * own is given back; see {@link DisplayCutoutUtils#paddingWithoutCutout}.
     *
     * <p>Safe on every dispatch: {@code View} applies the insets as an absolute padding rather than
     * accumulating them onto the user padding, so nothing carries over.
     */
    private void applyDisplayCutoutPadding(WindowInsets insets) {
        if (!mExtendIntoDisplayCutout || insets == null) return;

        WindowInsetsCompat compat = WindowInsetsCompat.toWindowInsetsCompat(insets);
        Insets cutout = compat.getInsets(WindowInsetsCompat.Type.displayCutout());
        if (cutout.left == 0 && cutout.top == 0 && cutout.right == 0 && cutout.bottom == 0) return;

        Insets bars = compat.getInsets(WindowInsetsCompat.Type.systemBars());
        setPadding(
                DisplayCutoutUtils.paddingWithoutCutout(cutout.left, bars.left, getPaddingLeft()),
                DisplayCutoutUtils.paddingWithoutCutout(cutout.top, bars.top, getPaddingTop()),
                DisplayCutoutUtils.paddingWithoutCutout(cutout.right, bars.right, getPaddingRight()),
                DisplayCutoutUtils.paddingWithoutCutout(cutout.bottom, bars.bottom, getPaddingBottom()));
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

        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), want);
    }

}
