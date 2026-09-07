package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;

import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.view.ViewUtils;


/**
 * The {@link TermuxActivity} relies on {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_ADJUST_RESIZE)}
 * set by {@link TermuxTerminalViewClient#setSoftKeyboardState(boolean, boolean)} to automatically
 * resize the view and push the terminal up when soft keyboard is opened. However, this does not
 * always work properly. When `enforce-char-based-input=true` is set in `termux.properties`
 * and {@link com.termux.view.TerminalView#onCreateInputConnection(EditorInfo)} sets the inputType
 * to `InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`
 * instead of the default `InputType.TYPE_NULL` for termux, some keyboards may still show suggestions.
 * Gboard does too, but only when text is copied and clipboard suggestions **and** number keys row
 * toggles are enabled in its settings. When number keys row toggle is not enabled, Gboard will still
 * show the row but will switch it with suggestions if needed. If its enabled, then number keys row
 * is always shown and suggestions are shown in an additional row on top of it. This additional row is likely
 * part of the candidates view returned by the keyboard app in {@link InputMethodService#onCreateCandidatesView()}.
 *
 * With the above configuration, the additional clipboard suggestions row partially covers the
 * extra keys/terminal. Reopening the keyboard/activity does not fix the issue. This is either a bug
 * in the Android OS where it does not consider the candidate's view height in its calculation to push
 * up the view or because Gboard does not include the candidate's view height in the height reported
 * to android that should be used, hence causing an overlap.
 *
 * Gboard logs the following entry to `logcat` when its opened with or without the suggestions bar showing:
 * I/KeyboardViewUtil: KeyboardViewUtil.calculateMaxKeyboardBodyHeight():62 leave 500 height for app when screen height:2392, header height:176 and isFullscreenMode:false, so the max keyboard body height is:1716
 * where `keyboard_height = screen_height - height_for_app - header_height` (62 is a hardcoded value in Gboard source code and may be a version number)
 * So this may in fact be due to Gboard but https://stackoverflow.com/questions/57567272 suggests
 * otherwise. Another similar report https://stackoverflow.com/questions/66761661.
 * Also check https://github.com/termux/termux-app/issues/1539.
 *
 * To fix these issues, `activity_termux.xml` has the constant 1sp transparent
 * `activity_termux_bottom_space_view` View at the bottom. This will appear as a line matching the
 * activity theme. When {@link TermuxActivity} {@link ViewTreeObserver.OnGlobalLayoutListener} is
 * called when any of the sub view layouts change,  like keyboard opening/closing keyboard,
 * extra keys/input view switched, etc, we check if the bottom space view is visible or not.
 * If its not, then we add a margin to the bottom of the root view, so that the keyboard does not
 * overlap the extra keys/terminal, since the margin will push up the view. By default the margin
 * added is equal to the height of the hidden part of extra keys/terminal. For Gboard's case, the
 * hidden part equals the `header_height`. The updates to margins may cause a jitter in some cases
 * when the view is redrawn if the margin is incorrect, but logic has been implemented to avoid that.
 *
 * The bottom margin is the only source of truth for the layout. It is derived from a single
 * invariant: "the bottom space view must sit at (or above) the bottom of the available window".
 * The margin is re-applied only when it actually changes (within {@code root_view_layout_tolerance}
 * of sensor noise), so a flickering freeform window never triggers a set/reset oscillation of the
 * bottom panel. A genuine change (keyboard shown/hidden, real resize, split divider moved) is
 * always far larger than the tolerance and is applied immediately.
 *
 * Three properties keep that controller stable:
 *
 * 1. The margin is *corrected* by the measured overlap, not replaced by it. The bottom space view
 *    is the last child of this view, so a bottom margin of M lifts it by exactly M px; the overlap
 *    measured while M is applied is therefore `(overlap with no margin) - M`. Setting
 *    `margin = overlap` (the previous behaviour) makes the controller alternate between the two
 *    values and, once the tolerance band swallows the difference, latch at half the overlap — which
 *    is exactly the "gap as tall as the keyboard" bug. Using `margin = margin + overlap` (with a
 *    signed overlap, so a negative one shrinks the margin) converges in a single pass and settles
 *    with the bottom space view resting on the bottom of the available window.
 *
 * 2. A measurement is cross-validated against the IME height before it is trusted
 *    ({@link #isMeasurementPlausible}). Only two worlds exist: the window is resized for the IME
 *    (so only a candidates row can hide the bottom space view), or it is not (so the IME covers
 *    its bottom `imeHeight` px). Anything else is a transient frame delivered while the IME changes
 *    its height in multi-window — where the visible frame and the window frame are updated at
 *    different moments and the window bottom is subtracted twice or more — and is ignored.
 *
 * 3. Growing the margin is confirmed before it is applied (transient frames report a different
 *    overlap on every pass, so they never confirm), while shrinking it is applied immediately so
 *    that dismissing the keyboard stays instant.
 */
