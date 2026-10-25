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
 * inactive/active-alpha sliders or the terminal background transparency changes so that every
 * styled element uses fresh colours without recomputing them on every draw / event.
 * <p>
 * The terminal background transparency only weights how much terminal background colour is
 * mixed into the controls drawn on it (see {@link #deriveFloatingColors(int)}); it never changes
 * their own alpha.
 */
public final class TermuxColorSchemeManager {

    // Process-scoped singleton: the bubble is a second TermuxActivity instance, and both
    // windows must use the same cached colours. A per-instance manager gave each window its
    // own cache, so a scheme change in one window left the other with stale colours.
    private static volatile TermuxColorSchemeManager sShared;

    @NonNull
    public static TermuxColorSchemeManager shared() {
        if (sShared == null) {
            synchronized (TermuxColorSchemeManager.class) {
                if (sShared == null) {
                    sShared = new TermuxColorSchemeManager();
                }
            }
        }
        return sShared;
    }

    private TermuxColorSchemeManager() {}

    /**
     * Opacity of the controls drawn on the terminal once their tint has been composited onto the
     * terminal background.
     */
    private static final int FLOATING_CONTROL_CONTRAST_OPACITY = 128; // 50%

    /**
     * The same, for the input panel while it floats over the terminal: a broad surface carrying
     * text, which the controls' 50% would leave competing with the terminal behind it.
     */
    private static final int FLOATING_PANEL_CONTRAST_OPACITY = 204; // 80%

    /**
     * Doubles the tint alpha because {@link #FLOATING_CONTROL_CONTRAST_OPACITY} halves it again,
     * leaving the controls at the configured percentage.
     */
    private static final int FLOATING_CONTROL_CONTRAST_TINT_SCALE = 2;

    /**
     * The same multiplier for the <b>inactive</b> tint. The exact compensation above only holds for
     * the active one; at 2 the resting tint carried more colour than the user's alpha asked for.
     */
    private static final float FLOATING_INACTIVE_CONTRAST_TINT_SCALE = 1.25f;

    // --- Panel / button colours ---
    private int mButtonBg = 0;
    private int mButtonActiveBg = 0;
    private int mButtonText = 0;
    private int mTextSelectionHighlightColor = 0;
    private boolean mIsSchemeLight = false;

    // --- Controls drawn on the terminal: input-panel toggle button + scrollbar thumb ---
    private int mFloatingButtonFill = 0;
    private int mFloatingButtonStroke = 0;

    // --- The input panel while it floats over the terminal ---
    private int mFloatingPanelFill = 0;
    private int mFloatingPanelStroke = 0;
    /** Background the two colours above were last mixed from. */
    private int mFloatingBackground = 0;
    /** Alpha percentages {@link #recompute} last ran with, reused when only the background changes. */
    private int mFloatingInactivePct = 0;
    private int mFloatingActivePct = 0;
    /**
     * Terminal background transparency (0-100%), the weight its colour is mixed into the colours
     * above with — see {@link #deriveFloatingColors(int)}. Retained for background-only refreshes.
     */
    private int mFloatingTransparencyPct = 0;

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
     * @param prefs app preferences (button alpha percentages, background transparency).
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs) {
        recompute(prefs, prefs.getTerminalBackgroundTransparency());
    }

    /**
     * (Re)compute ALL cached colours from the scheme, the panel alpha percentages and the given
     * terminal background transparency.
     *
     * @param prefs           app preferences (button alpha percentages).
     * @param transparencyPct The transparency the terminal is actually painted with, i.e.
     *                        {@code TermuxActivity.getEffectiveBackgroundTransparency()}.
     */
    public void recompute(@NonNull TermuxAppSharedPreferences prefs, int transparencyPct) {
        recompute(prefs.getButtonBgInactiveAlpha(), prefs.getButtonBgActiveAlpha(), transparencyPct);
    }

    /** Convenience overload using the default button alpha percentages and an opaque background. */
    public void recompute() {
        recompute(5, 12, 0);
    }

    /**
     * (Re)compute all cached colours from the scheme and explicit alpha percentages.
     *
     * @param inactivePct       Inactive button background alpha (0–100).
     * @param activePct         Active button background alpha (0–100).
     * @param transparencyPct   Terminal background transparency (0–100), used as the weight the
     *                          terminal background colour is mixed into the two controls drawn on
     *                          the terminal with. Their own transparency is NOT affected by it.
     */
    public void recompute(int inactivePct, int activePct, int transparencyPct) {
        mIsSchemeLight = ColorSchemeUtils.isTerminalSchemeLight();
        mFloatingInactivePct = inactivePct;
        mFloatingActivePct = activePct;
        mFloatingTransparencyPct = transparencyPct < 0 ? 0 : Math.min(transparencyPct, 100);

        // Raw scheme colours.
        mSchemeBackground = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
        mSchemeForeground = ColorSchemeUtils.getSchemeForeground();

        // Raw (translucent) panel button tints: dark for a light scheme, light for a dark one,
        // decided by the scheme background colour alone. The tints are kept as-is — the wallpaper
        // is not hidden by opaque panels, it is shown through the WHOLE window, because the decor
        // view is painted with the scheme background at the terminal's alpha (see
        // TermuxActivity.applySystemBarColors()). Panels stay transparent and therefore inherit
        // exactly the same "scheme bg at alpha A over the wallpaper" as the terminal itself, so the
        // entire window is uniformly translucent instead of patchy.
        int inactiveTint = ColorSchemeUtils.getButtonBackground(mIsSchemeLight, inactivePct);
        int activeTint = ColorSchemeUtils.getButtonActiveBackground(mIsSchemeLight, activePct);
        mButtonBg = inactiveTint;
        mButtonActiveBg = activeTint;
        mButtonText = mSchemeForeground;

        // The input-panel toggle and the scrollbar thumb stay readable against the terminal
        // background instead of merely lying on top of it.
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
     * Composite the terminal background into both tints (weighted by its transparency), then paint
     * them at {@link #FLOATING_CONTROL_CONTRAST_OPACITY} with the tint scaled up to compensate.
     * Tint direction follows {@code background}, which a shell can change behind the app's back.
     */
    private void deriveFloatingColors(int background) {
        final boolean backgroundIsLight = ColorSchemeUtils.isColorLight(background);
        final int overlayInactive = ColorSchemeUtils.getButtonBackground(backgroundIsLight,
                Math.min(100, Math.round(mFloatingInactivePct * FLOATING_INACTIVE_CONTRAST_TINT_SCALE)));
        final int overlayActive = ColorSchemeUtils.getButtonActiveBackground(backgroundIsLight,
                Math.min(100, Math.round(mFloatingActivePct * FLOATING_CONTROL_CONTRAST_TINT_SCALE)));
        // One composite each, then two opacities over it, so the panel and the controls agree
        // on colour and differ only in how much of the terminal each lets through.
        final int fill = compositeColors(background, overlayInactive);
        final int stroke = compositeColors(background, overlayActive);
        mFloatingButtonFill = withAlpha(fill, FLOATING_CONTROL_CONTRAST_OPACITY);
        mFloatingButtonStroke = withAlpha(stroke, FLOATING_CONTROL_CONTRAST_OPACITY);
        mFloatingPanelFill = withAlpha(fill, FLOATING_PANEL_CONTRAST_OPACITY);
        mFloatingPanelStroke = withAlpha(stroke, FLOATING_PANEL_CONTRAST_OPACITY);
        mFloatingBackground = background;
    }

    /**
     * Re-derive the floating controls' colours from the live terminal background, leaving the rest
     * of the cache alone. A shell can repaint that background through OSC 4/11 without
     * {@link TerminalColors#COLOR_SCHEME} noticing, so this is the only place that can pick the
     * live colour up. The transparency weight is not re-read here; the caller re-runs
     * {@link #recompute(int, int, int)} when it changes.
     *
     * @return {@code true} when the cached colours changed, i.e. the caller has to re-apply them.
     */
    public boolean refreshFloatingColorsForBackground(int background) {
        if (background == mFloatingBackground) return false;
        final int fill = mFloatingButtonFill;
        final int stroke = mFloatingButtonStroke;
        final int panelFill = mFloatingPanelFill;
        final int panelStroke = mFloatingPanelStroke;
        deriveFloatingColors(background);
        return fill != mFloatingButtonFill || stroke != mFloatingButtonStroke
            || panelFill != mFloatingPanelFill || panelStroke != mFloatingPanelStroke;
    }

    /** Apply {@code alpha} (0–255) to the RGB of {@code color}, keeping the scheme hue. */
    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // --- Getters ---

    public int getButtonBg() { return mButtonBg; }

    public int getButtonActiveBg() { return mButtonActiveBg; }

    public int getButtonText() { return mButtonText; }

    public int getTextSelectionHighlightColor() { return mTextSelectionHighlightColor; }

    /** Fill of the floating controls: the inactive tint at the user's alpha, composited with the
     * terminal background. */
    public int getFloatingButtonFill() { return mFloatingButtonFill; }

    /** Fill of the input panel while it floats over the terminal — {@link #getFloatingButtonFill()}
     * at {@link #FLOATING_PANEL_CONTRAST_OPACITY}. */
    public int getFloatingPanelFill() { return mFloatingPanelFill; }

    /** Stroke counterpart of {@link #getFloatingPanelFill()}. */
    public int getFloatingPanelStroke() { return mFloatingPanelStroke; }

    /** Stroke (and pressed fill) of the floating controls: the active tint at the user's alpha,
     * composited with the terminal background. */
    public int getFloatingButtonStroke() { return mFloatingButtonStroke; }

    public boolean isSchemeLight() { return mIsSchemeLight; }

    public int getSchemeBackground() { return mSchemeBackground; }

    public int getSchemeForeground() { return mSchemeForeground; }

    public int getDividerColor() { return mDividerColor; }

    public int getHistoryPopupBg() { return mHistoryPopupBg; }

    public int getHistoryTextColor() { return mHistoryTextColor; }

    public int getHistoryPopupSepColor() { return mHistoryPopupSepColor; }

    public int getHistoryHighlightFill() { return mHistoryHighlightFill; }

    // --- Static utility ---

    /**
     * Alpha-composite {@code overlay} (with alpha) on top of {@code background} (assumed opaque)
     * using the standard over operator.
     *
     * @return a fully opaque ARGB colour.
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
