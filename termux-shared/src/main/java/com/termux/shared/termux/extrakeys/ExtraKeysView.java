package com.termux.shared.termux.extrakeys;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.stream.Collectors;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.GridLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import com.termux.shared.R;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.shared.theme.ThemeUtils;

/**
 * A {@link View} showing extra keys (Escape, Ctrl, Alt, …) not normally available on an Android
 * soft keyboard.
 *
 * <p>Wire-up: declare it in a layout (or inflate it into a pager), call
 * {@link #setExtraKeysViewClient(IExtraKeysView)} for click/haptic callbacks, then
 * {@link #reload(ExtraKeysInfo, float)} with the keys to show. The Termux app defines it in
 * {@code res/layout/view_terminal_toolbar_extra_keys}, inflates it in
 * {@code TerminalToolbarViewPager.instantiateItem()}, loads it via
 * {@code TermuxAppSharedProperties.setExtraKeys()} and uses {@code TermuxTerminalExtraKeys}
 * (extends {@link TerminalExtraKeys}) as the client.
 */
public final class ExtraKeysView extends GridLayout implements SpecialButtonStateOwner {

    /** Direction for swipe gestures detected in editor mode. */
    public enum SwipeDirection {
        UP, DOWN, LEFT, RIGHT
    }

    /** Editor mode: assign signals vs move/swap cells. */
    public enum EditorMode {
        ASSIGN,
        MOVE
    }

    /** Listener for move-mode cell swap operations. */
    public interface EditorMoveListener {
        /**
         * Called when a drag-and-drop move completes in MOVE mode.
         * Coordinates are row/col indices from the view child tags (0-based, relative to visible grid).
         */
        void onCellMove(int fromRow, int fromCol, int toRow, int toCol);
    }

    /** Listener for editor-mode gestures on the extra keys view. */
    public interface EditorGestureListener {
        /**
         * Called when a button is tapped (short press without significant movement).
         */
        void onKeyTap(View button, int row, int col);

        /**
         * Called when a swipe gesture is detected on a button.
         */
        void onKeySwipe(View button, int row, int col, SwipeDirection direction);
    }

    /** Listener for editor-mode long presses (used to edit a button's custom label). */
    public interface EditorLongPressListener {
        /**
         * Called when a button is held long enough to be a long press (release without movement).
         */
        void onKeyLongPress(View button, int row, int col);
    }

    /** The client for the {@link ExtraKeysView}. */
    public interface IExtraKeysView {

        /**
         * This is called by {@link ExtraKeysView} when a button is clicked. This is also called
         * for {@link #mRepetitiveKeys} and {@link ExtraKeyButton} that have a popup set.
         * However, this is not called for {@link #mSpecialButtons}, whose state can instead be read
         * via a call to {@link #readSpecialButton(SpecialButton, boolean)}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         *                   The button may be a {@link ExtraKeyButton#KEY_MACRO} set which can be
         *                   checked with a call to {@link ExtraKeyButton#isMacro()}.
         * @param button The {@link MaterialButton} that was clicked.
         */
        void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button);

