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
 * Must be {@link #recompute(TermuxAppSharedPreferences)}d whenever the scheme, the
 * inactive/active-alpha sliders, the terminal background transparency or the
 * "contrast floating element background" switch change so that every styled element uses fresh
 * colours without recomputing them on every draw / event.
 * <p>
 * The terminal background transparency is used by exactly one thing here: as the weight the
 * terminal background COLOUR is mixed into the two controls drawn on the terminal with — and only
 * while the "contrast floating element background" option is on (see
 * {@link #deriveFloatingColors(int)}). It never changes the controls' own transparency.
 */
public final class TermuxColorSchemeManager {

    /**
     * Opacity the two controls drawn on the terminal are painted at in <b>contrast-background</b>
     * mode, where their tint has already been composited onto the terminal background. The tint
     * goes in at double strength there precisely because this halves it again, so the strength the
     * eye ends up seeing is the configured one.
     */
    private static final int FLOATING_CONTROL_CONTRAST_OPACITY = 128; // 50%

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
    /**
     * Terminal background transparency (0-100%), i.e. the weight its colour is mixed into the two
     * colours above with — see {@link #deriveFloatingColors(int)}. Only used while
     * {@link #mFloatingContrastBackground} is on. Also reused when only the background changes.
     */
    private int mFloatingTransparencyPct = 0;
    /**
     * The "contrast floating element background" option: whether the terminal background colour is
     * mixed into the two colours above at all. Off by default, i.e. both controls are the plain
     * translucent tints the panel buttons use.
     */
    private boolean mFloatingContrastBackground = false;

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
     * (Re)compute ALL cached colours from the scheme, the panel alpha percentages and the
     * configured terminal background transparency.
     *
     * <p>Use this only where no activity can supply the <em>effective</em> transparency (the
     * extra-keys editor preview): the terminal itself is painted with
     * {@code TermuxActivity.getEffectiveBackgroundTransparency()}, so the live activity must call
     * {@link #recompute(TermuxAppSharedPreferences, int)} with that value instead.
     *
     * @param prefs app preferences (button alpha percentages, background transparency, contrast
     *              floating element background).
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs) {
        recompute(prefs, prefs.getTerminalBackgroundTransparency());
    }

    /**
     * (Re)compute ALL cached colours from the scheme, the panel alpha percentages and the given
     * terminal background transparency.
     *
     * @param prefs           app preferences (button alpha percentages, contrast floating element
     *                        background).
     * @param transparencyPct The transparency the terminal is actually painted with, i.e.
     *                        {@code TermuxActivity.getEffectiveBackgroundTransparency()}.
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs, int transparencyPct) {
        recompute(prefs.getButtonBgInactiveAlpha(), prefs.getButtonBgActiveAlpha(), transparencyPct,
            prefs.isContrastFloatingElementBackgroundEnabled());
    }

    /** Convenience overload using the default button alpha percentages and an opaque background. */
    public void recompute() {
        recompute(5, 12, 0, false);
    }

    /**
     * (Re)compute all cached colours from the scheme and explicit alpha percentages.
     *
     * @param inactivePct       Inactive button background alpha (0–100).
     * @param activePct         Active button background alpha (0–100).
     * @param transparencyPct   Terminal background transparency (0–100), used as the weight the
     *                          terminal background colour is mixed into the two controls drawn on
     *                          the terminal with. Their own transparency is NOT affected by it, and
     *                          nothing is mixed in while {@code contrastBackground} is off.
     * @param contrastBackground Whether the terminal background is mixed into those two controls at
     *                          all; see {@link #deriveFloatingColors(int)}.
     */
    public void recompute(int inactivePct, int activePct, int transparencyPct,
                          boolean contrastBackground) {
        mIsSchemeLight = ColorSchemeUtils.isTerminalSchemeLight();
        mFloatingInactivePct = inactivePct;
        mFloatingActivePct = activePct;
        mFloatingTransparencyPct = transparencyPct < 0 ? 0 : Math.min(transparencyPct, 100);
        mFloatingContrastBackground = contrastBackground;

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
        // thumb. Off by default they are the SAME translucent tints the panel buttons use (same
        // function, same scheme-based light/dark decision); with the contrast option on they are
        // recomputed against the terminal background instead.
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
     * Cache the colours of the two controls drawn on the terminal — the input-panel toggle and the
     * scrollbar thumb — in whichever of the two modes the "contrast floating element background"
     * option selects.
     *
     * <p><b>Option off (default).</b> Both controls are the plain translucent tints the panel
     * buttons use, at exactly the alpha the user configured for them — black on a light scheme,
     * white on a dark one, decided from the scheme background colour alone. The terminal background
     * is not involved at all.
     *
     * <p><b>Option on.</b> The terminal background colour is mixed into the tint, weighted by the
     * terminal background's transparency: {@code colour = (1 - t) * tint + t * terminalBackground}.
     * Both are then composited into the background and painted at {@link
     * #FLOATING_CONTROL_CONTRAST_OPACITY}, with the tint taken at DOUBLE the configured alpha
     * because that halving undoes it — this reproduces the pre-{@code abed45b0} rendering, in which
     * the controls carry the terminal background instead of merely lying on it.
     *
     * <p>Why the control's own transparency survives the compositing: the composited colour is
     * opaque, so the 50% is what makes it translucent again — the control ends up letting the
     * terminal through at the configured strength, whichever tint direction it carries. Why the
     * mix keeps the direction true: at 0% the terminal background already sits under the control,
     * so the control is the plain tint; the more of that background the wallpaper replaces, the
     * more of the background's colour the control carries itself, and since the transparency is
     * capped at 50% the tint still outweighs the background — a dark tint on a light scheme can
     * never stop darkening and a light tint on a dark scheme can never stop lightening, whatever
     * the wallpaper shows through.
     *
     * <p>Contrast mode decides the tint direction from {@code background} rather than from the
     * cached scheme lightness, because there the colour only makes sense against the surface it is
     * painted on — the terminal background, which a shell can change behind the app's back. In the
     * default mode it is the scheme lightness, the same decision the panel buttons are drawn by.
     */
    private void deriveFloatingColors(int background) {
        if (!mFloatingContrastBackground) {
            mFloatingButtonFill =
                ColorSchemeUtils.getButtonBackground(mIsSchemeLight, mFloatingInactivePct);
            mFloatingButtonStroke =
                ColorSchemeUtils.getButtonActiveBackground(mIsSchemeLight, mFloatingActivePct);
        } else {
            final boolean backgroundIsLight = ColorSchemeUtils.isColorLight(background);
            final int overlayInactive = ColorSchemeUtils.getButtonBackground(backgroundIsLight,
                    Math.min(100, mFloatingInactivePct * 2));
            final int overlayActive = ColorSchemeUtils.getButtonActiveBackground(backgroundIsLight,
                    Math.min(100, mFloatingActivePct * 2));
            mFloatingButtonFill = withAlpha(compositeColors(background, overlayInactive),
                    FLOATING_CONTROL_CONTRAST_OPACITY);
            mFloatingButtonStroke = withAlpha(compositeColors(background, overlayActive),
                    FLOATING_CONTROL_CONTRAST_OPACITY);
        }
        mFloatingBackground = background;
    }

    /**
     * Re-derive the floating controls' colours from the background the terminal is actually
     * painting, leaving the rest of the cache alone.
     *
     * <p>Only meaningful in contrast-background mode, where the terminal background's colour is
     * mixed into both controls and a background that changed without the scheme changing would
     * leave them carrying a colour the terminal no longer shows. A running shell can repaint the
     * terminal through OSC 4/11 without {@link TerminalColors#COLOR_SCHEME} noticing — writing the
     * live colour back would discard the user's scheme — so this is the only place that can pick
     * the live colour up.
     *
     * <p>In the default mode the background is only remembered, not used: both controls are plain
     * scheme tints, so a background change must not repaint them (the cached colours are returned
     * unchanged, and the caller leaves them alone).
     *
     * <p>The transparency weight and the contrast option are NOT re-read here: they are
     * preferences, and the caller re-runs {@link #recompute(int, int, int, boolean)} when they
     * change.
     *
     * @return {@code true} when the cached colours changed, i.e. the caller has to re-apply them.
     */
    public boolean refreshFloatingColorsForBackground(int background) {
        if (background == mFloatingBackground) return false;
        if (!mFloatingContrastBackground) {
            mFloatingBackground = background;
            return false;
        }
        final int fill = mFloatingButtonFill;
        final int stroke = mFloatingButtonStroke;
        deriveFloatingColors(background);
        return fill != mFloatingButtonFill || stroke != mFloatingButtonStroke;
    }

    /**
     * @return Whether the terminal background is mixed into the two controls drawn on it — the
     *         "contrast floating element background" option.
     */
    public boolean isFloatingContrastBackgroundEnabled() { return mFloatingContrastBackground; }

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

    /**
     * @return Fill of the floating controls: the inactive tint at the user's alpha — either plain
     *         (default) or carried by the terminal background (see
     *         {@link #deriveFloatingColors(int)}).
     */
    public int getFloatingButtonFill() { return mFloatingButtonFill; }

    /**
     * @return Stroke (and pressed fill) of the floating controls: the active tint at the user's
     *         alpha — either plain (default) or carried by the terminal background.
     */
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