public class TermuxActivityRootView extends LinearLayout implements ViewTreeObserver.OnGlobalLayoutListener {

    public TermuxActivity mActivity;

    /** Log root view events. */
    private boolean ROOT_VIEW_LOGGING_ENABLED = false;

    private static final String LOG_TAG = "TermuxActivityRootView";

    private static int mStatusBarHeight;

    /** Returned by {@link #measureDesiredMargin} when the current frame must be ignored. */
    private static final int MEASUREMENT_INVALID = -1;

    /** How long a grown bottom margin must survive before it is applied. See {@link #mPendingMargin}. */
    private static final int MARGIN_CONFIRM_DELAY_MS = 80;

    // Layout tolerance (px), loaded lazily from @dimen/root_view_layout_tolerance. Any difference
    // between the currently applied bottom margin and the freshly measured one that is smaller than
    // this band is treated as sensor noise (the system window rect flickers a few px per frame) and
    // ignored, so the margin is not recomputed/re-applied every frame.
    private int mLayoutTolerancePx = -1;

    // Extra slack (px) loaded lazily from @dimen/root_view_candidates_slack, allowed on top of the
    // tolerance when validating a measurement against the IME height, to accommodate an IME
    // candidates row that the system does not include in the visible frame.
    private int mCandidatesSlackPx = -1;

    /**
     * A measured bottom margin that still has to be confirmed before it is applied, or
     * {@link #MEASUREMENT_INVALID} when nothing is pending. Transient frames delivered while the
     * IME changes its height report a different overlap on every pass, so they keep replacing the
     * pending value and never reach the confirmation; a real overlap is persistent and either
     * repeats on the next layout pass or is still there when the confirmation re-measures it.
     */
    private int mPendingMargin = MEASUREMENT_INVALID;

    private final Runnable mConfirmPendingMarginRunnable = new Runnable() {
        @Override
        public void run() {
            confirmPendingMargin();
        }
    };


