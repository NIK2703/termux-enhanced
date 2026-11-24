package com.termux.app.terminal.io.autocomplete;

import com.termux.app.TermuxAppConstants;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Layout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

/**
 * Owns the message-history auto-complete popup window and its lifecycle: building, showing,
 * positioning, content rebuild, bold-span refresh and dismissal. Contains no suggestion data and
 * no input/fetch logic — everything comes through {@link AutoCompleteDataProvider} and pre-read
 * resource dimensions, keeping rendering isolated from {@code AutoCompleteController}.
 */
final class AutoCompletePopupManager {

    @NonNull private final Context mContext;
    @NonNull private final AutoCompleteDataProvider mData;
    @NonNull private final TermuxColorSchemeManager mColorSchemeManager;

    // Resource dimensions captured once by the host.
    private final int mPopupCornerRadiusPx;
    private final int mPopupElevationPx;
    private final int mPopupMinWidthPx;
    private final int mPopupWidthMarginPx;
    private final float mPopupWidthFraction;
    private final int mPopupXOffsetPx;
    private final int mPopupEdgeMarginPx;
    private final int mPopupYOffsetPx;
    private final int mPopupMinYPx;
    private final float mPopupContentAlpha;
    private final float mPopupShadowAlpha;

    // ── Popup window state (owned here, not in the controller) ──
    @Nullable private PopupWindow mHistoryPopup;
    @Nullable private LinearLayout mHistoryContent;
    /** True while the swipe-down slide/fade-out is running on the popup's decor view. */
    private boolean mSwipeDismissAnimating;
    /** Decor being animated, so an incoming show can cancel the motion immediately. */
    @Nullable private View mSwipeDismissDecor;
    /**
     * Layout-change listener attached to the INPUT FIELD (not the decor view), so a reposition is
     * only triggered when the input field's own layout changes — not on every Activity-wide layout
     * pass (which caused a redundant second reposition and visible jitter).
     */
    @Nullable private View.OnLayoutChangeListener mSuggestionsLayoutListener;
    /** The view the layout listener is registered on, so we can unregister the exact same view. */
    @Nullable private View mLayoutListenerTarget;

    private int mLastPopupWidth = 0;
    private int mLastPopupX = 0;
    private int mLastPopupY = 0;
    private int mLastPopupHeight = 0;

    // Reused scratch array for getLocationInWindow in the hot positioning path
    // (every keystroke) — avoids a new int[2] allocation per call.
    private final int[] mTmpLoc = new int[2];

    // Version counters avoid comparing every row and the whole prefix on each keystroke.
    private int mLastAppliedSuggestionsVersion;
    private int mLastAppliedPrefixVersion;

    AutoCompletePopupManager(@NonNull Context context,
                             @NonNull AutoCompleteDataProvider data,
                             @NonNull TermuxColorSchemeManager colorSchemeManager,
                             int popupCornerRadiusPx, int popupElevationPx,
                             int popupItemPadHPx, int popupItemPadVPx,
                             int popupMinWidthPx, int popupWidthMarginPx,
                             float popupWidthFraction, int popupXOffsetPx,
                             int popupEdgeMarginPx, int popupYOffsetPx,
                             int popupMinYPx, float popupContentAlpha,
                             float popupShadowAlpha) {
        mContext = context;
        mData = data;
        mColorSchemeManager = colorSchemeManager;
        mPopupCornerRadiusPx = popupCornerRadiusPx;
        mPopupElevationPx = popupElevationPx;
        mPopupMinWidthPx = popupMinWidthPx;
        mPopupWidthMarginPx = popupWidthMarginPx;
        mPopupWidthFraction = popupWidthFraction;
        mPopupXOffsetPx = popupXOffsetPx;
        mPopupEdgeMarginPx = popupEdgeMarginPx;
        mPopupYOffsetPx = popupYOffsetPx;
        mPopupMinYPx = popupMinYPx;
        mPopupContentAlpha = popupContentAlpha;
        mPopupShadowAlpha = popupShadowAlpha;
    }

