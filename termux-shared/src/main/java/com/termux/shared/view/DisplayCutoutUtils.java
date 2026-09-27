package com.termux.shared.view;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

/**
 * Display cutout handling shared by the terminal and the settings window.
 *
 * <p>Two separate things are at stake, and they are not the same setting:
 * <ul>
 *     <li>whether the WINDOW may cover the cutout at all. It must: in the default cutout mode the
 *     window frame is kept out of the cutout in portrait, so that strip is outside the window and
 *     shows as black — and with a transparent status bar there is nothing behind it either.</li>
 *     <li>whether the CONTENT is laid out there too, or is held clear of it by the platform padding
 *     and leaves the background showing. That is the user's choice
 *     ({@code TermuxAppSharedPreferences#isExtendIntoCutout()}).</li>
 * </ul>
 */
public final class DisplayCutoutUtils {

    private DisplayCutoutUtils() {}

    /**
     * Let the window cover the display cutout, so the decor background paints that strip instead of
     * the system leaving a black gap there.
     *
     * <p>{@code ALWAYS} rather than {@code SHORT_EDGES}: in landscape the cutout is on a long edge
     * and SHORT_EDGES keeps the window out of it, which brings the gap back.
     *
     * <p>No-op below API 28, where cutouts do not exist, and on a null window.
     */
    public static void allowWindowIntoCutout(@Nullable Window window) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;

        final WindowManager.LayoutParams lp = window.getAttributes();
        if (lp.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
            return;   // setAttributes() relayouts the window
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(lp);
    }

    /**
     * The padding on one edge after giving back the part of the cutout that the system bars do not
     * already cover.
     *
     * <p>The padding {@code fitsSystemWindows} applies is the union of the bars and the cutout, so
     * the cutout only adds {@code max(0, cutout - bars)} to it. Subtracting the whole cutout instead
     * would zero the top padding in portrait, where the notch sits inside the status bar and the two
     * are the same height — the content would then start under the bar's clock and icons. With
     * fullscreen there is no status bar inset, so the whole cutout is given back, and in landscape
     * the cutout is on a long edge that no bar covers, so it is given back there too.
     */
    public static int paddingWithoutCutout(int cutout, int bars, int padding) {
        return Math.max(0, padding - Math.max(0, cutout - bars));
    }

    /**
     * Lets the content of a {@code fitsSystemWindows} view use the display cutout.
     *
     * <p>Install it with {@link View#setOnApplyWindowInsetsListener}, which replaces the view's own
     * insets policy — the listener runs that policy first, so the view still turns the insets into
     * padding, and the cutout is then taken back out of it. That is only safe because {@code View}
     * applies the insets as an absolute padding on every dispatch rather than accumulating them
     * onto the user padding, so nothing carries over.
     */
    public static class CutoutInsetsListener implements View.OnApplyWindowInsetsListener {

        private final View mView;
        private boolean mExtend;
        @Nullable private WindowInsets mLastInsets;

        public CutoutInsetsListener(@NonNull View view) {
            mView = view;
        }

        public void setExtend(boolean extend) {
            if (mExtend == extend) return;
            mExtend = extend;
            // Only a dispatch recomputes the padding, and none may arrive after a settings change.
            if (mLastInsets != null) mView.dispatchApplyWindowInsets(mLastInsets);
        }

        @Override
        public WindowInsets onApplyWindowInsets(@NonNull View v, @NonNull WindowInsets insets) {
            mLastInsets = insets;
            WindowInsets result = v.onApplyWindowInsets(insets);
            if (!mExtend) return result;

            WindowInsetsCompat compat = WindowInsetsCompat.toWindowInsetsCompat(insets);
            Insets cutout = compat.getInsets(WindowInsetsCompat.Type.displayCutout());
            if (cutout.left == 0 && cutout.top == 0 && cutout.right == 0 && cutout.bottom == 0) return result;

            Insets bars = compat.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    paddingWithoutCutout(cutout.left, bars.left, v.getPaddingLeft()),
                    paddingWithoutCutout(cutout.top, bars.top, v.getPaddingTop()),
                    paddingWithoutCutout(cutout.right, bars.right, v.getPaddingRight()),
                    paddingWithoutCutout(cutout.bottom, bars.bottom, v.getPaddingBottom()));
            return result;
        }
    }
}
