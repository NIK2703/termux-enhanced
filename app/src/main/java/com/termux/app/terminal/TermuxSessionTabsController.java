package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionTabsController {

    private final TermuxActivity mActivity;
    private final LinearLayout mTabsContainer;
    private final HorizontalScrollView mTabsScroll;
    private int mCurrentSessionIndex = -1;

    /**
     * False until the strip has been built at least once. The very first updateTabs() after a
     * cold start (Activity finished via BACK and reopened) must reveal the RESTORED active tab,
     * not pin the strip to the right end — so we distinguish "initial population" from a
     * user-initiated tab add (which legitimately scrolls to the end).
     */
    private boolean mBuilt = false;

    // Last terminal scheme colors applied via applySchemeColorsToTabs(). Stored so that tabs
    // created later in updateTabs() (e.g. a freshly opened session) are colored from the
    // Termux:Style scheme too, instead of the hardcoded layout default color.
    private int mSchemeTextColor = 0xFF000000;
    private int mSchemeBg = 0x4D000000;
    private int mSchemeBgActive = 0x80000000;
    private boolean mSchemeApplied = false;

    /** Whether the trailing placeholder page is currently present (for tab-strip blending). */
    private boolean mPlaceholderActive = false;

    /** Backing preference store for the tab-strip settings (tab height mode). */
    private final android.content.SharedPreferences mPrefs;

    /**
     * Cached, resolved metrics + preference values. Read through {@link #ensureDimens()} so a
     * title refresh no longer hits SharedPreferences twice (and {@code getDimension()} four
     * times) per tab per frame — with 8 tabs at 10-30 Hz that was ~500 synchronized SP reads
     * and ~1000 resource lookups per second for values that essentially never change.
     * Invalidated by {@link #invalidateDimens()} (tab-height mode change / styling reload).
     */
    private boolean mDimensCached = false;
    private boolean mSingleLineMode = true;
    private int mTabHeightPx = 0;
    private int mPadStartPx = 0;
    private int mCloseGapPx = 0;
    private int mTitleMaxWidthPx = 0;
    private float mTabCornerRadiusPx = 0f;

    /**
     * Cached per-tab render state (stored as the {@link R.id#session_tab_render_state_tag}
     * tag). Makes a title-only refresh a pure diff: every visual attribute is re-applied
     * only when it actually changed. This is what turns a 10-30 Hz OSC title animation from
     * a full measure/layout/StaticLayout/drawable-allocation storm per frame into a handful
     * of tag reads for the unchanged tabs and one setText for the animated one.
     *
     * <p>It also carries the tab's child views and its background drawable so the hot paths
     * ({@code onPageScrolled}, {@code applySchemeColorsToTabs}) never walk the view tree or
     * allocate a fresh {@link GradientDrawable} per frame.
     */
    private static final class TabRenderState {
        final TextView titleView;           // resolved once — never findViewById again
        final ImageButton closeButton;      // resolved once — never findViewById again
        TerminalSession session;   // session the click listeners are currently bound to
        CharSequence title;        // last title applied to the TextView
        int maxLines = -1;
        int padStart = -1;
        boolean truncated;         // last StaticLayout truncation decision
        int maxWidthApplied = -1;  // last title maxWidth applied
        int closeMarginApplied = -1; // last close-button marginStart applied
        int bgColor;               // last background colour applied
        boolean bgValid = false;
        boolean selected;
        boolean running = true;
        int exitStatus;
        boolean errorColor;
        int textColorApplied = 0;  // last scheme text colour applied (drives the colour diff)
        GradientDrawable bgDrawable; // reused background — mutated, never reallocated

        TabRenderState(View tabView) {
            titleView = tabView.findViewById(R.id.session_tab_title);
            closeButton = tabView.findViewById(R.id.session_tab_close);
        }
    }

    /**
     * @return the render state for {@code tabView}, creating (and view-resolving) it on first use.
     */
    @androidx.annotation.NonNull
    private TabRenderState getRenderState(@androidx.annotation.NonNull View tabView) {
        TabRenderState state = (TabRenderState) tabView.getTag(R.id.session_tab_render_state_tag);
        if (state == null) {
            state = new TabRenderState(tabView);
            tabView.setTag(R.id.session_tab_render_state_tag, state);
        }
        return state;
    }

    /** Resolve and cache the strip metrics / preference values. Cheap no-op after the first call. */
    private void ensureDimens() {
        if (mDimensCached) return;
        mDimensCached = true;
        mSingleLineMode = !"double".equals(mPrefs.getString("tab_height_mode", "single"));
        android.content.res.Resources r = mActivity.getResources();
        mTabHeightPx = Math.round(r.getDimension(mSingleLineMode
                ? R.dimen.terminal_tab_height_single
                : R.dimen.terminal_tab_height_double));
        mPadStartPx = Math.round(r.getDimension(mSingleLineMode
                ? R.dimen.terminal_tab_padding_start_single
                : R.dimen.terminal_tab_padding_start_double));
        mCloseGapPx = Math.round(r.getDimension(R.dimen.terminal_tab_close_gap));
        mTitleMaxWidthPx = Math.round(r.getDimension(R.dimen.terminal_tab_title_max_width));
        mTabCornerRadiusPx = r.getDimension(R.dimen.terminal_tab_corner_radius);
    }

    /**
     * Drop the cached strip metrics so the next {@link #ensureDimens()} re-reads the tab-height
     * preference and the resources. Call when the tab-height mode changes or the activity
     * styling is reloaded.
     */
    public void invalidateDimens() {
        mDimensCached = false;
    }

    /**
     * Drop all cached per-tab render state so the next populateTabView pass re-applies every
     * attribute unconditionally. Called after changes applied OUTSIDE the diff
     * ({@link #applySchemeColorsToTabs} / {@link #applyTabHeightMode}), keeping the cache
     * honest without teaching those two paths about the diff internals.
     */
    private void invalidateRenderStateCache() {
        if (mTabsContainer == null) return;
        for (int i = 0; i < mTabsContainer.getChildCount(); i++) {
            mTabsContainer.getChildAt(i).setTag(R.id.session_tab_render_state_tag, null);
        }
    }

    public TermuxSessionTabsController(TermuxActivity activity) {
        this.mActivity = activity;
        this.mPrefs = activity.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE);
        this.mTabsContainer = activity.findViewById(R.id.session_tabs);
        this.mTabsScroll = activity.findViewById(R.id.session_tabs_scroll);

        // Enable the CHANGING layout transition only: when a tab is removed the tabs to its right
        // glide smoothly into the freed space (and our own close animation drives the width). We do
        // NOT enable APPEARING/CHANGE_APPEARING — those animate a newly-added tab's width 0->full,
        // which makes the scrollable width unstable for ~220ms after an add and was the root cause of
        // the janky/under-scrolling end-scroll. Adding a tab at its full measured width lets the
        // strip geometry settle within one layout pass, so the right-end scroll reads correct widths.
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.setDuration(220);
        transition.setInterpolator(LayoutTransition.CHANGING,
                new AccelerateDecelerateInterpolator());
        mTabsContainer.setLayoutTransition(transition);
    }

    private int getTabCount() {
        if (mTabsContainer == null) return 0;
        return mTabsContainer.getChildCount() - 1; // last child is the (+) button, not a tab
    }

    @Nullable
    private View getTabAt(int index) {
        if (mTabsContainer == null) return null;
        return mTabsContainer.getChildAt(index);
    }

    /** Re-apply height, max-lines and start padding to all existing tab views. */
    public void applyTabHeightMode() {
        if (mTabsContainer == null) return;
        // The tab-height preference just changed — the cached metrics are stale.
        invalidateDimens();
        ensureDimens();
        final int heightPx = mTabHeightPx;
        final boolean singleLine = mSingleLineMode;
        final int padStartPx = mPadStartPx;
        for (int i = 0, n = getTabCount(); i < n; i++) {
            View tabView = getTabAt(i);
            if (tabView == null) continue;
            ViewGroup.LayoutParams lp = tabView.getLayoutParams();
            if (lp == null) continue;
            lp.height = heightPx;
            tabView.setLayoutParams(lp);
            tabView.setPadding(padStartPx,
                    tabView.getPaddingTop(),
                    tabView.getPaddingRight(),
                    tabView.getPaddingBottom());
            TabRenderState state = getRenderState(tabView);
            if (state.titleView != null) state.titleView.setMaxLines(singleLine ? 1 : 2);
        }
        // The populateTabView diff cache holds values applied under the previous height/mode;
        // drop it so the next refresh re-applies every attribute with the new settings.
        invalidateRenderStateCache();
    }

    public void updateTabs(List<TermuxSession> sessions) {
        if (mTabsContainer == null) return;

        TerminalSession currentSession = mActivity.getCurrentSession();
        int currentSessionIndex = -1;
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession s = sessions.get(i);
            if (s != null && s.getTerminalSession() == currentSession) {
                currentSessionIndex = i;
                break;
            }
        }

        // The add-tab button is the LAST child of mTabsContainer, so the real
        // session count is always childCount - 1.
        int sessionCount = mTabsContainer.getChildCount() - 1;
        int newCount = sessions.size();

        if (newCount > sessionCount) {
            // Add new tab views immediately before the add button. Existing views
            // (and the add button) stay in place, scrollX preserved.
            for (int i = sessionCount; i < newCount; i++) {
                TermuxSession termuxSession = sessions.get(i);
                View tabView = createTabView(termuxSession, i, i == currentSessionIndex);
                mTabsContainer.addView(tabView, mTabsContainer.getChildCount() - 1);
            }
        } else if (newCount < sessionCount) {
            // Remove trailing tab views (not the add button). No full wipe, scrollX preserved.
            mTabsContainer.removeViews(newCount, sessionCount - newCount);
            // A tab was closed — any sticky end-scroll from a previous add no longer applies.
            mEndScrollActive = false;
        }

        // Update titles, colors and selection state on ALL tabs in-place.
        // This covers the equal-count case (no structural change) and also syncs the
        // newly-added tabs above. No removeAllViews(), so the HorizontalScrollView keeps
        // its current scrollX. Skips the add button (last child).
        final int tabChildCount = mTabsContainer.getChildCount() - 1;
        for (int i = 0; i < tabChildCount && i < newCount; i++) {
            TermuxSession termuxSession = sessions.get(i);
            View tabView = mTabsContainer.getChildAt(i);
            // Never disturb a tab playing its close animation.
            if (Boolean.TRUE.equals(tabView.getTag(R.id.session_tab_closing_tag))) continue;
            populateTabView(tabView, termuxSession, i, i == currentSessionIndex);
        }

        // Scroll after the rebuild, through the single-owner requestScroll() (last-call-wins, so a
        // centre request from setCurrentSession() and this end request can never run two competing
        // smoothScrollTo()s in one frame).
        mCurrentSessionIndex = currentSessionIndex;
        if (!mBuilt) {
            // FIRST build (cold start after the app was finished via BACK, or a fresh launch).
            // Smoothly scroll the strip so the restored active tab is centred/visible. We go through
            // requestScroll(SCROLL_CENTRE) so the geometry is measured only AFTER the container is
            // laid out (deferred OnGlobalLayoutListener) — this fixes the old cold-start bug where a
            // plain post()'d centre read tabView.getLeft() before layout (geometry 0) and no-op'd to
            // scrollX=0, leaving the active tab off-screen. A user-initiated add (next branch) still
            // scrolls to the right end via scrollStripToEnd().
            requestScroll(SCROLL_CENTRE, currentSessionIndex);
            mBuilt = true;
        } else if (newCount > sessionCount) {
            // A new tab was just added: scroll the strip to its absolute right end so the new tab
            // AND the trailing (+) button are fully revealed. Both add paths (the (+) button tap and
            // the right-swipe placeholder commit) funnel through here, so this single call covers
            // them both. scrollStripToEnd() measures geometry only after the container is laid out
            // (see runEndScroll), so it always reaches the true right edge — no under-scroll.
            scrollStripToEnd();
        } else if (currentSessionIndex >= 0) {
            // Equal-count (structural) update — e.g. a rename or the onStart resync. No
            // auto-centre: with high-frequency title traffic this branch used to post a
            // 220 ms centre animator on EVERY frame (and overlapping animators fought over
            // scrollX). Explicit navigation still centres via scrollToTabIndex()/the cold
            // start path above; a jump-free clamp is enough to keep the active tab visible
            // when its width changed. If we are still in the sticky end-scroll from a
            // just-added tab, do NOT move at all — that would yank the strip back from the
            // right end. A real tab switch goes through setCurrentSession(), which clears
            // mEndScrollActive first.
            if (!mEndScrollActive) clampActiveTabVisible();
        }

        // Hide the (+) add-tab button once the terminal session limit is reached,
        // and restore it whenever a slot frees up (a tab is closed).
        updateAddButtonVisibility(sessions.size());
    }

    /**
     * Cosmetic-only refresh for high-frequency title updates (OSC spinners/progress).
     * Refreshes labels/colours of the EXISTING tab views via the populateTabView diff.
     *
     * <p>Never performs structural work: if the session count and the view count disagree
     * (a concurrent add/remove, e.g. the 100 ms close animation window), it simply returns
     * and lets the structural event's own {@link #updateTabs} call reconcile the strip.
     * Doing structure here would re-add a view for a session whose tab is mid-collapse.
     *
     * <p>Does NOT scroll the strip — only a jump-free {@link #clampActiveTabVisible()} keeps
     * the active tab revealed — and does not touch the pager or the session snapshot.
     */
    public void refreshTabAppearance(List<TermuxSession> sessions) {
        if (mTabsContainer == null) return;
        if (sessions.size() != getTabCount()) return;
        TerminalSession currentSession = mActivity.getCurrentSession();
        int currentSessionIndex = -1;
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession s = sessions.get(i);
            if (s != null && s.getTerminalSession() == currentSession) {
                currentSessionIndex = i;
                break;
            }
        }
        if (currentSessionIndex >= 0) mCurrentSessionIndex = currentSessionIndex;
        final int tabCount = getTabCount();
        final int sessionCount = sessions.size();
        for (int i = 0; i < tabCount && i < sessionCount; i++) {
            View tabView = getTabAt(i);
            if (tabView == null) continue;
            // Never disturb a tab playing its close animation.
            if (Boolean.TRUE.equals(tabView.getTag(R.id.session_tab_closing_tag))) continue;
            populateTabView(tabView, sessions.get(i), i, i == currentSessionIndex);
        }
        clampActiveTabVisible();
    }

    /**
     * Cheap, animation-free clamp: if a title width change pushed the active tab partially
     * outside the visible strip, scroll the MINIMAL distance to reveal it. Never recentres
     * (no jump), and never fights the end-scroll reservation (freshly added tab) or an
     * in-flight pager swipe.
     */
    private void clampActiveTabVisible() {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (mEndScrollActive) return;
        if (mActivity.isTerminalPageSwitchInProgress()) return;
        final int idx = mCurrentSessionIndex;
        if (idx < 0 || idx >= mTabsContainer.getChildCount() - 1) return;
        final View tabView = mTabsContainer.getChildAt(idx);
        if (tabView == null) return;
        final int viewport = mTabsScroll.getWidth();
        if (viewport <= 0) return;
        final int scrollX = mTabsScroll.getScrollX();
        final int maxScroll = Math.max(0, mTabsContainer.getMeasuredWidth() - viewport);
        int target = scrollX;
        if (tabView.getLeft() < scrollX) {
            target = tabView.getLeft();                          // clipped on the left
        } else if (tabView.getRight() > scrollX + viewport) {
            target = tabView.getRight() - viewport;              // clipped on the right
        }
        target = Math.max(0, Math.min(target, maxScroll));
        if (target != scrollX) mTabsScroll.scrollTo(target, 0);
    }

    /**
     * Show or hide the trailing (+) add-tab button based on the number of open sessions.
     * Once {@link TermuxTerminalSessionActivityClient#MAX_SESSIONS} is reached there is
     * nothing left to add, so the button is hidden; it reappears as soon as a slot frees up.
     */
    public void updateAddButtonVisibility(int sessionCount) {
        if (mTabsContainer == null) return;
        View addBtn = mTabsContainer.findViewById(R.id.new_session_tab_button);
        if (addBtn == null) return;
        // Use GONE once the session limit is reached so the button's footprint (width + marginEnd)
        // is released and no dead trailing gap is left in the strip. This is safe now that the
        // end-scroll is a self-driven ValueAnimator calling scrollTo() to a fixed target (no live
        // HSV re-clamp), and the target is measured in endScrollLayout AFTER the button has already
        // been hidden — so the strip scrolls exactly to the last real tab with no extra offset.
        addBtn.setVisibility(sessionCount >= TermuxTerminalSessionActivityClient.MAX_SESSIONS
                ? View.GONE : View.VISIBLE);
    }

    /**
     * Inflate a brand-new tab view.  The caller is responsible for populating it
     * (populateTabView is called at the end).
     */
    private View createTabView(TermuxSession termuxSession, int position, boolean isSelected) {
        LayoutInflater inflater = LayoutInflater.from(mActivity);
        View tabView = inflater.inflate(R.layout.item_session_tab, mTabsContainer, false);

        // Apply the configured tab height (compact single-line vs tall two-line).
        ensureDimens();
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        lp.height = mTabHeightPx;
        tabView.setLayoutParams(lp);

        // Populate text, colors, selection state and (re)bind click listeners.
        // populateTabView() always attaches click listeners to the view with the
        // correct session reference, so we do NOT set them here — doing so would
        // be immediately overwritten anyway.
        populateTabView(tabView, termuxSession, position, isSelected);
        return tabView;
    }

    /**
     * Populate or refresh an existing tab view's content (title, colours, selection state,
     * close button visibility) and rebind click listeners to the current session.
     *
     * <p>Every attribute is applied through the per-tab {@link TabRenderState} cache, so a
     * repeat call with unchanged values is a pure no-op (no setText, no padding, no
     * StaticLayout, no listener churn). This is what keeps high-frequency OSC title
     * animations (10-30 Hz) off the measure/layout path of the strip.
     *
     * <p>Click listeners are re-attached only when the session mapped to this view changed:
     * updateTabs() reuses tab views by position, and when a middle session is closed the
     * views shift down — without rebinding, the old listener's closure would still reference
     * the (dead) session that was at this position when the view was <em>created</em>.</p>
     */
    private void populateTabView(View tabView, TermuxSession termuxSession, int position, boolean isSelected) {
        ensureDimens();
        TabRenderState state = (TabRenderState) tabView.getTag(R.id.session_tab_render_state_tag);
        final boolean freshState = (state == null);
        if (freshState) {
            state = new TabRenderState(tabView);
            tabView.setTag(R.id.session_tab_render_state_tag, state);
        }
        // Resolved once, in the TabRenderState constructor — never findViewById again.
        final TextView titleView = state.titleView;
        final ImageButton closeButton = state.closeButton;
        if (titleView == null) return;

        TerminalSession terminalSession = termuxSession.getTerminalSession();

        // (Re)bind the session reference and the click listeners only when the session mapped
        // to this view changed (structural reuse after a middle tab closed). Keeps the
        // stale-closure fix without allocating three lambdas per tab per animation frame.
        if (freshState || state.session != terminalSession) {
            state.session = terminalSession;
            // Bind the session reference onto the view so closeSession() can locate the exact
            // tab to animate without relying on positional index (which shifts as other tabs
            // open/close).
            tabView.setTag(R.id.session_tab_session_tag, terminalSession);
            // Instant switch (animate=false): the pager jumps straight to the target page with
            // no smooth scroll, so no intermediate onPageScrolled events scroll the tab strip
            // through or highlight the in-between tabs. The clicked tab activates immediately.
            tabView.setOnClickListener(v -> {
                if (terminalSession != null)
                    mActivity.getTermuxTerminalSessionClient().setCurrentSession(terminalSession, false, false);
            });
            tabView.setOnLongClickListener(v -> {
                if (terminalSession != null)
                    mActivity.getTermuxTerminalSessionClient().renameSession(terminalSession);
                return true;
            });
            if (closeButton != null) {
                closeButton.setOnClickListener(v -> {
                    if (terminalSession != null)
                        closeSession(terminalSession);
                });
            }
        }

        // Max lines (single-line or two-line mode): apply only on change — setMaxLines()
        // requests a relayout unconditionally.
        boolean linesChanged = false;
        int desiredMaxLines = mSingleLineMode ? 1 : 2;
        if (state.maxLines != desiredMaxLines) {
            state.maxLines = desiredMaxLines;
            titleView.setMaxLines(desiredMaxLines);
            linesChanged = true;
        }

        // Start padding: apply only on change — View.setPadding() requests a relayout
        // unconditionally. Tighter for single-line (compact) tabs; the XML default (12dp)
        // applies to double-line tabs so the title has room for two lines.
        int desiredPadStart = mPadStartPx;
        if (state.padStart != desiredPadStart) {
            state.padStart = desiredPadStart;
            tabView.setPadding(desiredPadStart, tabView.getPaddingTop(),
                    tabView.getPaddingRight(), tabView.getPaddingBottom());
        }

        if (terminalSession != null) {
            String name = terminalSession.mSessionName;
            String title = terminalSession.getTitle();

            String displayTitle = SessionTitleUtils.resolveDisplayName(mActivity, name, title);

            // Title text: the only attribute expected to change on every animation frame.
            boolean titleChanged = !android.text.TextUtils.equals(state.title, displayTitle);
            if (titleChanged) {
                state.title = displayTitle;
                titleView.setText(displayTitle);
            }

            // When the title is truncated, reclaim the gap in front of the close (x) button and
            // hand it to the title (raise its max width by the same amount). The tab's total
            // width is unchanged, but more of the clipped label becomes visible. When the title
            // fits, restore the default gap and cap. The expensive StaticLayout measurement runs
            // only when the text (or the line mode) changed; maxWidth/margin are applied only
            // when the truncation DECISION flips.
            if (closeButton != null) {
                final int gapPx = mCloseGapPx;
                final int baseMaxWidthPx = mTitleMaxWidthPx;
                if (titleChanged || linesChanged || state.maxWidthApplied < 0) {
                    state.truncated = isTitleTruncated(titleView, displayTitle, baseMaxWidthPx);
                }
                int desiredMaxWidth = state.truncated ? baseMaxWidthPx + gapPx : baseMaxWidthPx;
                int desiredCloseMargin = state.truncated ? 0 : gapPx;
                if (state.maxWidthApplied != desiredMaxWidth) {
                    state.maxWidthApplied = desiredMaxWidth;
                    titleView.setMaxWidth(desiredMaxWidth);
                }
                if (state.closeMarginApplied != desiredCloseMargin) {
                    state.closeMarginApplied = desiredCloseMargin;
                    ViewGroup.MarginLayoutParams closeLp =
                            (ViewGroup.MarginLayoutParams) closeButton.getLayoutParams();
                    closeLp.setMarginStart(desiredCloseMargin);
                    closeButton.setLayoutParams(closeLp);
                }
            }

            // Text colour, close tint and strike-through derive solely from (running, exit).
            boolean sessionRunning = terminalSession.isRunning();
            int exitStatus = terminalSession.getExitStatus();
            boolean isErrorTab = !sessionRunning && exitStatus != 0;
            tabView.setTag(R.id.session_tab_error_tag, isErrorTab);
            // The colour also depends on the scheme's text colour, so a scheme change must be
            // part of the diff — otherwise the (no longer invalidated) cache would keep the
            // previous scheme's colour forever.
            if (freshState || state.running != sessionRunning
                    || state.exitStatus != exitStatus || state.errorColor != isErrorTab
                    || state.textColorApplied != mSchemeTextColor) {
                state.running = sessionRunning;
                state.exitStatus = exitStatus;
                state.errorColor = isErrorTab;
                state.textColorApplied = mSchemeTextColor;
                // Red for finished-with-error sessions, otherwise scheme foreground.
                if (isErrorTab) {
                    int errorColor = androidx.core.content.ContextCompat.getColor(
                        mActivity, com.termux.shared.R.color.terminal_tab_text_error);
                    titleView.setTextColor(errorColor);
                } else if (mSchemeApplied) {
                    titleView.setTextColor(mSchemeTextColor);
                }
                // Close-icon colour from the scheme.
                if (closeButton != null && mSchemeApplied) {
                    closeButton.setColorFilter(mSchemeTextColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
                }
                // Strike through for finished sessions.
                SessionAppearanceUtils.applyFinishedSessionStyling(titleView, sessionRunning,
                        exitStatus,
                        androidx.core.content.ContextCompat.getColor(mActivity, com.termux.shared.R.color.terminal_tab_text_error),
                        mSchemeTextColor);
            }
        }

        // Selection state: apply only on change (setSelected invalidates + re-renders).
        if (state.selected != isSelected) {
            state.selected = isSelected;
            tabView.setSelected(isSelected);
        }
        // Close button visibility: the diff reads the VIEW state (not the cache), so it
        // self-corrects against the mid-swipe toggling done by onPageScrolled().
        if (closeButton != null) {
            int desiredVisibility = isSelected ? View.VISIBLE : View.INVISIBLE;
            if (closeButton.getVisibility() != desiredVisibility)
                closeButton.setVisibility(desiredVisibility);
        }

        // Translucent background matching the signal panel: apply only when the target colour
        // differs from the cached one (setBackground allocates a new drawable every call).
        // Skipped mid-page-switch so the blend painted by onPageScrolled() is not snapped back
        // to a solid colour during the swipe (resetPageSelection re-asserts the clean state).
        if (mSchemeApplied && !mActivity.isTerminalPageSwitchInProgress()) {
            int desiredBg = isSelected ? mSchemeBgActive : mSchemeBg;
            if (!state.bgValid || state.bgColor != desiredBg) {
                setTabBackground(tabView, desiredBg);
                state.bgColor = desiredBg;
                state.bgValid = true;
            }
        }
    }

    /**
     * Whether {@code text} would be ellipsized inside {@code tv} given its current max width and
     * max lines. Measured synchronously with a {@link android.text.StaticLayout} so the result is
     * available immediately (the tab view is not yet laid out when this is called).
     */
    private boolean isTitleTruncated(TextView tv, String text, int maxWidthPx) {
        if (text == null || text.isEmpty()) return false;
        if (maxWidthPx <= 0) return false;
        // Fast reject: if the whole string already fits on a single line within maxWidth it can
        // neither wrap nor be ellipsized, so skip the comparatively expensive StaticLayout
        // measurement. Short titles ("bash", "vim", "~") — the overwhelming majority — take
        // this path, which is what keeps a 10-30 Hz OSC title animation off the layout path.
        if (tv.getPaint().measureText(text) <= maxWidthPx) return false;
        int maxLines = tv.getMaxLines();
        if (maxLines <= 0) maxLines = Integer.MAX_VALUE;
        android.text.StaticLayout layout = new android.text.StaticLayout(
                text, 0, text.length(), tv.getPaint(), maxWidthPx,
                android.text.Layout.Alignment.ALIGN_NORMAL,
                tv.getLineSpacingMultiplier(), tv.getLineSpacingExtra(),
                tv.getIncludeFontPadding(),
                android.text.TextUtils.TruncateAt.END, maxWidthPx);
        if (layout.getLineCount() > maxLines) return true;
        int lastLine = layout.getLineCount() - 1;
        return lastLine >= 0 && layout.getEllipsisCount(lastLine) > 0;
    }

    /**
     * Close a terminal session.  Runs a short "fly away & dissolve" animation on the
     * tab being closed, then finishes the underlying session.  The neighbours to the
     * right slide into the freed space via the container's LayoutTransition.
     */
    private void closeSession(TerminalSession session) {
        if (session == null) return;

        // Find the tab view that maps to this session.
        View tabView = null;
        if (mTabsContainer != null) {
            for (int i = 0; i < getTabCount(); i++) {
                View child = getTabAt(i);
                if (child == null) continue;
                Object tag = child.getTag(R.id.session_tab_session_tag);
                if (tag == session) {
                    tabView = child;
                    break;
                }
            }
        }

        if (tabView == null) {
            // No view found (e.g. race) — just finish the session, the normal
            // updateTabs() removal will follow.
            session.finishIfRunning();
            return;
        }

        // Guard against double-closing the same tab.
        if (Boolean.TRUE.equals(tabView.getTag(R.id.session_tab_closing_tag))) return;
        tabView.setTag(R.id.session_tab_closing_tag, Boolean.TRUE);

        animateTabClose(tabView, session);
    }

    /**
     * Animate the closing tab as the exact inverse of the open animation.
     *
     * <p>When a tab opens, its view is added to the container and its width grows
     * from 0 to full while the neighbours are pushed aside (the container's
     * LayoutTransition APPEARING + CHANGING animators). Closing mirrors this: we
     * collapse the closing view's width from its current value down to 0, which
     * makes the LinearLayout re-measure every frame so the surrounding tabs glide
     * inward to fill the freed space. A short alpha fade rides along so the shrinking
     * tab dissolves rather than clipping its contents.</p>
     *
     * <p>The view is removed from the container manually at the end of the animation
     * (before {@link TerminalSession#finishIfRunning()}), so the collapsed view never
     * flashes back to its original size: by the time updateTabs() runs the child is
     * already gone and the session/view counts already agree, so no trailing view is
     * removed and no stale view is re-shown.</p>
     */
    private void animateTabClose(final View tabView, final TerminalSession session) {
        // Disable the per-tab click while it animates out.
        tabView.setClickable(false);
        ImageButton closeBtn = tabView.findViewById(R.id.session_tab_close);
        if (closeBtn != null) closeBtn.setClickable(false);

        // Freeze the current pixel width and drive it down to 0. Using a fixed width
        // (instead of WRAP_CONTENT) lets the LinearLayout re-measure smoothly.
        final int startWidth = tabView.getWidth();
        if (startWidth <= 0) {
            // Not laid out yet — nothing to animate; remove immediately.
            removeClosingTab(tabView, session);
            return;
        }

        final ViewGroup.LayoutParams lp = tabView.getLayoutParams();

        // Suppress the container's DISAPPEARING/CHANGE reflow while we drive the
        // width ourselves, otherwise the two effects fight. CHANGING is already used
        // for the *open* case; here our per-frame requestLayout does the reflow.
        android.animation.ValueAnimator collapse =
                android.animation.ValueAnimator.ofInt(startWidth, 0);
        collapse.setDuration(100);
        collapse.setInterpolator(new AccelerateDecelerateInterpolator());
        collapse.addUpdateListener(animation -> {
            lp.width = (int) animation.getAnimatedValue();
            tabView.setLayoutParams(lp);
        });

        ObjectAnimator alpha = ObjectAnimator.ofFloat(tabView, View.ALPHA, 1f, 0f);

        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        set.playTogether(collapse, alpha);
        set.setDuration(100);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeClosingTab(tabView, session);
            }
        });
        set.start();
    }

    /**
     * Remove a fully-collapsed closing tab view from the container and finish its
     * session. Removing the view first keeps the container child count in sync with
     * the session count before updateTabs() runs, so no view is re-shown or
     * double-removed.
     */
    private void removeClosingTab(final View tabView, final TerminalSession session) {
        if (mTabsContainer != null) {
            mTabsContainer.removeView(tabView);
        }
        // Restore transient state on the (now detached) view in case it is ever
        // recycled by the layout system.
        tabView.setAlpha(1f);
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            tabView.setLayoutParams(lp);
        }
        tabView.setTag(R.id.session_tab_closing_tag, null);
        session.finishIfRunning();
    }

    /** Index scheduled for the next scroll runnable. */
    private int mPendingTabScrollIndex = -1;

    /**
     * Centre the tab at {@code index} in the strip. All scrolls funnel through the single-owner
     * {@link #requestScroll(int, int)} (last-call-wins), so an end-scroll requested by
     * {@link #scrollStripToEnd()} (on tab addition) wins over a centre request instead of fighting
     * it — that was the source of the previous janky scrolling.
     */
    // Single owner for all strip scrolls. CENTRE centres a tab; END scrolls to the absolute right
    // end (incl. the (+) button). last-call-wins across both modes — only ONE smoothScrollTo ever
    // runs per frame, so a centre request and an end request can never fight (which previously
    // caused janky scrolling when adding a tab).
    private static final int SCROLL_NONE = 0;
    private static final int SCROLL_CENTRE = 1;
    private static final int SCROLL_END = 2;
    private int mPendingScrollMode = SCROLL_NONE;
    // Set when an end-scroll is requested after a tab add. While active, a title-only refresh
    // (updateTabs with no size change) must NOT recentre the strip, otherwise the shell's OSC
    // title update would yank the strip back from the right end ~200ms after the add.
    private boolean mEndScrollActive = false;

    // Monotonic sequence guard: an END request always outranks a CENTRE request regardless of the
    // order in which they are posted to the looper. mScrollSeqPending is the sequence of the
    // runnable currently queued; a runnable whose sequence is older than the latest issued request
    // drops itself so a stale CENTRE can never clobber a newer END (the prior "last-call-wins"
    // rule was order-dependent and let a CENTRE posted after END win).
    private long mScrollSeq = 0;
    private long mScrollSeqPending = -1;

    /** Pending runnable that performs the end-scroll after the add animation settles. */
    private Runnable mPendingEndScroll = null;

    /** How long to wait for the new tab's width to finish growing before measuring the end. */
    private static final long END_SCROLL_DELAY_MS = 50;

    private final Runnable mScrollRunnable = new Runnable() {
        @Override
        public void run() {
            final long seq = mScrollSeqPending;
            mScrollSeqPending = -1;
            // A newer (higher-priority) request was issued after this runnable was queued: drop.
            if (seq < mScrollSeq) return;
            if (mTabsContainer == null || mTabsScroll == null) return;
            final int mode = mPendingScrollMode;
            mPendingScrollMode = SCROLL_NONE;
            if (mode == SCROLL_END) {
                runEndScroll();
            } else if (mode == SCROLL_CENTRE) {
                runCentreScroll(seq);
            }
        }
    };

    /**
     * Scroll the strip to its absolute right end (newly-added tab + trailing (+) button fully
     * revealed). The measurement is deferred so it runs only AFTER the new tab has finished growing
     * to its full width (a fixed delay covers the add animation) — by then the strip geometry is
     * final and the right end is measurable.
     *
     * <p>CRITICAL: we do NOT use mTabsScroll.smoothScrollTo(). HorizontalScrollView re-clamps the
     * in-flight smooth scroll to its content width on EVERY frame, so if the content width SHRINKS
     * while the animation runs (LayoutTransition CHANGING reflow, a late OSC title re-measure, or the
     * (+) button toggling GONE/VISIBLE) the strip freezes short of the true end — exactly the
     * "new tab + (+) button stay partly off-screen" symptom. Instead we drive scrollX ourselves via
     * a ValueAnimator that calls mTabsScroll.scrollTo(fixedTarget) to a target computed ONCE from the
     * settled final width, so the live re-clamp cannot shorten the trip. The LayoutTransition is
     * detached for the duration of the animation so the content width stays stable (the CHANGING
     * glide is sacrificed only for the add END-scroll; the close animation is unaffected).
     */
    private android.animation.ValueAnimator mEndScrollAnim = null;

    private void cancelEndScroll() {
        if (mEndScrollAnim != null) {
            mEndScrollAnim.cancel();
            mEndScrollAnim = null;
        }
        if (mPendingEndScroll != null && mTabsScroll != null) {
            mTabsScroll.removeCallbacks(mPendingEndScroll);
            mPendingEndScroll = null;
        }
    }

    private void runEndScroll() {
        if (mTabsContainer == null || mTabsScroll == null) return;

        // Cancel any in-flight end-scroll (a newer request, or a re-entry) before starting fresh.
        cancelEndScroll();

        // Wait for the new tab's width animation to settle before measuring the right end.
        mPendingEndScroll = new Runnable() {
            @Override
            public void run() {
                mPendingEndScroll = null;
                if (mTabsContainer == null || mTabsScroll == null) return;

                // Authoritative scrollable extent: the HorizontalScrollView clamps scroll to
                // [0, childMeasuredWidth - viewportWidth]. getMeasuredWidth() of the
                // wrap_content container already includes the (+) button's marginEnd and the
                // container padding, so this equals the true right end with nothing clipped.
                final int childMeasuredW = mTabsContainer.getMeasuredWidth();
                final int scrollW = mTabsScroll.getWidth();
                final int maxScroll = Math.max(0, childMeasuredW - scrollW);

                final int startX = mTabsScroll.getScrollX();
                if (startX == maxScroll) {
                    mEndScrollActive = false;
                    return;
                }
                // Self-driven scroll: we own the target, HSV's per-frame re-clamp is bypassed.
                mEndScrollAnim = android.animation.ValueAnimator.ofInt(startX, maxScroll);
                mEndScrollAnim.setDuration(250);
                mEndScrollAnim.setInterpolator(
                        new android.view.animation.AccelerateDecelerateInterpolator());
                mEndScrollAnim.addUpdateListener(anim ->
                        mTabsScroll.scrollTo((int) anim.getAnimatedValue(), 0));
                mEndScrollAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        mEndScrollActive = false;
                        mEndScrollAnim = null;
                    }
                    @Override
                    public void onAnimationCancel(android.animation.Animator animation) {
                        mEndScrollAnim = null;
                    }
                });
                mEndScrollAnim.start();
            }
        };
        mTabsScroll.postDelayed(mPendingEndScroll, END_SCROLL_DELAY_MS);
    }

    /** Queue a strip scroll. Single owner with a monotonic sequence so END always beats CENTRE. */
    private void requestScroll(int mode, int index) {
        if (mode == SCROLL_CENTRE) {
            // A CENTRE request must never clobber an in-flight/active END scroll. The sequence guard
            // (mScrollSeqPending) lets an END issued after this CENTRE win regardless of looper order.
            if (mEndScrollActive) return;
            mPendingTabScrollIndex = index;
        }
        mPendingScrollMode = mode;
        mScrollSeq++;
        mScrollSeqPending = mScrollSeq;
        if (mTabsScroll != null) {
            mTabsScroll.removeCallbacks(mScrollRunnable);
            mTabsScroll.post(mScrollRunnable);
        }
    }

    /** Pending runnable that performs the centre-scroll after the layout/width settles. */
    private Runnable mPendingCentreScroll = null;

    private void cancelCentreScroll() {
        if (mPendingCentreScroll != null && mTabsScroll != null) {
            mTabsScroll.removeCallbacks(mPendingCentreScroll);
            mPendingCentreScroll = null;
        }
    }

    /**
     * Centre a tab in the strip, measuring its geometry ONLY after the container has actually been
     * laid out. This mirrors the END path's OnGlobalLayoutListener deferred-measurement (runEndScroll)
     * and fixes the cold-start bug where a plain post()'d CENTRE read tabView.getLeft() before the
     * first layout pass (geometry 0) and no-op'd to scrollX=0, leaving the restored active tab
     * off-screen to the left. The measured scroll respects the live HSV clamp so it never overshoots.
     *
     * @param seq the sequence stamp captured by requestScroll BEFORE mScrollSeqPending was cleared,
     *            so the listener can drop itself if a newer (higher-priority) request supersedes it.
     */
    private void runCentreScroll(long seq) {
        if (mTabsContainer == null || mTabsScroll == null) return;

        cancelCentreScroll();

        final int idx = mPendingTabScrollIndex;
        if (idx < 0) return;

        // Wait for the new tab's width animation to settle before measuring the target.
        mPendingCentreScroll = new Runnable() {
            @Override
            public void run() {
                mPendingCentreScroll = null;
                if (mTabsContainer == null || mTabsScroll == null) return;
                // A newer (higher-priority) request was issued after this runnable was queued: drop.
                if (seq < mScrollSeq) return;
                if (idx >= mTabsContainer.getChildCount() - 1) return;
                final View tabView = mTabsContainer.getChildAt(idx);
                if (tabView == null) return;
                final int scrollW = mTabsScroll.getWidth();
                final int maxScroll = Math.max(0,
                        mTabsContainer.getMeasuredWidth() - scrollW);
                int scrollX = tabView.getLeft() - scrollW / 2 + tabView.getWidth() / 2;
                if (scrollX < 0) scrollX = 0;
                if (scrollX > maxScroll) scrollX = maxScroll;
                // Self-driven ValueAnimator writing a FIXED target is re-clamp-proof.
                // This is the CENTRE equivalent of runEndScroll(): HorizontalScrollView.smoothScrollTo()
                // re-clamps the in-flight animation to its (possibly changing) content width on
                // every frame, so the strip could yank left mid-animation on cold start — "janky".
                final int fromX = mTabsScroll.getScrollX();
                if (fromX == scrollX) return;
                final android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofInt(fromX, scrollX);
                anim.setDuration(220);
                anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                        if (mTabsScroll == null) return;
                        mTabsScroll.scrollTo((Integer) animation.getAnimatedValue(), 0);
                    }
                });
                anim.start();
            }
        };
        mTabsScroll.postDelayed(mPendingCentreScroll, END_SCROLL_DELAY_MS);
    }

    /**
     * Smoothly scroll the strip to its absolute right end (newly-added tab + trailing (+) button
     * fully revealed). Called ONLY after the new session's label has actually been set (the shell
     * emits an OSC title) — see TermuxTerminalSessionActivityClient.onTitleChanged. The new tab is
     * inserted at full width, so the container geometry is already final; the scroll is posted to
     * the next layout pass and reads the true right end there. Marks the strip end-scrolled so a
     * subsequent title-only refresh does not recentre it.
     */
    public void scrollStripToEnd() {
        if (mTabsContainer == null || mTabsScroll == null) return;
        // Mark the strip as end-scrolled so a subsequent title-only refresh does not recentre it.
        mEndScrollActive = true;
        // Post to the next layout pass — the new tab is inserted at full width, so by the time the
        // runnable runs the container has laid out at its final width and the end is measurable.
        requestScroll(SCROLL_END, -1);
    }

    /**
     * Force the tab strip to reveal (centre) a specific tab, waiting until the strip layout has
     * fully settled before measuring. Used for normal tab switches (not tab addition).
     */
    public void scrollToTabIndex(int index) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        final LayoutTransition transition = mTabsContainer.getLayoutTransition();
        if (transition != null && transition.isRunning()) {
            transition.addTransitionListener(new LayoutTransition.TransitionListener() {
                @Override
                public void startTransition(LayoutTransition t, ViewGroup container,
                                            View view, int transitionType) { }

                @Override
                public void endTransition(LayoutTransition t, ViewGroup container,
                                          View view, int transitionType) {
                    if (t.isRunning()) return;
                    t.removeTransitionListener(this);
                    requestScroll(SCROLL_CENTRE, index);
                }
            });
            return;
        }
        requestScroll(SCROLL_CENTRE, index);
    }

    /**
     * Re-apply the terminal color scheme to every existing tab view: text/close-icon color and a
     * translucent background matching the signal panel. Called from
     * {@code TermuxTerminalSessionActivityClient.applyPanelColors()} whenever the scheme changes.
     *
     * @param textColor  Foreground color derived from the scheme (contrast vs background).
     * @param bg         Translucent button background (dark for light schemes, light for dark).
     * @param bgActive   Translucent background for the selected tab.
     */
    public void applySchemeColorsToTabs(int textColor, int bg, int bgActive) {
        // Remember the scheme colors so tabs created later in updateTabs() are colored too.
        mSchemeTextColor = textColor;
        mSchemeBg = bg;
        mSchemeBgActive = bgActive;
        mSchemeApplied = true;

        if (mTabsContainer == null) return;
        Boolean errorTag = Boolean.TRUE;
        for (int i = 0, n = getTabCount(); i < n; i++) {
            View tabView = getTabAt(i);
            if (tabView == null) continue;
            TabRenderState state = getRenderState(tabView);
            // Error (finished-with-exit) tabs keep their red color; normal tabs use the scheme fg.
            if (state.titleView != null && !errorTag.equals(tabView.getTag(R.id.session_tab_error_tag))) {
                state.titleView.setTextColor(textColor);
            }
            if (state.closeButton != null) {
                state.closeButton.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
            }
            final boolean selected = tabView.isSelected();
            final int desiredBg = selected ? bgActive : bg;
            setTabBackground(tabView, desiredBg);
            // Keep the diff cache in sync with what we just painted INSTEAD of dropping it.
            // This method runs on every session switch (via applyPanelColors); invalidating the
            // cache here used to throw away the whole TabRenderState diff, so the very next
            // refresh re-applied every attribute (setMaxLines/setPadding/setText/setMaxWidth/
            // setLayoutParams/setTextColor/... → full relayout of every tab).
            state.bgColor = desiredBg;
            state.bgValid = true;
            state.textColorApplied = textColor;
            state.selected = selected;
        }

        // Give the (+) add button (last child, excluded from the loop above) its scheme
        // background while preserving the press/swipe active-state visual.
        applyAddButtonSchemeBackground();
    }

    /** Tell the controller whether the trailing placeholder page is present, so an in-progress
     *  right-swipe from the last tab blends that tab into the (+) add button. */
    public void setPlaceholderActive(boolean active) {
        mPlaceholderActive = active;
    }

    /**
     * Reserve the right-end scroll for a freshly-added tab. While reserved, the pager's
     * onPageScrolled() instant scrollTo() is suppressed (so it cannot fight the smooth END scroll)
     * and setCurrentSession() will not post a competing CENTRE scroll. The reservation is cleared
     * when the END scroll actually fires (after the new tab's label is set) or when the user makes
     * a genuine tab switch / closes a tab.
     */
    public void setEndScrollReserved(boolean reserved) {
        mEndScrollActive = reserved;
        if (!reserved) {
            // User-initiated navigation cancels the end-scroll reservation entirely: allow a
            // subsequent CENTRE request to win again. Also stop any in-flight self-driven scroll
            // so the user's drag is not fought.
            mScrollSeqPending = -1;
            cancelEndScroll();
        }
    }

    private void applyTabSelectionState(int index) {
        if (mTabsContainer == null) return;
        for (int i = 0, n = getTabCount(); i < n; i++) {
            View child = getTabAt(i);
            if (child == null) continue;
            TabRenderState state = getRenderState(child);
            boolean isSelected = (i == index);
            child.setSelected(isSelected);
            if (mSchemeApplied) {
                int desiredBg = isSelected ? mSchemeBgActive : mSchemeBg;
                setTabBackground(child, desiredBg);
                state.bgColor = desiredBg;
                state.bgValid = true;
            }
            state.selected = isSelected;
            if (state.closeButton != null) {
                state.closeButton.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
            }
        }
        applyAddButtonSchemeBackground();
    }

    private void applyAddButtonSchemeBackground() {
        if (mTabsContainer == null || !mSchemeApplied) return;
        View addBtn = mTabsContainer.getChildAt(mTabsContainer.getChildCount() - 1);
        if (addBtn != null) setAddButtonBackground(addBtn);
    }

    public void setCurrentSession(int index) {        if (mTabsContainer == null) return;
        if (index < 0 || index >= mTabsContainer.getChildCount() - 1) return;

        applyTabSelectionState(index);

        mCurrentSessionIndex = index;
        // A genuine tab selection (user tap / page settle) cancels any sticky end-scroll so the
        // strip can recentre on the chosen tab via updateTabs() (called from onSessionPageSelected).
        // BUT if an end-scroll is already reserved (we just added a tab), do NOT cancel it or post
        // a competing CENTRE scroll — that would fight the end-scroll. The end-scroll clears
        // mEndScrollActive itself once the animation finishes (see runEndScroll onAnimationEnd).
        if (mEndScrollActive) return;
        mEndScrollActive = false;
    }

    // ── Intermediate page-scroll support ──────────────────────────────────

    /**
     * Called during a horizontal page swipe ({@code onPageScrolled} from ViewPager2).
     * Interpolates the tab strip scroll position and the selection background of the
     * two adjacent tabs so the user sees a smooth transition between tabs rather than
     * an abrupt snap.
     *
     * @param position       the index of the left (leaving) page
     * @param positionOffset fraction from 0.0 (left page fully visible) to 1.0 (right
     *                       page fully visible)
     */
    public void onPageScrolled(int position, float positionOffset) {
        if (mTabsContainer == null || mTabsScroll == null) return;
        if (!mSchemeApplied) return;
        // Suppressed while the end-scroll owns the strip (a freshly added tab).
        if (mEndScrollActive) return;

        int leftIdx = position;
        int rightIdx = position + 1;

        // At offset 0 there is no transition, and at the very first frame of a
        // swipe the blend would be a no-op anyway (ratio ≈ 0). Guard to stay
        // efficient, but allow offset up to 1.0 so the final frame of a
        // cancelled gesture is full-strength.
        if (positionOffset <= 0f) return;
        if (leftIdx < 0) return;
        // Normally the (+) add button (last child) is excluded from blending. While the trailing
        // placeholder page is present, allow the last real tab (leftIdx == childCount-2) to blend
        // into the (+) button (rightIdx == childCount-1) so the strip follows the swipe exactly like
        // a normal tab-to-tab transition.
        if (rightIdx >= mTabsContainer.getChildCount() - 1
                && !(mPlaceholderActive && leftIdx == mTabsContainer.getChildCount() - 2)) return;

        View leftTab = mTabsContainer.getChildAt(leftIdx);
        View rightTab = mTabsContainer.getChildAt(rightIdx);
        if (leftTab == null || rightTab == null) return;

        // 1. Interpolate the HorizontalScrollView scroll position so the tab
        //    strip follows the finger during the swipe.
        int leftCenter = leftTab.getLeft() + leftTab.getWidth() / 2;
        int rightCenter = rightTab.getLeft() + rightTab.getWidth() / 2;
        int interpolatedCenter = (int) (leftCenter + (rightCenter - leftCenter) * positionOffset);
        int targetScrollX = interpolatedCenter - mTabsScroll.getWidth() / 2;
        mTabsScroll.scrollTo(targetScrollX, 0);

        // 2. Interpolate background tints: left tab fades from active → normal,
        //    right tab fades from normal → active.
        int blendedLeft = blendColors(mSchemeBgActive, mSchemeBg, positionOffset);
        int blendedRight = blendColors(mSchemeBg, mSchemeBgActive, positionOffset);
        setTabBackground(leftTab, blendedLeft);
        setTabBackground(rightTab, blendedRight);

        // 3. Show the close button on whichever tab the user is closer to.
        ImageButton leftClose = getRenderState(leftTab).closeButton;
        ImageButton rightClose = getRenderState(rightTab).closeButton;
        boolean closerToLeft = positionOffset < 0.5f;
        if (leftClose != null) leftClose.setVisibility(closerToLeft ? View.VISIBLE : View.INVISIBLE);
        if (rightClose != null) rightClose.setVisibility(closerToLeft ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Reset the visual tab selection state to a clean, non-interpolated state for
     * the given {@code currentIndex}.  Called from
     * {@code onPageScrollStateChanged(IDLE)} to clean up any intermediate blending
     * left by {@link #onPageScrolled} when a swipe gesture was cancelled (the
     * user started swiping then released back to the original page, which does
     * <b>not</b> fire {@code onPageSelected}).
     * <p>
     * This mirrors what {@link #setCurrentSession} does for selection state and
     * background tint, but without calling {@code smoothScrollTo} (which would
     * fight the already-correct scroll position).
     */
    public void resetPageSelection(int currentIndex) {
        if (mTabsContainer == null) return;
        applyTabSelectionState(currentIndex);
        mCurrentSessionIndex = currentIndex;
    }

    /**
     * Linearly interpolate between two ARGB colours.
     *
     * @param from  colour at ratio = 0.0
     * @param to    colour at ratio = 1.0
     * @param ratio blend factor in [0, 1]
     */
    /**
     * Set a rounded-rectangle background on a tab/view using the given scheme color,
     * preserving the rounded shape (the previous {@code setBackgroundTintList} flattened it).
     */
    /**
     * Set the background of the trailing (+) add button as an oval {@link StateListDrawable}
     * that shows an active (pressed / swipe-selected) fill. Unlike {@link #setTabBackground},
     * this preserves the press/swipe visual activation required by the add button, instead of
     * replacing it with a flat state-less rectangle. No stroke — the idle and active states
     * differ by fill only.
     */
    /** Cached (+) button drawable: rebuilt only when the scheme colours actually change. */
    private StateListDrawable mAddButtonDrawable = null;
    private int mAddButtonBg = 0;
    private int mAddButtonBgActive = 0;
    private boolean mAddButtonBgValid = false;

    private void setAddButtonBackground(View view) {
        if (!mAddButtonBgValid || mAddButtonDrawable == null
                || mAddButtonBg != mSchemeBg || mAddButtonBgActive != mSchemeBgActive) {
            mAddButtonDrawable = buildOvalStateListDrawable();
            mAddButtonBg = mSchemeBg;
            mAddButtonBgActive = mSchemeBgActive;
            mAddButtonBgValid = true;
        }
        if (view.getBackground() != mAddButtonDrawable) view.setBackground(mAddButtonDrawable);
    }

    private StateListDrawable buildOvalStateListDrawable() {
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(mSchemeBg);

        GradientDrawable active = new GradientDrawable();
        active.setShape(GradientDrawable.OVAL);
        active.setColor(mSchemeBgActive);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, active);
        states.addState(new int[]{android.R.attr.state_selected}, active);
        states.addState(new int[]{}, idle);
        return states;
    }

    private void setTabBackground(View view, int color) {
        TabRenderState state = (TabRenderState) view.getTag(R.id.session_tab_render_state_tag);
        GradientDrawable d = (state != null) ? state.bgDrawable : null;
        if (d == null) {
            d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            ensureDimens();
            d.setCornerRadius(mTabCornerRadiusPx);
            if (state != null) state.bgDrawable = d;
        }
        // Mutate the SAME drawable instead of allocating a fresh one per call: setColor()
        // invalidates it in place. onPageScrolled() calls this twice per swipe frame, so the
        // old version allocated two drawables and re-bound two backgrounds 60-120 times a second.
        d.setColor(color);
        if (view.getBackground() != d) view.setBackground(d);
    }

    private static int blendColors(int from, int to, float ratio) {
        int a = (int) (Color.alpha(from) * (1f - ratio) + Color.alpha(to) * ratio);
        int r = (int) (Color.red(from) * (1f - ratio) + Color.red(to) * ratio);
        int g = (int) (Color.green(from) * (1f - ratio) + Color.green(to) * ratio);
        int b = (int) (Color.blue(from) * (1f - ratio) + Color.blue(to) * ratio);
        return Color.argb(a, r, g, b);
    }
}