        /**
         * Called when the finger is lifted after a swipe gesture (ACTION_UP).
         * Equivalent to ACTION_UP on a physical key: gesture-activated modifiers are deactivated.
         * Regular keys are no-ops (already dispatched on press). Macros may be cancelled.
         * <p>
         * The default implementation is a no-op for backward compatibility.
         *
         * @param view The source view that was swiped.
         * @param buttonInfo The {@link ExtraKeyButton} for the swipe target button.
         * @param button The {@link MaterialButton} that was swiped.
         */
        default void onExtraKeyButtonGestureRelease(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        }
        /**
         * This is called by {@link ExtraKeysView} when a button is clicked so that the client
         * can perform any hepatic feedback. This is only called in the {@link MaterialButton.OnClickListener}
         * and not for every repeat. Its also called for {@link #mSpecialButtons}.
         *
         * @param view The view that was clicked.
         * @param buttonInfo The {@link ExtraKeyButton} for the button that was clicked.
         * @param button The {@link MaterialButton} that was clicked.
         * @return Return {@code true} if the client handled the feedback, otherwise {@code false}
         * so that {@link ExtraKeysView#performExtraKeyButtonHapticFeedback(View, ExtraKeyButton, MaterialButton)}
         * can handle it depending on system settings.
         */
        boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button);

    }

    /** Defines the default value for {@link #mButtonTextColor} defined by current theme. */
    public static final int ATTR_BUTTON_TEXT_COLOR = R.attr.extraKeysButtonTextColor;
    /** Defines the default value for {@link #mButtonActiveTextColor} defined by current theme. */
    public static final int ATTR_BUTTON_ACTIVE_TEXT_COLOR = R.attr.extraKeysButtonActiveTextColor;
    /** Defines the default value for {@link #mButtonBackgroundColor} defined by current theme. */
    public static final int ATTR_BUTTON_BACKGROUND_COLOR = R.attr.extraKeysButtonBackgroundColor;
    /** Defines the default value for {@link #mButtonActiveBackgroundColor} defined by current theme. */
    public static final int ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR = R.attr.extraKeysButtonActiveBackgroundColor;

    /** Defines the default fallback value for {@link #mButtonTextColor} if {@link #ATTR_BUTTON_TEXT_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    /** Defines the default fallback value for {@link #mButtonActiveTextColor} if {@link #ATTR_BUTTON_ACTIVE_TEXT_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_ACTIVE_TEXT_COLOR = 0xFF80DEEA;
    /** Defines the default fallback value for {@link #mButtonBackgroundColor} if {@link #ATTR_BUTTON_BACKGROUND_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_BACKGROUND_COLOR = 0xFF1A1A1A;
    /** Defines the default fallback value for {@link #mButtonActiveBackgroundColor} if {@link #ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR} is undefined. */
    public static final int DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR = 0xFF424242;

    /** Button margin in dp. Only the trailing side of each button carries the margin, so the
     *  visual distance between adjacent buttons equals the margin (1x) on both axes. */
    public static final int BUTTON_MARGIN_HORIZONTAL_DP = 2;
    /** Button vertical margin in dp (see {@link #BUTTON_MARGIN_HORIZONTAL_DP}). */
    public static final int BUTTON_MARGIN_VERTICAL_DP = 2;
    /** Default button corner radius in dp. */
    public static final int BUTTON_CORNER_RADIUS_DP = 12;

    /** Current button corner radius in dp, loaded from preferences. */
    private int mButtonCornerRadiusDp = BUTTON_CORNER_RADIUS_DP;

    /** Current button margin horizontal in dp, loaded from preferences. */
    private float mButtonMarginHorizontalDp = BUTTON_MARGIN_HORIZONTAL_DP;
    /** Current button margin vertical in dp, loaded from preferences. */
    private float mButtonMarginVerticalDp = BUTTON_MARGIN_VERTICAL_DP;

    /**
     * Whether the first row of buttons should keep its top margin (used when the session tabs
     * panel sits above the extra keys, so the panel does not touch the tabs). When false the
     * top margin of the first row is dropped so the buttons touch the container's top border.
     */
    private boolean mTopMarginEnabled;

    private boolean mEditorEdgeIndicatorsEnabled;
    private int mEditorEdgeColor = 0xFF888888;
    private final Paint mEditorEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float EDITOR_EDGE_THICKNESS_DP = 2f;

    // ── Runtime swipe-direction edge indicators ──
    private boolean mRuntimeEdgeIndicatorsEnabled = true;
    private final Paint mRuntimeEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float RUNTIME_EDGE_THICKNESS_DP = 2f;

    private static final class RuntimeEdgeInfo {
        final int flags;                    // bitmask: 1=UP 2=DOWN 4=LEFT 8=RIGHT
        @Nullable final ExtraKeyButton up;
        @Nullable final ExtraKeyButton down;
        @Nullable final ExtraKeyButton left;
        @Nullable final ExtraKeyButton right;

        RuntimeEdgeInfo(int flags,
                        @Nullable ExtraKeyButton up, @Nullable ExtraKeyButton down,
                        @Nullable ExtraKeyButton left, @Nullable ExtraKeyButton right) {
            this.flags = flags;
            this.up = up; this.down = down; this.left = left; this.right = right;
        }

        @Nullable
        ExtraKeyButton target(SwipeDirection dir) {
            switch (dir) {
                case UP:    return up;
                case DOWN:  return down;
                case LEFT:  return left;
                case RIGHT: return right;
            }
            return null;
        }
    }

    /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int MIN_LONG_PRESS_DURATION = 200;
    /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int MAX_LONG_PRESS_DURATION = 3000;
    /** Defines the fallback duration in milliseconds for {@link #mLongPressTimeout}. */
    public static final int FALLBACK_LONG_PRESS_DURATION = 400;

    /** Defines the minimum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int MIN_LONG_PRESS__REPEAT_DELAY = 5;
    /** Defines the maximum allowed duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int MAX_LONG_PRESS__REPEAT_DELAY = 2000;
    /** Defines the default duration in milliseconds for {@link #mLongPressRepeatDelay}. */
    public static final int DEFAULT_LONG_PRESS_REPEAT_DELAY = 80;

    /** The implementation of the {@link IExtraKeysView} that acts as a client for the {@link ExtraKeysView}. */
    protected IExtraKeysView mExtraKeysViewClient;

    /** The map for the {@link SpecialButton} and their {@link SpecialButtonState}. Defaults to
     * the one returned by {@link #getDefaultSpecialButtons(SpecialButtonStateOwner)}. */
    protected ArrayMap<SpecialButton, SpecialButtonState> mSpecialButtons;

    /** The keys for the {@link SpecialButton} added to {@link #mSpecialButtons}. This is automatically
     * set when the call to {@link #setSpecialButtons(Map)} is made. */
    protected Set<String> mSpecialButtonsKeys;

    /**
     * The list of keys that auto-repeat when their extra keys button is long pressed, by calling
     * {@link IExtraKeysView#onExtraKeyButtonClick(View, ExtraKeyButton, MaterialButton)} every
     * {@link #mLongPressRepeatDelay} <em>milliseconds</em> after {@link #mLongPressTimeout} has
     * passed. Defaults to {@link ExtraKeysConstants#PRIMARY_REPETITIVE_KEYS}.
     */
    protected List<String> mRepetitiveKeys;

    /** The text color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_TEXT_COLOR}. */
    protected int mButtonTextColor;
    /** The text color for the extra keys button when its active.
     * Defaults to {@link #DEFAULT_BUTTON_ACTIVE_TEXT_COLOR}. */
    protected int mButtonActiveTextColor;
    /** The background color for the extra keys button. Defaults to {@link #DEFAULT_BUTTON_BACKGROUND_COLOR}. */
    protected int mButtonBackgroundColor;
    /** The background color for the extra keys button when its active. Defaults to
     * {@link #DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR}. */
    protected int mButtonActiveBackgroundColor;

    /** Defines whether text for the extra keys button should be all capitalized automatically. */
    protected boolean mButtonTextAllCaps = true;

    /** If true, font size is reduced when column count or macro bind count increases. */
    private boolean mDynamicFontSize = true;

    /**
     * Landscape compaction: when active, {@link #reload(ExtraKeysInfo, float)} folds the stored rows
     * into one or two rows (see {@link ExtraKeysCompaction}). The flag lives on the view instead of
     * being passed to {@code reload()} so that every reload path — the activity, the session-profile
     * client and the editor preview — picks it up without changing its call signature.
     */
    private boolean mCompactLandscape;

    /** Order of keys inside a folded row. Only meaningful while {@link #mCompactLandscape} is set. */
    @NonNull
    private ExtraKeysCompaction.Mode mCompactMode = ExtraKeysCompaction.Mode.ROWS;

    /** Row count the grid was actually built with by the last {@link #reload(ExtraKeysInfo, float)}. */
    private int mLastReloadedRowCount;

    /**
     * The fold state the grid <em>on screen</em> was built with. {@link #setLandscapeCompact}
     * compares a new request against this, not the previous request: a caller may set the flag and
     * skip the rebuild, after which a flag-to-flag comparison would answer "nothing to do" forever.
     */
    private boolean mBuiltCompactLandscape;
    @NonNull
    private ExtraKeysCompaction.Mode mBuiltCompactMode = ExtraKeysCompaction.Mode.ROWS;
    /** {@code false} until a {@code reload()} has actually built a grid. */
    private boolean mBuiltGrid;

    /** The base font size in sp for button labels. Defaults to 14. */
    private int mBaseFontSizeSp = 14;

    /** Cached fitted font size from last successful dynamic font calculation. -1 = not yet computed. */
    private float mCachedFittedFontSp = -1f;

    /**
     * What the last dynamic-font pass was computed for: this view's size and the grid dimensions.
     * Either half changing makes the record stale, so {@link #onLayout} re-measures whenever it no
     * longer matches. Written by {@link #recordFittedFontState()}, zeroed by
     * {@link #invalidateFittedFont()}.
     */
    private int mFittedFontWidth;
    private int mFittedFontHeight;
    private int mFittedFontColumns;
    private int mFittedFontRows;

    /** Original display text for each button before all-caps transformation. */
    private final ArrayMap<MaterialButton, String> mOriginalButtonTexts = new ArrayMap<>();

    /** Tag key for storing {@link ExtraKeyButton} on each MaterialButton. */
    private static final int TAG_EXTRA_KEY_INFO = R.id.tag_extra_key_info;

    /**
     * Defines the duration in milliseconds before a press turns into a long press. The default
     * duration used is the one returned by a call to {@link ViewConfiguration#getLongPressTimeout()}
     * which will return the system defined duration which can be changed in accessibility settings.
     * The duration must be in between {@link #MIN_LONG_PRESS_DURATION} and {@link #MAX_LONG_PRESS_DURATION},
     * otherwise {@link #FALLBACK_LONG_PRESS_DURATION} is used.
     */
    protected int mLongPressTimeout;

    /**
     * Defines the duration in milliseconds for the delay between trigger of each repeat of
     * {@link #mRepetitiveKeys}. The default value is defined by {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY}.
     * The duration must be in between {@link #MIN_LONG_PRESS__REPEAT_DELAY} and
     * {@link #MAX_LONG_PRESS__REPEAT_DELAY}, otherwise {@link #DEFAULT_LONG_PRESS_REPEAT_DELAY} is used.
     */
    protected int mLongPressRepeatDelay;

    /** Behaviour of {@link #mSpecialButtons}: {@link SpecialButtonMode#STICKY} (default) or
     *  {@link SpecialButtonMode#HOLD} — see the enum for the exact interaction. */
    protected SpecialButtonMode mSpecialButtonMode = SpecialButtonMode.STICKY;

    /** The behaviour mode for the {@link #mSpecialButtons}. */
    public enum SpecialButtonMode {
        /** Tap toggles the button on/off; long hold locks it on. */
        STICKY,
        /** Button is active only while touched, deactivating on release. */
        HOLD
    }

    /** Editor gesture listener. When non-null, the view is in editor mode. */
    @Nullable
    private EditorGestureListener mEditorListener;

    /** Pointer state for editor gesture tracking. */
    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;
    private float mDownX;
    private float mDownY;
    private boolean mGestureConsumed;
    @Nullable private View mActiveChild;
    private int mSwipeThreshold; // pixels
    /** Long-press listener (editor mode). */
    @Nullable
    private EditorLongPressListener mEditorLongPressListener;
    /** Pending delayed long-press task; fires while the finger is still held. */
    @Nullable
    private Runnable mLpRunnable;
    private boolean mLpFired;

    /** Runtime swipe detection: X coordinate of finger down. */
    private float mTouchDownX;
    /** Runtime swipe detection: Y coordinate of finger down. */
    private float mTouchDownY;
    /** Runtime swipe detection: non-null when a swipe has been detected during the current touch sequence. */
    @Nullable
    private SwipeDirection mRuntimeSwipeDirection;

    /** Gesture press/release tracking: the ExtraKeyButton currently held by an active swipe gesture. */
    @Nullable
    private ExtraKeyButton mGestureActiveButton;
    /** Gesture press/release tracking: the MaterialButton of the active gesture. */
    @Nullable
    private MaterialButton mGestureActiveMaterialButton;
    /** Gesture press/release tracking: the source view of the active gesture. */
    @Nullable
    private View mGestureActiveView;

    protected Handler mHandler;
    protected SpecialButtonsLongHoldRunnable mSpecialButtonsLongHoldRunnable;
    /** Recursive main-thread runnable that drives auto-repeat after a long-press. Null when inactive. */
    protected Runnable mRepetitiveRunnable;
    protected int mLongPressCount;

    // Haptic state is cached so performExtraKeyButtonHapticFeedback() never has to perform a
    // per-press Binder IPC to the SettingsProvider. Refreshed on attach, on every
    // reloadActivityStyling(), and via a ContentObserver when the system toggles change.
    private int mHapticMode = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC;
    private boolean mHapticFeedbackEnabled;
    private boolean mZenModeAllowsSound = true;
    @Nullable
    private ContentObserver mHapticObserver;

    private ColorStateList mButtonBgTint;
    private ColorStateList mButtonActiveBgTint;

    private final List<MaterialButton> mButtonPool = new ArrayList<>();

    /**
     * Display density, used to turn dp margins and sp font sizes into pixels. Not final: the
     * activity declares {@code density} in its {@code configChanges}, so a display-size change
     * reaches this view without recreating it. Refreshed on every
     * {@link #reload(ExtraKeysInfo, float)}.
     */
    private float mDensity;

    private final TextPaint mMeasPaint = new TextPaint();

    private final RectF mEditorOval = new RectF();
    private final Path mEditorPath = new Path();

    private final android.graphics.Rect mHitRect = new android.graphics.Rect();

    @Nullable
    private SwipeDirection mEditorSwipeDir;

    /** Current editor mode. Defaults to ASSIGN (tap → signal picker, swipe → gesture picker). */
    private EditorMode mEditorMode = EditorMode.ASSIGN;

    /** Listener for move-mode drag-and-drop operations. Null unless MOVE mode is active. */
    @Nullable
    private EditorMoveListener mEditorMoveListener;

    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUseDefaultMargins(false);

        mDensity = getResources().getDisplayMetrics().density;

        setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);
        setSpecialButtons(getDefaultSpecialButtons(this));

        setButtonColors(
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_TEXT_COLOR, DEFAULT_BUTTON_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_TEXT_COLOR, DEFAULT_BUTTON_ACTIVE_TEXT_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_BACKGROUND_COLOR, DEFAULT_BUTTON_BACKGROUND_COLOR),
            ThemeUtils.getSystemAttrColor(context, ATTR_BUTTON_ACTIVE_BACKGROUND_COLOR, DEFAULT_BUTTON_ACTIVE_BACKGROUND_COLOR));

        setLongPressTimeout(ViewConfiguration.getLongPressTimeout());
        setLongPressRepeatDelay(DEFAULT_LONG_PRESS_REPEAT_DELAY);

        ViewConfiguration vc = ViewConfiguration.get(context);
        int touchSlop = vc.getScaledTouchSlop();
        int minThresholdDp = 16;
        int minThresholdPx = (int) (minThresholdDp * mDensity);
        mSwipeThreshold = Math.max(touchSlop, minThresholdPx);
    }

    /** Set {@link #mExtraKeysViewClient}. */
    public void setExtraKeysViewClient(IExtraKeysView extraKeysViewClient) {
        mExtraKeysViewClient = extraKeysViewClient;
    }

    /** Set {@link #mRepetitiveKeys}. Must not be {@code null}. */
    public void setRepetitiveKeys(@NonNull List<String> repetitiveKeys) {
        mRepetitiveKeys = repetitiveKeys;
    }

    /** Set {@link #mSpecialButtonsKeys}. Must not be {@code null}. */
    public void setSpecialButtons(@NonNull ArrayMap<SpecialButton, SpecialButtonState> specialButtons) {
        mSpecialButtons = specialButtons;
        mSpecialButtonsKeys = this.mSpecialButtons.keySet().stream().map(SpecialButton::getKey).collect(Collectors.toSet());
    }

    /**
     * Set the {@link ExtraKeysView} button colors.
     *
     * @param buttonTextColor The value for {@link #mButtonTextColor}.
     * @param buttonActiveTextColor The value for {@link #mButtonActiveTextColor}.
     * @param buttonBackgroundColor The value for {@link #mButtonBackgroundColor}.
     * @param buttonActiveBackgroundColor The value for {@link #mButtonActiveBackgroundColor}.
     */
    public void setButtonColors(int buttonTextColor, int buttonActiveTextColor, int buttonBackgroundColor, int buttonActiveBackgroundColor) {
        mButtonTextColor = buttonTextColor;
        mButtonActiveTextColor = buttonActiveTextColor;
        mButtonBackgroundColor = buttonBackgroundColor;
        mButtonActiveBackgroundColor = buttonActiveBackgroundColor;
        mButtonBgTint = ColorStateList.valueOf(mButtonBackgroundColor);
        mButtonActiveBgTint = ColorStateList.valueOf(mButtonActiveBackgroundColor);
        // Re-tint any buttons already laid out (e.g. when the color scheme changes at runtime,
        // after reload() has built the buttons). New buttons created by a later reload() read
        // these fields directly.
        applyColorsToExistingButtons();
    }

    /** Re-apply the current button colors to every child view already added by {@link #reload(ExtraKeysInfo, float)}. */
    private void applyColorsToExistingButtons() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof MaterialButton)) continue;
            MaterialButton button = (MaterialButton) child;
            applyButtonColors(button, false);
        }
        // Keep tinted special buttons consistent with their current active state.
        for (SpecialButtonState state : mSpecialButtons.values()) {
            for (MaterialButton button : state.buttons) {
                applyButtonColors(button, state.isActive);
            }
        }
    }

    private void applyButtonColors(MaterialButton button, boolean active) {
        button.setTextColor(active ? mButtonActiveTextColor : mButtonTextColor);
        button.setBackgroundTintList(active ? mButtonActiveBgTint : mButtonBgTint);
    }

    /** Get {@link #mButtonTextColor}. */
    public int getButtonTextColor() {
        return mButtonTextColor;
    }

    /** Get {@link #mButtonActiveTextColor}. */
    public int getButtonActiveTextColor() {
        return mButtonActiveTextColor;
    }

    /** Get {@link #mButtonBackgroundColor}. */
    public int getButtonBackgroundColor() {
        return mButtonBackgroundColor;
    }

    /** Get {@link #mButtonActiveBackgroundColor}. */
    public int getButtonActiveBackgroundColor() {
        return mButtonActiveBackgroundColor;
    }

    /** Set {@link #mButtonTextAllCaps}. */
    public void setButtonTextAllCaps(boolean buttonTextAllCaps) {
        if (mButtonTextAllCaps == buttonTextAllCaps) return;
        mButtonTextAllCaps = buttonTextAllCaps;
        mCachedFittedFontSp = -1f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof MaterialButton) {
                MaterialButton btn = (MaterialButton) child;
                String original = mOriginalButtonTexts.get(btn);
                if (original == null) {
                    CharSequence t = btn.getText();
                    original = t != null ? t.toString() : "";
                    mOriginalButtonTexts.put(btn, original);
                }
                btn.setText(getDisplayTextForCurrentCapsMode(original));
                btn.setAllCaps(false);
            }
        }
        post(this::applyDynamicFontAfterLayout);
    }

    public void setDynamicFontSize(boolean enabled) {
        if (mDynamicFontSize == enabled) return;
        mDynamicFontSize = enabled;
        if (!enabled) {
            mCachedFittedFontSp = -1f;
            // Reset all buttons to base font immediately when dynamic is turned off
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof MaterialButton) {
                    ((MaterialButton) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, mBaseFontSizeSp);
                }
            }
        }
        // Run immediately if layout is ready, otherwise post.
        // Synchronous execution avoids races with activity recreate() destroying the view.
        if (getWidth() > 0 && getColumnCount() > 0) {
            applyDynamicFontAfterLayout();
        } else {
            post(this::applyDynamicFontAfterLayout);
        }
    }

    public void setBaseFontSizeSp(int sp) {
        if (mBaseFontSizeSp == sp) return;
        mBaseFontSizeSp = sp;
        mCachedFittedFontSp = -1f;
        post(this::applyDynamicFontAfterLayout);
    }

    /**
     * Turn landscape compaction on/off and pick the order of keys inside a folded row. The flag is
     * read by the next {@link #reload(ExtraKeysInfo, float)}; this method does not rebuild the grid
     * itself — the caller also decides when to reload. The fitted-font cache is dropped when the
     * layout really changes, since it was measured for the previous column count.
     *
     * @param active {@code true} to fold the rows, {@code false} to render the stored layout as-is
     * @param mode   order of keys inside a folded row, must not be {@code null}
     * @return {@code true} when a reload would build a different grid than the one on screen.
     *         Switching {@code mode} while the fold is off is not a change.
     */
    public boolean setLandscapeCompact(boolean active, @NonNull ExtraKeysCompaction.Mode mode) {
        // Compare with the grid on screen, not the previous request — see mBuiltCompactLandscape.
        final boolean layoutChanged = !mBuiltGrid
            || mBuiltCompactLandscape != active
            || (active && mBuiltCompactMode != mode);
        mCompactLandscape = active;
        mCompactMode = mode;
        if (!layoutChanged) return false;
        mCachedFittedFontSp = -1f;
        return true;
    }

    /** @return {@code true} if the next {@link #reload(ExtraKeysInfo, float)} will fold the rows. */
    public boolean isLandscapeCompactActive() {
        return mCompactLandscape;
    }

    /** @return the order of keys inside a folded row. */
    @NonNull
    public ExtraKeysCompaction.Mode getCompactMode() {
        return mCompactMode;
    }

    /**
     * @return the row count the grid was actually built with by the last successful
     *         {@link #reload(ExtraKeysInfo, float)} — i.e. after compaction, not the stored count.
     */
    public int getReloadedRowCount() {
        return mLastReloadedRowCount;
    }

    public void setButtonMargins(float dp) {
        mButtonMarginHorizontalDp = dp;
        mButtonMarginVerticalDp = dp;
    }

    /**
     * Control whether the first row of buttons keeps its configured top margin.
     * <p>
     * When enabled and a layout is already loaded the existing buttons have their top
     * margins re-applied immediately; otherwise the next {@link #reload(ExtraKeysInfo, float)}
     * picks it up.
     *
     * @param enabled {@code true} keeps the first row's top margin (e.g. tabs sit above the
     *                panel), {@code false} drops it so the panel's top border touches the buttons.
     */
    public void setTopMarginEnabled(boolean enabled) {
        if (mTopMarginEnabled == enabled) return;
        mTopMarginEnabled = enabled;
        int cols = getColumnCount();
        if (cols <= 0) return;
        int marginVerticalPx = marginVerticalPx();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                continue;
            }
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            int row = i / cols;
            lp.topMargin = (mTopMarginEnabled && row == 0) ? marginVerticalPx : 0;
            child.setLayoutParams(lp);
        }
        requestLayout();
    }

    /**
     * Apply margins for one button. Only the trailing side of each button carries the margin
     * (symmetric margins would double the gap, since GridLayout adds facing margins of neighbours);
     * the first row keeps its top margin only when top-margin mode is enabled.
     */
    private void applyButtonMargins(GridLayout.LayoutParams param, int row, int col, int rows, int cols) {
        int marginHorizontalPx = marginHorizontalPx();
        int marginVerticalPx = marginVerticalPx();
        int left = 0;
        int right = (col == cols - 1) ? 0 : marginHorizontalPx;
        int top = (row == 0 && mTopMarginEnabled) ? marginVerticalPx : 0;
        int bottom = (row == rows - 1) ? 0 : marginVerticalPx;
        param.setMargins(left, top, right, bottom);
    }

    private int marginHorizontalPx() {
        return (int) (mButtonMarginHorizontalDp * mDensity);
    }

    private int marginVerticalPx() {
        return (int) (mButtonMarginVerticalDp * mDensity);
    }

    private int computeButtonWidthPx() {
        return getWidth() / getColumnCount() - marginHorizontalPx();
    }

    private int computeMaxLines(MaterialButton button, int availableHeightPx) {
        mMeasPaint.setTypeface(button.getPaint().getTypeface());
        mMeasPaint.setTextSize(button.getTextSize());
        int lineHeight = mMeasPaint.getFontMetricsInt(null);
        float lineSpacing = button.getLineSpacingMultiplier();
        if (lineSpacing > 0f) lineHeight = (int) (lineHeight * lineSpacing);
        lineHeight += (int) button.getLineSpacingExtra();
        return Math.max(1, availableHeightPx / Math.max(1, lineHeight));
    }

    public void requestDynamicFontUpdate() {
        post(this::applyDynamicFontAfterLayout);
    }

    /** Get {@link #mLongPressTimeout}. */
    public int getLongPressTimeout() {
        return mLongPressTimeout;
    }

    /** Set {@link #mLongPressTimeout}. */
    public void setLongPressTimeout(int longPressDuration) {
        if (longPressDuration >= MIN_LONG_PRESS_DURATION && longPressDuration <= MAX_LONG_PRESS_DURATION) {
            mLongPressTimeout = longPressDuration;
        } else {
            mLongPressTimeout = FALLBACK_LONG_PRESS_DURATION;
        }
    }

    /** Set {@link #mLongPressRepeatDelay}. */
    public void setLongPressRepeatDelay(int longPressRepeatDelay) {
        if (longPressRepeatDelay >= MIN_LONG_PRESS__REPEAT_DELAY && longPressRepeatDelay <= MAX_LONG_PRESS__REPEAT_DELAY) {
            mLongPressRepeatDelay = longPressRepeatDelay;
        } else {
            mLongPressRepeatDelay = DEFAULT_LONG_PRESS_REPEAT_DELAY;
        }
    }

    /** Set {@link #mSpecialButtonMode}. Must not be {@code null}. */
    public void setSpecialButtonMode(@NonNull SpecialButtonMode specialButtonMode) {
        mSpecialButtonMode = specialButtonMode;
    }

    /** Get the default map that can be used for {@link #mSpecialButtons}. */
    @NonNull
    public ArrayMap<SpecialButton, SpecialButtonState> getDefaultSpecialButtons(SpecialButtonStateOwner owner) {
        ArrayMap<SpecialButton, SpecialButtonState> map = new ArrayMap<>(4);
        map.put(SpecialButton.CTRL, new SpecialButtonState(owner));
        map.put(SpecialButton.ALT, new SpecialButtonState(owner));
        map.put(SpecialButton.SHIFT, new SpecialButtonState(owner));
        map.put(SpecialButton.FN, new SpecialButtonState(owner));
        return map;
    }

    /**
     * Reload this instance of {@link ExtraKeysView} with the info passed in {@code extraKeysInfo}.
     *
     * @param extraKeysInfo The {@link ExtraKeysInfo} that defines the necessary info for the extra keys.
     * @param heightPx The height in pixels of the parent surrounding the {@link ExtraKeysView}. It must
     *                 be a single child.
     */
    @SuppressLint("ClickableViewAccessibility")
    public void reload(ExtraKeysInfo extraKeysInfo, float heightPx) {
        if (extraKeysInfo == null)
            return;

        stopScheduledExecutors();
        mButtonPool.clear();
        mOriginalButtonTexts.clear();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof MaterialButton) {
                mButtonPool.add((MaterialButton) child);
            }
        }

        for(SpecialButtonState state : mSpecialButtons.values())
            state.buttons = new ArrayList<>();

        removeAllViews();

        // Re-read the density here as well as in the constructor: a display-size change does not
        // recreate this view (see the field), and both the margins below and the fitted font depend
        // on it.
        mDensity = getResources().getDisplayMetrics().density;

        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        mButtonCornerRadiusDp = props != null ? props.getExtraKeysCornerRadius() : BUTTON_CORNER_RADIUS_DP;
        mButtonMarginHorizontalDp = props != null ? props.getExtraKeysButtonMargin() : BUTTON_MARGIN_HORIZONTAL_DP;
        mButtonMarginVerticalDp = mButtonMarginHorizontalDp;
        mBaseFontSizeSp = props != null ? props.getExtraKeysFontSize() : 14;

        ExtraKeyButton[][] buttons = extraKeysInfo.getMatrix();

        // Landscape compaction: fold the stored rows into one or two rows. Purely positional — the
        // cells themselves are the same objects in the same reading order, only the row breaks move.
        // When there is nothing to fold (already one/two rows, or a matrix with no cells at all) the
        // stored layout is used as-is.
        if (mCompactLandscape) {
            ExtraKeyButton[][] compacted = ExtraKeysCompaction.compact(buttons, mCompactMode);
            if (compacted != null) buttons = compacted;
        }
        mLastReloadedRowCount = buttons.length;

        setRowCount(buttons.length);
        setColumnCount(maximumLength(buttons));

        int maxCols = getColumnCount();
        for (int row = 0; row < buttons.length; row++) {
            for (int col = 0; col < buttons[row].length; col++) {
                final ExtraKeyButton buttonInfo = buttons[row][col];

                MaterialButton button;
                if (isSpecialButton(buttonInfo)) {
                    button = createSpecialButton(buttonInfo.getKey(), true);
                    if (button == null) {
                        // The grid was torn down and only partly rebuilt — what is on screen is not
                        // a grid, so the next fold decision must answer "rebuild me".
                        mBuiltGrid = false;
                        return;
                    }
                } else if (!mButtonPool.isEmpty()) {
                    button = mButtonPool.remove(0);
                    button.setText(null);
                    button.setOnClickListener(null);
                    button.setOnTouchListener(null);
                    button.setTag(null);
                    button.setTag(TAG_EXTRA_KEY_INFO, null);
                    button.setPressed(false);
                } else {
                    button = createDefaultMaterialButton(getContext());
                }
                String originalDisplay = buttonInfo.getDisplay();
                String displayText = getDisplayTextForCurrentCapsMode(originalDisplay);
                mOriginalButtonTexts.put(button, originalDisplay);
                button.setText(displayText);
                button.setAllCaps(false);
                button.setTag(TAG_EXTRA_KEY_INFO, buttonInfo);
                // Initial font size — will be refined by applyDynamicFontAfterLayout() post-layout
                boolean isSingleChar = displayText != null && displayText.codePointCount(0, displayText.length()) == 1;
                boolean isMacroButton = displayText != null && (buttonInfo.isMacro() || buttonInfo.hasDelay());
                float initialFontSp;
                if (isSingleChar) {
                    initialFontSp = mBaseFontSizeSp;
                } else if (mDynamicFontSize && mCachedFittedFontSp > 0) {
                    initialFontSp = isMacroButton
                        ? Math.max(mCachedFittedFontSp - 2f, 8f)
                        : Math.max(mCachedFittedFontSp, 8f);
                } else {
                    initialFontSp = isMacroButton
                        ? Math.max(mBaseFontSizeSp - 2f, 8f)
                        : mBaseFontSizeSp;
                }
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, initialFontSp);

                if (isSingleChar) {
                    button.setMaxLines(1);
                    button.setSingleLine(true);
                } else {
                    // Multi-line with ellipsize (font size refined post-layout)
                    button.setSingleLine(false);
                    button.setHorizontallyScrolling(false);

                    // Calculate max lines from available height using the actual initial font size
                    int buttonH = (int) (heightPx + 0.5f) - marginVerticalPx();
                    int textAreaH = buttonH;

                    button.setMaxLines(computeMaxLines(button, textAreaH));
                    button.setEllipsize(TextUtils.TruncateAt.END);
                }
                applyButtonColors(button, false);
                button.setCornerRadius((int) (mButtonCornerRadiusDp * mDensity));

                button.setOnClickListener(view -> {
                    performExtraKeyButtonHapticFeedback(view, buttonInfo, button, false);
                    onAnyExtraKeyButtonClick(view, buttonInfo, button);
                });

                button.setOnTouchListener((view, event) -> {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            // Save touch start position for swipe detection
                            mTouchDownX = event.getX();
                            mTouchDownY = event.getY();
                            mRuntimeSwipeDirection = null;

                            button.setBackgroundTintList(mButtonActiveBgTint);

                            // In HOLD mode a special button activates immediately on touch and stays
                            // active only while held. There is no long-press competition, so we do not
                            // start any scheduled executors and just engage the hold.
                            if (isHoldModeSpecialButton(buttonInfo)) {
                                SpecialButtonState holdState = getSpecialButtonState(buttonInfo);
                                if (holdState != null) {
                                    holdState.setIsActive(true);
                                    holdState.setIsHolding(true);
                                }
                                return true;
                            }
                            // Start long press scheduled executors which will be stopped in next MotionEvent
                            startScheduledExecutors(view, buttonInfo, button);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            // If a swipe was already detected, no further processing needed
                            if (mRuntimeSwipeDirection != null) return true;

                            // Check for 4-direction swipe: compute displacement from touch down
                            float dx = event.getX() - mTouchDownX;
                            float dy = event.getY() - mTouchDownY;
                            SwipeDirection swipeDir = detectDirection(dx, dy);
                            if (swipeDir != null && getSwipeExtraKeyButton(buttonInfo, swipeDir) != null) {
                                mRuntimeSwipeDirection = swipeDir;
                                invalidate();
                                stopScheduledExecutors();
                                button.setBackgroundTintList(mButtonBgTint);
                                // If in HOLD mode, end the hold since the swipe takes priority
                                if (isHoldModeSpecialButton(buttonInfo)) {
                                    endSpecialButtonHold(buttonInfo);
                                }
                                // Fire action — identical to button tap (onClick)
                                ExtraKeyButton swipeBtn = getSwipeExtraKeyButton(buttonInfo, swipeDir);
                                mGestureActiveButton = swipeBtn;
                                mGestureActiveMaterialButton = button;
                                mGestureActiveView = view;
                                onAnyExtraKeyButtonClick(view, swipeBtn, button);
                                performExtraKeyButtonHapticFeedback(view, swipeBtn, button, true);
                                // Start repeat for swipe target if it's a repetitive key (UP/DOWN/LEFT/RIGHT/etc)
                                if (mRepetitiveKeys.contains(swipeBtn.getKey())) {
                                    mLongPressCount = 0;
                                    startRepetitiveRepeat(view, swipeBtn, button);
                                }
                                return true;
                            }

                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            mRuntimeSwipeDirection = null;
                            invalidate();
                            button.setBackgroundTintList(mButtonBgTint);
                            stopScheduledExecutors();
                            // Gesture cleanup on cancel (e.g. parent stole the touch)
                            releaseGesture(view, button);
                            // Handle HOLD mode for base button after gesture cleanup
                            if (isHoldModeSpecialButton(buttonInfo)) {
                                endSpecialButtonHold(buttonInfo);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            button.setBackgroundTintList(mButtonBgTint);
                            stopScheduledExecutors();

                            // Swipe has priority over HOLD mode: release gesture first,
                            // then handle HOLD for the base button if needed.
                            if (mRuntimeSwipeDirection != null) {
                                mRuntimeSwipeDirection = null;
                                invalidate();
                                releaseGesture(null, null);
                                if (isHoldModeSpecialButton(buttonInfo)) {
                                    endSpecialButtonHold(buttonInfo);
                                }
                                return true;
                            }

                            // In HOLD mode a special button deactivates on release (no swipe)
                            if (isHoldModeSpecialButton(buttonInfo)) {
                                endSpecialButtonHold(buttonInfo);
                                return true;
                            }

                            // Also check for swipe at release time (may have crossed threshold during liftoff)
                            if (buttonInfo != null) {
                                float upDx = event.getX() - mTouchDownX;
                                float upDy = event.getY() - mTouchDownY;
                                SwipeDirection upSwipe = detectDirection(upDx, upDy);
                                if (upSwipe != null) {
                                    ExtraKeyButton swipeBtn = getSwipeExtraKeyButton(buttonInfo, upSwipe);
                                    if (swipeBtn != null) {
                                        mRuntimeSwipeDirection = upSwipe;
                                        if (isHoldModeSpecialButton(buttonInfo)) {
                                            endSpecialButtonHold(buttonInfo);
                                        }
                                        // Finger already up — fire action then release immediately
                                        onAnyExtraKeyButtonClick(view, swipeBtn, button);
                                        if (mExtraKeysViewClient != null) {
                                            mExtraKeysViewClient.onExtraKeyButtonGestureRelease(view, swipeBtn, button);
                                        }
                                        performExtraKeyButtonHapticFeedback(view, swipeBtn, button, true);
                                        return true;
                                    }
                                }
                            }

                            // If not a long-press repeat, perform normal click
                            if (mLongPressCount == 0) {
                                view.performClick();
                            }
                            return true;

                        default:
                            return true;
                    }
                });

                                // ── Runtime swipe edge indicator tag ──
                                // Store swipe-target info so dispatchDraw can draw direction indicators
                                // and check modifier lock state without re-parsing the ExtraKeyButton tree.
                                {
                                    ExtraKeyButton su = buttonInfo.getSwipeUp();
                                    ExtraKeyButton sd = buttonInfo.getSwipeDown();
                                    ExtraKeyButton sl = buttonInfo.getSwipeLeft();
                                    ExtraKeyButton sr = buttonInfo.getSwipeRight();
                                    int sf = 0;
                                    if (su != null) sf |= 1;
                                    if (sd != null) sf |= 2;
                                    if (sl != null) sf |= 4;
                                    if (sr != null) sf |= 8;
                                    if (sf != 0) {
                                        button.setTag(new RuntimeEdgeInfo(sf, su, sd, sl, sr));
                                    }
                                }

                LayoutParams param = new GridLayout.LayoutParams();
                param.width = 0;
                param.height = 0;
                applyButtonMargins(param, row, col, buttons.length, maxCols);
                param.columnSpec = GridLayout.spec(col, GridLayout.FILL, 1.f);
                param.rowSpec = GridLayout.spec(row, GridLayout.FILL, 1.f);
                button.setLayoutParams(param);

                addView(button);
            }
        }

        // Drop the fit record (measured for the previous grid) rather than measuring here: this runs
        // before the window has been re-laid out, so a measurement now would divide the OLD width by
        // the NEW column count. onLayout() measures against the real size. The cached value itself is
        // kept so the first frame does not jump.
        invalidateFittedFont();

        // Record the fold state the grid on screen was built with; the next setLandscapeCompact()
        // compares a new request against this, not against the requested flag.
        mBuiltCompactLandscape = mCompactLandscape;
        mBuiltCompactMode = mCompactMode;
        mBuiltGrid = true;
    }

    /**
     * Forget which grid and size the cached fit was measured for, so the next layout pass re-measures.
     *
     * <p>The cached font size itself is left alone — see {@link #reload(ExtraKeysInfo, float)}.
     */
    private void invalidateFittedFont() {
        mFittedFontWidth = 0;
        mFittedFontHeight = 0;
        mFittedFontColumns = 0;
        mFittedFontRows = 0;
    }

    /** Remember the grid and the size the last dynamic-font/truncation pass was computed for. */
    private void recordFittedFontState() {
        mFittedFontWidth = getWidth();
        mFittedFontHeight = getHeight();
        mFittedFontColumns = getColumnCount();
        mFittedFontRows = getRowCount();
    }

    /**
     * Whether the last pass was computed for the grid and the size that are on screen right now. A
     * zeroed record never matches, because a built grid has at least one column and one row.
     */
    private boolean isFittedFontCurrent() {
        return mFittedFontColumns > 0
            && mFittedFontColumns == getColumnCount()
            && mFittedFontRows == getRowCount()
            && mFittedFontWidth == getWidth()
            && mFittedFontHeight == getHeight();
    }

    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (mExtraKeysViewClient != null)
            mExtraKeysViewClient.onExtraKeyButtonClick(view, buttonInfo, button);
    }

    public void performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button, boolean isGesture) {
        // All haptic settings are read from the cached fields (see refreshHapticState()) so that
        // no Binder IPC to SettingsProvider happens on the per-press hot path.
        if (mHapticMode == TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_OFF) return;
        if (!isGesture && mHapticMode == TermuxPropertyConstants.IVALUE_EXTRA_KEYS_HAPTIC_GESTURES) return;

        if (mExtraKeysViewClient != null) {
            if (mExtraKeysViewClient.performExtraKeyButtonHapticFeedback(view, buttonInfo, button))
                return;
        }

        if (mHapticFeedbackEnabled) {
            // On API < 28 suppress feedback only in total-silence (zen_mode == 2) mode.
            if (Build.VERSION.SDK_INT >= 28 || mZenModeAllowsSound) {
                button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
    }

    /**
     * Refresh the cached haptic state from {@link TermuxAppSharedProperties} and the system
     * {@link Settings}. Called on attach and on every {@code reloadActivityStyling} so that the
     * per-press {@link #performExtraKeyButtonHapticFeedback} never performs Binder IPC itself.
     */
    public void refreshHapticState() {
        TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
        mHapticMode = props != null ? props.getExtraKeysHaptic()
            : TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_HAPTIC;
        ContentResolver resolver = getContext().getContentResolver();
        mHapticFeedbackEnabled = Settings.System.getInt(resolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0;
        if (Build.VERSION.SDK_INT < 28) {
            // Use the literal setting name: Settings.Global.ZEN_MODE is not exposed by the SDK stub
            // this project compiles against, whereas the "zen_mode" key is stable across versions.
            mZenModeAllowsSound = Settings.Global.getInt(resolver, "zen_mode", 0) != 2;
        }
    }

    /** Register a ContentObserver that refreshes the haptic cache when system haptic/zen settings change. */
    private void registerHapticObserver() {
        if (mHapticObserver != null) return;
        mHapticObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                refreshHapticState();
            }
        };
        ContentResolver resolver = getContext().getContentResolver();
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), false, mHapticObserver);
        if (Build.VERSION.SDK_INT < 28) {
            resolver.registerContentObserver(
                Settings.Global.getUriFor("zen_mode"), false, mHapticObserver);
        }
    }

    /** Unregister the haptic ContentObserver. */
    private void unregisterHapticObserver() {
        if (mHapticObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mHapticObserver);
            mHapticObserver = null;
        }
    }

    public void onAnyExtraKeyButtonClick(View view, @NonNull ExtraKeyButton buttonInfo, MaterialButton button) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressCount > 0) return;
            // In HOLD mode the special button is driven entirely by touch events, so a click
            // (which would normally toggle) must not interfere with the hold state.
            if (mSpecialButtonMode == SpecialButtonMode.HOLD) return;
            SpecialButtonState state = getSpecialButtonState(buttonInfo);
            if (state == null) return;

            // Toggle active state and disable lock state if new state is not active
            state.setIsActive(!state.isActive);
            if (!state.isActive)
                state.setIsLocked(false);
        } else {
            onExtraKeyButtonClick(view, buttonInfo, button);
        }
    }

    public void startScheduledExecutors(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        stopScheduledExecutors();
        mLongPressCount = 0;
        if (mRepetitiveKeys.contains(buttonInfo.getKey())) {
            // Auto repeat key if long pressed until ACTION_UP stops it by calling stopScheduledExecutors.
            // Currently, only one (last) repeat key can run at a time. Old ones are stopped.
            startRepetitiveRepeat(view, buttonInfo, button);
        } else if (isSpecialButton(buttonInfo)) {
            // Lock the key if long pressed by running mSpecialButtonsLongHoldRunnable after
            // waiting for mLongPressTimeout milliseconds. If user does not long press, then the
            // ACTION_UP triggered will cancel the runnable by calling stopScheduledExecutors before
            // it has a chance to run.
            SpecialButtonState state = getSpecialButtonState(buttonInfo);
            if (state == null) return;
            ensureHandler();
            mSpecialButtonsLongHoldRunnable = new SpecialButtonsLongHoldRunnable(state);
            mHandler.postDelayed(mSpecialButtonsLongHoldRunnable, mLongPressTimeout);
        }
    }

    /**
     * Schedule the auto-repeat runnable on the main thread. The first fire happens after
     * {@link #mLongPressTimeout}; every subsequent fire repeats every {@link #mLongPressRepeatDelay}.
     * Because everything runs on the UI thread, repeated terminal input goes through the normal
     * UI path instead of being written from a background pool thread.
     */
    private void startRepetitiveRepeat(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        ensureHandler();
        mRepetitiveRunnable = new Runnable() {
            @Override
            public void run() {
                mLongPressCount++;
                onExtraKeyButtonClick(view, buttonInfo, button);
                if (mHandler != null)
                    mHandler.postDelayed(this, mLongPressRepeatDelay);
            }
        };
        mHandler.postDelayed(mRepetitiveRunnable, mLongPressTimeout);
    }

    public void stopScheduledExecutors() {
        if (mRepetitiveRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mRepetitiveRunnable);
            mRepetitiveRunnable = null;
        }

        if (mSpecialButtonsLongHoldRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mSpecialButtonsLongHoldRunnable);
            mSpecialButtonsLongHoldRunnable = null;
        }
    }

    /**
     * Deactivate a special button that was engaged in {@link SpecialButtonMode#HOLD} mode because the
     * finger was released (or the touch was cancelled). Only affects buttons currently held.
     *
     * @param buttonInfo The {@link ExtraKeyButton} for the special button being released.
     */
    private void endSpecialButtonHold(ExtraKeyButton buttonInfo) {
        SpecialButtonState state = getSpecialButtonState(buttonInfo);
        if (state == null) return;
        if (state.isHolding) {
            state.setIsHolding(false);
            state.setIsActive(false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshHapticState();
        registerHapticObserver();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScheduledExecutors();
        unregisterHapticObserver();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
    }

    public class SpecialButtonsLongHoldRunnable implements Runnable {
        public final SpecialButtonState mState;

        public SpecialButtonsLongHoldRunnable(SpecialButtonState state) {
            mState = state;
        }

        public void run() {
            mState.setIsLocked(!mState.isActive);
            mState.setIsActive(!mState.isActive);
            mLongPressCount++;
        }
    }

    /** Check whether a {@link ExtraKeyButton} is a {@link SpecialButton}. */
    public boolean isSpecialButton(ExtraKeyButton button) {
        return mSpecialButtonsKeys.contains(button.getKey());
    }

    private boolean isHoldModeSpecialButton(@Nullable ExtraKeyButton button) {
        return mSpecialButtonMode == SpecialButtonMode.HOLD && isSpecialButton(button);
    }

    @Nullable
    private SpecialButtonState getSpecialButtonState(@NonNull ExtraKeyButton buttonInfo) {
        return mSpecialButtons.get(SpecialButton.valueOf(buttonInfo.getKey()));
    }

    private void ensureHandler() {
        if (mHandler == null)
            mHandler = new Handler(Looper.getMainLooper());
    }

    private void releaseGesture(@Nullable View fallbackView, @Nullable MaterialButton fallbackButton) {
        if (mGestureActiveButton != null && mExtraKeysViewClient != null) {
            mExtraKeysViewClient.onExtraKeyButtonGestureRelease(
                    mGestureActiveView != null ? mGestureActiveView : fallbackView,
                    mGestureActiveButton,
                    mGestureActiveMaterialButton != null ? mGestureActiveMaterialButton : fallbackButton);
        }
        mGestureActiveButton = null;
        mGestureActiveMaterialButton = null;
        mGestureActiveView = null;
    }

    /**
     * Read whether {@link SpecialButton} registered in {@link #mSpecialButtons} is active or not.
     *
     * @param specialButton The {@link SpecialButton} to read.
     * @param autoSetInActive Set to {@code true} if {@link SpecialButtonState#isActive} should be
     *                        set {@code false} if button is not locked.
     * @return Returns {@code null} if button does not exist in {@link #mSpecialButtons}. If button
     *         exists, then returns {@code true} if the button is created in {@link ExtraKeysView}
     *         and is active, otherwise {@code false}.
     */
    @Nullable
    public Boolean readSpecialButton(SpecialButton specialButton, boolean autoSetInActive) {
        SpecialButtonState state = mSpecialButtons.get(specialButton);
        if (state == null) return null;

        if (!state.isCreated || !state.isActive)
            return false;

        // Disable active state only if not locked and not currently held down
        if (autoSetInActive && !state.isLocked && !state.isHolding)
            state.setIsActive(false);

        return true;
    }
    private String getDisplayTextForCurrentCapsMode(@Nullable String originalText) {
        if (originalText == null) return "";
        if (!mButtonTextAllCaps) return originalText;
        return originalText.toUpperCase(Locale.ROOT);
    }

    /**
     * Create a MaterialButton configured with zero internal padding/insets.
     * This ensures the text area equals the button area for accurate multi-line
     * measurement. Boilerplate is defined once here instead of at every creation site.
     */
    static MaterialButton createDefaultMaterialButton(Context context) {
        MaterialButton button = new MaterialButton(context, null, android.R.attr.buttonBarButtonStyle);
        button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setIncludeFontPadding(false);
        return button;
    }

    public MaterialButton createSpecialButton(String buttonKey, boolean needUpdate) {
        SpecialButtonState state = mSpecialButtons.get(SpecialButton.valueOf(buttonKey));
        if (state == null) return null;
        state.setIsCreated(true);
        MaterialButton button = createDefaultMaterialButton(getContext());
        applyButtonColors(button, state.isActive);
        button.setCornerRadius((int) (mButtonCornerRadiusDp * mDensity));
        if (needUpdate) {
            state.buttons.add(button);
        }
        return button;
    }

    @Nullable
    private static ExtraKeyButton getSwipeExtraKeyButton(ExtraKeyButton buttonInfo, SwipeDirection direction) {
        if (buttonInfo == null || direction == null) return null;
        switch (direction) {
            case UP:    return buttonInfo.getSwipeUp();
            case DOWN:  return buttonInfo.getSwipeDown();
            case LEFT:  return buttonInfo.getSwipeLeft();
            case RIGHT: return buttonInfo.getSwipeRight();
        }
        return null;
    }

    // ── SpecialButtonStateOwner implementation ──

    @Override
    public void invalidateView() {
        invalidate();
    }

    /** Set the editor gesture listener. Non-null activates editor mode. */
    public void setEditorGestureListener(@Nullable EditorGestureListener listener) {
        mEditorListener = listener;
    }

    /** Set the editor edge indicator color and enable edge indicators. */
    public void setEditorEdgeColor(int color) {
        mEditorEdgeColor = color;
        mEditorEdgeIndicatorsEnabled = true;
    }

    /** Enable or disable editor (tag-based) edge indicators. */
    public void setEditorEdgeIndicatorsEnabled(boolean enabled) {
        mEditorEdgeIndicatorsEnabled = enabled;
        invalidate();
    }

    /** Enable or disable runtime swipe-direction edge indicators. */
    public void setRuntimeEdgeIndicatorsEnabled(boolean enabled) {
        mRuntimeEdgeIndicatorsEnabled = enabled;
        invalidate();
    }

    /** Set the editor interaction mode. */
    public void setEditorMode(@NonNull EditorMode mode) {
        mEditorMode = mode;
    }

    /** Set the listener for move-mode drag-and-drop operations. */
    public void setEditorMoveListener(@Nullable EditorMoveListener listener) {
        mEditorMoveListener = listener;
    }

    /** Set the long-press listener (fires on a held press released without movement). */
    public void setEditorLongPressListener(@Nullable EditorLongPressListener listener) {
        mEditorLongPressListener = listener;
    }

    /**
     * Binary search to find the largest font size (in sp, rounded to int) where
     * {@code testString} fits within {@code maxWidthPx}.
     */
    static float findFittingFontSizeSp(
            @NonNull TextPaint paint,
            @NonNull String testString,
            float maxWidthPx,
            float maxSp,
            float minSp,
            @NonNull DisplayMetrics metrics
    ) {
        if (maxWidthPx <= 0 || minSp >= maxSp) return minSp;

        int lo = (int) minSp;
        int hi = (int) maxSp;

        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            float midPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, mid, metrics);
            paint.setTextSize(midPx);
            if (paint.measureText(testString) <= maxWidthPx) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return lo;
    }

    /**
     * Called via {@code post()} after a layout pass, after {@link #reload(ExtraKeysInfo, float)},
     * or from a setter that changes an input of the fit. Picks a font size so "WWWW" fits in every
     * multi-character button, then runs macro-text truncation. Only reached from a post-layout
     * point, because everything it reads must belong to the same layout (see {@code onLayout()}).
     */
    private void applyDynamicFontAfterLayout() {
        if (getWidth() <= 0 || getColumnCount() <= 0) {
            post(this::applyDynamicFontAfterLayout);
            return;
        }

        if (!mDynamicFontSize) {
            applyMacroTruncationAfterLayout();
            recordFittedFontState();
            return;
        }

        // Each non-trailing button loses one (trailing) margin; the narrowest cell is cellW - margin.
        int buttonW = computeButtonWidthPx();
        if (buttonW <= 0) {
            applyMacroTruncationAfterLayout();
            return;
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float maxFontSp = mBaseFontSizeSp;
        float minFontSp = 8f;
        String testString = "WWWW";

        // Pick a representative typeface from the first button
        Typeface typeface = Typeface.DEFAULT;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof MaterialButton) {
                typeface = ((MaterialButton) child).getPaint().getTypeface();
                break;
            }
        }
        mMeasPaint.setTypeface(typeface);

        float fittedFontSp = findFittingFontSizeSp(
                mMeasPaint, testString, buttonW,
                maxFontSp, minFontSp, metrics);
        mCachedFittedFontSp = fittedFontSp;
        // Record what this pass was computed for — the size and the grid — so that a later change to
        // either is recognised as "this no longer describes what is on screen" and re-measured.
        recordFittedFontState();

        // Button height for maxLines recalculation
        int cellH = getRowCount() > 0 ? getHeight() / getRowCount() : 0;
        int buttonH = cellH - marginVerticalPx();
        if (buttonH < 1) buttonH = 1;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof MaterialButton)) continue;
            MaterialButton button = (MaterialButton) child;
            CharSequence text = button.getText();
            if (text == null || text.length() == 0) continue;

            String display = text.toString();
            int codePointCount = display.codePointCount(0, display.length());

            if (codePointCount <= 1) {
                // Single-char: keep full base font size
                button.setTextSize(TypedValue.COMPLEX_UNIT_SP, mBaseFontSizeSp);
                button.setMaxLines(1);
                button.setSingleLine(true);
                continue;
            }

            // Multi-char: macro buttons get -2sp extra breathing room
            boolean isMacroButton = false;
            Object tagInfo = button.getTag(TAG_EXTRA_KEY_INFO);
            if (tagInfo instanceof ExtraKeyButton)
                isMacroButton = ((ExtraKeyButton) tagInfo).isMacro() || ((ExtraKeyButton) tagInfo).hasDelay();
            float actualFontSp = isMacroButton ? Math.max(fittedFontSp - 2f, minFontSp) : fittedFontSp;

            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, actualFontSp);

            // Recalculate maxLines based on actual font size
            button.setMaxLines(computeMaxLines(button, buttonH));
        }

        // Macro-text truncation uses the newly set font sizes
        applyMacroTruncationAfterLayout();
    }

    private void applyMacroTruncationAfterLayout() {
        if (getWidth() <= 0 || getColumnCount() <= 0) return;
        int buttonW = computeButtonWidthPx();
        if (buttonW <= 0) return;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof MaterialButton)) continue;
            MaterialButton button = (MaterialButton) child;
            CharSequence text = button.getText();
            if (text == null || text.length() == 0) continue;
            String display = text.toString();
            if (!display.contains(" ")) continue;

            int maxLines = button.getMaxLines();
            if (maxLines <= 0) continue;

            mMeasPaint.setTypeface(button.getPaint().getTypeface());
            mMeasPaint.setTextSize(button.getTextSize());

            String truncated = truncateMacroText(display, mMeasPaint, buttonW, maxLines);
            if (truncated != null) {
                button.setText(truncated);
                button.setEllipsize(null);
            }
        }
    }

    /**
     * First point at which the grid dimensions and this view's size describe the same layout: a
     * measurement between {@code reload()} (which changes the column count) and this pass would
     * divide the old width by the new column count. Posted rather than run inline because setting
     * a child's text size re-requests layout; the runnable reads final bounds.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Nothing to do when the last pass was computed for exactly this grid and size. With the
        // dynamic font off there is no fit to refresh, but the macro-text truncation depends on the
        // cell width too, and applyDynamicFontAfterLayout() is the entry point that runs it.
        if (isFittedFontCurrent()) return;

        post(this::applyDynamicFontAfterLayout);
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mEditorEdgeIndicatorsEnabled && mEditorListener != null) {

        float thickness = EDITOR_EDGE_THICKNESS_DP * mDensity;
        float halfThick = thickness / 2f;
        float cornerPx = edgeCornerRadiusPx();

        mEditorEdgePaint.setColor(mEditorEdgeColor);
        configureEdgePaint(mEditorEdgePaint, thickness);

        mEditorPath.rewind();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof int[])) continue;
            int[] arr = (int[]) tag;
            if (arr.length < 3) continue;
            int flags = arr[2];

            float l = child.getLeft();
            float t = child.getTop();
            float r = child.getRight();
            float b = child.getBottom();

            float effectiveCornerPx = effectiveCornerRadiusPx(l, t, r, b, cornerPx);
            float effectiveCenterRadius = Math.max(0f, effectiveCornerPx - halfThick);

            if (effectiveCenterRadius <= 0f) {
                mEditorEdgePaint.setStyle(Paint.Style.FILL);
                mEditorEdgePaint.setStrokeWidth(0);
                if ((flags & 1) != 0) { // top
                    canvas.drawRect(l + halfThick, t, r - halfThick, t + thickness, mEditorEdgePaint);
                }
                if ((flags & 2) != 0) { // bottom
                    canvas.drawRect(l + halfThick, b - thickness, r - halfThick, b, mEditorEdgePaint);
                }
                if ((flags & 4) != 0) { // left
                    canvas.drawRect(l, t + halfThick, l + thickness, b - halfThick, mEditorEdgePaint);
                }
                if ((flags & 8) != 0) { // right
                    canvas.drawRect(r - thickness, t + halfThick, r, b - halfThick, mEditorEdgePaint);
                }
                mEditorEdgePaint.setStyle(Paint.Style.STROKE);
                mEditorEdgePaint.setStrokeWidth(thickness);
                continue;
            }

            if ((flags & 1) != 0) {
                drawTopEdgeArc(canvas, mEditorEdgePaint, l, t, r, b, effectiveCornerPx, effectiveCenterRadius);
            }
            if ((flags & 2) != 0) {
                drawBottomEdgeArc(canvas, mEditorEdgePaint, l, t, r, b, effectiveCornerPx, effectiveCenterRadius);
            }
            if ((flags & 4) != 0) {
                drawLeftEdgeArc(canvas, mEditorEdgePaint, l, t, r, b, effectiveCornerPx, effectiveCenterRadius);
            }
            if ((flags & 8) != 0) {
                drawRightEdgeArc(canvas, mEditorEdgePaint, l, t, r, b, effectiveCornerPx, effectiveCenterRadius);
            }
            }
        }
        drawRuntimeEdgeIndicators(canvas);
    }

    /**
     * Draw swipe-direction edge indicators in runtime mode: a thin STROKE path inset so the outer
     * stroke edge aligns with the child boundary (same geometry as the editor {@code dispatchDraw}).
     * Inactive edges use a luminance-shifted {@link #mButtonBackgroundColor}; active edges use
     * {@link #mButtonActiveBackgroundColor}. An edge is active when the gesture swiped on this
     * button in its direction, or the swipe target is a modifier in active/locked state.
     */
    private void drawRuntimeEdgeIndicators(Canvas canvas) {
        if (!mRuntimeEdgeIndicatorsEnabled) return;
        float thickness = RUNTIME_EDGE_THICKNESS_DP * mDensity;
        float halfThick = thickness / 2f;
        float cornerPx = edgeCornerRadiusPx();

        configureEdgePaint(mRuntimeEdgePaint, thickness);

        // Whether a swipe is currently happening and which child is the source
        boolean swipeActive = (mRuntimeSwipeDirection != null && mGestureActiveView != null);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof RuntimeEdgeInfo)) continue;
            RuntimeEdgeInfo info = (RuntimeEdgeInfo) tag;
            if (info.flags == 0) continue;

            float l = child.getLeft();
            float t = child.getTop();
            float r = child.getRight();
            float b = child.getBottom();

            float ecp = effectiveCornerRadiusPx(l, t, r, b, cornerPx);

            // INSET: stroke outer edge sits exactly at the child boundary.
            // Same geometry as the editor's dispatchDraw.
            float cr = Math.max(0f, ecp - halfThick);

            // Only the button currently being swiped lights up for the swipe direction
            boolean isSwipeSource = swipeActive && (child == mGestureActiveView);

            if (cr <= 0f) {
                // Corner too small for arcs — use FILL rects inset from edge
                mRuntimeEdgePaint.setStyle(Paint.Style.FILL);
                mRuntimeEdgePaint.setStrokeWidth(0);

                if ((info.flags & 1) != 0) {
                    mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.UP, isSwipeSource));
                    canvas.drawRect(l, t, r, t + thickness, mRuntimeEdgePaint);
                }
                if ((info.flags & 2) != 0) {
                    mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.DOWN, isSwipeSource));
                    canvas.drawRect(l, b - thickness, r, b, mRuntimeEdgePaint);
                }
                if ((info.flags & 4) != 0) {
                    mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.LEFT, isSwipeSource));
                    canvas.drawRect(l, t, l + thickness, b, mRuntimeEdgePaint);
                }
                if ((info.flags & 8) != 0) {
                    mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.RIGHT, isSwipeSource));
                    canvas.drawRect(r - thickness, t, r, b, mRuntimeEdgePaint);
                }

                mRuntimeEdgePaint.setStyle(Paint.Style.STROKE);
                mRuntimeEdgePaint.setStrokeWidth(thickness);
                continue;
            }

            // TOP edge
            if ((info.flags & 1) != 0) {
                mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.UP, isSwipeSource));
                drawTopEdgeArc(canvas, mRuntimeEdgePaint, l, t, r, b, ecp, cr);
            }

            // BOTTOM edge
            if ((info.flags & 2) != 0) {
                mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.DOWN, isSwipeSource));
                drawBottomEdgeArc(canvas, mRuntimeEdgePaint, l, t, r, b, ecp, cr);
            }

            // LEFT edge
            if ((info.flags & 4) != 0) {
                mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.LEFT, isSwipeSource));
                drawLeftEdgeArc(canvas, mRuntimeEdgePaint, l, t, r, b, ecp, cr);
            }

            // RIGHT edge
            if ((info.flags & 8) != 0) {
                mRuntimeEdgePaint.setColor(edgeColor(info, SwipeDirection.RIGHT, isSwipeSource));
                drawRightEdgeArc(canvas, mRuntimeEdgePaint, l, t, r, b, ecp, cr);
            }
        }
    }

    private float edgeCornerRadiusPx() {
        float cornerPx = mButtonCornerRadiusDp * mDensity;
        if (cornerPx <= 0) cornerPx = 1f;
        return cornerPx;
    }

    private float effectiveCornerRadiusPx(float l, float t, float r, float b, float cornerPx) {
        float maxR = Math.min(r - l, b - t) / 2f;
        float ecp = Math.min(cornerPx, maxR);
        if (ecp <= 0) ecp = 1f;
        return ecp;
    }

    private void configureEdgePaint(Paint paint, float thickness) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thickness);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setAntiAlias(true);
    }

    private void drawTopEdgeArc(Canvas canvas, Paint paint, float l, float t, float r, float b, float ecp, float cr) {
        mEditorPath.reset();
        mEditorOval.set(l + ecp - cr, t + ecp - cr, l + ecp + cr, t + ecp + cr);
        mEditorPath.arcTo(mEditorOval, 230f, 40f, true);
        mEditorOval.set(r - ecp - cr, t + ecp - cr, r - ecp + cr, t + ecp + cr);
        mEditorPath.arcTo(mEditorOval, 270f, 40f, false);
        canvas.drawPath(mEditorPath, paint);
    }

    private void drawBottomEdgeArc(Canvas canvas, Paint paint, float l, float t, float r, float b, float ecp, float cr) {
        mEditorPath.reset();
        mEditorOval.set(r - ecp - cr, b - ecp - cr, r - ecp + cr, b - ecp + cr);
        mEditorPath.arcTo(mEditorOval, 50f, 40f, true);
        mEditorOval.set(l + ecp - cr, b - ecp - cr, l + ecp + cr, b - ecp + cr);
        mEditorPath.arcTo(mEditorOval, 90f, 40f, false);
        canvas.drawPath(mEditorPath, paint);
    }

    private void drawLeftEdgeArc(Canvas canvas, Paint paint, float l, float t, float r, float b, float ecp, float cr) {
        mEditorPath.reset();
        mEditorOval.set(l + ecp - cr, t + ecp - cr, l + ecp + cr, t + ecp + cr);
        mEditorPath.arcTo(mEditorOval, 220f, -40f, true);
        mEditorOval.set(l + ecp - cr, b - ecp - cr, l + ecp + cr, b - ecp + cr);
        mEditorPath.arcTo(mEditorOval, 180f, -40f, false);
        canvas.drawPath(mEditorPath, paint);
    }

    private void drawRightEdgeArc(Canvas canvas, Paint paint, float l, float t, float r, float b, float ecp, float cr) {
        mEditorPath.reset();
        mEditorOval.set(r - ecp - cr, b - ecp - cr, r - ecp + cr, b - ecp + cr);
        mEditorPath.arcTo(mEditorOval, 40f, -40f, true);
        mEditorOval.set(r - ecp - cr, t + ecp - cr, r - ecp + cr, t + ecp + cr);
        mEditorPath.arcTo(mEditorOval, 360f, -40f, false);
        canvas.drawPath(mEditorPath, paint);
    }

    /**
     * Resolve the color for a single edge indicator.
     *
     * @param isSwipeSource whether {@code info} belongs to the button
     *                       currently being swiped ({@link #mGestureActiveView}).
     */
    private int edgeColor(@NonNull RuntimeEdgeInfo info, @NonNull SwipeDirection dir,
                          boolean isSwipeSource) {
        // Active swipe on THIS button in THIS direction
        if (isSwipeSource && mRuntimeSwipeDirection == dir)
            return mButtonActiveBackgroundColor;

        // Modifier target in active/locked state (applies to all buttons)
        ExtraKeyButton target = info.target(dir);
        if (target != null && isSpecialButton(target)) {
            SpecialButtonState state = getSpecialButtonState(target);
            if (state != null && state.isActive)
                return mButtonActiveBackgroundColor;
        }

        return mButtonBackgroundColor;
    }

    @Nullable
    private SwipeDirection detectDirection(float dx, float dy) {
        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);
        if (Math.max(absDx, absDy) < mSwipeThreshold) return null;
        if (absDx > absDy * 1.5f) {
            return dx > 0 ? SwipeDirection.RIGHT : SwipeDirection.LEFT;
        } else if (absDy > absDx * 1.5f) {
            return dy > 0 ? SwipeDirection.DOWN : SwipeDirection.UP;
        } else {
            return null; // diagonal — ambiguous, reject
        }
    }

    @Nullable
    private View findChildAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            child.getHitRect(mHitRect);
            if (mHitRect.contains((int) x, (int) y)) return child;
        }
        return null;
    }

    private boolean isInsideView(@Nullable View view, float x, float y) {
        if (view == null) return false;
        view.getHitRect(mHitRect);
        return mHitRect.contains((int) x, (int) y);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mEditorListener != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    View child = findChildAt(ev.getX(), ev.getY());
                    if (child != null) {
                        mActiveChild = child;
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mActiveChild != null) return true;
                    return false;
            }
            return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent ev) {
        if (mEditorListener == null) {
            return super.onTouchEvent(ev);
        }
        return handleEditorGesture(ev);
    }

    private boolean handleEditorGesture(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (mActiveChild == null) return false;
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);

                mActivePointerId = ev.getPointerId(0);
                mDownX = ev.getX();
                mDownY = ev.getY();
                mGestureConsumed = false;
                mLpFired = false;
                scheduleLongPress(mActiveChild);

                mActiveChild.setPressed(true);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mActivePointerId == INVALID_POINTER_ID || mGestureConsumed) return true;

                // In MOVE mode, skip swipe detection — just track for drag destination
                if (mEditorMode == EditorMode.MOVE) return true;

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) return true;

                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                float dx = x - mDownX;
                float dy = y - mDownY;

                SwipeDirection direction = detectDirection(dx, dy);
                if (direction != null) {
                    // A swipe cancels the pending long press (it's a swipe, not a hold).
                    cancelEditorLongPress();
                    mEditorSwipeDir = direction;
                    invalidate();
                    fireSwipe(mActiveChild, direction);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mActivePointerId == INVALID_POINTER_ID) return true;

                // In MOVE mode, resolve drop target and fire move listener
                if (mEditorMode == EditorMode.MOVE) {
                    handleEditorMoveUp(ev);
                    resetTouchState();
                    return true;
                }

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (mLpFired) {
                    // Long press already fired while the finger was held — nothing more to do.
                    resetTouchState();
                    return true;
                }
                cancelEditorLongPress();
                if (pointerIndex >= 0 && !mGestureConsumed) {
                    float x = ev.getX(pointerIndex);
                    float y = ev.getY(pointerIndex);
                    float dx = x - mDownX;
                    float dy = y - mDownY;

                    SwipeDirection direction = detectDirection(dx, dy);
                    if (direction != null) {
                        mEditorSwipeDir = direction;
                        invalidate();
                        fireSwipe(mActiveChild, direction);
                    } else if (isInsideView(mActiveChild, x, y)) {
                        fireTap(mActiveChild);
                    }
                }

                resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                resetTouchState();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndexAction = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndexAction);
                if (pointerId == mActivePointerId) {
                    resetTouchState();
                }
                return true;
            }
        }
        return false;
    }

    private void fireTap(@Nullable View button) {
        int[] coord = cellCoordsOf(button);
        if (coord == null) return;

        final View fButton = button;
        final int row = coord[0];
        final int col = coord[1];

        button.post(() -> {
            if (!isAttachedToWindow() || mEditorListener == null) return;
            mEditorListener.onKeyTap(fButton, row, col);
        });
    }

    private void fireLongPress(@Nullable View button) {
        int[] coord = cellCoordsOf(button);
        if (coord == null) return;

        final View fButton = button;
        final int row = coord[0];
        final int col = coord[1];

        button.post(() -> {
            if (!isAttachedToWindow() || mEditorLongPressListener == null) return;
            mEditorLongPressListener.onKeyLongPress(fButton, row, col);
        });
    }

    private void fireSwipe(@Nullable View button, SwipeDirection direction) {
        int[] coord = cellCoordsOf(button);
        if (coord == null) return;

        final View fButton = button;
        final int row = coord[0];
        final int col = coord[1];
        final SwipeDirection fDir = direction;

        mGestureConsumed = true;

        button.setPressed(false);
        button.cancelLongPress();

        button.post(() -> {
            if (!isAttachedToWindow() || mEditorListener == null) return;
            mEditorListener.onKeySwipe(fButton, row, col, fDir);
        });
    }

    @Nullable
    private int[] cellCoordsOf(@Nullable View button) {
        if (button == null) return null;
        Object tag = button.getTag();
        if (!(tag instanceof int[])) return null;
        return (int[]) tag;
    }

    /** Resolve drop target on ACTION_UP in MOVE mode and fire EditorMoveListener. */
    private void handleEditorMoveUp(@NonNull MotionEvent ev) {
        if (mActiveChild == null || mEditorMoveListener == null) return;

        Object srcTag = mActiveChild.getTag();
        if (!(srcTag instanceof int[]) || ((int[]) srcTag).length < 2) return;
        int fromRow = ((int[]) srcTag)[0];
        int fromCol = ((int[]) srcTag)[1];

        int pointerIndex = ev.findPointerIndex(mActivePointerId);
        if (pointerIndex < 0) return;

        View target = findChildAt(ev.getX(pointerIndex), ev.getY(pointerIndex));
        if (target == null || target == mActiveChild) return;

        Object dstTag = target.getTag();
        if (!(dstTag instanceof int[]) || ((int[]) dstTag).length < 2) return;
        int toRow = ((int[]) dstTag)[0];
        int toCol = ((int[]) dstTag)[1];

        if (fromRow == toRow && fromCol == toCol) return;

        final int fR = fromRow, fC = fromCol, tR = toRow, tC = toCol;
        mActiveChild.post(() -> {
            if (!isAttachedToWindow() || mEditorMoveListener == null) return;
            mEditorMoveListener.onCellMove(fR, fC, tR, tC);
        });
    }

    private void resetTouchState() {
        cancelEditorLongPress();
        if (mActiveChild != null) {
            mActiveChild.setPressed(false);
        }
        mActiveChild = null;
        mActivePointerId = INVALID_POINTER_ID;
        mGestureConsumed = false;
        mEditorSwipeDir = null;
        mLpFired = false;

        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    /** Schedule a long press on {@code button} to fire after the system long-press timeout. */
    private void scheduleLongPress(@Nullable View button) {
        cancelEditorLongPress();
        // Long press is only meaningful in tap-like editor modes (not MOVE) with a listener.
        if (button == null || mEditorLongPressListener == null || mEditorMode == EditorMode.MOVE) {
            return;
        }
        final View fb = button;
        mLpRunnable = () -> {
            mLpRunnable = null;
            if (mLpFired) return;
            mLpFired = true;
            fireLongPress(fb);
        };
        postDelayed(mLpRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelEditorLongPress() {
        if (mLpRunnable != null) {
            removeCallbacks(mLpRunnable);
            mLpRunnable = null;
        }
    }

    public static int maximumLength(Object[][] matrix) {
        return ExtraKeysCompaction.maximumLength(matrix);
    }

    /**
     * Truncate a macro button's display text at bind boundaries.
     * Returns null if the full text fits (no truncation needed).
     * Returns a shortened string with "+..." suffix if truncation is needed.
     */
    private static String truncateMacroText(String fullText, TextPaint paint, int widthPx, int maxLines) {
        // Binds are joined with "+" in the composed display, so that is the boundary to cut at.
        // Splitting on whitespace would be wrong because a single bind may itself contain spaces
        // (a quoted literal token such as "ls -la").
        String[] binds = fullText.split("\\+");
        if (binds.length <= 1) return null;

        // Check if full text already fits
        if (fitsInLines(fullText, paint, widthPx, maxLines)) {
            return null;
        }

        // Linear scan from largest to smallest (optimal for typical 2-10 bind macros)
        for (int n = binds.length - 1; n >= 1; n--) {
            String candidate = joinBinds(binds, n) + "+...";
            if (fitsInLines(candidate, paint, widthPx, maxLines)) {
                return candidate;
            }
        }

        // Even first bind + "+..." doesn't fit — let Android handle truncation
        return null;
    }

    /**
     * Check if text fits within the given width in the given number of lines using StaticLayout.
     */
    private static boolean fitsInLines(String text, TextPaint paint, int widthPx, int maxLines) {
        if (widthPx <= 0 || maxLines <= 0 || text == null || text.isEmpty()) return true;
        StaticLayout layout;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build();
        } else {
            layout = new StaticLayout(text, paint, widthPx, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        }
        return layout.getLineCount() <= maxLines;
    }

    /**
     * Join the first N binds with "+", matching the composed display format.
     */
    private static String joinBinds(String[] binds, int count) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(count, binds.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append('+');
            sb.append(binds[i]);
        }
        return sb.toString();
    }
}
