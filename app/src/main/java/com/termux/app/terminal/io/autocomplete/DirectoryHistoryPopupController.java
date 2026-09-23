package com.termux.app.terminal.io.autocomplete;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.terminal.TermuxColorSchemeManager;

import java.util.ArrayList;

/**
 * Owns the UI + gesture state for the directory-history popup (swipe-up on the
 * new-tab button).
 * <p>
 * This is fully decoupled from the message-history popup: it keeps its own
 * {@link PopupWindow}, item views, scroll container, highlight index and edge
 * auto-scroll loop. The button's {@code OnTouchListener} in {@code TermuxActivity}
 * drives it via {@link #show(View)}, {@link #isShowing()}, {@link #updateHighlight(float, float)}
 * and the selection dispatch ({@link #getHighlightIndex()}, {@link #confirmClear()},
 * {@link #pick(int)}).
 */
public final class DirectoryHistoryPopupController {

    /** Tag value marking the synthetic top "CLEAR HISTORY…" row. */
    public static final int CLEAR_ALL_TAG = -2;

    /** Callback back into the host activity for directory-history actions. */
    public interface Callback {
        /** Record the current session's CWD into history; returns the path or null. */
        @Nullable
        String recordCurrentDirectory();

        /** A directory item was chosen: open a fresh session starting there. */
        void onDirectoryPicked(@NonNull String directory);

        /** User confirmed wiping the whole directory history. */
        void onClearAllDirectories();
    }

    private final Context mContext;
    private final DirectoryHistoryController mDirCtrl;
    private final TermuxColorSchemeManager mColorScheme;
    private final Callback mCallback;
    private final int mPopupGapDp;
    private final int mPopupMaxHeightDp;

    /** The directory popup window (own instance, not shared with message popup). */
    @Nullable private PopupWindow mPopup;

    /** Item views inside the popup, index-aligned with the displayed order. */
    private final ArrayList<TextView> mItemViews = new ArrayList<>();

    /** The scrollable container inside the popup, used for edge auto-scroll. */
    @Nullable private ScrollView mScroll;

    /** Currently highlighted item index while dragging, or -1 for none. */
    private int mHighlightIndex = -1;
    /** The currently highlighted row view (null when none), so a highlight change
     *  repaints only the two affected rows instead of the whole list (P1). */
    @Nullable private TextView mActiveHighlightView = null;
    /** Reused per-highlight / per-frame location buffer (avoids int[2] churn, P1/P2). */
    private final int[] mTmpLoc = new int[2];
    /** Precomputed outline radius (dpToPx once) reused by the rounded-corner
     *  ViewOutlineProvider (P2 — getOutline runs on every resize). */
    private final int mOutlineRadiusPx;

    /** Last finger Y (screen) while the popup is open, for continuous edge scroll. */
    private float mFingerY = 0f;

    /** True while the edge auto-scroll loop is scheduled/running. */
    private boolean mAutoScrolling = false;
    /** Timestamp of the last auto-scroll tick, for frame-rate-independent velocity. */
    private long mLastScrollTimeMs = 0;

    @Nullable private View mAnchor;

    /** When true, the popup is laid out inverted (newest at top, below the button). */
    private boolean mInverted = false;

    public void setInverted(boolean inverted) {
        mInverted = inverted;
    }

    public DirectoryHistoryPopupController(@NonNull Context context,
                                           @NonNull DirectoryHistoryController dirCtrl,
                                           @NonNull TermuxColorSchemeManager colorScheme,
                                           @NonNull Callback callback,
                                           int popupGapDp,
                                           int popupMaxHeightDp) {
        mContext = context;
        mDirCtrl = dirCtrl;
        mColorScheme = colorScheme;
        mCallback = callback;
        mPopupGapDp = popupGapDp;
        mPopupMaxHeightDp = popupMaxHeightDp;
        mOutlineRadiusPx = TermuxActivityUtils.dpToPx(mContext, 12);
    }

    /** @return true if there is at least one entry to show (capturing CWD on demand). */
    public boolean shouldShow() {
        if (!mDirCtrl.getHistoryList().isEmpty()) return true;
        // Capture CWD on demand (e.g. first time). May legitimately be rejected — the default
        // working directory is filtered out — so show() would dismiss after an empty capture
        // and the swipe would be consumed for no visible effect.
        mCallback.recordCurrentDirectory();
        return !mDirCtrl.getHistoryList().isEmpty();
    }