    public TermuxActivityRootView(Context context) {
        super(context);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxActivityRootView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setActivity(TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * Sets whether root view logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsRootViewLoggingEnabled(boolean value) {
        ROOT_VIEW_LOGGING_ENABLED = value;
    }

    @Override
    public void onGlobalLayout() {
        if (mActivity == null || !mActivity.isVisible()) return;

        boolean root_view_logging_enabled = ROOT_VIEW_LOGGING_ENABLED;

        if (root_view_logging_enabled)
            Logger.logVerbose(LOG_TAG, ":\nonGlobalLayout:");

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();
        if (params == null) return;

        int currentMargin = params.bottomMargin;
        int desiredMargin = measureDesiredMargin(currentMargin, root_view_logging_enabled);
        if (desiredMargin == MEASUREMENT_INVALID)
            return;

        // Apply only on a real change. Within the tolerance band the measured window rect is just
        // sensor noise (freeform window flicker), so we keep the existing margin and avoid the
        // set/reset feedback loop that previously jittered the bottom panel every frame. A genuine
        // layout change is always larger than the band and still snaps to the new value immediately.
        int delta = Math.abs(desiredMargin - currentMargin);
        if (delta <= mLayoutTolerancePx) {
            if (root_view_logging_enabled)
                Logger.logVerbose(LOG_TAG, "Margin within tolerance of desired (" + currentMargin +
                        " vs " + desiredMargin + ", delta " + delta + " <= " + mLayoutTolerancePx + "), leaving as is");
            clearPendingMargin();
            return;
        }

        // Shrinking the margin only ever gives space back to the layout and is self-correcting, so
        // it is applied right away (this is what makes dismissing the keyboard instant).
        if (desiredMargin < currentMargin) {
            clearPendingMargin();
            applyBottomMargin(desiredMargin, root_view_logging_enabled);
            return;
        }

        // Growing the margin has to be confirmed first: that is the direction in which a bogus
        // measurement used to be latched into a permanent gap above the panel.
        if (mPendingMargin == MEASUREMENT_INVALID
                || Math.abs(desiredMargin - mPendingMargin) > mLayoutTolerancePx) {
            mPendingMargin = desiredMargin;
            removeCallbacks(mConfirmPendingMarginRunnable);
            postDelayed(mConfirmPendingMarginRunnable, MARGIN_CONFIRM_DELAY_MS);
            if (root_view_logging_enabled)
                Logger.logVerbose(LOG_TAG, "Queued unconfirmed bottom margin " + desiredMargin +
                        " (current " + currentMargin + ")");
            return;
        }

        if (root_view_logging_enabled)
            Logger.logVerbose(LOG_TAG, "Confirmed queued bottom margin " + desiredMargin);
        clearPendingMargin();
        applyBottomMargin(desiredMargin, root_view_logging_enabled);
    }

    /**
     * Force-recompute and re-apply the bottom margin, bypassing the tolerance band and the
     * confirmation delay. The plausibility gate still applies: forcing a relayout is not a reason
     * to trust an impossible measurement.
     */
    public void forceRelayout() {
        if (mActivity == null || !mActivity.isVisible()) return;

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();
        if (params == null) return;

        boolean root_view_logging_enabled = ROOT_VIEW_LOGGING_ENABLED;

        int desiredMargin = measureDesiredMargin(params.bottomMargin, root_view_logging_enabled);
        if (desiredMargin == MEASUREMENT_INVALID)
            return;

        clearPendingMargin();

        if (desiredMargin != params.bottomMargin)
            applyBottomMargin(desiredMargin, root_view_logging_enabled);
    }

    /**
     * Measure the bottom margin this view should currently have.
     *
     * @param currentMargin The bottom margin that is currently applied.
     * @param logging Whether to log the measurement.
     * @return The target bottom margin in px, or {@link #MEASUREMENT_INVALID} if the current frame
     *         is not trustworthy and the margin must be left alone.
     */
    private int measureDesiredMargin(int currentMargin, boolean logging) {
        View bottomSpaceView = mActivity.getTermuxActivityBottomSpaceView();
        if (bottomSpaceView == null) return MEASUREMENT_INVALID;

        // Get the position Rects of the bottom space view and the main window holding it
        Rect[] windowAndViewRects = ViewUtils.getWindowAndViewRects(bottomSpaceView, mStatusBarHeight);
        if (windowAndViewRects == null)
            return MEASUREMENT_INVALID;

        Rect windowAvailableRect = windowAndViewRects[0];
        Rect bottomSpaceViewRect = windowAndViewRects[1];

        ensureTolerancesLoaded();

        if (logging) {
            Logger.logVerbose(LOG_TAG, "windowAvailableRect " + ViewUtils.toRectString(windowAvailableRect) + ", bottomSpaceViewRect " + ViewUtils.toRectString(bottomSpaceViewRect));
            Logger.logVerbose(LOG_TAG, "windowAvailableRect.bottom " + windowAvailableRect.bottom +
                ", bottomSpaceViewRect.bottom " + bottomSpaceViewRect.bottom +
                ", diff " + (bottomSpaceViewRect.bottom - windowAvailableRect.bottom) + ", bottom " + currentMargin +
                ", isRectAbove " + ViewUtils.isRectAbove(windowAvailableRect, bottomSpaceViewRect));
        }

        if (!isMeasurementPlausible(windowAvailableRect, logging))
            return MEASUREMENT_INVALID;

        // Single invariant: the bottom space view must not be hidden behind the window bottom.
        // pxHidden > 0  -> view is hidden by that many px, the margin has to grow by that much.
        // pxHidden <= 0 -> view is visible (or above), the margin has to shrink by that much.
        // The overlap is deliberately NOT clamped at 0: a negative value is what lets a margin that
        // is larger than needed come back down instead of being stuck at its old value.
        int pxHidden = bottomSpaceViewRect.bottom - windowAvailableRect.bottom;

        // The bottom space view is the last child of this view, so a bottom margin of M lifts it by
        // exactly M px and the overlap measured while M is applied is `(overlap with no margin) - M`.
        // The margin must therefore be corrected by the measured overlap, not replaced by it —
        // replacing it makes the controller alternate between 0 and the overlap and, once the
        // tolerance band swallows the difference, latch at half the overlap.
        int desiredMargin = currentMargin + pxHidden;
        if (desiredMargin < 0) desiredMargin = 0;

        return clampMarginToWindow(desiredMargin);
    }

    /**
     * Cross-validate the measured available window against the IME height.
     *
     * Only two worlds can legitimately hide the bottom space view:
     * 1. the window was resized for the IME, so at most an IME candidates row (which the system
     *    does not include in the visible frame) can still cover it — the available window bottom
     *    then sits at, or just above, the window bottom;
     * 2. the window was not resized (fullscreen, or a ROM that ignores {@code ADJUST_RESIZE}), so
     *    the IME covers its bottom {@code imeHeight} px.
     *
     * Everything else is a transient frame: while the IME changes its height (which is what
     * switching between the extra keys and the text input panel does) in multi-window, the visible
     * frame and the window frame are updated at different moments and the keyboard height ends up
     * being subtracted twice or more from the window bottom. Turning such a reading into a margin
     * is what produced the permanent, keyboard-sized gap above the panel.
     *
     * @return {@code true} if the measurement may be used, {@code false} if the frame must be
     *         ignored. Always {@code true} when no independent IME height is available (API < 30
     *         without {@code ADJUST_RESIZE}), where the legacy behaviour is all we have.
     */
    private boolean isMeasurementPlausible(Rect windowAvailableRect, boolean logging) {
        int imeBottom = mActivity.getLastImeBottomPx();
        if (imeBottom <= 0)
            return true;

        View decor = getRootView();
        if (decor == null) return true;

        int[] decorLocation = new int[2];
        decor.getLocationOnScreen(decorLocation);
        int decorBottom = decorLocation[1] + decor.getHeight();
        if (decorBottom <= 0) return true;

        int slack = mLayoutTolerancePx + mCandidatesSlackPx;

        boolean resizedWorld = Math.abs(windowAvailableRect.bottom - decorBottom) <= slack;
        boolean overlappedWorld = Math.abs(windowAvailableRect.bottom - (decorBottom - imeBottom)) <= slack;

        if (resizedWorld || overlappedWorld)
            return true;

        if (logging)
            Logger.logVerbose(LOG_TAG, "Ignoring implausible window measurement: windowAvailableRect.bottom " +
                windowAvailableRect.bottom + " matches neither the resized window bottom " + decorBottom +
                " nor the IME overlapped bottom " + (decorBottom - imeBottom) + " (slack " + slack +
                ", imeBottom " + imeBottom + ")");

        return false;
    }

    private void applyBottomMargin(int margin, boolean logging) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();
        if (params == null) return;

        if (logging)
            Logger.logVerbose(LOG_TAG, "Setting bottom margin to " + margin);
        params.setMargins(0, 0, 0, margin);
        setLayoutParams(params);
    }

    /** Apply the pending margin if a fresh measurement still asks for it. */
    private void confirmPendingMargin() {
        if (mPendingMargin == MEASUREMENT_INVALID) return;

        boolean logging = ROOT_VIEW_LOGGING_ENABLED;

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) getLayoutParams();
        int currentMargin = params == null ? 0 : params.bottomMargin;

        int desiredMargin = measureDesiredMargin(currentMargin, logging);
        int pending = mPendingMargin;
        mPendingMargin = MEASUREMENT_INVALID;

        if (desiredMargin == MEASUREMENT_INVALID
                || Math.abs(desiredMargin - pending) > mLayoutTolerancePx) {
            // The layout moved on (or the frame is not trustworthy) -> drop the candidate.
            if (logging)
                Logger.logVerbose(LOG_TAG, "Dropping queued bottom margin " + pending + ", now measuring " +
                        (desiredMargin == MEASUREMENT_INVALID ? "an implausible frame" : String.valueOf(desiredMargin)));
            return;
        }

        applyBottomMargin(pending, logging);
    }

