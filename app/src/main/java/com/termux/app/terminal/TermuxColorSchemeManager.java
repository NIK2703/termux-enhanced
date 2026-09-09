package com.termux.app.terminal;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

/**
 * Caches all UI colours derived from the terminal colour scheme and panel transparency prefs.
 * <p>
 * Must be {@link #recompute(TermuxAppSharedPreferences)}d whenever the scheme or the
 * inactive/active-alpha sliders change so that every styled element uses fresh colours
 * without recomputing them on every draw / event.
 */
public final class TermuxColorSchemeManager {

    // --- Panel / button colours ---
    private int mButtonBg = 0;
    private int mButtonActiveBg = 0;
    private int mButtonText = 0;
    private int mTextSelectionHighlightColor = 0;
    private boolean mIsSchemeLight = false;

    // --- Raw scheme colours ---
    private int mSchemeBackground = 0;
    private int mSchemeForeground = 0;


    // --- Derived surfaces ---
    private int mHeaderBackground = 0;   // scheme bg + inactive overlay
    private int mDividerColor = 0;       // scheme fg @ ~20%
    private int mDialogBackground = 0;   // scheme bg (opaque)
    private int mDialogTextColor = 0;    // scheme fg

    // --- Context-popup colours ---
    private int mHistoryPopupBg = 0;
    private int mHistoryTextColor = 0;
    private int mHistoryPopupSepColor = 0;
    private int mHistoryHighlightFill = 0;

    /**
     * (Re)compute ALL cached colours from the scheme and panel alpha percentages.
     *
     * @param prefs The app preferences (used to read button alpha percentages).
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs) {
        recompute(prefs.getButtonBgInactiveAlpha(), prefs.getButtonBgActiveAlpha());
    }

    /** Convenience overload using the default button alpha percentages. */
    public void recompute() {
        recompute(5, 12);
    }

    /**
     * (Re)compute all cached colours from the scheme and explicit alpha percentages.
     *
     * @param inactivePct Inactive button background alpha (0–100).
     * @param activePct   Active button background alpha (0–100).
     */
    public void recompute(int inactivePct, int activePct) {
        mIsSchemeLight = ColorSchemeUtils.isTerminalSchemeLight();

        // Raw scheme colours.
        mSchemeBackground = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        mSchemeForeground = ColorSchemeUtils.getSchemeForeground();

        // Raw (translucent) panel button tints: dark for a light scheme, light for a dark one,
        // decided by the scheme background colour alone.
        int inactiveTint = ColorSchemeUtils.getButtonBackground(mIsSchemeLight, inactivePct);
        int activeTint = ColorSchemeUtils.getButtonActiveBackground(mIsSchemeLight, activePct);

        // Panel button colours: the translucent tints are kept as-is. The wallpaper is not hidden
        // by opaque panels — it is shown through the WHOLE window, because the decor view is
        // painted with the scheme background at the terminal's alpha (see
        // TermuxActivity.applySystemBarColors()). Panels stay transparent and therefore inherit
        // exactly the same "scheme bg at alpha A over the wallpaper" as the terminal itself,
        // so the entire window is uniformly translucent instead of patchy.
        mButtonBg = inactiveTint;
        mButtonActiveBg = activeTint;
        mButtonText = mSchemeForeground;
        // Text-selection highlight: scheme foreground tinted to ~15% alpha (scheme-consistent,
        // not a hardcoded black/white).
        mTextSelectionHighlightColor = withAlpha(mSchemeForeground, 38);

        // Derived surfaces.
        mHeaderBackground = compositeColors(mSchemeBackground, inactiveTint);
        mDividerColor = withAlpha(mSchemeForeground, 0x33);
        mDialogBackground = mSchemeBackground;
        mDialogTextColor = mSchemeForeground;

        // Context-popup colours
        mHistoryPopupBg = compositeColors(mSchemeBackground, inactiveTint);
        mHistoryTextColor = mButtonText;
        mHistoryPopupSepColor = withAlpha(mHistoryTextColor, 0x3C);
        // Highlight of the history popup item under the finger: scheme foreground @ ~15%.
        mHistoryHighlightFill = withAlpha(mHistoryTextColor, 0x26);
    }

    /** Apply {@code alpha} (0–255) to the RGB of {@code color}, keeping the scheme hue. */
    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // --- Getters ---

    /** @return Cached panel/button background colour. */
    public int getButtonBg() { return mButtonBg; }

    /** @return Cached panel/button active background colour. */
    public int getButtonActiveBg() { return mButtonActiveBg; }

    /** @return Cached panel/button text (scheme foreground) colour. */
    public int getButtonText() { return mButtonText; }

    /** @return Cached text selection highlight colour. */
    public int getTextSelectionHighlightColor() { return mTextSelectionHighlightColor; }

    /** @return Whether the current scheme is perceived as light. */
    public boolean isSchemeLight() { return mIsSchemeLight; }

    /** @return Cached raw scheme background colour. */
    public int getSchemeBackground() { return mSchemeBackground; }

    /** @return Cached raw scheme foreground colour. */
    public int getSchemeForeground() { return mSchemeForeground; }

    /** @return Cached header background (scheme bg + inactive-element overlay). */
    public int getHeaderBackground() { return mHeaderBackground; }

    /** @return Cached divider colour (scheme foreground @ ~20% alpha). */
    public int getDividerColor() { return mDividerColor; }

    /** @return Cached dialog background colour (opaque scheme background). */
    public int getDialogBackground() { return mDialogBackground; }

    /** @return Cached dialog text colour (scheme foreground). */
    public int getDialogTextColor() { return mDialogTextColor; }

    /** @return Cached context-popup background colour (scheme bg + inactive overlay). */
    public int getHistoryPopupBg() { return mHistoryPopupBg; }

    /** @return Cached history popup text colour. */
    public int getHistoryTextColor() { return mHistoryTextColor; }

    /** @return Cached history popup separator colour (foreground @ ~24% alpha). */
    public int getHistoryPopupSepColor() { return mHistoryPopupSepColor; }

    /** @return Cached highlight fill for the history popup item under the finger. */
    public int getHistoryHighlightFill() { return mHistoryHighlightFill; }

    // --- Static utility ---

    /**
     * Alpha-composite {@code overlay} (with alpha) on top of {@code background}
     * (assumed opaque) using standard over operator.
     *
     * @return Fully opaque ARGB colour.
     */
    public static int compositeColors(int background, int overlay) {
        int alpha = Color.alpha(overlay);
        if (alpha == 0) return background;
        if (alpha == 255) return overlay;
        int invAlpha = 255 - alpha;
        int r = (Color.red(background) * invAlpha + Color.red(overlay) * alpha) / 255;
        int g = (Color.green(background) * invAlpha + Color.green(overlay) * alpha) / 255;
        int b = (Color.blue(background) * invAlpha + Color.blue(overlay) * alpha) / 255;
        return Color.rgb(r, g, b);
    }
}