    /**
     * Build and show the directory-history popup anchored above the new-tab
     * button. Newest-at-bottom, a top "CLEAR HISTORY…" row wipes the whole list.
     * Picking a directory opens a fresh session starting in that directory.
     */
    public void show(@NonNull View anchor) {
        dismiss();
        mItemViews.clear();
        mHighlightIndex = -1;
        mAnchor = anchor;

        // Capture the current directory first so it appears in the list.
        mCallback.recordCurrentDirectory();
        if (mDirCtrl.getHistoryList().isEmpty()) return;

        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);

        int padH = TermuxActivityUtils.dpToPx(mContext, 14);
        int padV = TermuxActivityUtils.dpToPx(mContext, 10);

        if (mInverted) {
            // Newest at the TOP: iterate FORWARD so index 0 (newest) ends first.
            for (int i = 0; i < mDirCtrl.getHistoryList().size(); i++) {
                final String directory = mDirCtrl.getHistoryList().get(i);
                TextView tv = new TextView(mContext);
                tv.setText(directory);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tv.setTextColor(mColorScheme.getHistoryTextColor());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setPadding(padH, padV, padH, padV);
                tv.setClickable(true);
                tv.setTag(i);
                content.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                mItemViews.add(tv);
            }

            // Synthetic "CLEAR HISTORY…" row pinned at the BOTTOM of the popup,
            // with a separator ABOVE it (between the last history item and clear row).
            if (!mDirCtrl.getHistoryList().isEmpty()) {
                View sep = new View(mContext);
                sep.setBackgroundColor(mColorScheme.getHistoryPopupSepColor());
                content.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, TermuxActivityUtils.dpToPx(mContext, 1)));

