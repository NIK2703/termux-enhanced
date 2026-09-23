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

    /**
     * Opacity a control drawn on the terminal is painted at, after its tint has been mixed into the
     * terminal background. The tint is mixed in at DOUBLE the slider strength precisely because this
     * halves it again, so the strength the eye ends up seeing is the configured one.
     */
    private static final int FLOATING_CONTROL_OPACITY = 128; // 50%

    // --- Panel / button colours ---
    private int mButtonBg = 0;
    private int mButtonActiveBg = 0;
    private int mButtonText = 0;
    private int mTextSelectionHighlightColor = 0;
    private boolean mIsSchemeLight = false;

    // --- Controls drawn on the terminal: input-panel toggle button + scrollbar thumb ---
    private int mFloatingButtonFill = 0;
    private int mFloatingButtonStroke = 0;
    /** Background the two colours above were last mixed from. */
    private int mFloatingBackground = 0;
    /** Alpha percentages {@link #recompute} last ran with, reused when only the background changes. */
    private int mFloatingInactivePct = 0;
    private int mFloatingActivePct = 0;

    // --- Raw scheme colours ---
    private int mSchemeBackground = 0;
    private int mSchemeForeground = 0;

    // --- Derived surfaces ---
    private int mDividerColor = 0;       // scheme fg @ ~20%

    // --- Context-popup colours ---
    private int mHistoryPopupBg = 0;
    private int mHistoryTextColor = 0;
    private int mHistoryPopupSepColor = 0;
    private int mHistoryHighlightFill = 0;

    /**
     * (Re)compute ALL cached colours from the scheme and panel alpha percentages.
     *
     * @param prefs app preferences (button alpha percentages).
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
        mFloatingInactivePct = inactivePct;
        mFloatingActivePct = activePct;

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

        // The two controls drawn straight on the terminal — the input-panel toggle and the scrollbar
        // thumb — carry the terminal background inside their own colour instead of taking a plain
        // translucent tint, so they read as part of the terminal rather than shapes pasted on it.
        deriveFloatingColors(mSchemeBackground);

        // Text-selection highlight: scheme foreground tinted to ~15% alpha (scheme-consistent,
        // not a hardcoded black/white).
        mTextSelectionHighlightColor = withAlpha(mSchemeForeground, 38);

        // Derived surfaces.
        mDividerColor = withAlpha(mSchemeForeground, 0x33);

        // Context-popup colours
        mHistoryPopupBg = compositeColors(mSchemeBackground, inactiveTint);
        mHistoryTextColor = mButtonText;
        mHistoryPopupSepColor = withAlpha(mHistoryTextColor, 0x3C);
        // Highlight of the history popup item under the finger: scheme foreground @ ~15%.
        mHistoryHighlightFill = withAlpha(mHistoryTextColor, 0x26);
    }

    /**
     * Mix the inactive/active tints into {@code background} and cache the result as the colours of
     * the controls drawn on the terminal.
     *
     * <p>The tint direction is decided from {@code background} rather than from the cached scheme
     * lightness: these colours only make sense against the surface they are painted on, and that
     * surface is the terminal background, which a shell can change behind the app's back.
     */
    private void deriveFloatingColors(int background) {
        final boolean backgroundIsLight = ColorSchemeUtils.isColorLight(background);
        final int overlayInactive = ColorSchemeUtils.getButtonBackground(backgroundIsLight,
                Math.min(100, mFloatingInactivePct * 2));
        final int overlayActive = ColorSchemeUtils.getButtonActiveBackground(backgroundIsLight,
                Math.min(100, mFloatingActivePct * 2));
        mFloatingButtonFill = withAlpha(compositeColors(background, overlayInactive),
                FLOATING_CONTROL_OPACITY);
        mFloatingButtonStroke = withAlpha(compositeColors(background, overlayActive),
                FLOATING_CONTROL_OPACITY);
        mFloatingBackground = background;
    }

    /**
     * Re-derive the floating controls' colours from the background the terminal is actually
     * painting, leaving the rest of the cache alone.
     *
     * <p>A running shell can repaint the terminal through OSC 4/11 without {@link
     * TerminalColors#COLOR_SCHEME} noticing — writing the live colour back would discard the
     * user's scheme — so without this the controls would keep the background they were mixed with
     * when the scheme was last applied.
     *
     * @return {@code true} when the cached colours changed, i.e. the caller has to re-apply them.
     */
    public boolean refreshFloatingColorsForBackground(int background) {
        if (background == mFloatingBackground) return false;
        final int fill = mFloatingButtonFill;
        final int stroke = mFloatingButtonStroke;
        deriveFloatingColors(background);
        return fill != mFloatingButtonFill || stroke != mFloatingButtonStroke;
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

    /** @return Fill of the floating controls: terminal background + inactive tint, at 50%. */
    public int getFloatingButtonFill() { return mFloatingButtonFill; }

    /** @return Stroke (and pressed fill) of the floating controls: background + active tint, at 50%. */
    public int getFloatingButtonStroke() { return mFloatingButtonStroke; }

    /** @return Whether the current scheme is perceived as light. */
    public boolean isSchemeLight() { return mIsSchemeLight; }

    /** @return Cached raw scheme background colour. */
    public int getSchemeBackground() { return mSchemeBackground; }

    /** @return Cached raw scheme foreground colour. */
    public int getSchemeForeground() { return mSchemeForeground; }

    /** @return Cached divider colour (scheme foreground @ ~20% alpha). */
    public int getDividerColor() { return mDividerColor; }

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
