package com.termux.shared.interact;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

/**
 * Toast helpers that never leave text invisible.
 *
 * <p>A plain {@link Toast#makeText(Context, CharSequence, int)} is inflated with the *caller*
 * theme, so on the terminal — whose activity theme carries the Termux:Style colour-scheme
 * overlay — the toast ends up half-themed: the platform picks the background from one source
 * (system toast frame / {@code colorBackgroundFloating}) and the text colour from another
 * ({@code textColorPrimary} = scheme foreground). When the app theme and the terminal scheme
 * disagree, that is white text on a white popup.
 *
 * <p>{@link #styleToast(Toast)} therefore paints both halves itself: background = scheme
 * background, text = {@link ColorSchemeUtils#getSchemeForeground()} (which is guaranteed to
 * contrast with the scheme background).
 */
public final class ToastUtils {

    /** Corner radius of the toast background, in dp. */
    private static final int CORNER_RADIUS_DP = 18;

    /** Main-thread handler reused by {@link #showToast} so it can be called off the UI thread. */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private ToastUtils() {}

    /**
     * Show a toast with scheme-safe colours. May be called from any thread — the call is
     * posted to the main thread.
     *
     * @param context      The {@link Context} to show the toast with.
     * @param text         The text to show. Nothing is shown if {@code null} or empty.
     * @param longDuration Whether {@link Toast#LENGTH_LONG} should be used instead of
     *                     {@link Toast#LENGTH_SHORT}.
     */
    public static void showToast(@NonNull final Context context, final CharSequence text,
                                 final boolean longDuration) {
        if (text == null || text.length() == 0) return;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast toast = makeStyledToast(context, text, longDuration);
            if (toast != null) toast.show();
        } else {
            MAIN_HANDLER.post(() -> {
                Toast toast = makeStyledToast(context, text, longDuration);
                if (toast != null) toast.show();
            });
        }
    }

    /**
     * Build (but do not show) a toast with scheme-safe colours. Must be called on the main
     * thread when the caller wants to tweak the toast further (gravity, …) before showing it.
     *
     * @return The styled {@link Toast}, or {@code null} when there is nothing to show.
     */
    @Nullable
    public static Toast makeStyledToast(@NonNull final Context context, final CharSequence text,
                                        final boolean longDuration) {
        if (text == null || text.length() == 0) return null;

        Toast toast = Toast.makeText(context, text,
                longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        styleToast(toast);
        return toast;
    }

    /**
     * Paint an existing toast with the active terminal colour scheme so its text always
     * contrasts with its own background.
     */
    @SuppressWarnings("deprecation") // Toast.getView() is the only way to recolor a system toast
    public static void styleToast(@NonNull final Toast toast) {
        View view = toast.getView();
        if (view == null) return; // Nothing to recolor — leave the system toast alone.

        final Context context = view.getContext();

        // Force the background opaque: a translucent scheme background over the system toast
        // frame would again produce an arbitrary, unreadable mix.
        final int background =
                0xFF000000 | (TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] & 0x00FFFFFF);
        // getSchemeForeground() returns black on a light scheme and white on a dark one, so the
        // text can never match the background above.
        final int foreground = ColorSchemeUtils.getSchemeForeground();

        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(CORNER_RADIUS_DP * context.getResources().getDisplayMetrics().density);
        view.setBackground(drawable);

        TextView message = findMessageTextView(view);
        if (message != null) {
            message.setTextColor(foreground);
            // The platform toast draws a black text shadow; on light text it only muddies it.
            message.setShadowLayer(0, 0, 0, 0);
        }
    }

    /** The toast's text view: {@code @android:id/message}, or the first {@link TextView} found. */
    @Nullable
    private static TextView findMessageTextView(@NonNull View root) {
        View message = root.findViewById(android.R.id.message);
        if (message instanceof TextView) return (TextView) message;
        if (root instanceof TextView) return (TextView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findMessageTextView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