                TextView tv = new TextView(mContext);
                tv.setText(mContext.getString(R.string.input_history_clear_all));
                tv.setGravity(Gravity.CENTER);
                tv.setAllCaps(true);
                tv.setTextColor(mColorScheme.getHistoryTextColor());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                tv.setPadding(padH, padV, padH, padV);
                tv.setClickable(true);
                tv.setTag(CLEAR_ALL_TAG);
                content.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                mItemViews.add(tv);
            }
        } else {
            // Synthetic "CLEAR HISTORY…" row pinned at the TOP of the popup.
            if (!mDirCtrl.getHistoryList().isEmpty()) {
                TextView tv = new TextView(mContext);
                tv.setText(mContext.getString(R.string.input_history_clear_all));
                tv.setGravity(Gravity.CENTER);
                tv.setAllCaps(true);
                tv.setTextColor(mColorScheme.getHistoryTextColor());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                tv.setPadding(padH, padV, padH, padV);
                tv.setClickable(true);
                tv.setTag(CLEAR_ALL_TAG);
                content.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                mItemViews.add(tv);

                View sep = new View(mContext);
                sep.setBackgroundColor(mColorScheme.getHistoryPopupSepColor());
                content.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, TermuxActivityUtils.dpToPx(mContext, 1)));
            }

            // Newest at the BOTTOM: iterate REVERSE so index 0 (newest) ends last.
            for (int i = mDirCtrl.getHistoryList().size() - 1; i >= 0; i--) {
                final String directory = mDirCtrl.getHistoryList().get(i);
                TextView tv = new TextView(mContext);
                tv.setText(directory);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tv.setTextColor(mColorScheme.getHistoryTextColor());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setPadding(padH, padV, padH, padV);
                tv.setClickable(true);
                tv.setTag(i);
                content.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                mItemViews.add(tv);
            }
        }

        int popupWidth = Math.min(
                mContext.getResources().getDisplayMetrics().widthPixels - TermuxActivityUtils.dpToPx(mContext, 24),
                TermuxActivityUtils.dpToPx(mContext, 320));

        ScrollView scroll = new ScrollView(mContext);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mScroll = scroll;
        // Clip children to the popup's rounded corners so highlights/separators near the
        // edges don't spill outside. Guard against 0 dims during WRAP_CONTENT resize: leave
        // the outline empty (no clipping) instead of clipping to nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, mOutlineRadiusPx);
                    }
                }
            });
            scroll.setClipToOutline(true);
        }

        mPopup = new PopupWindow(scroll, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // Bottom panel (!mInverted): popup opens ABOVE the button and must grow from its BOTTOM
        // edge — PopupWindow resolves the animation BEFORE mAboveAnchor is set, so without an
        // explicit style it would grow from the TOP. Top panel: framework default already correct.
        if (!mInverted) {
            mPopup.setAnimationStyle(R.style.HistoryPopupGrowFromBottomAnimation);
        }
        // Elevation shadow needs a fully opaque background drawable for WindowManager to
        // derive a valid Outline (GradientDrawable.getOutline bails when alpha < 255); the
        // 10% visual transparency is applied to the ScrollView via setAlpha() instead.
        mPopup.setElevation(TermuxActivityUtils.dpToPx(mContext, 16));
        // Rounded rect, fully opaque scheme colour; getOutline() overrides outline.setAlpha()
        // so shadow opacity can differ from elevation size.
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty()) {
                    // setAlpha requires API 31+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        outline.setAlpha(0.65f);
                    }
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(TermuxActivityUtils.dpToPx(mContext, 12));
        popupBgDrawable.setColor(mColorScheme.getHistoryPopupBg()); // must be opaque for getOutline
        mPopup.setBackgroundDrawable(popupBgDrawable);
        // 10% visual transparency on content only (not the background, so the outline stays valid).
        scroll.setAlpha(0.9f);
        mPopup.setClippingEnabled(true);
        mPopup.setTouchable(false);
        mPopup.setFocusable(false);

        // Measure first so the popup is placed in one window transaction: showAsDropDown(below)
        // + update(above) flashed a frame in the wrong spot and paid a second IPC/re-layout.
        content.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeight = content.getMeasuredHeight();
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int popupGap = TermuxActivityUtils.dpToPx(mContext, mPopupGapDp);
        int maxHeight;
        if (mInverted) {
            int screenHeight = mContext.getResources().getDisplayMetrics().heightPixels;
            int roomBelow = screenHeight - (anchorLoc[1] + anchor.getHeight()) - popupGap;
            maxHeight = Math.min(TermuxActivityUtils.dpToPx(mContext, mPopupMaxHeightDp), roomBelow);
            int popupHeight = Math.min(contentHeight, maxHeight);
            mPopup.setHeight(popupHeight);
            // Position the popup BELOW the anchor, flush to it (no gap).
            mPopup.showAsDropDown(anchor, 0, 0, Gravity.START);

            // Start scrolled to the TOP — newest entries are at the top in inverted mode.
            final ScrollView scrollRef = scroll;
            scrollRef.post(() -> scrollRef.scrollTo(0, 0));
        } else {
            int roomAbove = Math.max(TermuxActivityUtils.dpToPx(mContext, 48), anchorLoc[1] - TermuxActivityUtils.dpToPx(mContext, 8) - popupGap);
            maxHeight = Math.min(TermuxActivityUtils.dpToPx(mContext, mPopupMaxHeightDp), roomAbove);
            int popupHeight = Math.min(contentHeight, maxHeight);
            mPopup.setHeight(popupHeight);
            mPopup.showAsDropDown(anchor, 0, -(anchor.getHeight() + popupHeight + popupGap), Gravity.START);

            // jump straight to the bottom — fullScroll(FOCUS_DOWN) animates, which looks
            // like the list is scrolling past entries as the popup appears.
            final ScrollView scrollRef = scroll;
            scrollRef.post(() -> {
                View child = scrollRef.getChildAt(0);
                if (child != null) scrollRef.scrollTo(0, child.getHeight());
            });
        }
    }

    /** Dismiss the popup and reset all highlight / auto-scroll state. */
    public void dismiss() {
        if (mPopup != null) {
            try { mPopup.dismiss(); } catch (Exception ignored) {}
            mPopup = null;
        }
        mItemViews.clear();
        mScroll = null;
        mAutoScrolling = false;   // stop any pending edge-scroll loop
        mLastScrollTimeMs = 0;
        mHighlightIndex = -1;
        mActiveHighlightView = null;
        mAnchor = null;
    }

    /** @return true while the directory-history popup is visible. */
    public boolean isShowing() {
        return mPopup != null && mPopup.isShowing();
    }

    /** @return the index of the currently highlighted item, or {@link #CLEAR_ALL_TAG}/-1. */
    public int getHighlightIndex() {
        return mHighlightIndex;
    }

    /**
     * Track the finger to highlight the item under it while the popup is open.
     * Mirrors the message-popup highlight behaviour (sharp accent fill + contrast
     * text on the active row, plain text otherwise) and keeps edge auto-scroll alive.
     */
    public void updateHighlight(float rawX, float rawY) {
        if (mScroll == null || !isShowing()) return;

        // Remember the finger position so the continuous edge auto-scroll keeps
        // running even when the finger rests (no ACTION_MOVE) on the edge band.
        mFingerY = rawY;
        autoScrollNearEdge();

        // P1 hit-test: one getLocationOnScreen, then derive each row's screen rect from
        // getTop()/getLeft() + scroll offset — not a hierarchy walk per row (~12k/sec).
        int newIndex = -1;
        TextView newView = null;
        mScroll.getLocationOnScreen(mTmpLoc);
        final int scrollTop = mTmpLoc[1] - mScroll.getScrollY();
        final int scrollLeft = mTmpLoc[0] - mScroll.getScrollX();
        final int scrollBottom = mTmpLoc[1] + mScroll.getHeight();
        if (rawY >= scrollTop && rawY <= scrollBottom) {
            for (TextView tv : mItemViews) {
                int tvTop = scrollTop + tv.getTop();
                int tvBottom = tvTop + tv.getHeight();
                int tvLeft = scrollLeft + tv.getLeft();
                int tvRight = tvLeft + tv.getWidth();
                if (rawX >= tvLeft && rawX <= tvRight
                        && rawY >= tvTop && rawY <= tvBottom) {
                    Object tag = tv.getTag();
                    if (tag instanceof Integer) {
                        newIndex = (Integer) tag;
                        newView = tv;
                    }
                    break;
                }
            }
        }
        if (newIndex == mHighlightIndex) return;

        // P1 repaint: only the two affected rows, not the whole list. Text colour is set at build.
        if (mActiveHighlightView != null) {
            mActiveHighlightView.setBackgroundColor(Color.TRANSPARENT);
            mActiveHighlightView = null;
        }
        if (newView != null) {
            newView.setBackgroundColor(mColorScheme.getHistoryHighlightFill());
            mActiveHighlightView = newView;
        }
        mHighlightIndex = newIndex;
    }

    /** Scroll continuously while the finger rests/drags in a 36dp edge band (postOnAnimation loop). */
    private void autoScrollNearEdge() {
        if (mScroll == null || !isShowing()) {
            mAutoScrolling = false;
            return;
        }
        mScroll.getLocationOnScreen(mTmpLoc);
        int top = mTmpLoc[1];
        int bottom = mTmpLoc[1] + mScroll.getHeight();
        int band = TermuxActivityUtils.dpToPx(mContext, 36);      // edge-sensitive zone
        int maxStep = TermuxActivityUtils.dpToPx(mContext, 24);   // max px scrolled per 16ms reference interval

        // Time-based step so speed is consistent across 60/90/120 Hz displays.
        long now = SystemClock.uptimeMillis();
        float frameRatio;
        if (mLastScrollTimeMs == 0) {
            frameRatio = 1f;
        } else {
            long dt = Math.min(now - mLastScrollTimeMs, 48L);
            frameRatio = dt / 16f;
        }
        mLastScrollTimeMs = now;

        float rawY = mFingerY;
        int step;
        if (rawY < top + band) {
            float t = Math.min(1f, (top + band - rawY) / band);
            step = -Math.round(maxStep * t * frameRatio);
        } else if (rawY > bottom - band) {
            float t = Math.min(1f, (rawY - (bottom - band)) / band);
            step = Math.round(maxStep * t * frameRatio);
        } else {
            mAutoScrolling = false;
            return;
        }

        mScroll.scrollBy(0, step);

        // Keep the loop alive; reschedule on next vsync for smooth scrolling.
        mAutoScrolling = true;
        mScroll.postOnAnimation(this::autoScrollNearEdge);
    }

    /** Dispatch a directory pick for the given highlight index (no-op if out of range). */
    public void pick(int index) {
        if (index < 0 || index >= mDirCtrl.getHistoryList().size()) return;
        mCallback.onDirectoryPicked(mDirCtrl.getHistoryList().get(index));
    }

    /** "CLEAR HISTORY…" item: confirm, then wipe the whole directory history. */
    public void confirmClear() {
        final MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(mContext);
        b.setTitle(mContext.getString(R.string.directory_history_clear_dialog_title));
        b.setMessage(mContext.getString(R.string.directory_history_clear_confirm));
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            mCallback.onClearAllDirectories();
        });
        b.setNegativeButton(android.R.string.no, null);
        androidx.appcompat.app.AlertDialog dialog = b.create();
        dialog.show();
    }
}
