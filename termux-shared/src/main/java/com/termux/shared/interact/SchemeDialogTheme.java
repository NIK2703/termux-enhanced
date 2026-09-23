package com.termux.shared.interact;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;

import com.termux.shared.R;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

/**
 * Applies the active Termux:Style colour scheme to an activity's whole UI from the first frame:
 * layers {@link R.style.ThemeOverlay_BaseActivity_Scheme} (placeholder {@code @color/scheme_dialog_*})
 * over the base context and resolves those placeholders to the live scheme at runtime — see
 * {@link #wrapActivityTheme(Context)}.
 */
public final class SchemeDialogTheme {

    private SchemeDialogTheme() {}

    /**
     * Wrap an activity context so its whole UI (toolbar, preferences, switches, context-menu
     * popups) is inflated in the active Termux:Style scheme from the first frame. The activity's
     * own theme is kept; the scheme overlay is applied on top.
     */
    @NonNull
    public static Context wrapActivityTheme(@NonNull Context context) {
        int fg = ColorSchemeUtils.getSchemeForeground();
        int bg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        return new SchemeActivityContext(context, R.style.ThemeOverlay_BaseActivity_Scheme, fg, bg);
    }

    /** Keeps the base theme, applies the scheme overlay once, and resolves
     *  {@code @color/scheme_dialog_*} to the live scheme via {@link #getResources()}. */
    private static final class SchemeActivityContext extends ContextThemeWrapper {

        private final int mStyleRes;
        private final int mFg;
        private final int mBg;
        private final int mDivider;
        private Resources mSchemeResources;
        private boolean mOverlayApplied;

        SchemeActivityContext(@NonNull Context base, int styleRes, int fg, int bg) {
            super(base, 0);
            mStyleRes = styleRes;
            mFg = fg;
            mBg = bg;
            mDivider = blend(fg, bg, 0.3f);
        }

        @Override
        public Theme getTheme() {
            Theme theme = super.getTheme();
            if (!mOverlayApplied) {
                theme.applyStyle(mStyleRes, true);
                mOverlayApplied = true;
            }
            return theme;
        }

        @Override
        public Resources getResources() {
            if (mSchemeResources == null) {
                mSchemeResources = new SchemeResources(super.getResources(), mFg, mBg, mDivider);
            }
            return mSchemeResources;
        }
    }

    /**
     * {@link Resources} wrapper that resolves the {@code scheme_dialog_*} colours to the active
     * scheme. Everything else is delegated to the base resources.
     */
    private static final class SchemeResources extends Resources {

        private final Resources mBase;
        private final int mFg;
        private final int mBg;
        private final int mDivider;
        private final int mButtonBg;
        private final int mButtonActiveBg;
        private final int mSelector;
        private final int mCodeBlock;

        SchemeResources(@NonNull Resources base, int fg, int bg, int divider) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            mBase = base;
            mFg = fg;
            mBg = bg;
            mDivider = divider;
            // Translucent button backgrounds over the scheme background (5% / 12% alpha),
            // matching ColorSchemeUtils.getButtonBackground / getButtonActiveBackground.
            mButtonBg = blendWithAlpha(bg, fg, 0x0D);
            mButtonActiveBg = blendWithAlpha(bg, fg, 0x1F);
            // Pressed-row selector: scheme foreground at ~25% alpha.
            mSelector = blendWithAlpha(fg, 0, 0x40);
            // Markdown code-block background: scheme foreground at ~8% alpha.
            mCodeBlock = blendWithAlpha(bg, fg, 0x14);
        }

        /** Composite {@code overlay} (argb with alpha) over {@code base} (opaque). */
        private static int blendWithAlpha(int base, int overlay, int alpha) {
            int inv = 0xFF - alpha;
            int r = (android.graphics.Color.red(base) * inv + android.graphics.Color.red(overlay) * alpha) / 0xFF;
            int g = (android.graphics.Color.green(base) * inv + android.graphics.Color.green(overlay) * alpha) / 0xFF;
            int b = (android.graphics.Color.blue(base) * inv + android.graphics.Color.blue(overlay) * alpha) / 0xFF;
            return android.graphics.Color.rgb(r, g, b);
        }

        @Override
        public int getColor(int id, @NonNull Theme theme) throws NotFoundException {
            int resolved = mapSchemeColor(id);
            return resolved != 0 ? resolved : mBase.getColor(id, theme);
        }