    private void clearPendingMargin() {
        mPendingMargin = MEASUREMENT_INVALID;
        removeCallbacks(mConfirmPendingMarginRunnable);
    }

    /**
     * Guard against a runaway margin if the layout ever stops reacting to it: a bottom margin can
     * never legitimately be taller than the window it lives in.
     */
    private int clampMarginToWindow(int margin) {
        View decor = getRootView();
        int maxMargin = decor == null ? 0 : decor.getHeight();
        if (maxMargin > 0 && margin > maxMargin)
            margin = maxMargin;
        if (margin < 0)
            margin = 0;
        return margin;
    }

    private void ensureTolerancesLoaded() {
        if (mLayoutTolerancePx < 0) {
            mLayoutTolerancePx = Math.round(getResources().getDimensionPixelSize(
                    com.termux.R.dimen.root_view_layout_tolerance));
        }
        if (mCandidatesSlackPx < 0) {
            mCandidatesSlackPx = Math.round(getResources().getDimensionPixelSize(
                    com.termux.R.dimen.root_view_candidates_slack));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearPendingMargin();
    }

    public static class WindowInsetsListener implements View.OnApplyWindowInsetsListener {
        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            mStatusBarHeight =  WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(WindowInsetsCompat.Type.statusBars()).top;
            // Let view window handle insets however it wants
            return v.onApplyWindowInsets(insets);
        }
    }

}