    // ── Public surface used by AutoCompleteController ──

    boolean isShowing() {
        return (mHistoryPopup != null && mHistoryPopup.isShowing());
    }

    void show(@NonNull EditText inputField, @NonNull String input) {
        cancelSwipeDismissAnimation();
        showAutoCompletePopup(inputField, input);
    }

    void update(@NonNull String newText, @NonNull EditText inputField) {
        cancelSwipeDismissAnimation();
        updatePopupContent(newText, inputField);
    }

    void dismiss() {
        cancelSwipeDismissAnimation();
        dismissAutoCompleteSuggestions();
    }

    /** Popup content reported a downward swipe; the controller owns the suppression state. */
    private void onPopupSwipeDown() {
        mData.onSuggestionsPopupSwipedDown();
    }

    /**
     * Hide with a subtle downward slide plus a fade. The animation runs on the popup's decor view
     * (so the rounded background fades with the rows); window animations are deliberately not used
     * here because they are not reliably played for a {@code showAtLocation()} popup.
     */
    void dismissWithSwipeAnimation() {
        final PopupWindow popup = mHistoryPopup;
        final View decor = popup != null && popup.isShowing()
                ? popup.getContentView().getRootView() : null;
        if (decor == null) {
            dismissAutoCompleteSuggestions();
            return;
        }
        mSwipeDismissAnimating = true;
        mSwipeDismissDecor = decor;
        decor.animate().cancel();
        decor.setAlpha(1f);
        decor.setTranslationY(0f);
        decor.animate()
                .translationY(mContext.getResources().getDimensionPixelSize(R.dimen.autocomplete_popup_swipe_slide_distance))
                .alpha(0f)
                .setDuration(TermuxAppConstants.Autocomplete.POPUP_SLIDE_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (!mSwipeDismissAnimating) return;
                    mSwipeDismissAnimating = false;
                    mSwipeDismissDecor = null;
                    dismissAutoCompleteSuggestions();
                })
                .start();
    }

    /** Cancel an in-flight swipe dismissal and drop the popup immediately. */
    private void cancelSwipeDismissAnimation() {
        if (!mSwipeDismissAnimating) return;
        mSwipeDismissAnimating = false;
        if (mSwipeDismissDecor != null) mSwipeDismissDecor.animate().cancel();
        mSwipeDismissDecor = null;
        // The caller is about to show the new row set. Clearing the controller's
        // candidates here would leave showAutoCompletePopup() with an empty list.
        dismissAutoCompleteSuggestions(false);
    }

    // ── Width / geometry (shared by both windows) ──

    int computePopupWidth(int fieldWidth) {
        int base = Math.max(fieldWidth, mPopupMinWidthPx);
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = (int) (displayWidth * mPopupWidthFraction);
        maxWidth = Math.min(maxWidth, displayWidth - 2 * mPopupWidthMarginPx);
        return Math.min(base, maxWidth);
    }

    private int calcPopupX(@NonNull EditText inputField, int popupWidth) {
        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);
        return calcPopupX(inputField, popupWidth, loc[0], loc[1]);
    }

    private int calcPopupX(@NonNull EditText inputField, int popupWidth, int locX, int locY) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorX = 0;
        if (layout != null && cursorPos >= 0) {
            cursorX = layout.getPrimaryHorizontal(cursorPos);
        }
        int popupX = locX + (int) cursorX - inputField.getScrollX() + mPopupXOffsetPx;
        int displayWidth = mContext.getResources().getDisplayMetrics().widthPixels;
        if (popupX + popupWidth > displayWidth - mPopupEdgeMarginPx) {
            popupX = displayWidth - popupWidth - mPopupEdgeMarginPx;
        }
        if (popupX < mPopupEdgeMarginPx) popupX = mPopupEdgeMarginPx;
        return popupX;
    }

    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth) {
        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);
        return calcPopupY(inputField, popupHeight, popupWidth, loc[0], loc[1]);
    }

    private int calcPopupY(@NonNull EditText inputField, int popupHeight, int popupWidth, int locX, int locY) {
        int cursorPos = inputField.getSelectionStart();
        Layout layout = inputField.getLayout();
        float cursorY = 0;
        if (layout != null && cursorPos >= 0) {
            cursorY = layout.getLineTop(layout.getLineForOffset(cursorPos));
        }
        // Anchor the popup to the caret: it appears ABOVE the caret's line in the
        // input field. If there is no room above (it would fall above the allowed
        // minimum), show it BELOW the caret line instead — so it never covers the caret.
        int popupY = locY + (int) cursorY - inputField.getScrollY() - popupHeight - mPopupYOffsetPx;
        if (popupY < mPopupMinYPx) {
            popupY = locY + (int) cursorY - inputField.getScrollY() + mPopupEdgeMarginPx;
        }
        return popupY;
    }

    // ── Show / build ──

    private void showAutoCompletePopup(@NonNull EditText inputField, @NonNull String input) {
        final boolean hasHistory = !mData.getSuggestions().isEmpty();

        boolean historyReuse = mHistoryPopup != null && mHistoryPopup.isShowing()
                && mHistoryContent != null && mHistoryContent.getParent() != null;

        if (!hasHistory) {
            // Nothing to show: drop any live window and bail out.
            if (mHistoryPopup != null) {
                try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
                mHistoryPopup = null;
                mHistoryContent = null;
            }
            return;
        }

        if (historyReuse && mHistoryContent != null) {
            final int savedScroll = mHistoryContent.getScrollY();
            rebuildHistoryViews(mHistoryContent, input);
            mHistoryContent.setScrollY(savedScroll);
        } else {
            if (mHistoryPopup != null) { try { mHistoryPopup.dismiss(); } catch (Exception ignored) {} mHistoryPopup = null; }
            mHistoryContent = new LinearLayout(mContext);
            mHistoryContent.setOrientation(LinearLayout.VERTICAL);
            mHistoryContent.setBackgroundColor(Color.TRANSPARENT);
            rebuildHistoryViews(mHistoryContent, input);
            mHistoryPopup = buildPopupWindow(mHistoryContent);
            showPopupAtCaret(mHistoryPopup, inputField);
        }

        // Versions were recorded by rebuildHistoryViews() above.
        // Resize instantly to the new row set; no animation is attached to the popup,
        // so adding/removing suggestions changes the window without any motion.
        applyPopupGeometry(inputField);
        if (mSuggestionsLayoutListener == null) {
            mSuggestionsLayoutListener =
                    (v, l, t, r, b, ol, ot, or, ob) -> {
                        // Geometry unchanged (size of the input field is the same) → no
                        // reposition. This drops the redundant reposition on per-character
                        // input where the text changes but the field's box does not.
                        if (l == ol && t == ot && r == or && b == ob) return;
                        repositionAutoCompletePopup();
                    };
            mLayoutListenerTarget = inputField;
            inputField.addOnLayoutChangeListener(mSuggestionsLayoutListener);
        }
    }

    private void showPopupAtCaret(@NonNull PopupWindow popup, @NonNull EditText inputField) {
        int w = computePopupWidth(inputField.getWidth());
        int h = 0;
        if (popup.getContentView() instanceof ScrollView) {
            View child = ((ScrollView) popup.getContentView()).getChildAt(0);
            if (child != null) h = child.getMeasuredHeight();
        }
        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);
        int x = calcPopupX(inputField, w, loc[0], loc[1]);
        int y = calcPopupY(inputField, h, w, loc[0], loc[1]);
        try {
            popup.showAtLocation(inputField, Gravity.NO_GRAVITY, x, y);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    private PopupWindow buildPopupWindow(@NonNull LinearLayout content) {
        int sumWidth = computePopupWidth(mData.getInputField() != null ? mData.getInputField().getWidth() : 0);

        SwipeDownScrollView scroll = new SwipeDownScrollView(mContext, this::onPopupSwipeDown);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, mPopupCornerRadiusPx);
                    }
                }
            });
            scroll.setClipToOutline(true);
        }

        content.measure(
                View.MeasureSpec.makeMeasureSpec(sumWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        PopupWindow popup = new PopupWindow(scroll, sumWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // No framework DropDownUp grow/shrink window animation: while typing, a
        // narrowing suggestion list dismisses the popup, and the default animation
        // makes it visibly collapse. Show/hide must stay instant.
        popup.setAnimationStyle(0);
        // The theme can also give PopupWindow enter/exit Transitions (a slide/scale of
        // the decor view). Those survive setAnimationStyle(0) and are the source of the
        // remaining shrink/shift motion, so clear them explicitly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            popup.setEnterTransition(null);
            popup.setExitTransition(null);
        }
        popup.setElevation(mPopupElevationPx);
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    outline.setAlpha(mPopupShadowAlpha);
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(mPopupCornerRadiusPx);
        // The alpha goes on the background, not the content ScrollView: the background is drawn on
        // the decor view, so content alpha never makes the panel translucent. A translucent fill
        // still yields a valid outline, so the elevation shadow is unaffected.
        final int bg = mColorSchemeManager.getHistoryPopupBg();
        popupBgDrawable.setColor(Color.argb(Math.round(mPopupContentAlpha * 255),
                Color.red(bg), Color.green(bg), Color.blue(bg)));
        popup.setBackgroundDrawable(popupBgDrawable);
        popup.setClippingEnabled(true);
        popup.setTouchable(true);
        popup.setFocusable(false);
        popup.setOnDismissListener(() -> {
            if (popup == mHistoryPopup) { mHistoryPopup = null; mHistoryContent = null; }
        });
        return popup;
    }

    // ── Reposition / geometry ──

    void repositionAutoCompletePopup() {
        if (!isShowing()) return;
        // Keep the popup alive while a swipe-to-select gesture holds a deliberate
        // text selection (it repeatedly moves the caret inside the text). Without
        // this guard a layout event mid-swipe would dismiss the popup behind the
        // gesture, which both fixes miss.
        if (mData.isSwipeActive()) return;
        EditText inputField = mData.getInputField();
        // Do not dismiss while an IME composition is active: the caret can briefly
        // sit inside the composing span even though the user is still typing.
        if (inputField != null && !AutoCompleteController.hasComposingSpan(inputField)) {
            int caret = inputField.getSelectionStart();
            if (caret < 0 || caret != inputField.getText().length()) {
                dismissAutoCompleteSuggestions();
                return;
            }
        }
        if (inputField == null) return;
        applyPopupGeometry(inputField);
    }

    private void applyPopupGeometry(@NonNull EditText inputField) {
        if (!isShowing()) {
            return;
        }
        Layout layout = inputField.getLayout();
        if (layout == null) {
            inputField.post(() -> applyPopupGeometry(inputField));
            return;
        }
        int w = mLastPopupWidth > 0 ? mLastPopupWidth : computePopupWidth(inputField.getWidth());
        if (w <= 0) {
            return;
        }

        int[] loc = mTmpLoc;
        inputField.getLocationInWindow(loc);

        int historyH = 0;
        if (mHistoryPopup != null && mHistoryPopup.isShowing() && mHistoryContent != null) {
            mHistoryContent.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            historyH = mHistoryContent.getMeasuredHeight();
        }

        int historyX = calcPopupX(inputField, w, loc[0], loc[1]);
        int historyY = calcPopupY(inputField, historyH, w, loc[0], loc[1]);

        // Skip update() when the position and size did not change; it causes jitter.
        if (mHistoryPopup != null && mHistoryPopup.isShowing()
                && historyX == mLastPopupX && historyY == mLastPopupY
                && w == mLastPopupWidth && historyH == mLastPopupHeight) {
            return;
        }

        if (mHistoryPopup != null && mHistoryPopup.isShowing()) {
            mLastPopupWidth = w;
            mLastPopupHeight = historyH;
            mHistoryPopup.update(historyX, historyY, w, historyH);
            mLastPopupX = historyX;
            mLastPopupY = historyY;
        }
    }

    // ── Content update (Path B) ──

    private void updatePopupContent(@NonNull String newText, @NonNull EditText inputField) {
        boolean historyShowing = mHistoryPopup != null && mHistoryPopup.isShowing();
        if (!historyShowing) {
            showAutoCompletePopup(inputField, newText);
            return;
        }

        boolean historyChanged = mData.getSuggestionsVersion() != mLastAppliedSuggestionsVersion;
        boolean prefixChanged = mData.getPrefixVersion() != mLastAppliedPrefixVersion;

        if ((historyChanged || prefixChanged) && mHistoryContent != null) {
            // Rebuild (not in-place Spannable mutation): an in-place bold-span edit on a TextView
            // inside a showing PopupWindow is not guaranteed to repaint, so a backspace would
            // leave old characters bold.
            final int savedScroll = mHistoryContent.getScrollY();
            rebuildHistoryViews(mHistoryContent, newText);
            mHistoryContent.setScrollY(savedScroll);
        }

        // Versions are updated inside rebuildHistoryViews()/show.
        // Resize instantly only when the row set changed. A prefix-only keystroke keeps
        // the current geometry, so the window neither moves nor animates while typing.
        if (historyChanged) applyPopupGeometry(inputField);
    }

    // ── Per-window rebuild + bold spans ──

    private void rebuildHistoryViews(@NonNull LinearLayout content, @NonNull String input) {
        // A row that is sliding out after a swipe must not be rebound to a different
        // candidate. Remove it before reusing child slots; its pending end action will
        // see that the holder is already detached.
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child instanceof SuggestionRowHolder
                    && ((SuggestionRowHolder) child).isDeleting()) {
                content.removeViewAt(i);
            }
        }
        final int n = displayCount();
        final int existing = content.getChildCount();
        // Reversed rendering: oldest item on top, newest (history index 0) at the
        // very bottom of the list. History entries are stored newest-first, so we
        // walk from (n-1) down to 0.
        for (int i = n - 1; i >= 0; i--) {
            String suggestion = mData.getSuggestions().get(i);
            // Candidate metadata is index-aligned with the suggestions, so no search is needed.
            SuggestionEngine.Candidate candidate = mData.getCandidate(i);
            final int viewIdx = n - 1 - i;
            TextView tv = null;
            if (viewIdx < existing) {
                View child = content.getChildAt(viewIdx);
                if (child instanceof SuggestionRowHolder) {
                    tv = ((SuggestionRowHolder) child).row();
                }
            }
            if (tv != null) {
                // Rebind in place — no view, listener or drawable allocation on
                // the per-keystroke path. Only the SpannableString is rebuilt
                // (the bold prefix and truncation track the typed input).
                mData.rebindSuggestionTextView(tv, suggestion, input, candidate);
            } else {
                if (viewIdx < existing) content.removeViewAt(viewIdx);
                TextView nv = mData.buildSuggestionTextView(suggestion, input, candidate);
                // The holder supplies the swipe-left delete underlay; it adds no
                // per-keystroke cost because it is reused with its row.
                SuggestionRowHolder holder = new SuggestionRowHolder(mContext, nv);
                content.addView(holder, viewIdx, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        // Drop surplus views if the shown set shrank.
        while (content.getChildCount() > n) {
            content.removeViewAt(content.getChildCount() - 1);
        }
        // Record both versions so the next update does not rebuild unconditionally.
        mLastAppliedSuggestionsVersion = mData.getSuggestionsVersion();
        mLastAppliedPrefixVersion = mData.getPrefixVersion();
    }

    // ── Display math ──

    private int displayCount() {
        return Math.min(displayMax(), mData.getSuggestions().size());
    }

    private int displayMax() {
        int maxCount = mData.getDisplayMax();
        if (maxCount < 0) maxCount = 0;
        if (maxCount > TermuxPreferenceConstants.TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX) {
            maxCount = TermuxPreferenceConstants.TERMUX_APP.SUGGESTIONS_MAX_COUNT_MAX;
        }
        return maxCount;
    }

    // ── Dismiss ──

    private void dismissAutoCompleteSuggestions() {
        dismissAutoCompleteSuggestions(true);
    }

    /**
     * @param notifyController whether the controller should drop its candidate list. False when
     *                         an incoming show will reuse that list immediately.
     */
    private void dismissAutoCompleteSuggestions(boolean notifyController) {
        if (mHistoryPopup == null) {
            return;
        }
        if (mHistoryPopup != null) {
            try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
            mHistoryPopup = null;
        }
        mHistoryContent = null;
        mLastPopupX = 0;
        mLastPopupY = 0;
        mLastPopupHeight = 0;
        if (mSuggestionsLayoutListener != null) {
            if (mLayoutListenerTarget != null) {
                mLayoutListenerTarget.removeOnLayoutChangeListener(mSuggestionsLayoutListener);
            }
            mSuggestionsLayoutListener = null;
            mLayoutListenerTarget = null;
        }
        // The controller owns the suggestion data; it clears its own lists and
        // resets the fetch state in onSuggestionDismissed().
        if (notifyController) mData.onSuggestionDismissed();
    }

    // ── Swipe-down-to-hide gesture ──

    /**
     * Popup content that turns a deliberate downward drag into a dismissal. Horizontal
     * drags stay with the row's word-restore/delete handlers (they disallow interception);
     * an upward drag still belongs to the ScrollView.
     */
    private static final class SwipeDownScrollView extends ScrollView {

        @NonNull private final Runnable mOnSwipeDown;
        /** Minimum downward travel, scaled to the device's touch slop. */
        private final int mThresholdPx;

        private float mDownX;
        private float mDownY;
        private boolean mTracking;
        private boolean mConsumingGesture;

        SwipeDownScrollView(@NonNull Context context, @NonNull Runnable onSwipeDown) {
            super(context);
            mOnSwipeDown = onSwipeDown;
            mThresholdPx = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
        }

        /** Returns true once the downward gesture has been taken over. */
        private boolean trackSwipe(@NonNull MotionEvent ev) {
            final int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mDownX = ev.getX();
                mDownY = ev.getY();
                mTracking = true;
                mConsumingGesture = false;
            } else if (mTracking && action == MotionEvent.ACTION_MOVE) {
                final float dx = ev.getX() - mDownX;
                final float dy = ev.getY() - mDownY;
                // Downward, clearly vertical, and no content left to scroll up.
                if (dy > mThresholdPx && dy > Math.abs(dx) * 1.4f && getScrollY() <= 0) {
                    mTracking = false;
                    mConsumingGesture = true;
                    // Dismiss on the next frame instead of inside touch dispatch.
                    post(mOnSwipeDown);
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mTracking = false;
                mConsumingGesture = false;
            }
            return false;
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull MotionEvent ev) {
            if (trackSwipe(ev)) return true;
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent ev) {
            if (mConsumingGesture) {
                final int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mConsumingGesture = false;
                }
                return true;
            }
            // A drag that starts on the ScrollView itself never reaches interception
            // for MOVE, so detect it here too.
            if (trackSwipe(ev)) return true;
            return super.onTouchEvent(ev);
        }
    }
}