        @Override
        public int getColor(int id) throws NotFoundException {
            int resolved = mapSchemeColor(id);
            return resolved != 0 ? resolved : mBase.getColor(id);
        }

        @Override
        @NonNull
        public ColorStateList getColorStateList(int id) throws NotFoundException {
            int resolved = mapSchemeColor(id);
            if (resolved != 0) {
                return ColorStateList.valueOf(resolved);
            }
            return mBase.getColorStateList(id);
        }

        /**
         * Theme-attribute resolution ({@code ?attr/colorSurface} → {@code @color/scheme_dialog_surface})
         * goes through here, not {@link #getColor(int)}; returning the live scheme ARGB is what lets
         * the dialog be correct on the first frame with no repaint.
         */
        @Override
        public void getValue(int id, @NonNull TypedValue outValue, boolean resolveRefs) throws NotFoundException {
            int resolved = mapSchemeColor(id);
            if (resolved != 0) {
                outValue.type = TypedValue.TYPE_INT_COLOR_ARGB8;
                outValue.data = resolved;
                outValue.resourceId = id;
                outValue.changingConfigurations = 0;
                outValue.assetCookie = 0;
                outValue.string = null;
                outValue.density = 0;
                return;
            }
            mBase.getValue(id, outValue, resolveRefs);
        }

        /** @return The scheme ARGB for a {@code scheme_dialog_*} resource id, or 0 if not one. */
        private int mapSchemeColor(int id) {
            if (id == R.color.scheme_dialog_background
                    || id == R.color.scheme_dialog_surface) {
                return mBg;
            }
            if (id == R.color.scheme_dialog_foreground
                    || id == R.color.scheme_dialog_on_surface) {
                return mFg;
            }
            if (id == R.color.scheme_dialog_divider) {
                return mDivider;
            }
            if (id == R.color.scheme_dialog_button_background) {
                return mButtonBg;
            }
            if (id == R.color.scheme_dialog_button_active_background) {
                return mButtonActiveBg;
            }
            if (id == R.color.scheme_dialog_selector) {
                return mSelector;
            }
            if (id == R.color.scheme_dialog_code_block) {
                return mCodeBlock;
            }
            return 0;
        }
    }

        /** Linearly blend {@code from} -> {@code to} by {@code ratio} (0 = from, 1 = to), opaque. */
        private static int blend(int from, int to, float ratio) {
            int r = Math.round(android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * ratio);
            int g = Math.round(android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * ratio);
            int b = Math.round(android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * ratio);
            return android.graphics.Color.rgb(r, g, b);
        }

    /**
     * Apply the scheme background + title + navigation-icon colour to the activity's toolbar,
     * overriding the theme's red {@code colorPrimary}; make the status bar transparent with icon
     * tint following the scheme foreground.
     */
    public static void applyToToolbar(@NonNull android.app.Activity activity) {
        int fg = ColorSchemeUtils.getSchemeForeground();
        int bg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];

        android.view.View toolbar = activity.findViewById(com.termux.shared.R.id.toolbar);
        if (toolbar instanceof androidx.appcompat.widget.Toolbar) {
            androidx.appcompat.widget.Toolbar abToolbar = (androidx.appcompat.widget.Toolbar) toolbar;
            abToolbar.setBackgroundColor(bg);
            abToolbar.setTitleTextColor(fg);
            android.graphics.drawable.Drawable navIcon = abToolbar.getNavigationIcon();
            if (navIcon != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTintList(
                    navIcon, android.content.res.ColorStateList.valueOf(fg));
            }
        } else if (toolbar != null) {
            toolbar.setBackgroundColor(bg);
            android.widget.TextView title = toolbar.findViewById(androidx.appcompat.R.id.title);
            if (title != null) title.setTextColor(fg);
        }

        boolean isBgLight = com.termux.shared.termux.extrakeys.ColorSchemeUtils.isColorLight(bg);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.view.Window window = activity.getWindow();
            if (window != null) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                // Born scheme-coloured: the window background is the scheme background from the
                // first frame, so the markdown/editor content never flashes the default theme bg.
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                // The status bar is transparent, so its text/icons are drawn over the toolbar's
                // scheme background. On a light scheme the default white icons would be invisible,
                // so force dark status-bar text/icons (API 23+).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    int visibility = window.getDecorView().getSystemUiVisibility();
                    if (isBgLight)
                        visibility |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    else
                        visibility &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    window.getDecorView().setSystemUiVisibility(visibility);
                }
            }
        }
    }
}
