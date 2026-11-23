package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalPreviewRenderer;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the all-sessions overview: lifecycle, card construction, preview snapshots, selection and
 * focus/IME hand-off.
 *
 * <p>The overview is a sibling view inside the activity's content area, not a dialog, so it follows
 * rotation, split-screen and insets automatically. While open, its top edge is extended under the
 * system status bar so the grid can scroll behind the transparent bar; the grid keeps a matching
 * top inset so cards at rest stay below the bar instead of being clipped by it. It renders live
 * emulator previews without attaching a second {@link TerminalView}, so opening it never changes
 * a PTY size.</p>
 */
public final class SessionOverviewController {

    private final TermuxActivity mActivity;
    private final FrameLayout mOverlay;
    private final SessionOverviewGridView mGrid;
    private final LayoutInflater mInflater;
    /** Live terminal background used for the window surface, shadows and status-bar icons. */
    private int mOverlayBackgroundColor = Color.BLACK;
    /** Siblings of the overlay that are transparent while it is open. */
    private final List<View> mHiddenLiveViews = new ArrayList<>();
    private final List<Float> mHiddenLiveViewAlphas = new ArrayList<>();
    private final List<Card> mCards = new ArrayList<>();
    @Nullable
    private final SessionOverviewDragController mDragController;
    private int mTabCornerRadius;



    @Nullable
    private final FrameLayout mGhostLayer;
    @Nullable
    private final ScrollView mScrollView;
    @Nullable
    private final View mContentArea;
    @Nullable
    private final View mTabPanel;
    @Nullable
    private final ViewGroup mRootView;

    /** Non-zero while the overlay is drawn behind the status bar, in pixels. */
    private int mStatusBarTopExtension;
    private boolean mStatusBarExtensionApplied;
    private int mOverlayOriginalTopMargin;
    private int mGridOriginalPaddingTop;
    private boolean mRootOriginalClipChildren;
    private boolean mRootOriginalClipToPadding;

    @Nullable
    private Transition mTransition;
    @Nullable
    private ClosingTransition mClosingTransition;
    @Nullable
    private Card mPendingClosingCard;

    /** How many frames to wait for the grid to lay out before starting with whatever exists. */
    private static final int MAX_START_ATTEMPTS = 20;

    /** 50% alpha for the compact pill so the terminal stays visible underneath it. */
    private static final int OVERVIEW_TAB_BACKGROUND_ALPHA = 0x80;

    private boolean mShowing;
    /** True between activity stop and resume while the overview stays open in the background. */
    private boolean mActivityStopped;
    private boolean mRenderPosted;
    private int mCardWidth;
    private int mCardHeight;

    /** Last overview scroll offset left by the user; -1 until the first close. */
    private int mRememberedScrollY = -1;
    /** True while an opening should start near {@link #mRememberedScrollY}. */
    private boolean mRestoreScrollOnOpen;
    @Nullable
    private ViewTreeObserver mScrollRestoreObserver;
    @Nullable
    private ViewTreeObserver.OnGlobalLayoutListener mScrollRestoreLayoutListener;

    @Nullable
    private View mPreviousFocus;
    private boolean mHadIme;
    /** True while a resume has put the window's IME into the overview's hidden mode. */
    private boolean mSoftKeyboardHiddenForResume;

    private final Runnable mRenderPreviewsRunnable = () -> {
        mRenderPosted = false;
        renderPreviews();
    };

    /** Coalesced repaint of only the cards whose sessions produced output. */
    private final List<TerminalSession> mPendingTextSessions = new ArrayList<>();
    private boolean mTextRenderPosted;
    private final Runnable mTextRenderRunnable = () -> {
        mTextRenderPosted = false;
        if (!mShowing || mActivityStopped || mCardWidth <= 0 || mCardHeight <= 0) {
            mPendingTextSessions.clear();
            return;
        }
        final int textSize = previewTextSize();
        final Typeface typeface = previewTypeface();
        for (TerminalSession session : mPendingTextSessions) {
            final Card card = findCard(session);
            if (card != null) renderCard(card, textSize, typeface);
        }
        mPendingTextSessions.clear();
    };

    /**
     * Repaint the previews while the overview is open. Output already triggers a repaint, but the
     * cursor blinker changes the emulator without a text event; a low-rate tick keeps the cards
     * looking like live terminals instead of frozen snapshots.
     */
    private final Runnable mPreviewRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mShowing || mActivityStopped || mGrid == null) return;
            schedulePreviewRender();
            mGrid.postDelayed(this, 500L);
        }
    };

    /**
     * Waits for the first real grid layout, then starts the single flight animator. The grid is
     * requested and posted before the traversal runs, so a few retries are expected; the cap
     * keeps a permanently unlaid-out window from leaving the overlay in its transparent start
     * state.
     */
    private final Runnable mStartTransitionRunnable = new Runnable() {
        @Override
        public void run() {
            final Transition transition = mTransition;
            if (transition == null || transition.started || !mShowing) return;
            if (!transitionTargetsReady(transition)) {
                if (++transition.startAttempts < MAX_START_ATTEMPTS && mGrid != null) {
                    mGrid.postOnAnimation(this);
                    return;
                }
            }
            scrollToActiveCard();
            startTransition(transition);
        }
    };

    public SessionOverviewController(@NonNull TermuxActivity activity) {
        mActivity = activity;
        mOverlay = activity.findViewById(R.id.session_overview);
        mGrid = activity.findViewById(R.id.session_overview_grid);
        mGhostLayer = activity.findViewById(R.id.session_overview_ghost_layer);
        mScrollView = activity.findViewById(R.id.session_overview_scroll);
        mContentArea = activity.findViewById(R.id.activity_termux_root_relative_layout);
        mTabPanel = activity.findViewById(R.id.session_tabs_container);
        final View rootView = activity.findViewById(R.id.activity_termux_root_view);
        mRootView = rootView instanceof ViewGroup ? (ViewGroup) rootView : null;
        mInflater = LayoutInflater.from(activity);
        reloadDimensions();

        mDragController = mGrid != null
                ? new SessionOverviewDragController(mGrid,
                mScrollView instanceof SessionOverviewScrollView
                        ? (SessionOverviewScrollView) mScrollView : null,
                new SessionOverviewDragController.Host() {
                    @Override
                    public boolean canStartDrag(@NonNull SessionOverviewCardView card) {
                        if (!mShowing || mTransition != null || mClosingTransition != null
                                || mPendingClosingCard != null) {
                            return false;
                        }
                        if (mGrid == null || mCards.size() < 2
                                || mGrid.getChildCount() != mCards.size()) {
                            return false;
                        }
                        final Card found = findCardByRoot(card);
                        if (found == null || found.closing || found.root.isSettling()) {
                            return false;
                        }
                        final TermuxService service = mActivity.getTermuxService();
                        return service != null
                                && service.getIndexOfSession(found.session) >= 0;
                    }

                    @Override
                    public void onReorderCommitted(int fromIndex, int toIndex,
                                                   @NonNull List<SessionOverviewCardView> orderedCards) {
                        commitReorder(fromIndex, toIndex, orderedCards);
                    }
                })
                : null;

        if (mOverlay != null) {
            // The window/decor background is the backdrop. Keeping the overlay transparent lets
            // the wallpaper and its system blur stay visible while the live content is hidden.
            mOverlay.setBackgroundColor(Color.TRANSPARENT);
            mOverlay.setOnClickListener(v -> hide());
            mOverlay.setVisibility(View.GONE);
        }
        if (mScrollView != null) {
            // Taps on a card are consumed by the card; taps in the gaps reach the scroll view and
            // close the overview, matching the old "tap outside a card" behaviour.
            mScrollView.setOnClickListener(v -> hide());
        }
        if (mGrid != null) {
            mGrid.setOnGridLayoutListener((cardWidth, cardHeight, columns, rows) -> {
                if (mDragController != null && mDragController.isActive()) {
                    mDragController.cancelForModelChange();
                }
                mCardWidth = cardWidth;
                mCardHeight = cardHeight;
                if (!mShowing) return;
                scrollToActiveCard();
                schedulePreviewRender();
            });
        }

        final TabPanelLayout tabPanel = activity.findViewById(R.id.session_tabs_container);
        if (tabPanel != null) {
            tabPanel.setListener(new TabPanelLayout.Listener() {
                @Override
                public boolean canOpenOverview() {
                    return !mShowing && hasSessions();
                }

                @Override
                public boolean opensOnSwipeDown() {
                    return !mActivity.isTabPanelAtBottom();
                }

                @Override
                public void onOverviewRequested() {
                    show();
                }
            });
        }
    }

    public boolean isShowing() {
        return mShowing;
    }

    /** The overlay view, used by the activity's touch tracking so a tap cannot close the panel. */
    @Nullable
    public View getView() {
        return mOverlay;
    }

    private boolean hasSessions() {
        TermuxService service = mActivity.getTermuxService();
        return service != null && service.getTermuxSessionsSize() > 0;
    }

    /**
     * The background the active terminal is actually painting, not the static scheme colour: a
     * shell can repaint it with OSC 4/11 while the overview is open. The scheme is only a
     * fallback for the moment before a session is attached.
     */
    private int terminalBackgroundColor() {
        final TerminalSession session = mActivity.getCurrentSession();
        final TerminalEmulator emulator = session != null ? session.getEmulator() : null;
        if (emulator != null) {
            return emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        }
        return mActivity.getColorSchemeManager().getSchemeBackground();
    }

    /**
     * Paint the window surface behind the overview with the live terminal background. The
     * window keeps the terminal's transparency alpha, so the wallpaper and its blur remain the
     * background throughout the overview instead of being replaced by an opaque scrim.
     */
    private void applyOverviewBackground() {
        final int target = terminalBackgroundColor();
        mOverlayBackgroundColor = target;
        final Transition opening = mTransition;
        if (opening != null && opening.backgroundTransition) {
            // The opening flight owns the decor colour. Update its destination, but let the
            // animator paint the next frame instead of jumping to it now.
            opening.backgroundEnd = target;
        } else if (mClosingTransition == null) {
            mActivity.applyOverviewWindowBackground(target);
        }
        if (mStatusBarExtensionApplied) {
            applyOverviewStatusBarAppearance(target);
        }
    }

    /**
     * Make every sibling of the overlay transparent while the overview is open. The overlay
     * itself is transparent, so without this the live terminal, strip and floating controls
     * would show through the grid and the window background.
     *
     * <p>Alpha, not visibility, is used deliberately: the strip must stay {@link View#isShown()}
     * and laid out for the closing tab flight, and the active terminal must still be drawable
     * for the expansion snapshot.</p>
     */
    private void hideLiveContentForOverview() {
        if (!(mContentArea instanceof ViewGroup)) return;
        final ViewGroup content = (ViewGroup) mContentArea;
        for (int i = 0; i < content.getChildCount(); i++) {
            final View child = content.getChildAt(i);
            if (child == mOverlay || mHiddenLiveViews.contains(child)) continue;
            mHiddenLiveViews.add(child);
            mHiddenLiveViewAlphas.add(child.getAlpha());
            child.setAlpha(0f);
        }
    }

    /** Restore the live content only after the transparent overlay has been removed. */
    private void restoreLiveContentAfterOverview() {
        for (int i = 0; i < mHiddenLiveViews.size(); i++) {
            final View view = mHiddenLiveViews.get(i);
            if (view != null) view.setAlpha(mHiddenLiveViewAlphas.get(i));
        }
        mHiddenLiveViews.clear();
        mHiddenLiveViewAlphas.clear();
    }

    /**
     * Open the overview and take focus. Returns false when there is no session to show or the
     * views are not available (e.g. during an interrupted activity start).
     */
    public boolean show() {
        if (mShowing || mOverlay == null || mGrid == null) return false;
        mActivityStopped = false;
        TermuxService service = mActivity.getTermuxService();
        if (service == null || service.getTermuxSessionsSize() == 0) return false;

        mPreviousFocus = mActivity.getCurrentFocus();
        mHadIme = mActivity.computeImeVisibility();

        // Snapshot the terminal and the visible tab rectangles before the keyboard starts
        // disappearing: those are the pixels the user was looking at, and they are the only
        // honest source for the flight. The grid targets are read later, every frame.
        final Transition transition = prepareTransition(service);

        // Mark the overview as showing before dismissing popups or hiding the keyboard. Those
        // calls produce focus/IME churn, and the activity must treat that churn as part of this
        // transition instead of as a user closing the input panel.
        mShowing = true;
        mActivity.dismissAutoCompleteSuggestions();
        mActivity.dismissMessageHistoryPopup();
        final View focused = mActivity.getCurrentFocus();
        // The input panel floats above the terminal with its own elevation, so it would punch
        // through the overview. Hide it for the duration of the overview, but save its
        // text/caret and leave the per-session visibility/focus memory intact: this is the same
        // preserve-and-restore contract a session switch uses.
        mActivity.hideTextInputPanelForOverview();
        KeyboardUtils.hideSoftKeyboard(mActivity,
                focused != null ? focused : mOverlay);

        // The card is the terminal, not the whole content area. Match the terminal grid before
        // the first measure so the opening flight already has exact thumbnail targets. The
        // window surface keeps the terminal's own alpha, so the wallpaper and blur stay behind
        // the grid instead of being covered by an opaque overview colour.
        final int backgroundStart = mActivity.getWindowBackgroundColor();
        applyOverviewBackground();
        if (transition != null) {
            // Hold the app background for the first frame. The opening flight crossfades it into
            // the live terminal backdrop instead of replacing it the moment the overlay appears.
            mActivity.applyWindowBackgroundColor(backgroundStart);
            transition.backgroundStart = backgroundStart;
            transition.backgroundEnd = mOverlayBackgroundColor;
            transition.backgroundTransition = true;
        }
        updateGridAspect();
        buildCards(service.getTermuxSessions());
        if (mCards.isEmpty()) {
            mShowing = false;
            releaseCards();
            discardTransition(transition);
            mActivity.restoreTextInputPanelAfterOverview();
            mActivity.updateToggleTextInputButtonIcon();
            // applyOverviewBackground() changed the window colour before the card list turned
            // out to be empty; put the normal scheme background back.
            mActivity.applySchemeColors();
            // The keyboard was already hidden above; restore the state we interrupted.
            restorePreviousFocus();
            return false;
        }

        // The overlay is transparent, so the live terminal, strip and controls must stop
        // painting for the duration of the overview. Their alpha is restored only after the
        // overlay is gone, otherwise they would flash through the grid on close.
        hideLiveContentForOverview();

        // Restore the position the user left last time as soon as the visible grid has a real
        // layout. The one-shot global listener below is the point where card bounds are fresh;
        // the posted call just covers a grid whose geometry is already cached.
        mRestoreScrollOnOpen = true;
        scheduleScrollRestoreAfterLayout();

        // Let the overlay paint behind the transparent system status bar. The grid gets the same
        // amount as top padding, so the resting layout is unchanged while scrolled rows can pass
        // under the status bar instead of disappearing behind an opaque strip.
        final boolean statusBarExtended = extendOverlayIntoStatusBar();
        if (statusBarExtended) applyOverviewStatusBarAppearance(mOverlayBackgroundColor);
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.requestFocus();
        mGrid.requestLayout();
        schedulePreviewRender();
        // The grid layout listener usually does this during the first traversal. Posting it as
        // well covers a reopening with the same session count, where the grid's cached geometry
        // means the listener may not fire again.
        mGrid.post(this::scrollToActiveCard);
        mGrid.removeCallbacks(mPreviewRefreshRunnable);
        mGrid.postDelayed(mPreviewRefreshRunnable, 500L);

        if (transition != null) {
            mTransition = transition;
            installTransitionVisuals(transition);
            mGrid.post(mStartTransitionRunnable);
            // Hard fallback for a grid that never reports a usable layout (for example a window
            // that is still being attached): never leave the flight stuck before its first frame.
            mOverlay.postDelayed(() -> {
                if (mTransition == transition && mShowing && !transition.started) {
                    startTransition(transition);
                }
            }, 400L);
        }
        return true;
    }

    /** Close the overview and restore the focus target that was active when it opened. */
    public void hide() {
        hide(true);
    }

    /** Close without restoring focus, used while switching sessions or leaving the activity. */
    public void hide(boolean restoreFocus) {
        if (!mShowing) return;

        // Keep the position the user left. If the opening restore has not run yet, the previous
        // remembered value is more truthful than the stale scroll offset still in the view.
        if (!mRestoreScrollOnOpen && mScrollView != null) {
            mRememberedScrollY = mScrollView.getScrollY();
        }
        mRestoreScrollOnOpen = false;
        clearScrollRestoreAfterLayout();

        // The flight refers to cards and tabs that are about to disappear. Put everything back
        // in its final place before the overlay is hidden or the card list is released.
        cancelTransition();
        cancelPendingClosingSelection();
        cancelClosingTransition();

        // The resume path pre-empted the platform's window-focus IME show. Undo that mode before
        // the focus hand-off below, or an explicit show after closing the overview is ignored by
        // the window's ALWAYS_HIDDEN state.
        if (mSoftKeyboardHiddenForResume) {
            mSoftKeyboardHiddenForResume = false;
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
        }

        if (!restoreFocus) {
            // Hiding the focused overlay hands focus back to whatever the system chooses. That
            // hand-off must not be mistaken for a user focus change: a visible input panel would
            // otherwise be closed before the session switch has a chance to apply its own state.
            mActivity.setSessionOverviewRestoringFocus(true);
        }

        // Restore the panel behind the still-visible overlay. The desired state is read from
        // the current session, not from a snapshot: closing the active card while the overview
        // is open can legitimately change which session owns the panel. A session switch that
        // follows (if any) then applies the target session's own panel state.
        mShowing = false;
        mActivity.restoreTextInputPanelAfterOverview();
        if (mOverlay != null) mOverlay.setVisibility(View.GONE);
        restoreOverlayFromStatusBar();
        // The overlay is gone, so the live content can paint again. The window background goes
        // back to the scheme colour after the content is restored, never before: a transparent
        // overview over live content would flash the terminal through the grid.
        restoreLiveContentAfterOverview();
        mActivity.applySchemeColors();
        releaseCards();
        mActivity.updateToggleTextInputButtonIcon();

        if (restoreFocus) {
            restorePreviousFocus();
        } else {
            mPreviousFocus = null;
            mHadIme = false;
            clearRestoringFocusWhenPosted();
        }
    }

    /**
     * Run the remembered-position restore after the visible grid has laid out. The overview was
     * {@code GONE}, so making it visible always schedules a traversal; waiting for its global
     * layout avoids computing card targets from the previous window shape.
     */
    private void scheduleScrollRestoreAfterLayout() {
        clearScrollRestoreAfterLayout();
        if (mGrid == null) return;
        final ViewTreeObserver observer = mGrid.getViewTreeObserver();
        if (!observer.isAlive()) return;

        final ViewTreeObserver.OnGlobalLayoutListener listener =
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (mScrollRestoreLayoutListener != this) return;
                        clearScrollRestoreAfterLayout();
                        if (!mShowing) return;
                        scrollToActiveCard();
                        mRestoreScrollOnOpen = false;
                    }
                };
        mScrollRestoreObserver = observer;
        mScrollRestoreLayoutListener = listener;
        observer.addOnGlobalLayoutListener(listener);
    }

    private void clearScrollRestoreAfterLayout() {
        final ViewTreeObserver.OnGlobalLayoutListener listener = mScrollRestoreLayoutListener;
        final ViewTreeObserver observer = mScrollRestoreObserver;
        mScrollRestoreLayoutListener = null;
        mScrollRestoreObserver = null;
        if (listener != null && observer != null && observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(listener);
        }
    }

    /**
     * Stretch the overlay into the status-bar inset while keeping the grid's resting layout
     * unchanged.
     *
     * <p>The window is already laid out edge-to-edge and the status bar is transparent, but the
     * activity root consumes the top inset as padding. A negative top margin lets the overview
     * draw above that padding; the same amount is added to the grid's top padding, so its
     * viewport grows by exactly the strip it gained and the cards keep their size and resting
     * position. Once the user scrolls, the padding moves out of the way and rows slide under the
     * status bar.</p>
     *
     * @return true when the overlay is now extended and its status-bar chrome should follow.
     */
    private boolean extendOverlayIntoStatusBar() {
        if (mStatusBarExtensionApplied) return true;
        if (mOverlay == null || mGrid == null || mRootView == null) return false;

        final int topInset = mRootView.getPaddingTop();
        if (topInset <= 0) return false;

        final ViewGroup.LayoutParams layoutParams = mOverlay.getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams)) return false;

        final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) layoutParams;
        mOverlayOriginalTopMargin = margins.topMargin;
        mGridOriginalPaddingTop = mGrid.getPaddingTop();
        mRootOriginalClipChildren = mRootView.getClipChildren();
        mRootOriginalClipToPadding = mRootView.getClipToPadding();

        margins.topMargin = -topInset;
        mOverlay.setLayoutParams(margins);
        mGrid.setPadding(mGrid.getPaddingLeft(), mGridOriginalPaddingTop + topInset,
                mGrid.getPaddingRight(), mGrid.getPaddingBottom());
        // The overlay lives inside the padded root and now paints above its parent's top edge;
        // both parents must allow that drawing or the top strip is clipped away.
        mRootView.setClipChildren(false);
        mRootView.setClipToPadding(false);

        mStatusBarTopExtension = topInset;
        mStatusBarExtensionApplied = true;
        return true;
    }

    /** Put the overlay back inside the content area and restore the normal status-bar icons. */
    private void restoreOverlayFromStatusBar() {
        if (!mStatusBarExtensionApplied) return;
        mStatusBarExtensionApplied = false;

        if (mOverlay != null) {
            final ViewGroup.LayoutParams layoutParams = mOverlay.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) layoutParams).topMargin =
                        mOverlayOriginalTopMargin;
                mOverlay.setLayoutParams(layoutParams);
            }
        }
        if (mGrid != null) {
            mGrid.setPadding(mGrid.getPaddingLeft(), mGridOriginalPaddingTop,
                    mGrid.getPaddingRight(), mGrid.getPaddingBottom());
        }
        if (mRootView != null) {
            mRootView.setClipChildren(mRootOriginalClipChildren);
            mRootView.setClipToPadding(mRootOriginalClipToPadding);
        }

        mStatusBarTopExtension = 0;
        restoreStatusBarAppearanceAfterOverview();
    }

    /**
     * Keep the system status bar transparent while the overview is visible. The window surface
     * paints the live terminal background underneath the bar, so the icon appearance follows
     * that background's lightness instead of the old black scrim assumption.
     */
    private void applyOverviewStatusBarAppearance(int background) {
        final Window window = mActivity.getWindow();
        if (window == null) return;

        window.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
        }

        final View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ColorSchemeUtils.isColorLight(background)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    /** Restore the status-bar icon colour selected by the current terminal scheme. */
    private void restoreStatusBarAppearanceAfterOverview() {
        final Window window = mActivity.getWindow();
        if (window == null) return;

        final View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (mActivity.isCachedSchemeLight()) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    /**
     * Clear the focus-restore latch on the next loop, using a view that is actually attached so
     * the callback is not silently dropped while the activity is stopping.
     */
    private void clearRestoringFocusWhenPosted() {
        final View decor = mActivity.getWindow() != null
                ? mActivity.getWindow().getDecorView() : null;
        final View token = (mOverlay != null && mOverlay.isAttachedToWindow())
                ? mOverlay : (decor != null && decor.isAttachedToWindow() ? decor : null);
        if (token != null) {
            token.post(() -> mActivity.setSessionOverviewRestoringFocus(false));
        } else {
            mActivity.setSessionOverviewRestoringFocus(false);
        }
    }

    private void restorePreviousFocus() {
        final View target = (mPreviousFocus != null
                && mPreviousFocus.isAttachedToWindow()
                && mPreviousFocus.getVisibility() == View.VISIBLE)
                ? mPreviousFocus
                : mActivity.getActiveTerminalView();
        final boolean showKeyboard = mHadIme;
        mPreviousFocus = null;
        mHadIme = false;

        if (target == null || !target.isAttachedToWindow()) {
            // Nothing to hand focus to. Do not leave the suppression latch set for a focus
            // request that can never be delivered.
            mActivity.setSessionOverviewRestoringFocus(false);
            return;
        }

        // The terminal focus listener hides the keyboard / closes a visible input panel on focus
        // changes. This hand-off is ours, not the user's: suppress that listener until the focus
        // event has been delivered, then restore normal behaviour.
        mActivity.setSessionOverviewRestoringFocus(true);
        target.requestFocus();
        target.post(() -> mActivity.setSessionOverviewRestoringFocus(false));

        if (showKeyboard) {
            target.post(() -> {
                if (!mShowing && target.isAttachedToWindow()) {
                    KeyboardUtils.showSoftKeyboard(mActivity, target);
                }
            });
        }
    }

    private void selectSession(@NonNull TerminalSession session) {
        if (!mShowing || mClosingTransition != null || mPendingClosingCard != null) return;

        // A tap can land while the opening flight is still in the air. Land that flight first:
        // the closing expansion must start from the grid's final card geometry, not from a ghost
        // that is still shrinking into the card.
        final Transition opening = mTransition;
        if (opening != null) {
            if (opening.started) applyTransitionProgress(opening, 1f);
            cleanupTransition(opening);
        }

        final Card card = findCard(session);
        if (card == null) {
            positionTabStripForSession(session);
            hide(false);
            switchToSession(session);
            return;
        }
        beginClosingSelection(card);
    }

    /**
     * Apply the target tab's final selection and scroll while the overview still hides the
     * strip, then start the expansion. This gives a background card the same tab flight as the
     * active one: the ghost has a selected, on-screen target even when the old tab was inactive
     * or scrolled out of view.
     */
    private void beginClosingSelection(@NonNull Card card) {
        final TermuxService service = mActivity.getTermuxService();
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        final int index = service != null ? service.getIndexOfSession(card.session) : -1;
        if (tabs == null || index < 0) {
            beginClosingExpansion(card);
            return;
        }

        mPendingClosingCard = card;
        tabs.prepareSelectionForOverview(index, () -> {
            if (mPendingClosingCard != card || !mShowing) return;
            mPendingClosingCard = null;
            if (mClosingTransition != null) return;
            if (findCard(card.session) != card) {
                hide(false);
                return;
            }
            beginClosingExpansion(card);
        });
    }

    /** Start the expansion after the strip is already in its final state behind the overview. */
    private void beginClosingExpansion(@NonNull Card card) {
        if (!mShowing || mClosingTransition != null) return;
        final ClosingTransition closing = prepareClosingTransition(card);
        if (closing == null) {
            hide(false);
            switchToSession(card.session);
            return;
        }
        mClosingTransition = closing;
        startClosingTransition(closing);
    }

    /** Abort a selection that is still waiting for the strip's final layout. */
    private void cancelPendingClosingSelection() {
        if (mPendingClosingCard == null) return;
        mPendingClosingCard = null;
        restoreTabSelectionToCurrentSession();
    }

    /** Put the highlighted tab and strip scroll back on the session that is still current. */
    private void restoreTabSelectionToCurrentSession() {
        final TermuxService service = mActivity.getTermuxService();
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        final TerminalSession current = mActivity.getCurrentSession();
        if (service == null || tabs == null || current == null) return;
        final int index = service.getIndexOfSession(current);
        if (index >= 0) {
            tabs.setCurrentSession(index);
            tabs.scrollToTabIndexImmediately(index);
        }
    }

    private void switchToSession(@NonNull TerminalSession session) {
        if (mActivity.getTermuxTerminalSessionClient() != null) {
            // The overview already put the strip in place while its live copy was transparent.
            // Do not let the ordinary instant-switch path start a second, visible centring
            // animation after the terminal has finished expanding.
            mActivity.getTermuxTerminalSessionClient()
                    .setCurrentSession(session, false, false, true);
        }
    }

    /** Put the strip's selected tab in place while the overlay still hides the move. */
    private void positionTabStripForSession(@NonNull TerminalSession session) {
        final TermuxService service = mActivity.getTermuxService();
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (service == null || tabs == null) return;
        final int index = service.getIndexOfSession(session);
        if (index >= 0) tabs.scrollToTabIndexImmediately(index);
    }

    /**
     * Close one session from the overview. The activity client owns the pager and selection
     * hand-off, so a background card can be removed without moving the user to a neighbour.
     *
     * <p>A running session must be killed first: {@link
     * TermuxTerminalSessionActivityClient#removeFinishedSession} is deliberately for sessions that
     * already exited, and calling it on a live shell is a no-op. The normal
     * {@code onSessionFinished} callback then removes a session that exits with an auto-close
     * status. A session that is already finished but kept because of its exit status is removed
     * directly.</p>
     *
     * @return true when the close was accepted; false when the card must spring back.
     */
    private boolean closeSession(@NonNull Card card) {
        if (!mShowing || card.closing || mClosingTransition != null
                || mPendingClosingCard != null) return false;
        card.closing = true;
        if (card.session.isRunning()) {
            card.session.finishIfRunning();
            return true;
        }
        final TermuxTerminalSessionActivityClient client =
                mActivity.getTermuxTerminalSessionClient();
        if (client != null) {
            client.removeFinishedSession(card.session);
        } else {
            card.session.finishIfRunning();
        }
        return true;
    }

    /** Called after the live session list changed. Rebuilds the grid while it is open. */
    public void onSessionsChanged() {
        if (!mShowing) return;
        // The closing flight owns the selected card and its tab. A structural update in the
        // middle of the 300 ms expansion would replace those views under the ghost; let the
        // flight finish, then hide and switch in the usual way.
        if (mClosingTransition != null || mPendingClosingCard != null) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null || service.getTermuxSessionsSize() == 0) {
            hide(false);
            return;
        }

        // A session switch can hand us a terminal with different columns, rows or background.
        // Re-target the cards before the rebuilt previews are measured.
        applyOverviewBackground();
        updateGridAspect();
        final List<TermuxSession> sessions = service.getTermuxSessions();
        // Session-list callbacks also fire for non-structural updates (for example when a tab is
        // re-bound). Rebuilding in that case would cancel a flight that is already in the air.
        if (hasSameSessions(sessions)) {
            refreshSelection();
            schedulePreviewRender();
            return;
        }

        // A pure deletion keeps the surviving cards in their old relative order. Remember where
        // they were before the rebuild so they can slide into the vacated slot instead of
        // appearing there instantly.
        final boolean animateRemoval = isPureRemoval(sessions);
        final Map<TerminalSession, float[]> oldCenters =
                animateRemoval ? captureCardCenters(mCards) : null;

        cancelTransition();
        if (mDragController != null) mDragController.cancelForModelChange();
        buildCards(sessions);
        if (mCards.isEmpty()) {
            hide(false);
            return;
        }
        mGrid.requestLayout();
        if (oldCenters != null) animateRemovalSettle(oldCenters);
        schedulePreviewRender();
    }

    private boolean hasSameSessions(@NonNull List<TermuxSession> sessions) {
        int cardIndex = 0;
        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;
            if (cardIndex >= mCards.size() || mCards.get(cardIndex).session != session) {
                return false;
            }
            cardIndex++;
        }
        return cardIndex == mCards.size();
    }

    /**
     * True when the new session list is the old one with one or more entries removed, with no
     * additions or reordering. Only that shape can reuse the current slot positions as the
     * starting point of a layout animation.
     */
    private boolean isPureRemoval(@NonNull List<TermuxSession> sessions) {
        if (mCards.isEmpty()) return false;
        int oldIndex = 0;
        int newCount = 0;
        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;
            while (oldIndex < mCards.size() && mCards.get(oldIndex).session != session) {
                oldIndex++;
            }
            if (oldIndex >= mCards.size()) return false;
            oldIndex++;
            newCount++;
        }
        return newCount > 0 && newCount < mCards.size();
    }

    /** Visual centres of the cards currently in the grid, keyed by their sessions. */
    @NonNull
    private static Map<TerminalSession, float[]> captureCardCenters(@NonNull List<Card> cards) {
        final Map<TerminalSession, float[]> centers = new IdentityHashMap<>();
        for (Card card : cards) {
            final View root = card.root;
            if (root.getWidth() <= 0 || root.getHeight() <= 0) continue;
            centers.put(card.session, new float[]{
                    root.getLeft() + root.getWidth() / 2f + root.getTranslationX(),
                    root.getTop() + root.getHeight() / 2f + root.getTranslationY()
            });
        }
        return centers;
    }

    /**
     * Start the surviving cards from their old centres and let the drag controller settle them
     * into the freshly built grid. The new children are measured on the next layout pass, which
     * is why the controller waits before it applies the starting translations.
     */
    private void animateRemovalSettle(@NonNull Map<TerminalSession, float[]> oldCenters) {
        if (mDragController == null || mGrid == null || mCards.isEmpty()) return;
        final int count = mCards.size();
        final float[] slotCenters = mGrid.getSlotCenters(count);
        if (slotCenters == null || slotCenters.length != count * 2) return;

        final List<SessionOverviewCardView> cards = new ArrayList<>(count);
        final float[] fromCenterXs = new float[count];
        final float[] fromCenterYs = new float[count];
        final float[] targetCenterXs = new float[count];
        final float[] targetCenterYs = new float[count];
        for (int i = 0; i < count; i++) {
            final Card card = mCards.get(i);
            final float[] oldCenter = oldCenters.get(card.session);
            if (oldCenter == null) return;
            cards.add(card.root);
            fromCenterXs[i] = oldCenter[0];
            fromCenterYs[i] = oldCenter[1];
            targetCenterXs[i] = slotCenters[i * 2];
            targetCenterYs[i] = slotCenters[i * 2 + 1];
        }
        mDragController.animateLayoutChange(cards, fromCenterXs, fromCenterYs,
                targetCenterXs, targetCenterYs);
    }

    /**
     * Apply the final visual order produced by an overview drag to the live model. The drag has
     * already reordered the grid children; this moves the dragged session once through the pager
     * (which owns the service move) and then rebuilds the tab strip in the new order.
     */
    private void commitReorder(int fromIndex, int toIndex,
                               @NonNull List<SessionOverviewCardView> orderedCards) {
        final TermuxService service = mActivity.getTermuxService();
        if (service == null) {
            hide(false);
            return;
        }

        final List<Card> newOrder = new ArrayList<>(orderedCards.size());
        for (SessionOverviewCardView root : orderedCards) {
            final Card card = findCardByRoot(root);
            if (card == null) {
                newOrder.clear();
                break;
            }
            newOrder.add(card);
        }

        boolean complete = newOrder.size() == mCards.size();
        if (complete) {
            for (Card card : mCards) {
                if (!newOrder.contains(card)) {
                    complete = false;
                    break;
                }
            }
        }
        if (!complete) {
            buildCards(service.getTermuxSessions());
            if (mGrid != null) mGrid.requestLayout();
            schedulePreviewRender();
            return;
        }

        mCards.clear();
        mCards.addAll(newOrder);

        final SessionPagerManager pager = mActivity.getSessionPagerManager();
        if (pager != null) {
            // The visual order is a remove-and-insert, not a series of pairwise swaps. Replaying
            // swaps made the model depend on the exact path the finger took and could disagree
            // with the final grid when the finger crossed a slot more than once.
            pager.moveSession(fromIndex, toIndex);
        }

        final List<TermuxSession> serviceSessions = service.getTermuxSessions();
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (!hasSameSessions(serviceSessions)) {
            // The pager was unavailable or refused a move. Do not leave the overview showing an
            // order the terminals do not have: restore both surfaces from the service list.
            if (tabs != null) tabs.updateTabs(serviceSessions);
            buildCards(serviceSessions);
            if (mCards.isEmpty()) {
                hide(false);
                return;
            }
            if (mGrid != null) mGrid.requestLayout();
            schedulePreviewRender();
            return;
        }

        if (tabs != null) {
            tabs.updateTabs(serviceSessions);
            final TerminalSession current = mActivity.getCurrentSession();
            final int activeIndex = current != null
                    ? service.getIndexOfSession(current) : -1;
            if (activeIndex >= 0) tabs.scrollToTabIndexImmediately(activeIndex);
        }
        mActivity.saveSessionSnapshot();
        if (mGrid != null) mGrid.post(this::scrollToActiveCard);
        schedulePreviewRender();
    }

    /** Repaint the card preview when a session produces output while the overview is open. */
    public void onSessionTextChanged(@NonNull TerminalSession session) {
        if (!mShowing || mActivityStopped || mGrid == null
                || mCardWidth <= 0 || mCardHeight <= 0) return;
        if (findCard(session) == null || mPendingTextSessions.contains(session)) return;
        mPendingTextSessions.add(session);
        if (mTextRenderPosted) return;
        mTextRenderPosted = true;
        mGrid.post(mTextRenderRunnable);
    }

    /** Cheap refresh for high-frequency OSC title updates. */
    public void refreshTitles() {
        if (!mShowing) return;
        final TerminalSession current = mActivity.getCurrentSession();
        for (Card card : mCards) {
            // A running card can be waiting for its shell to die after a close gesture. If the
            // process has now exited but the session stays in the list because of its exit
            // status, the card is visible and closable again; the next tap removes it.
            if (card.closing && !card.session.isRunning()) {
                card.closing = false;
                card.root.resetSwipeState();
            }
            bindTab(card, card.session == current);
        }
    }

    /** Update the selected-card highlight after a page change. */
    public void refreshSelection() {
        refreshTitles();
        // Do not auto-scroll to the active card while the selected card is expanding: the
        // closing flight reads that card's live bounds every frame.
        if (mClosingTransition != null) return;
        scrollToActiveCard();
    }

    /**
     * Stop animation and preview work while the activity is in the background, but keep the
     * overview itself open. Returning to the app must land on the same grid, not on the terminal
     * that was hidden behind it.
     *
     * <p>Any in-flight opening or expansion is completed or cancelled to a stable frame before
     * the window stops drawing. The grid keeps its cards and bitmaps so the resume path can
     * repaint them without rebuilding or replaying the opening flight.</p>
     */
    public void onActivityStopped() {
        if (!mShowing) return;
        mActivityStopped = true;

        cancelTransition();
        cancelPendingClosingSelection();
        cancelClosingTransition();
        if (mDragController != null) mDragController.cancelForModelChange();

        if (mGrid != null) {
            mGrid.removeCallbacks(mRenderPreviewsRunnable);
            mGrid.removeCallbacks(mPreviewRefreshRunnable);
            mGrid.removeCallbacks(mTextRenderRunnable);
        }
        mRenderPosted = false;
        mTextRenderPosted = false;
        mPendingTextSessions.clear();
    }

    /**
     * Re-assert the overview after the activity returns to the foreground.
     *
     * <p>The normal resume path restores the terminal's panel, focus and scheme without knowing
     * that the overview is still covering them. Rebuild nothing: hide the restored live content
     * again, take focus back and restart the preview tick, so the user returns to exactly the
     * grid and scroll position they left.</p>
     */
    public void onActivityResumed() {
        mActivityStopped = false;
        if (!mShowing || mOverlay == null) return;

        // The activity may have restored the input panel and the terminal focus while the
        // overlay was in the background. Apply the same temporary suppression that show() uses.
        hideLiveContentForOverview();
        mActivity.hideTextInputPanelForOverview();
        KeyboardUtils.setSoftKeyboardAlwaysHiddenAndAdjustResize(mActivity);
        mSoftKeyboardHiddenForResume = true;

        applyOverviewBackground();
        if (extendOverlayIntoStatusBar()) {
            applyOverviewStatusBarAppearance(mOverlayBackgroundColor);
        }

        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.requestFocus();
        KeyboardUtils.hideSoftKeyboard(mActivity, mOverlay);

        if (mGrid != null) {
            // The window shape or terminal viewport can have changed while stopped (multi-window,
            // a rotated device, an external display). Keep the cards matched to the new shape.
            updateGridAspect();
            mGrid.requestLayout();
            mGrid.removeCallbacks(mPreviewRefreshRunnable);
            mGrid.postDelayed(mPreviewRefreshRunnable, 500L);
        }
        schedulePreviewRender();
    }

    /** Release overlay state when the activity is destroyed and will not be resumed. */
    public void onActivityDestroyed() {
        if (!mShowing) return;
        onActivityStopped();
        clearScrollRestoreAfterLayout();
        mShowing = false;
        mActivityStopped = false;
        mPreviousFocus = null;
        mHadIme = false;
        mSoftKeyboardHiddenForResume = false;
        releaseCards();
        mHiddenLiveViews.clear();
        mHiddenLiveViewAlphas.clear();
    }

    // ── Opening transition ─────────────────────────────────────────────────

    /**
     * Capture the source pixels and rectangles before the overlay and the keyboard change the
     * layout. Only the sources are captured here. A tab keeps its source size and title while it
     * moves and fades out, then its target size and title are applied once while it is invisible;
     * after that only its start edge and vertical centre move, so no title is re-laid out on
     * every frame.
     *
     * <p>Tab rectangles are captured at their full layout size, not their visible intersection:
     * the overlay clips a partially scrolled tab against the same edge as the strip, while the
     * full width preserves the title's original truncation.</p>
     */
    @Nullable
    private Transition prepareTransition(@NonNull TermuxService service) {
        if (mOverlay == null || mGhostLayer == null || mContentArea == null) return null;

        final Transition transition = new Transition();

        final TerminalView terminal = mActivity.getActiveTerminalView();
        final Rect terminalContent = new Rect();
        if (terminal != null && terminal.isAttachedToWindow() && terminal.isShown()
                && terminal.getWidth() > 0 && terminal.getHeight() > 0
                && terminal.getTerminalContentBounds(terminalContent)) {
            Bitmap full = null;
            try {
                // A transparent terminal must keep its alpha: the overview shows the window
                // background directly, and flattening the snapshot to RGB would turn the
                // wallpaper visible through the terminal into an opaque sheet during the flight.
                final Bitmap.Config config = terminal.isOpaque()
                        ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
                full = Bitmap.createBitmap(terminal.getWidth(), terminal.getHeight(), config);
                terminal.draw(new Canvas(full));

                // Fly the glyph grid, not the whole TerminalView. The background bands around
                // the grid are not part of the card preview; including them makes the terminal
                // shrink with margins and jump when the preview replaces it at the end.
                transition.terminalBitmap = cropToGrid(full, terminalContent);
                full = null;
                transition.terminalSource = windowRectOf(terminal, terminalContent);
            } catch (RuntimeException ignored) {
                // A terminal that cannot be drawn (e.g. mid-detach) simply does not fly.
                recycleBitmap(full);
            }
        }

        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) {
            // Every visible strip tab flies to its card, not just the active one. The panel
            // snapshot is taken after all of these sources are hidden, so the ghosts are the
            // only copies of those tabs during the opening flight.
            for (TermuxSession termuxSession : new ArrayList<>(service.getTermuxSessions())) {
                if (termuxSession == null) continue;
                final TerminalSession session = termuxSession.getTerminalSession();
                if (session == null) continue;
                final View tabView = tabs.findTabForSession(session);
                if (tabView == null || !tabView.isShown()) continue;

                // A tab partially scrolled out of the strip must still fly with its original
                // width and title truncation. The overlay clips the overhang exactly where the
                // strip does, so using getGlobalVisibleRect() here would instead re-render the
                // title for a narrower rectangle and make it change on the first frame.
                final Rect visible = new Rect();
                if (!tabView.getGlobalVisibleRect(visible) || visible.isEmpty()) continue;
                final Rect bounds = windowRectOf(tabView);
                if (bounds.isEmpty()) continue;

                final TextView title = tabView.findViewById(R.id.session_tab_title);
                final View closeButton = tabView.findViewById(R.id.session_tab_close);
                int closeFootprint = 0;
                Rect closeBounds = null;
                int closeTint = title != null ? title.getCurrentTextColor() : Color.WHITE;
                if (closeButton != null && closeButton.getVisibility() == View.VISIBLE) {
                    closeFootprint = closeButton.getWidth();
                    final ViewGroup.LayoutParams closeParams = closeButton.getLayoutParams();
                    if (closeParams instanceof ViewGroup.MarginLayoutParams) {
                        closeFootprint += ((ViewGroup.MarginLayoutParams) closeParams)
                                .getMarginStart();
                    }
                    // Keep the full cross bounds too: if the X is partly scrolled off screen,
                    // the overlay clips it just like the strip instead of shrinking the icon.
                    final Rect fullCloseBounds = windowRectOf(closeButton);
                    if (!fullCloseBounds.isEmpty()) {
                        closeBounds = fullCloseBounds;
                    }
                    if (closeButton instanceof ImageButton) {
                        final ColorStateList tint =
                                ((ImageButton) closeButton).getImageTintList();
                        if (tint != null) closeTint = tint.getDefaultColor();
                    }
                }
                final float fallbackTextSize = mActivity.getResources()
                        .getDimensionPixelSize(R.dimen.terminal_tab_text_size);
                transition.tabSources.add(new SourceTab(
                        session,
                        tabView,
                        title != null ? title.getCurrentTextColor() : Color.WHITE,
                        title != null ? title.getTextSize() : fallbackTextSize,
                        title != null ? title.getTypeface() : null,
                        tabs.getTabBackgroundColor(tabView),
                        tabs.getTabCornerRadiusPx(),
                        tabView.getPaddingStart(), tabView.getPaddingTop(),
                        tabView.getPaddingEnd() + closeFootprint,
                        tabView.getPaddingBottom(),
                        bounds,
                        closeBounds,
                        closeTint));
            }
        }

        // The live terminal, strip and controls are transparent while the overview is open. The
        // captured ghosts are the only copies of the terminal and strip during the flight; the
        // window background itself stays visible underneath.
        return transition;
    }

    private static Rect windowRectOf(@NonNull View view) {
        final int[] location = new int[2];
        view.getLocationInWindow(location);
        return new Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    @NonNull
    private static Rect windowRectOf(@NonNull View view, @NonNull Rect localBounds) {
        final int[] location = new int[2];
        view.getLocationInWindow(location);
        return new Rect(location[0] + localBounds.left, location[1] + localBounds.top,
                location[0] + localBounds.right, location[1] + localBounds.bottom);
    }

    /** Crop a full TerminalView snapshot down to the glyph grid the thumbnail represents. */
    @NonNull
    private static Bitmap cropToGrid(@NonNull Bitmap full, @NonNull Rect grid) {
        if (grid.left == 0 && grid.top == 0
                && grid.width() == full.getWidth() && grid.height() == full.getHeight()) {
            return full;
        }
        final Bitmap cropped = Bitmap.createBitmap(
                full, grid.left, grid.top, grid.width(), grid.height());
        if (cropped != full) full.recycle();
        return cropped;
    }

    private static Rect offsetRect(@NonNull Rect source, @NonNull int[] origin) {
        return new Rect(source.left - origin[0], source.top - origin[1],
                source.right - origin[0], source.bottom - origin[1]);
    }

    private static FrameLayout.LayoutParams frameParams(@NonNull Rect rect) {
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(0, rect.width()), Math.max(0, rect.height()));
        params.leftMargin = rect.left;
        params.topMargin = rect.top;
        return params;
    }

    private static Rect lerpRect(@NonNull Rect start, @NonNull Rect end, float progress) {
        return new Rect(
                SessionOverviewTransitionMath.lerp(start.left, end.left, progress),
                SessionOverviewTransitionMath.lerp(start.top, end.top, progress),
                SessionOverviewTransitionMath.lerp(start.right, end.right, progress),
                SessionOverviewTransitionMath.lerp(start.bottom, end.bottom, progress));
    }

    /**
     * Move a ghost's start edge along the source-to-target line without changing its laid-out
     * size; its vertical centre still follows the line.
     *
     * <p>{@code layout} is the rect the view is currently laid out at. Translation is used
     * instead of new layout params so the title is never measured or re-truncated per frame.
     * Keeping the start edge, rather than the centre, fixed during the invisible width change
     * means the beginning of the title does not slide when the truncated part grows.</p>
     */
    private static void moveGhostAlongPath(@NonNull View view, @NonNull Rect layout,
                                           @NonNull Rect source, @NonNull Rect target,
                                           float fraction) {
        final float f = SessionOverviewTransitionMath.clamp01(fraction);
        final boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final float sourceStart = rtl ? source.right : source.left;
        final float targetStart = rtl ? target.right : target.left;
        final float start = SessionOverviewTransitionMath.lerp(sourceStart, targetStart, f);
        final float centerY = source.centerY()
                + (target.centerY() - source.centerY()) * f;
        view.setTranslationX(rtl ? start - layout.right : start - layout.left);
        view.setTranslationY(centerY - layout.centerY());
    }

    /**
     * Keep a resting card attached to its flying tab. The card is shifted by the same path
     * delta that moves the tab's start edge and vertical centre, so its thumbnail travels with
     * the tab for the whole flight.
     *
     * <p>{@code anchorTab} is the tab's resting rect in content coordinates; the card is
     * translated relative to that rect. Sources and targets must be measured without the card's
     * own translation, otherwise next frame would feed the movement back into the path.
     * Terminals that own the terminal snapshot (the active card on opening, the selected card
     * on closing) are deliberately not moved by this helper.</p>
     */
    private static void moveCardWithTab(@NonNull View cardRoot, @NonNull Rect anchorTab,
                                        @NonNull Rect sourceTab, @NonNull Rect targetTab,
                                        float fraction) {
        final float f = SessionOverviewTransitionMath.clamp01(fraction);
        final boolean rtl = cardRoot.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final float sourceStart = rtl ? sourceTab.right : sourceTab.left;
        final float targetStart = rtl ? targetTab.right : targetTab.left;
        final float currentStart = SessionOverviewTransitionMath.lerp(
                sourceStart, targetStart, f);
        final float currentCenterY = sourceTab.centerY()
                + (targetTab.centerY() - sourceTab.centerY()) * f;
        final float anchorStart = rtl ? anchorTab.right : anchorTab.left;
        cardRoot.setTranslationX(currentStart - anchorStart);
        cardRoot.setTranslationY(currentCenterY - anchorTab.centerY());
    }

    /**
     * Bounds of a direct card child in content coordinates with the card root's temporary
     * transition translation removed. Targets stay in the resting grid geometry even while the
     * card itself is following its tab.
     *
     * <p>The card's parent is measured instead of un-translating the card's own window rect:
     * window coordinates are rounded, so subtracting a fractional translation every frame would
     * make the target wobble by a pixel and feed that wobble back into the path.</p>
     */
    @Nullable
    private Rect cardChildTarget(@NonNull Card card, @NonNull View child,
                                 @NonNull int[] origin) {
        final ViewParent parent = card.root.getParent();
        if (parent instanceof View) {
            final View parentView = (View) parent;
            final Rect parentRect = localRect(parentView, origin);
            if (parentRect != null) {
                final float left = parentRect.left - parentView.getScrollX()
                        + card.root.getLeft();
                final float top = parentRect.top - parentView.getScrollY()
                        + card.root.getTop();
                return new Rect(
                        Math.round(left + child.getLeft()),
                        Math.round(top + child.getTop()),
                        Math.round(left + child.getRight()),
                        Math.round(top + child.getBottom()));
            }
        }

        final Rect root = localRect(card.root, origin);
        if (root == null || root.isEmpty()) return null;
        final float left = root.left - card.root.getTranslationX();
        final float top = root.top - card.root.getTranslationY();
        return new Rect(
                Math.round(left + child.getLeft()),
                Math.round(top + child.getTop()),
                Math.round(left + child.getRight()),
                Math.round(top + child.getBottom()));
    }

    @Nullable
    private Rect cardTabTarget(@NonNull Card card, @NonNull int[] origin) {
        return cardChildTarget(card, card.tab, origin);
    }

    /** Build the flying views at their captured source positions and hide what they replace. */
    private void installTransitionVisuals(@NonNull Transition transition) {
        if (mGhostLayer == null || mContentArea == null) return;
        mGhostLayer.removeAllViews();

        final int[] origin = contentOrigin();
        if (origin == null) return;

        if (transition.terminalBitmap != null && transition.terminalSource != null) {
            final Card active = findCard(mActivity.getCurrentSession());
            if (active != null) {
                transition.activeCard = active;
                // The snapshot, not the card sitting in its final slot, owns the shadow for the
                // duration of the flight.
                setCardShadowSuppressed(active, true);
                active.preview.setAlpha(0f);
                active.preview.setVisibility(View.INVISIBLE);

                final ImageView ghost = new ImageView(mActivity);
                ghost.setScaleType(ImageView.ScaleType.FIT_XY);
                ghost.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                ghost.setImageBitmap(transition.terminalBitmap);
                // Start with no shadow and square corners: at progress zero this is the live
                // terminal, which does not float. Both grow with the flight toward the card.
                FloatingShadow.apply(ghost, 0, floatingShadowColor());
                ghost.setOutlineProvider(new RoundedOutlineProvider(0f));
                ghost.setClipToOutline(true);
                transition.terminalShadowElevation =
                        FloatingShadow.elevationPx(mActivity.getResources());

                transition.terminalSourceLocal = offsetRect(transition.terminalSource, origin);
                ghost.setLayoutParams(frameParams(transition.terminalSourceLocal));
                mGhostLayer.addView(ghost);
                transition.terminalGhost = ghost;
            } else {
                recycleBitmap(transition.terminalBitmap);
                transition.terminalBitmap = null;
            }
        }

        // Hide the flying strip tabs before drawing the panel snapshot. Each one is represented
        // by its own ghost; leaving them in the snapshot would draw every flying tab twice.
        for (SourceTab source : transition.tabSources) {
            source.tabView.setVisibility(View.INVISIBLE);
            transition.hiddenTabs.add(source.tabView);
        }
        installOpeningTabPanelGhost(transition, origin);

        for (SourceTab source : transition.tabSources) {
            final Card card = findCard(source.session);
            if (card == null) continue;

            final SessionOverviewTabTitleView ghost =
                    new SessionOverviewTabTitleView(mActivity);
            ghost.setMaxLines(card.tab.getMaxLines());
            ghost.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            // Match the strip title and the card title exactly. A different includeFontPadding
            // shifts the baseline on the first and last frame, which reads as the tab jumping
            // even though its bounds are continuous.
            ghost.setIncludeFontPadding(true);
            ghost.setLineSpacing(card.tab.getLineSpacingExtra(),
                    card.tab.getLineSpacingMultiplier());
            ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ghost.setFullTitle(card.fullTitle);
            ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.textSize);
            ghost.setTextColor(source.textColor);
            ghost.setPaintFlags(card.tab.getPaintFlags());
            if (source.typeface != null) ghost.setTypeface(source.typeface);
            ghost.setPaddingRelative(source.padStart, source.padTop,
                    source.padEnd, source.padBottom);

            final GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setColor(source.backgroundColor);
            background.setCornerRadius(source.cornerRadius);
            ghost.setBackground(background);

            final Rect sourceRect = offsetRect(source.bounds, origin);
            ghost.setLayoutParams(frameParams(sourceRect));
            // The terminal ghost's elevation grows during the flight. Elevation participates in
            // child ordering, so lift the tab above that maximum or it would slide under the
            // shrinking terminal despite being added later.
            ghost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(
                    transition.terminalShadowElevation));
            mGhostLayer.addView(ghost);

            // Every flying tab owns a close cross. The active strip tab already shows one, so
            // its cross follows the pill from the first frame. An inactive strip tab has no
            // cross; its ghost stays invisible until the card style lands, then fades in at the
            // end of the target pill instead of popping in when the real card is revealed.
            final ImageView closeGhost = new ImageView(mActivity);
            closeGhost.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            closeGhost.setImageResource(R.drawable.ic_close);
            closeGhost.setImageTintList(ColorStateList.valueOf(
                    source.closeBounds != null ? source.closeTint : card.textColor));
            closeGhost.setAlpha(source.closeBounds != null ? 0.7f : 0f);
            closeGhost.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final Rect closeSourceLocal = source.closeBounds != null
                    ? offsetRect(source.closeBounds, origin) : null;
            closeGhost.setLayoutParams(frameParams(
                    closeSourceLocal != null ? closeSourceLocal : sourceRect));
            closeGhost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(
                    transition.terminalShadowElevation));
            mGhostLayer.addView(closeGhost);

            transition.tabGhosts.add(new TabGhost(card, ghost, background, sourceRect,
                    closeGhost, closeSourceLocal));

            // The real tab keeps its slot but stops painting. Hide the card's pill and close
            // cross for every flying tab: an inactive strip tab has no cross of its own, so
            // leaving the card cross visible would float an X over the thumbnail for the whole
            // flight. Cleanup reveals both when the ghost has landed.
            card.tab.setVisibility(View.INVISIBLE);
            card.close.setVisibility(View.INVISIBLE);
            transition.hiddenPills.add(card);
        }

        // The live terminal, strip and controls are already transparent (see
        // hideLiveContentForOverview()). The captured terminal and strip snapshots are therefore
        // the only copies of those surfaces while the window background stays visible.
        applyOpeningCardAlpha(transition, 0f);
    }

    /**
     * Draw a snapshot of the tab strip and fade it out with the opening flight. The live strip
     * is transparent while the overview is open, so without this copy the panel would disappear
     * on the first frame while the tab ghosts were still travelling.
     */
    private void installOpeningTabPanelGhost(@NonNull Transition transition,
                                             @NonNull int[] origin) {
        if (mGhostLayer == null || mTabPanel == null) return;
        final Rect panelWindow = windowRectOf(mTabPanel);
        if (panelWindow.isEmpty()) return;
        final Bitmap bitmap = captureViewBitmap(mTabPanel);
        if (bitmap == null) return;

        final ImageView ghost = new ImageView(mActivity);
        ghost.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ghost.setImageBitmap(bitmap);
        transition.tabPanelSourceLocal = offsetRect(panelWindow, origin);
        ghost.setLayoutParams(frameParams(transition.tabPanelSourceLocal));
        ghost.setAlpha(1f);
        mGhostLayer.addView(ghost);
        transition.tabPanelGhost = ghost;
        transition.tabPanelBitmap = bitmap;
    }

    @Nullable
    private static Bitmap captureViewBitmap(@NonNull View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        try {
            final Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
                    Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            return bitmap;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean transitionTargetsReady(@NonNull Transition transition) {
        if (mGrid == null || !mGrid.isLaidOut()
                || mCardWidth <= 0 || mCardHeight <= 0) {
            return false;
        }
        if (transition.activeCard != null) {
            final View root = transition.activeCard.root;
            final View preview = transition.activeCard.preview;
            if (!root.isLaidOut() || root.getWidth() <= 0 || root.getHeight() <= 0) return false;
            if (!preview.isLaidOut()
                    || preview.getWidth() <= 0 || preview.getHeight() <= 0) {
                return false;
            }
        }
        for (TabGhost ghost : transition.tabGhosts) {
            final View root = ghost.card.root;
            final View pill = ghost.card.tab;
            if (!root.isLaidOut() || root.getWidth() <= 0 || root.getHeight() <= 0) return false;
            if (pill.getWidth() <= 0 || pill.getHeight() <= 0) return false;
            if (ghost.closeGhost != null
                    && (ghost.card.close.getWidth() <= 0
                    || ghost.card.close.getHeight() <= 0)) {
                return false;
            }
        }
        return true;
    }

    private void startTransition(@NonNull Transition transition) {
        if (mTransition != transition || transition.started) return;
        transition.started = true;
        if (mGrid != null) mGrid.removeCallbacks(mStartTransitionRunnable);

        // The active card may be in a row below the first 2x2 page. Put it on screen before the
        // first frame so the terminal does not fly toward a target outside the visible strip.
        // By now the grid has a real layout, so it is safe to retire the restore preference.
        scrollToActiveCard();
        mRestoreScrollOnOpen = false;
        applyTransitionProgress(transition, 0f);

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SessionOverviewTransitionMath.DURATION_MS);
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(animation ->
                applyTransitionProgress(transition,
                        (float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishTransition(transition);
            }
        });
        transition.animator = animator;
        animator.start();
    }

    /**
     * Advance the opening flight. The terminal moves continuously; each tab moves and fades out
     * at source size, is transformed once while invisible, then moves the rest of the way and
     * fades in at card size.
     */
    private void applyTransitionProgress(@NonNull Transition transition, float progress) {
        if (mTransition != transition) return;
        final float p = SessionOverviewTransitionMath.clamp01(progress);

        if (transition.backgroundTransition) {
            mActivity.applyWindowBackgroundColor(SessionOverviewTransitionMath.blendColor(
                    transition.backgroundStart, transition.backgroundEnd, p));
        }
        applyOpeningCardAlpha(transition, p);

        final int[] origin = contentOrigin();

        if (transition.terminalGhost != null && transition.activeCard != null && origin != null) {
            // The active card's preview is the terminal snapshot's destination, not a companion
            // of the active tab. Read it without the card's temporary translation so the
            // snapshot always lands in the resting grid slot.
            final Rect target = cardChildTarget(
                    transition.activeCard, transition.activeCard.preview, origin);
            if (target != null && transition.terminalSourceLocal != null) {
                transition.terminalGhost.setLayoutParams(frameParams(lerpRect(
                        transition.terminalSourceLocal, target, p)));
            }
            // The shadow travels with the snapshot and grows from the square, shadowless live
            // terminal into the rounded card. The card's own shadow stays suppressed until the
            // snapshot lands, so there is never a second shadow parked in the grid slot.
            transition.terminalGhost.setElevation(transition.terminalShadowElevation * p);
            transition.terminalGhost.setOutlineProvider(new RoundedOutlineProvider(
                    SessionOverviewTransitionMath.lerp(
                            0f, transition.activeCard.thumbnailCornerRadius, p)));
            transition.terminalGhost.setAlpha(
                    SessionOverviewTransitionMath.ghostAlpha(p));

            final float previewAlpha = SessionOverviewTransitionMath.previewAlpha(p);
            final ImageView preview = transition.activeCard.preview;
            if (previewAlpha <= 0f) {
                preview.setAlpha(0f);
                preview.setVisibility(View.INVISIBLE);
            } else {
                preview.setVisibility(View.VISIBLE);
                preview.setAlpha(previewAlpha);
            }
        }

        if (origin == null) return;
        if (transition.tabPanelGhost != null) {
            transition.tabPanelGhost.setAlpha(
                    SessionOverviewTransitionMath.openingTabPanelAlpha(p));
        }
        final float sourceAlpha = SessionOverviewTransitionMath.tabSourceAlpha(p);
        final float targetAlpha = SessionOverviewTransitionMath.tabTargetAlpha(p);
        final float sourcePath =
                SessionOverviewTransitionMath.tabSourcePathFraction(p);
        final float targetPath =
                SessionOverviewTransitionMath.tabTargetPathFraction(p);
        for (TabGhost ghost : transition.tabGhosts) {
            // Transform at the midpoint of the invisible gap, not at the start of the fade-in,
            // so a dropped frame cannot expose the size/style change.
            if (!ghost.targetApplied
                    && p >= SessionOverviewTransitionMath.TAB_MOTION_SPLIT) {
                ghost.targetApplied = applyOpeningTabTarget(ghost, origin);
            }

            // The active card belongs to the terminal snapshot, which is already shrinking into
            // its final slot. Every other card follows its tab so the thumbnail moves with it.
            final boolean companion = ghost.card != transition.activeCard;
            if (ghost.targetApplied) {
                ghost.view.setAlpha(targetAlpha);
                if (ghost.targetBounds != null) {
                    // The target copy is already at card size and style. Only its start edge and
                    // vertical centre move for the rest of the flight, so the title is never
                    // re-truncated.
                    moveGhostAlongPath(ghost.view, ghost.targetBounds,
                            ghost.sourceBounds, ghost.targetBounds, targetPath);
                    if (companion) {
                        moveCardWithTab(ghost.card.root, ghost.targetBounds,
                                ghost.sourceBounds, ghost.targetBounds, targetPath);
                    }
                }
                if (ghost.closeGhost != null) {
                    if (ghost.closeTargetApplied) {
                        // Keep the cross rigidly attached to the target pill's end: same
                        // translation as the title, so it cannot drift on its own.
                        ghost.closeGhost.setTranslationX(ghost.view.getTranslationX());
                        ghost.closeGhost.setTranslationY(ghost.view.getTranslationY());
                        ghost.closeGhost.setAlpha(0.7f * targetAlpha);
                    } else {
                        ghost.closeGhost.setAlpha(0f);
                    }
                }
            } else {
                ghost.view.setAlpha(sourceAlpha);
                final Rect liveTarget = cardTabTarget(ghost.card, origin);
                if (liveTarget != null) {
                    moveGhostAlongPath(ghost.view, ghost.sourceBounds,
                            ghost.sourceBounds, liveTarget, sourcePath);
                    if (companion) {
                        moveCardWithTab(ghost.card.root, liveTarget,
                                ghost.sourceBounds, liveTarget, sourcePath);
                    }
                }
                if (ghost.closeGhost != null) {
                    // The source cross, if any, is part of the same rigid tab body. An inactive
                    // strip tab has none, so its target cross stays invisible until the hand-off.
                    ghost.closeGhost.setTranslationX(ghost.view.getTranslationX());
                    ghost.closeGhost.setTranslationY(ghost.view.getTranslationY());
                    ghost.closeGhost.setAlpha(ghost.closeSourceBounds != null
                            ? 0.7f * sourceAlpha : 0f);
                }
            }
        }
    }

    /**
     * Put one opening tab ghost at its final card position and style.
     *
     * <p>This runs at most once per ghost, while the ghost is invisible. The title is rendered
     * once for the target width instead of being rebuilt for every intermediate width. After
     * this, the fade-in only changes the ghost's start edge, vertical centre and alpha. Returns
     * false when the card has not been laid out yet, in which case the ghost stays invisible and
     * the call is retried on the next frame.</p>
     */
    private boolean applyOpeningTabTarget(@NonNull TabGhost ghost, @NonNull int[] origin) {
        final Rect target = cardTabTarget(ghost.card, origin);
        if (target == null || target.isEmpty()) return false;

        ghost.targetBounds = new Rect(target);
        final TextView finalPill = ghost.card.tab;
        ghost.view.setLayoutParams(frameParams(target));
        ghost.view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                finalPill.getTextSize());
        ghost.view.setTextColor(finalPill.getCurrentTextColor());
        ghost.view.setPaintFlags(finalPill.getPaintFlags());
        ghost.background.setColor(ghost.card.tabBackgroundColor);
        ghost.background.setCornerRadius(mTabCornerRadius);
        ghost.view.setPaddingRelative(finalPill.getPaddingStart(), finalPill.getPaddingTop(),
                finalPill.getPaddingEnd(), finalPill.getPaddingBottom());
        // The width usually changes too, in which case onSizeChanged renders the title again.
        // This call covers a target with the same width but a different text size or padding.
        ghost.view.refreshTitle();

        if (ghost.closeGhost != null) {
            final Rect closeTarget = cardChildTarget(ghost.card, ghost.card.close, origin);
            if (closeTarget != null && !closeTarget.isEmpty()) {
                ghost.closeGhost.setLayoutParams(frameParams(closeTarget));
                ghost.closeGhost.setImageTintList(ColorStateList.valueOf(ghost.card.textColor));
                ghost.closeTargetApplied = true;
            }
        }
        return true;
    }

    private void finishTransition(@NonNull Transition transition) {
        if (mTransition != transition) return;
        applyTransitionProgress(transition, 1f);
        cleanupTransition(transition);
    }

    /**
     * Fade every card that is not represented by the flying terminal. All of them share the
     * flying tab's target fade, so a thumbnail emerges at 75% of the motion whether or not its
     * own tab was visible in the strip and has a ghost to move it. The active card owns the
     * terminal snapshot until the final frame, so it must stay out of this fade.
     */
    private void applyOpeningCardAlpha(@NonNull Transition transition, float progress) {
        if (transition.activeCard == null) return;
        final float alpha = SessionOverviewTransitionMath.openingCardAlpha(progress);
        for (Card card : mCards) {
            if (card != transition.activeCard) card.root.setAlpha(alpha);
        }
    }

    /** Abort the flight and put every real view back where it belongs. */
    private void cancelTransition() {
        final Transition transition = mTransition;
        if (transition == null) return;
        if (transition.started) {
            applyTransitionProgress(transition, 1f);
        }
        cleanupTransition(transition);
    }

    private void cleanupTransition(@NonNull Transition transition) {
        if (mTransition != transition) return;
        mTransition = null;
        if (mGrid != null) mGrid.removeCallbacks(mStartTransitionRunnable);

        final ValueAnimator animator = transition.animator;
        transition.animator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            if (animator.isRunning()) animator.cancel();
        }

        for (View tab : transition.hiddenTabs) {
            tab.setVisibility(View.VISIBLE);
        }
        for (Card card : transition.hiddenPills) {
            card.tab.setVisibility(View.VISIBLE);
            card.close.setVisibility(View.VISIBLE);
        }
        for (Card card : mCards) {
            card.root.setAlpha(1f);
            card.root.setTranslationX(0f);
            card.root.setTranslationY(0f);
        }
        if (transition.activeCard != null) {
            transition.activeCard.preview.setAlpha(1f);
            transition.activeCard.preview.setVisibility(View.VISIBLE);
            setCardShadowSuppressed(transition.activeCard, false);
        }
        if (transition.backgroundTransition) {
            // The flight may be cancelled before its first frame. The overview backdrop must
            // still be the terminal colour, not the app colour captured at the start.
            mActivity.applyOverviewWindowBackground(transition.backgroundEnd);
        }
        if (transition.terminalGhost != null) {
            transition.terminalGhost.setImageDrawable(null);
        }
        if (transition.tabPanelGhost != null) {
            transition.tabPanelGhost.setImageDrawable(null);
        }
        if (mGhostLayer != null) mGhostLayer.removeAllViews();
        recycleBitmap(transition.terminalBitmap);
        transition.terminalBitmap = null;
        transition.terminalGhost = null;
        recycleBitmap(transition.tabPanelBitmap);
        transition.tabPanelBitmap = null;
        transition.tabPanelGhost = null;
        transition.tabPanelSourceLocal = null;
    }

    /** Release sources captured for a transition that was never installed. */
    private static void discardTransition(@Nullable Transition transition) {
        if (transition == null) return;
        recycleBitmap(transition.terminalBitmap);
        transition.terminalBitmap = null;
        recycleBitmap(transition.tabPanelBitmap);
        transition.tabPanelBitmap = null;
        transition.tabSources.clear();
    }

    // ── Closing transition ─────────────────────────────────────────────────

    /**
     * Prepare the exact reverse of the opening terminal flight: the selected card grows back into
     * the terminal grid, with the terminal snapshot opaque from the first frame.
     *
     * <p>Both ends of the flight are the glyph grid, not the whole {@link TerminalView}. The
     * view's background margins are absent from the card, so including them would make the
     * expansion change aspect ratio and jump when the live terminal takes over. For the session
     * that is currently visible the grid is cropped from a {@link View#draw(Canvas)} snapshot, so
     * the first and last frames match the terminal pixels exactly. A background session has no
     * attached terminal view, so its emulator is rendered through
     * {@link TerminalPreviewRenderer} at the grid size instead; no second TerminalView is created
     * and no PTY is resized.</p>
     */
    @Nullable
    private ClosingTransition prepareClosingTransition(@NonNull Card card) {
        if (mOverlay == null || mGhostLayer == null || mContentArea == null) return null;
        if (!card.root.isLaidOut() || card.root.getWidth() <= 0 || card.root.getHeight() <= 0) {
            return null;
        }

        final int[] origin = contentOrigin();
        if (origin == null) return null;
        final Rect cardStart = localRect(card.preview, origin);
        if (cardStart == null || cardStart.isEmpty()) return null;

        final TerminalView terminal = mActivity.getActiveTerminalView();
        final Rect terminalContent = new Rect();
        final boolean hasTerminalContent = terminal != null
                && terminal.isAttachedToWindow()
                && terminal.getWidth() > 0 && terminal.getHeight() > 0
                && terminal.getTerminalContentBounds(terminalContent);
        final Rect targetWindow = hasTerminalContent
                ? windowRectOf(terminal, terminalContent)
                : windowRectOf(mContentArea);
        final Rect terminalTarget = offsetRect(targetWindow, origin);
        if (terminalTarget.isEmpty()) return null;

        final TerminalEmulator emulator = card.session.getEmulator();
        if (emulator == null) return null;

        // The overview keeps the window background visible, so the expansion snapshot must keep
        // the terminal's own alpha. An RGB bitmap here would turn the wallpaper and its blur into
        // an opaque sheet for the duration of the closing flight.
        final int transparency = mActivity.getEffectiveBackgroundTransparency();
        final Bitmap.Config bitmapConfig = transparency > 0
                ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;

        Bitmap bitmap = null;
        Bitmap full = null;
        try {
            if (hasTerminalContent && terminal.getCurrentSession() == card.session) {
                full = Bitmap.createBitmap(terminal.getWidth(), terminal.getHeight(),
                        bitmapConfig);
                terminal.draw(new Canvas(full));
                bitmap = cropToGrid(full, terminalContent);
                full = null;
            } else {
                bitmap = Bitmap.createBitmap(terminalTarget.width(), terminalTarget.height(),
                        bitmapConfig);
                TerminalPreviewRenderer.render(emulator, bitmap, previewTextSize(),
                        previewTypeface(), scrollTopRow(card.session), transparency);
            }
        } catch (RuntimeException ignored) {
            recycleBitmap(full);
            recycleBitmap(bitmap);
            return null;
        }

        final ClosingTransition transition = new ClosingTransition(card, bitmap,
                cardStart, terminalTarget);
        if (!installClosingVisuals(transition)) {
            recycleBitmap(bitmap);
            return null;
        }
        // Install the flying pills first: each one hides its real target tab, so the panel
        // snapshot taken afterwards has an empty slot for every ghost to land in instead of
        // drawing the returning tabs twice. The ghosts are brought back above the snapshot below.
        installClosingTabVisuals(transition, origin);
        installClosingTabPanelGhost(transition, origin);
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            ghost.view.bringToFront();
        }
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            if (ghost.closeGhost != null) ghost.closeGhost.bringToFront();
        }
        return transition;
    }

    private boolean installClosingVisuals(@NonNull ClosingTransition transition) {
        if (mGhostLayer == null) return false;
        mGhostLayer.removeAllViews();

        final ImageView ghost = new ImageView(mActivity);
        ghost.setScaleType(ImageView.ScaleType.FIT_XY);
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        ghost.setImageBitmap(transition.terminalBitmap);
        ghost.setAlpha(0f);
        // The card's own shadow is suppressed while this snapshot expands; the snapshot starts
        // with the card's shadow shape and radius so the hand-off at progress zero is invisible.
        FloatingShadow.apply(ghost, FloatingShadow.elevationPx(mActivity.getResources()),
                floatingShadowColor());
        ghost.setOutlineProvider(new RoundedOutlineProvider(
                transition.card.thumbnailCornerRadius));
        ghost.setClipToOutline(true);
        transition.terminalShadowElevation =
                FloatingShadow.elevationPx(mActivity.getResources());
        ghost.setLayoutParams(frameParams(transition.cardStartLocal));

        mGhostLayer.addView(ghost);
        transition.terminalGhost = ghost;
        setCardShadowSuppressed(transition.card, true);
        transition.tabBaseAlpha = transition.card.tab.getAlpha();
        transition.closeBaseAlpha = transition.card.close.getAlpha();
        return true;
    }

    /**
     * Draw the final tab strip over the overview so it can fade in during the expansion.
     *
     * <p>The strip was already selected and scrolled behind the overlay before this snapshot was
     * taken, so the first visible frame after the overlay disappears matches it exactly. Every
     * returning target tab is hidden by {@link #installClosingTabVisuals} before this capture;
     * those slots are intentionally empty so the flying pills are the only copies drawn there
     * until they land.</p>
     */
    private void installClosingTabPanelGhost(@NonNull ClosingTransition transition,
                                             @NonNull int[] origin) {
        if (mGhostLayer == null || mTabPanel == null) return;
        final Rect panelWindow = windowRectOf(mTabPanel);
        if (panelWindow.isEmpty()) return;
        final Bitmap bitmap = captureViewBitmap(mTabPanel);
        if (bitmap == null) return;

        final ImageView ghost = new ImageView(mActivity);
        ghost.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ghost.setImageBitmap(bitmap);
        final Rect panelLocal = offsetRect(panelWindow, origin);
        ghost.setLayoutParams(frameParams(panelLocal));
        ghost.setAlpha(0f);
        mGhostLayer.addView(ghost);
        transition.tabPanelGhost = ghost;
        transition.tabPanelBitmap = bitmap;
    }

    /**
     * Reverse the opening tab flight for every visible card tab. Each ghost moves and fades out
     * on its card, is transformed once while invisible, then moves into its strip slot and fades
     * in. All target tabs are hidden before the panel snapshot is taken, so the snapshot and the
     * ghosts never draw the same tab at once.
     */
    private void installClosingTabVisuals(@NonNull ClosingTransition transition,
                                          @NonNull int[] origin) {
        if (mGhostLayer == null) return;
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs == null) return;

        for (Card card : new ArrayList<>(mCards)) {
            // Only animate tabs the user can actually see on the grid. Off-screen cards keep
            // their real pill under the expanding terminal and do not need a ghost.
            if (visibleLocalRect(card.tab, origin) == null) continue;
            final Rect startBounds = localRect(card.tab, origin);
            if (startBounds == null || startBounds.isEmpty()) continue;

            final View targetTab = tabs.findTabForSession(card.session);
            if (targetTab == null || !targetTab.isShown()) continue;
            // Read the full strip bounds, not just the visible intersection: a tab may be
            // scrolled partly out of view and still be the right landing spot for its ghost.
            final Rect targetBounds = localRect(targetTab, origin);
            if (targetBounds == null || targetBounds.isEmpty()) continue;

            final TextView targetTitle = targetTab.findViewById(R.id.session_tab_title);
            final View targetClose = targetTab.findViewById(R.id.session_tab_close);
            int closeFootprint = 0;
            Rect targetCloseBounds = null;
            int targetCloseTint = targetTitle != null
                    ? targetTitle.getCurrentTextColor() : card.textColor;
            if (targetClose != null && targetClose.getVisibility() == View.VISIBLE) {
                closeFootprint = targetClose.getWidth();
                final ViewGroup.LayoutParams params = targetClose.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    closeFootprint += ((ViewGroup.MarginLayoutParams) params).getMarginStart();
                }
                // Keep the full target bounds. The overlay clips a partially scrolled tab
                // at the same edge as the strip, so the returning ghost lands on the real
                // title layout instead of a narrower intersection.
                targetCloseBounds = localRect(targetClose, origin);
                if (targetClose instanceof ImageButton) {
                    final ColorStateList tint = ((ImageButton) targetClose).getImageTintList();
                    if (tint != null) targetCloseTint = tint.getDefaultColor();
                }
            }

            final SessionOverviewTabTitleView ghost =
                    new SessionOverviewTabTitleView(mActivity);
            ghost.setMaxLines(card.tab.getMaxLines());
            ghost.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            // The card title and the strip title both include font padding; keeping it here
            // avoids a baseline jump when the ghost lands.
            ghost.setIncludeFontPadding(true);
            ghost.setLineSpacing(card.tab.getLineSpacingExtra(),
                    card.tab.getLineSpacingMultiplier());
            ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ghost.setFullTitle(card.fullTitle);
            ghost.setTextSize(TypedValue.COMPLEX_UNIT_PX, card.tab.getTextSize());
            ghost.setTextColor(card.tab.getCurrentTextColor());
            ghost.setPaintFlags(targetTitle != null
                    ? targetTitle.getPaintFlags() : card.tab.getPaintFlags());
            final Typeface targetTypeface = targetTitle != null
                    ? targetTitle.getTypeface() : card.tab.getTypeface();
            if (targetTypeface != null) ghost.setTypeface(targetTypeface);
            ghost.setPaddingRelative(card.tab.getPaddingStart(), card.tab.getPaddingTop(),
                    card.tab.getPaddingEnd(), card.tab.getPaddingBottom());

            final GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setColor(card.tabBackgroundColor);
            background.setCornerRadius(mTabCornerRadius);
            ghost.setBackground(background);
            ghost.setLayoutParams(frameParams(startBounds));
            // Keep the returning tab above the expanding terminal for the whole flight.
            ghost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(
                    transition.terminalShadowElevation));
            mGhostLayer.addView(ghost);

            final float fallbackTextSize = mActivity.getResources()
                    .getDimensionPixelSize(R.dimen.terminal_tab_text_size);
            final float targetTextSize = targetTitle != null
                    ? targetTitle.getTextSize() : fallbackTextSize;
            final int targetTextColor = targetTitle != null
                    ? targetTitle.getCurrentTextColor() : card.tab.getCurrentTextColor();
            final int targetBackgroundColor = tabs.getTabBackgroundColor(targetTab);
            final int targetCornerRadius = tabs.getTabCornerRadiusPx();
            final int targetPadStart = targetTab.getPaddingStart();
            final int targetPadTop = targetTab.getPaddingTop();
            final int targetPadEnd = targetTab.getPaddingEnd() + closeFootprint;
            final int targetPadBottom = targetTab.getPaddingBottom();

            final Rect startCloseBounds = localRect(card.close, origin);
            final int startCloseTint = card.textColor;
            ImageView closeGhost = null;
            // Only the selected strip tab has a close cross to fly to. A card whose target tab
            // has no cross would otherwise park its own X over the thumbnail for the whole
            // expansion; hide it with the card pill instead.
            if (startCloseBounds != null && !startCloseBounds.isEmpty()
                    && targetCloseBounds != null) {
                closeGhost = new ImageView(mActivity);
                closeGhost.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                closeGhost.setImageResource(R.drawable.ic_close);
                closeGhost.setImageTintList(ColorStateList.valueOf(startCloseTint));
                closeGhost.setAlpha(0.7f);
                closeGhost.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                closeGhost.setLayoutParams(frameParams(startCloseBounds));
                closeGhost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(
                        transition.terminalShadowElevation));
                mGhostLayer.addView(closeGhost);
            }

            final ClosingTabGhost tabGhost = new ClosingTabGhost(card, ghost, background,
                    startBounds, targetTextSize, targetTextColor, targetBackgroundColor,
                    targetCornerRadius, targetPadStart, targetPadTop, targetPadEnd,
                    targetPadBottom,
                    targetTitle != null ? targetTitle.getPaintFlags() : card.tab.getPaintFlags(),
                    targetTab, targetBounds, closeGhost,
                    targetClose, targetCloseBounds, targetCloseTint);
            tabGhost.tabBaseAlpha = card.tab.getAlpha();
            tabGhost.closeBaseAlpha = card.close.getAlpha();
            tabGhost.targetTabVisibility = targetTab.getVisibility();
            transition.tabGhosts.add(tabGhost);

            // The real compact pill is exactly covered by its ghost at progress zero. The card
            // cross is hidden for every flying pill: only the selected target tab has a cross
            // for the ghost to land on, and an un-anchored X must not float over the expansion.
            card.tab.setVisibility(View.INVISIBLE);
            card.close.setVisibility(View.INVISIBLE);

            // Keep each strip slot (INVISIBLE, not GONE) but stop painting it. The panel snapshot
            // taken after this method therefore has a gap at every landing spot, and cleanup
            // restores all of them before the overlay is removed.
            targetTab.setVisibility(View.INVISIBLE);
        }
    }

    private void startClosingTransition(@NonNull ClosingTransition transition) {
        if (mClosingTransition != transition || transition.started) return;
        transition.started = true;
        // The overview backdrop crossfades back to the normal app background while the selected
        // terminal expands, so the live terminal lands on the same colour it had before opening.
        transition.backgroundStart = mActivity.getWindowBackgroundColor();
        transition.backgroundEnd = mActivity.getSchemeWindowBackgroundColor();
        transition.backgroundTransition = true;
        applyClosingProgress(transition, 0f);

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SessionOverviewTransitionMath.DURATION_MS);
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(animation ->
                applyClosingProgress(transition,
                        (float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishClosingTransition(transition);
            }
        });
        transition.animator = animator;
        animator.start();
    }

    /**
     * Advance the closing flight. The terminal expands continuously; each tab moves and fades
     * out at card size, is transformed once while invisible, then moves the rest of the way and
     * fades in at strip size.
     */
    private void applyClosingProgress(@NonNull ClosingTransition transition, float progress) {
        if (mClosingTransition != transition) return;
        final float p = SessionOverviewTransitionMath.clamp01(progress);

        if (transition.backgroundTransition) {
            mActivity.applyWindowBackgroundColor(SessionOverviewTransitionMath.blendColor(
                    transition.backgroundStart, transition.backgroundEnd, p));
        }
        applyClosingCardAlpha(transition, p);

        final int[] origin = contentOrigin();
        if (origin == null) return;

        if (transition.tabPanelGhost != null) {
            transition.tabPanelGhost.setAlpha(
                    SessionOverviewTransitionMath.closingTabPanelAlpha(p));
        }

        // The selected card's own preview is hidden at progress zero. Read it without the card's
        // temporary translation (when a cancel restores it) so the expansion always starts in
        // the resting grid slot.
        final Rect start = cardChildTarget(
                transition.card, transition.card.preview, origin);
        final Rect target = closingTerminalTarget(origin, transition.terminalTargetLocal);
        if (transition.terminalGhost != null && target != null) {
            transition.terminalGhost.setLayoutParams(frameParams(lerpRect(
                    start != null ? start : transition.cardStartLocal, target, p)));
        }

        final float ghostAlpha = SessionOverviewTransitionMath.closingGhostAlpha(p);
        if (transition.terminalGhost != null) {
            transition.terminalGhost.setAlpha(ghostAlpha);
            // Reverse of the opening shadow: the card's floating shadow travels out with the
            // expanding snapshot and is square and shadowless by the time it reaches the live
            // terminal. The card's own shadow stays suppressed for the whole flight.
            transition.terminalGhost.setElevation(
                    transition.terminalShadowElevation * (1f - p));
            transition.terminalGhost.setOutlineProvider(new RoundedOutlineProvider(
                    SessionOverviewTransitionMath.lerp(
                            transition.card.thumbnailCornerRadius, 0f, p)));
        }

        // The selected card preview is hidden immediately and the full-size terminal snapshot is
        // opaque from progress zero, so the expansion starts without an unfade. Every visible
        // card tab has a ghost that moves and fades out on the card, then moves into its strip
        // slot and fades in. If the selected card had no visible tab to animate, its pill and
        // cross simply disappear under the expanding terminal.
        final float previewAlpha = SessionOverviewTransitionMath.closingPreviewAlpha(p);
        transition.card.preview.setAlpha(previewAlpha);
        transition.card.preview.setVisibility(previewAlpha <= 0f
                ? View.INVISIBLE : View.VISIBLE);
        boolean selectedTabHasGhost = false;
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            applyClosingTabProgress(transition, ghost, origin, p);
            if (ghost.card == transition.card) selectedTabHasGhost = true;
        }
        if (!selectedTabHasGhost) {
            transition.card.tab.setAlpha(transition.tabBaseAlpha * previewAlpha);
            transition.card.close.setAlpha(transition.closeBaseAlpha * previewAlpha);
        }
    }

    /**
     * Fade the rest of the grid out while the selected terminal expands. All unselected cards
     * share the flying tab's source fade, so a thumbnail disappears during the first 25% of the
     * motion whether or not its own tab was visible in the strip and has a ghost to move it.
     * The selected card is already owned by the opaque terminal snapshot, so only the neighbours
     * follow this alpha.
     */
    private void applyClosingCardAlpha(@NonNull ClosingTransition transition, float progress) {
        final float alpha = SessionOverviewTransitionMath.closingCardAlpha(progress);
        for (Card card : mCards) {
            if (card != transition.card) card.root.setAlpha(alpha);
        }
    }

    private void applyClosingTabProgress(@NonNull ClosingTransition transition,
                                         @NonNull ClosingTabGhost ghost, @NonNull int[] origin,
                                         float p) {
        // Transform at the midpoint of the invisible gap, not at the start of the fade-in, so a
        // dropped frame cannot expose the size/style change.
        if (!ghost.targetApplied
                && p >= SessionOverviewTransitionMath.TAB_MOTION_SPLIT) {
            ghost.targetApplied = applyClosingTabTarget(ghost, origin);
        }

        final float sourceAlpha = SessionOverviewTransitionMath.tabSourceAlpha(p);
        final float targetAlpha = SessionOverviewTransitionMath.tabTargetAlpha(p);
        final float sourcePath =
                SessionOverviewTransitionMath.tabSourcePathFraction(p);
        final float targetPath =
                SessionOverviewTransitionMath.tabTargetPathFraction(p);

        if (ghost.targetApplied) {
            ghost.view.setAlpha(targetAlpha);
            // The target copy is already at strip size and style. Only its start edge and
            // vertical centre move for the rest of the expansion, so the title is never
            // re-truncated.
            moveGhostAlongPath(ghost.view, ghost.targetBounds, ghost.startBounds,
                    ghost.targetBounds, targetPath);
            if (ghost.closeGhost != null) {
                if (ghost.closeTargetApplied) {
                    // The target cross is part of the target pill. Copying the title's
                    // translation keeps it attached instead of letting it fly independently.
                    ghost.closeGhost.setTranslationX(ghost.view.getTranslationX());
                    ghost.closeGhost.setTranslationY(ghost.view.getTranslationY());
                    ghost.closeGhost.setAlpha(0.7f * targetAlpha);
                } else {
                    ghost.closeGhost.setAlpha(0f);
                }
            }
        } else {
            ghost.view.setAlpha(sourceAlpha);
            moveGhostAlongPath(ghost.view, ghost.startBounds, ghost.startBounds,
                    ghost.targetBounds, sourcePath);
            if (ghost.closeGhost != null) {
                // The card's cross belongs to the source pill; keep it rigid with the title
                // while the pair fades out.
                ghost.closeGhost.setTranslationX(ghost.view.getTranslationX());
                ghost.closeGhost.setTranslationY(ghost.view.getTranslationY());
                ghost.closeGhost.setAlpha(0.7f * sourceAlpha);
            }
        }

        // The selected card owns the expanding terminal snapshot, so it stays out of this
        // coupling. Every other card travels with its returning tab so its thumbnail does not
        // stay behind while the tab fades out.
        if (ghost.card != transition.card && ghost.targetBounds != null) {
            moveCardWithTab(ghost.card.root, ghost.startBounds, ghost.startBounds,
                    ghost.targetBounds, ghost.targetApplied ? targetPath : sourcePath);
        }
    }

    /**
     * Put one returning tab ghost at its final strip position and style.
     *
     * <p>Like the opening target, this runs once while the ghost is invisible. The strip target
     * is already captured, so the only per-frame work left is moving the start edge and fading
     * while the vertical centre follows the line.</p>
     */
    private boolean applyClosingTabTarget(@NonNull ClosingTabGhost ghost, @NonNull int[] origin) {
        // Use the full strip tab, not its visible intersection. The ghost may be partly
        // scrolled out of the panel at the landing edge; the overlay supplies that clip, while
        // the full width keeps the title layout identical to the real tab.
        final Rect liveTarget = localRect(ghost.targetTabView, origin);
        final Rect target = liveTarget != null ? liveTarget : ghost.targetBounds;
        if (target == null || target.isEmpty()) return false;
        ghost.targetBounds = new Rect(target);

        ghost.view.setLayoutParams(frameParams(target));
        ghost.view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                ghost.targetTextSize);
        ghost.view.setTextColor(ghost.targetTextColor);
        // Paint flags cannot be interpolated; use the target's flags so the hand-off to the
        // strip's finished/running styling has no snap.
        ghost.view.setPaintFlags(ghost.targetPaintFlags);
        ghost.background.setColor(ghost.targetBackgroundColor);
        ghost.background.setCornerRadius(ghost.targetCornerRadius);
        ghost.view.setPaddingRelative(ghost.targetPadStart, ghost.targetPadTop,
                ghost.targetPadEnd, ghost.targetPadBottom);
        ghost.view.refreshTitle();

        if (ghost.closeGhost != null) {
            final Rect liveClose = ghost.targetCloseView != null
                    && ghost.targetCloseView.getVisibility() == View.VISIBLE
                    ? localRect(ghost.targetCloseView, origin) : null;
            final Rect targetClose = liveClose != null ? liveClose : ghost.targetCloseBounds;
            if (targetClose != null && !targetClose.isEmpty()) {
                ghost.closeGhost.setLayoutParams(frameParams(targetClose));
                ghost.closeGhost.setImageTintList(
                        ColorStateList.valueOf(ghost.targetCloseTint));
                ghost.closeTargetApplied = true;
            }
        }
        return true;
    }

    @Nullable
    private Rect closingTerminalTarget(@NonNull int[] origin, @NonNull Rect fallback) {
        final TerminalView terminal = mActivity.getActiveTerminalView();
        if (terminal != null && terminal.isAttachedToWindow()
                && terminal.getWidth() > 0 && terminal.getHeight() > 0) {
            final Rect content = new Rect();
            if (terminal.getTerminalContentBounds(content)) {
                final Rect live = offsetRect(windowRectOf(terminal, content), origin);
                if (!live.isEmpty()) return live;
            }
        }
        if (mContentArea != null) {
            final Rect live = localRect(mContentArea, origin);
            if (live != null && !live.isEmpty()) return live;
        }
        return fallback;
    }

    private void finishClosingTransition(@NonNull ClosingTransition transition) {
        if (mClosingTransition != transition) return;
        applyClosingProgress(transition, 1f);
        final TerminalSession session = transition.session;
        cleanupClosingTransition(transition);

        // The tab strip was already selected and scrolled behind the overview before the
        // expansion started. Only the visible hand-off is left.
        hide(false);
        switchToSession(session);
    }

    /** Abort the expansion and put the card's own preview and tab back in place. */
    private void cancelClosingTransition() {
        final ClosingTransition transition = mClosingTransition;
        if (transition == null) return;
        cleanupClosingTransition(transition);
        // The cancelled flight may have left the decor halfway to the app background. The
        // overview is still open, so put its terminal backdrop back before restoring the strip.
        applyOverviewBackground();
        // The strip was pre-positioned for the target before the flight. A cancelled flight must
        // leave the highlight on the session that is still current.
        restoreTabSelectionToCurrentSession();
    }

    private void cleanupClosingTransition(@NonNull ClosingTransition transition) {
        if (mClosingTransition != transition) return;
        mClosingTransition = null;

        final ValueAnimator animator = transition.animator;
        transition.animator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            if (animator.isRunning()) animator.cancel();
        }

        transition.card.preview.setAlpha(1f);
        transition.card.preview.setVisibility(View.VISIBLE);
        for (Card card : mCards) {
            card.root.setAlpha(1f);
            card.root.setTranslationX(0f);
            card.root.setTranslationY(0f);
        }
        setCardShadowSuppressed(transition.card, false);
        transition.card.tab.setAlpha(transition.tabBaseAlpha);
        transition.card.close.setAlpha(transition.closeBaseAlpha);
        transition.card.tab.setVisibility(View.VISIBLE);
        transition.card.close.setVisibility(View.VISIBLE);
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            if (ghost.card != transition.card) {
                ghost.card.tab.setAlpha(ghost.tabBaseAlpha);
                ghost.card.close.setAlpha(ghost.closeBaseAlpha);
                ghost.card.tab.setVisibility(View.VISIBLE);
                ghost.card.close.setVisibility(View.VISIBLE);
            }
            ghost.targetTabView.setVisibility(ghost.targetTabVisibility);
        }
        transition.tabGhosts.clear();

        if (transition.terminalGhost != null) {
            transition.terminalGhost.setImageDrawable(null);
        }
        if (transition.tabPanelGhost != null) {
            transition.tabPanelGhost.setImageDrawable(null);
        }
        if (mGhostLayer != null) mGhostLayer.removeAllViews();
        recycleBitmap(transition.terminalBitmap);
        transition.terminalBitmap = null;
        transition.terminalGhost = null;
        recycleBitmap(transition.tabPanelBitmap);
        transition.tabPanelBitmap = null;
        transition.tabPanelGhost = null;
    }

    @Nullable
    private int[] contentOrigin() {
        if (mContentArea == null || !mContentArea.isAttachedToWindow()) return null;
        final int[] location = new int[2];
        mContentArea.getLocationInWindow(location);
        // The ghost layer moves up with the extended overlay, so everything measured in window
        // coordinates has to be rebased by the same amount while the extension is active.
        location[1] -= mStatusBarTopExtension;
        return location;
    }

    @Nullable
    private Rect localRect(@NonNull View view, @NonNull int[] origin) {
        if (!view.isAttachedToWindow()) return null;
        final int[] location = new int[2];
        view.getLocationInWindow(location);
        final int left = location[0] - origin[0];
        final int top = location[1] - origin[1];
        return new Rect(left, top, left + view.getWidth(), top + view.getHeight());
    }

    @Nullable
    private static Rect visibleLocalRect(@NonNull View view, @NonNull int[] origin) {
        final Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds) || bounds.isEmpty()) return null;
        return offsetRect(bounds, origin);
    }

    @Nullable
    private Card findCard(@Nullable TerminalSession session) {
        if (session == null) return null;
        for (Card card : mCards) {
            if (card.session == session) return card;
        }
        return null;
    }

    @Nullable
    private Card findCardByRoot(@NonNull View root) {
        for (Card card : mCards) {
            if (card.root == root) return card;
        }
        return null;
    }

    /**
     * Move the grid to the remembered position while keeping the active card fully visible.
     *
     * <p>On the first call after opening, the preferred offset is the one saved when the overview
     * last closed. On later calls (a selection change, a reorder, a resize) it is the offset the
     * user is currently looking at, so an incidental layout must not throw the grid back to an
     * old position. In both cases the offset is shifted by the smallest amount needed to keep the
     * whole active card between the status-bar strip and the bottom of the viewport. With up to
     * four sessions the scroll range is zero.</p>
     *
     * <p>This runs before the opening animator starts, so the terminal has a fully visible card
     * to fly into.</p>
     */
    private void scrollToActiveCard() {
        if (!mShowing || mClosingTransition != null || mPendingClosingCard != null
                || (mDragController != null && mDragController.isActive())
                || mScrollView == null || mGrid == null || !mGrid.isLaidOut()) {
            return;
        }
        final SessionOverviewGridMath.Layout layout = mGrid.getGridLayout();
        if (layout == null || layout.columns <= 0 || layout.cardHeight <= 0) return;
        final int viewportHeight = mScrollView.getHeight();
        if (viewportHeight <= 0) return;

        final int topInset = mGrid.getPaddingTop();
        final int maxScroll = Math.max(0, mGrid.getHeight() - viewportHeight);
        final int currentScroll = mScrollView.getScrollY();
        final Card active = findCard(mActivity.getCurrentSession());
        final int index = active != null ? mCards.indexOf(active) : -1;
        final int target;
        if (index < 0) {
            final int preferred = mRestoreScrollOnOpen
                    ? Math.max(0, mRememberedScrollY) : currentScroll;
            target = Math.max(0, Math.min(maxScroll, preferred));
        } else {
            final int row = index / layout.columns;
            // The row's top in the grid's coordinate space, excluding the status-bar strip.
            final int rowTop = layout.top + row * (layout.cardHeight + layout.gap);
            final int preferred = mRestoreScrollOnOpen
                    ? (mRememberedScrollY >= 0 ? mRememberedScrollY : rowTop)
                    : currentScroll;
            final int cardTop = topInset + rowTop;
            target = SessionOverviewScrollMath.scrollForActiveCard(
                    preferred, viewportHeight, topInset, maxScroll,
                    cardTop, cardTop + layout.cardHeight);
        }
        if (currentScroll != target) {
            mScrollView.scrollTo(0, target);
        }
    }

    private void buildCards(@NonNull List<TermuxSession> sessions) {
        releaseCards();
        final TerminalSession current = mActivity.getCurrentSession();
        for (TermuxSession termuxSession : new ArrayList<>(sessions)) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;

            final SessionOverviewCardView root =
                    (SessionOverviewCardView) mInflater.inflate(
                            R.layout.item_session_overview_card, mGrid, false);
            final ImageView preview = root.findViewById(R.id.session_overview_preview);
            final SessionOverviewTabTitleView tab =
                    root.findViewById(R.id.session_overview_tab);
            final ImageButton close = root.findViewById(R.id.session_overview_close);
            final Card card = new Card(root, preview, tab, close, session);

            root.setOnClickListener(v -> selectSession(card.session));
            if (mDragController != null) {
                root.setOnTouchListener((view, event) -> mDragController.onTouch(root, event));
            }
            root.setOnSwipeDismissListener((view, direction) -> closeSession(card));
            close.setOnClickListener(v -> closeSession(card));
            applyTabMetrics(card);
            applyCardAppearance(card);
            bindTab(card, session == current);
            mGrid.addView(root);
            mCards.add(card);
        }
    }

    private void reloadDimensions() {
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        mTabCornerRadius = tabs != null
                ? tabs.getTabCornerRadiusPx()
                : mActivity.getResources().getDimensionPixelSize(
                        R.dimen.terminal_tab_corner_radius);
    }

    /**
     * Re-apply resource-dependent sizes after a configuration change.
     *
     * <p>An opening flight is deliberately not cancelled here. {@code keyboardHidden} is a
     * declared configuration change in the manifest, and the flight itself hides the IME; a
     * cancel would abort the animation before its first frame. The flight reads the card and pill
     * bounds live on every frame, so a rotation or split-screen resize can simply retarget it for
     * the few remaining milliseconds.</p>
     */
    public void onConfigurationChanged(@NonNull Configuration config) {
        reloadDimensions();
        if (mDragController != null) mDragController.cancelForModelChange();
        if (!mShowing) return;
        applyOverviewBackground();
        updateGridAspect();
        final TerminalSession current = mActivity.getCurrentSession();
        for (Card card : mCards) {
            applyTabMetrics(card);
            applyCardAppearance(card);
            if (card.tabBackground != null) {
                card.tabBackground.setCornerRadius(mTabCornerRadius);
            }
            bindTab(card, card.session == current);
        }
        schedulePreviewRender();
    }

    /**
     * Repaint the window background and the card shadows after the live terminal background
     * changes. This runs for OSC palette updates and scheme swaps while the overview is open.
     */
    public void onTerminalBackgroundChanged() {
        if (!mShowing) return;
        applyOverviewBackground();
        for (Card card : mCards) {
            applyCardAppearance(card);
        }
    }

    /**
     * Cards carry no chrome of their own: the terminal snapshot is the card, with the compact
     * tab floating over its top edge. Removing the background, border and press ripple also
     * keeps the thumbnail from pulsing under the tab when the card is tapped. The shared
     * floating shadow is the only chrome, so the thumbnail still lifts off the terminal
     * background exactly like the controls drawn over it.
     */
    private void applyCardAppearance(@NonNull Card card) {
        card.root.setBackground(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            card.root.setForeground(null);
        }
        // Clipping is owned by applyThumbnailCornerRadius(): the root outline is the same
        // rounded rectangle as the preview, so both the snapshot and the tab stay inside the
        // card's corners. Do not reset it to BOUNDS or disable clipping here.
        // The outline is installed by applyTabMetrics() so the shadow follows the thumbnail's
        // rounded corners. Do not replace it with BOUNDS here; this method also runs when only
        // the terminal background changes.
        final Resources res = mActivity.getResources();
        // While a card's terminal snapshot is flying, the snapshot carries the shadow and the
        // card's own shadow is suppressed. Otherwise the grid would show a stationary shadow in
        // the final slot while the thumbnail is still somewhere between the terminal and the card.
        final int elevation = card.shadowSuppressed ? 0 : FloatingShadow.elevationPx(res);
        FloatingShadow.apply(card.root, elevation, floatingShadowColor());
        card.preview.setBackground(null);
    }

    /** The shared card/ghost shadow colour for the current terminal scheme. */
    private int floatingShadowColor() {
        return FloatingShadow.color(mActivity.getResources(),
                ColorSchemeUtils.isColorLight(mOverlayBackgroundColor),
                R.fraction.floating_shadow_alpha);
    }

    /**
     * Turn the card's own floating shadow off while its terminal snapshot is in flight, and back
     * on once the snapshot has landed or the transition is cancelled.
     */
    private void setCardShadowSuppressed(@Nullable Card card, boolean suppressed) {
        if (card == null || card.shadowSuppressed == suppressed) return;
        card.shadowSuppressed = suppressed;
        applyCardAppearance(card);
    }

    /**
     * Round the preview and the shadow with the compact tab's effective radius. The tab height
     * changes with the single/double-line mode and Android clamps a background's corner radius to
     * half the tab height, so a fixed 18dp thumbnail would no longer match the tab. The radius
     * below mirrors that clamp exactly.
     */
    private void applyThumbnailCornerRadius(@NonNull Card card) {
        final float radius = card.thumbnailCornerRadius;
        // The preview outline rounds the bitmap itself. The root outline and clip are the
        // second line of defence: some ImageView implementations and hardware layers do not
        // apply a child outline to the bitmap, while a ViewGroup clipToOutline always clips
        // every child to the rounded card. Both use the same radius, so the shadow, preview
        // and tab agree.
        final RoundedOutlineProvider outline = new RoundedOutlineProvider(radius);
        card.preview.setOutlineProvider(outline);
        card.preview.setClipToOutline(true);
        card.root.setOutlineProvider(outline);
        card.root.setClipToOutline(true);
    }

    /**
     * Visible radius of a tab background after Android clamps its corners to half the tab
     * height. The strip asks for 18dp in both line modes, but the single-line tab is 24dp high
     * and the two-line tab is 34dp high, so the visible radii are 12dp and 17dp.
     */
    static float effectiveTabCornerRadius(int tabCornerRadiusPx, int tabHeightPx) {
        return Math.min(tabCornerRadiusPx, tabHeightPx / 2f);
    }

    /**
     * Re-read the compact tab metrics from resources. XML supplies the initial values; this
     * keeps the tab in step with density and font-scale changes without re-inflating the card.
     */
    private void applyTabMetrics(@NonNull Card card) {
        final Resources res = mActivity.getResources();
        // The card tab is the strip tab's destination, not a differently sized pill. Reuse the
        // strip's current height, corner radius and line count so the opening flight never
        // changes the tab's shape (and so the single/double-line preference is respected in the
        // overview too). The overview keeps its own padding: the end inset reserves the separate
        // close target, which is not a child of the pill.
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        final int height = tabs != null
                ? tabs.getTabHeightPx()
                : res.getDimensionPixelSize(R.dimen.session_overview_tab_height);
        final int cornerRadius = tabs != null
                ? tabs.getTabCornerRadiusPx()
                : res.getDimensionPixelSize(R.dimen.terminal_tab_corner_radius);
        final int maxLines = tabs != null ? tabs.getTabMaxLines() : 1;
        final int margin = res.getDimensionPixelSize(R.dimen.session_overview_tab_margin);
        final int padStart = res.getDimensionPixelSize(
                R.dimen.session_overview_tab_padding_horizontal);
        final int padEnd = res.getDimensionPixelSize(R.dimen.session_overview_tab_padding_end);
        final int padVertical = res.getDimensionPixelSize(
                R.dimen.session_overview_tab_padding_vertical);
        final int closeWidth = res.getDimensionPixelSize(R.dimen.terminal_tab_close_size);
        final int textSize = res.getDimensionPixelSize(
                R.dimen.terminal_tab_text_size);
        mTabCornerRadius = cornerRadius;
        card.thumbnailCornerRadius = effectiveTabCornerRadius(cornerRadius, height);
        applyThumbnailCornerRadius(card);

        final ViewGroup.LayoutParams tabParams = card.tab.getLayoutParams();
        tabParams.height = height;
        if (tabParams instanceof ViewGroup.MarginLayoutParams) {
            final ViewGroup.MarginLayoutParams margins =
                    (ViewGroup.MarginLayoutParams) tabParams;
            margins.topMargin = margin;
            margins.bottomMargin = margin;
            // The pill is inset from the card's top, start and end by the same amount.
            margins.setMarginStart(margin);
            margins.setMarginEnd(margin);
        }
        card.tab.setLayoutParams(tabParams);
        card.tab.setPaddingRelative(padStart, padVertical, padEnd, padVertical);
        card.tab.setHorizontallyScrolling(false);
        card.tab.setMaxLines(maxLines);
        // Truncation is always the alpha fade; never let setMaxLines() or a theme bring back
        // the platform ellipsis in either line-count mode.
        card.tab.setEllipsize(null);
        card.tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        card.tab.refreshTitle();

        final ViewGroup.MarginLayoutParams closeParams =
                (ViewGroup.MarginLayoutParams) card.close.getLayoutParams();
        closeParams.width = closeWidth;
        closeParams.height = height;
        closeParams.topMargin = margin;
        // Keep the same 8dp inner padding on the close side as the title has on the start side.
        closeParams.setMarginEnd(margin + padStart);
        card.close.setLayoutParams(closeParams);
    }

    private void bindTab(@NonNull Card card, boolean selected) {
        final TermuxColorSchemeManager colors = mActivity.getColorSchemeManager();
        final TerminalSession session = card.session;
        final String title = SessionTitleUtils.resolveDisplayName(mActivity,
                session.mSessionName, session.getTitle());

        final boolean titleChanged = !TextUtils.equals(card.fullTitle, title);
        final boolean selectionChanged = card.selected != selected;
        if (titleChanged) {
            card.fullTitle = title;
            card.tab.setFullTitle(title);
        }
        if (titleChanged || selectionChanged) {
            card.root.setContentDescription(mActivity.getString(selected
                            ? R.string.session_overview_session_selected_description
                            : R.string.session_overview_session_description,
                    title));
            card.close.setContentDescription(mActivity.getString(
                    R.string.session_overview_close_session_description, title));
        }

        // The strip's tab backgrounds are translucent because they sit on a panel. A card
        // preview is terminal content, so composite the same tint onto the scheme background
        // first to keep the pill's colour stable, then apply 50% alpha so glyphs underneath stay
        // visible through it.
        final int background = overviewTabBackground(colors.getSchemeBackground(),
                selected ? colors.getButtonActiveBg() : colors.getButtonBg());
        if (card.tabBackground == null) {
            card.tabBackground = new GradientDrawable();
            card.tabBackground.setShape(GradientDrawable.RECTANGLE);
            card.tabBackground.setCornerRadius(mTabCornerRadius);
            card.tabBackground.setColor(background);
            card.tabBackgroundColor = background;
            card.tab.setBackground(card.tabBackground);
        } else if (card.tabBackgroundColor != background) {
            card.tabBackground.setColor(background);
            card.tabBackgroundColor = background;
        }

        if (selectionChanged) {
            card.selected = selected;
            card.tab.setSelected(selected);
            card.root.setSelected(selected);
        }

        final int textColor = colors.getButtonText();
        card.close.setImageTintList(ColorStateList.valueOf(textColor));
        final boolean running = session.isRunning();
        final int exitStatus = session.getExitStatus();
        if (!card.stateValid || card.running != running
                || card.exitStatus != exitStatus || card.textColor != textColor) {
            final int errorColor = ContextCompat.getColor(mActivity,
                    com.termux.shared.R.color.terminal_tab_text_error);
            SessionAppearanceUtils.applyFinishedSessionStyling(card.tab,
                    running, exitStatus, errorColor, textColor);
            card.stateValid = true;
            card.running = running;
            card.exitStatus = exitStatus;
            card.textColor = textColor;
        }
    }

    /**
     * Compose a translucent tint onto an opaque background. The result is then given the compact
     * pill's own 50% alpha, so the pill keeps the tab's colour without hiding the terminal.
     */
    private static int opaqueOver(int background, int foreground) {
        final int alpha = Color.alpha(foreground);
        if (alpha >= 255) return foreground;
        final int inverse = 255 - alpha;
        return Color.argb(255,
                (Color.red(foreground) * alpha + Color.red(background) * inverse) / 255,
                (Color.green(foreground) * alpha + Color.green(background) * inverse) / 255,
                (Color.blue(foreground) * alpha + Color.blue(background) * inverse) / 255);
    }

    /** Replaces a colour's alpha while keeping its RGB. */
    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * The compact pill's background: the strip tint composited over the scheme background, then
     * made 50% transparent so the terminal stays visible underneath.
     */
    static int overviewTabBackground(int schemeBackground, int tint) {
        return withAlpha(opaqueOver(schemeBackground, tint), OVERVIEW_TAB_BACKGROUND_ALPHA);
    }

    private void schedulePreviewRender() {
        if (!mShowing || mActivityStopped || mRenderPosted || mGrid == null) return;
        if (mCardWidth <= 0 || mCardHeight <= 0) return;
        mRenderPosted = true;
        mGrid.post(mRenderPreviewsRunnable);
    }

    private void renderPreviews() {
        if (!mShowing || mActivityStopped || mCardWidth <= 0 || mCardHeight <= 0) return;
        final int textSize = previewTextSize();
        final Typeface typeface = previewTypeface();
        for (Card card : mCards) {
            renderCard(card, textSize, typeface);
        }
    }

    private int previewTextSize() {
        return mActivity.getPreferences() != null
                ? mActivity.getPreferences().getFontSizePx()
                : Math.round(14f * mActivity.getResources().getDisplayMetrics().scaledDensity);
    }

    @NonNull
    private Typeface previewTypeface() {
        return mActivity.getTermuxTerminalSessionClient() != null
                ? mActivity.getTermuxTerminalSessionClient().getTerminalTypeface()
                : Typeface.MONOSPACE;
    }

    /**
     * Re-target the cards after the live terminal viewport changed.
     *
     * <p>Opening the overview hides the keyboard, so the active terminal view can expand from the
     * IME-compressed height to its normal height after the grid has already been built. The cards
     * must follow the normal maximum, not the compressed first frame.</p>
     */
    public void onTerminalViewportChanged() {
        if (!mShowing || mClosingTransition != null || mPendingClosingCard != null) return;
        updateGridAspect();
        schedulePreviewRender();
    }

    /**
     * Size every card to the largest glyph grid the terminal can display with the keyboard
     * hidden. The preview renderer preserves the grid's aspect ratio, so matching the card to it
     * removes the background bands above and below the terminal, and using the maximum viewport
     * keeps all cards at one constant size when the active terminal happens to be compressed by
     * the IME. A missing terminal leaves the grid on its content-area fallback.
     */
    private void updateGridAspect() {
        if (mGrid == null) return;
        final TerminalView terminal = mActivity.getActiveTerminalView();
        final int viewportHeight = mActivity.getTerminalViewportHeightForOverview();
        double aspect = terminal != null
                ? TerminalPreviewRenderer.aspectRatioForViewport(terminal.getWidth(),
                        viewportHeight, previewTextSize(), previewTypeface())
                : 0;
        if (aspect <= 0) {
            final TerminalSession session = mActivity.getCurrentSession();
            final TerminalEmulator emulator = session != null ? session.getEmulator() : null;
            aspect = emulator != null
                    ? TerminalPreviewRenderer.aspectRatio(emulator,
                            previewTextSize(), previewTypeface())
                    : 0;
        }
        mGrid.setCardAspect(aspect);
    }

    private void renderCard(@NonNull Card card, int textSize, @NonNull Typeface typeface) {
        final TerminalEmulator emulator = card.session.getEmulator();
        if (emulator == null) {
            card.preview.setImageDrawable(null);
            releaseBitmap(card);
            return;
        }

        if (card.bitmap != null
                && (card.bitmap.getWidth() != mCardWidth
                || card.bitmap.getHeight() != mCardHeight)) {
            card.preview.setImageDrawable(null);
            releaseBitmap(card);
        }
        if (card.bitmap == null) {
            card.bitmap = Bitmap.createBitmap(mCardWidth, mCardHeight,
                    Bitmap.Config.RGB_565);
        }

        TerminalPreviewRenderer.render(emulator, card.bitmap, textSize, typeface,
                scrollTopRow(card.session));
        card.preview.setImageBitmap(card.bitmap);
        // The bitmap object is reused, so an ImageView that already holds it would not know the
        // pixels changed. Explicit invalidation is what makes the card live rather than a still.
        card.preview.invalidate();
    }

    private int scrollTopRow(@NonNull TerminalSession session) {
        final TerminalView active = mActivity.getActiveTerminalView();
        if (active != null && active.getCurrentSession() == session) {
            return active.getTopRow();
        }
        return mActivity.getTextInputState().getScrollTopRow(session);
    }

    private void releaseCards() {
        if (mDragController != null) mDragController.cancelForModelChange();
        if (mGrid != null) {
            mGrid.removeCallbacks(mRenderPreviewsRunnable);
            mGrid.removeCallbacks(mPreviewRefreshRunnable);
            mGrid.removeCallbacks(mTextRenderRunnable);
        }
        mRenderPosted = false;
        mTextRenderPosted = false;
        mPendingTextSessions.clear();
        for (Card card : mCards) {
            card.preview.setImageDrawable(null);
            releaseBitmap(card);
        }
        mCards.clear();
        if (mGrid != null) mGrid.removeAllViews();
    }

    private static void releaseBitmap(@NonNull Card card) {
        recycleBitmap(card.bitmap);
        card.bitmap = null;
    }

    private static void recycleBitmap(@Nullable Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    /** A tab as it looked in the strip before the overview covered it. */
    private static final class SourceTab {
        final TerminalSession session;
        final View tabView;
        final int textColor;
        final float textSize;
        @Nullable
        final Typeface typeface;
        final int backgroundColor;
        final int cornerRadius;
        final int padStart;
        final int padTop;
        final int padEnd;
        final int padBottom;
        final Rect bounds;
        @Nullable
        final Rect closeBounds;
        final int closeTint;

        SourceTab(TerminalSession session, View tabView, int textColor, float textSize,
                  @Nullable Typeface typeface, int backgroundColor, int cornerRadius,
                  int padStart, int padTop, int padEnd, int padBottom, Rect bounds,
                  @Nullable Rect closeBounds, int closeTint) {
            this.session = session;
            this.tabView = tabView;
            this.textColor = textColor;
            this.textSize = textSize;
            this.typeface = typeface;
            this.backgroundColor = backgroundColor;
            this.cornerRadius = cornerRadius;
            this.padStart = padStart;
            this.padTop = padTop;
            this.padEnd = padEnd;
            this.padBottom = padBottom;
            this.bounds = new Rect(bounds);
            this.closeBounds = closeBounds != null ? new Rect(closeBounds) : null;
            this.closeTint = closeTint;
        }
    }

    /**
     * The flying copy of one visible tab. It moves and fades out at its source size, is placed
     * at its final card size and style while invisible, then moves the rest of the way and fades
     * in. Size and title are laid out at most twice, never once per animation frame.
     *
     * <p>The close-cross ghost shares the title ghost's translation in both phases. It therefore
     * stays attached to the pill's end instead of interpolating toward the target cross on its
     * own; the target cross simply fades in with the target pill.</p>
     */
    private static final class TabGhost {
        final Card card;
        final SessionOverviewTabTitleView view;
        final GradientDrawable background;
        final Rect sourceBounds;
        @Nullable
        final ImageView closeGhost;
        @Nullable
        final Rect closeSourceBounds;
        @Nullable
        Rect targetBounds;
        boolean targetApplied;
        boolean closeTargetApplied;

        TabGhost(Card card, SessionOverviewTabTitleView view, GradientDrawable background,
                 Rect sourceBounds, @Nullable ImageView closeGhost,
                 @Nullable Rect closeSourceBounds) {
            this.card = card;
            this.view = view;
            this.background = background;
            this.sourceBounds = new Rect(sourceBounds);
            this.closeGhost = closeGhost;
            this.closeSourceBounds = closeSourceBounds != null
                    ? new Rect(closeSourceBounds) : null;
        }
    }

    /**
     * The reverse of one opening tab flight. Every visible card tab owns one of these. It moves
     * and fades out at card size, is placed at strip size and style while invisible, then moves
     * the rest of the way and fades in. Size and title are laid out at most twice, never once
     * per animation frame.
     *
     * <p>When the target strip tab has a visible cross, the cross ghost copies the title
     * ghost's translation and stays attached to the pill end in both phases.</p>
     */
    private static final class ClosingTabGhost {
        final Card card;
        final SessionOverviewTabTitleView view;
        final GradientDrawable background;
        final Rect startBounds;
        final float targetTextSize;
        final int targetTextColor;
        final int targetBackgroundColor;
        final int targetCornerRadius;
        final int targetPadStart;
        final int targetPadTop;
        final int targetPadEnd;
        final int targetPadBottom;
        final int targetPaintFlags;
        final View targetTabView;
        Rect targetBounds;
        @Nullable
        final ImageView closeGhost;
        @Nullable
        final View targetCloseView;
        @Nullable
        final Rect targetCloseBounds;
        final int targetCloseTint;
        boolean targetApplied;
        boolean closeTargetApplied;
        /** Visibility to put back on the strip tab after the flight. */
        int targetTabVisibility = View.VISIBLE;
        /** Alpha of the real card pill and cross before they were hidden for this ghost. */
        float tabBaseAlpha = 1f;
        float closeBaseAlpha = 1f;

        ClosingTabGhost(Card card, SessionOverviewTabTitleView view,
                        GradientDrawable background, Rect startBounds,
                        float targetTextSize, int targetTextColor, int targetBackgroundColor,
                        int targetCornerRadius, int targetPadStart, int targetPadTop,
                        int targetPadEnd, int targetPadBottom, int targetPaintFlags,
                        View targetTabView, Rect targetBounds,
                        @Nullable ImageView closeGhost,
                        @Nullable View targetCloseView,
                        @Nullable Rect targetCloseBounds, int targetCloseTint) {
            this.card = card;
            this.view = view;
            this.background = background;
            this.startBounds = new Rect(startBounds);
            this.targetTextSize = targetTextSize;
            this.targetTextColor = targetTextColor;
            this.targetBackgroundColor = targetBackgroundColor;
            this.targetCornerRadius = targetCornerRadius;
            this.targetPadStart = targetPadStart;
            this.targetPadTop = targetPadTop;
            this.targetPadEnd = targetPadEnd;
            this.targetPadBottom = targetPadBottom;
            this.targetPaintFlags = targetPaintFlags;
            this.targetTabView = targetTabView;
            this.targetBounds = new Rect(targetBounds);
            this.closeGhost = closeGhost;
            this.targetCloseView = targetCloseView;
            this.targetCloseBounds = targetCloseBounds != null
                    ? new Rect(targetCloseBounds) : null;
            this.targetCloseTint = targetCloseTint;
        }
    }

    /** Everything owned by one closing expansion, released as a unit. */
    private static final class ClosingTransition {
        final Card card;
        final TerminalSession session;
        Bitmap terminalBitmap;
        final Rect cardStartLocal;
        final Rect terminalTargetLocal;
        ImageView terminalGhost;
        /** Full floating-shadow elevation the expanding snapshot fades out of. */
        float terminalShadowElevation;
        /** Window surface blend from the overview backdrop back to the app background. */
        int backgroundStart;
        int backgroundEnd;
        boolean backgroundTransition;
        Bitmap tabPanelBitmap;
        ImageView tabPanelGhost;
        final List<ClosingTabGhost> tabGhosts = new ArrayList<>();
        ValueAnimator animator;
        boolean started;
        float tabBaseAlpha;
        float closeBaseAlpha;

        ClosingTransition(Card card, Bitmap terminalBitmap,
                         @NonNull Rect cardStartLocal, @NonNull Rect terminalTargetLocal) {
            this.card = card;
            this.session = card.session;
            this.terminalBitmap = terminalBitmap;
            this.cardStartLocal = new Rect(cardStartLocal);
            this.terminalTargetLocal = new Rect(terminalTargetLocal);
        }
    }

    /** Everything owned by one opening flight, released as a unit. */
    private static final class Transition {
        Bitmap terminalBitmap;
        Rect terminalSource;
        Rect terminalSourceLocal;
        ImageView terminalGhost;
        /** Full floating-shadow elevation the flying terminal snapshot grows into. */
        float terminalShadowElevation;
        /** Window surface blend from the app background into the overview backdrop. */
        int backgroundStart;
        int backgroundEnd;
        boolean backgroundTransition;
        Bitmap tabPanelBitmap;
        Rect tabPanelSourceLocal;
        ImageView tabPanelGhost;
        Card activeCard;
        final List<SourceTab> tabSources = new ArrayList<>();
        final List<TabGhost> tabGhosts = new ArrayList<>();
        final List<View> hiddenTabs = new ArrayList<>();
        final List<Card> hiddenPills = new ArrayList<>();
        ValueAnimator animator;
        boolean started;
        int startAttempts;
    }

    /** Rounds a view and its shadow to the tab's effective corner radius. */
    private static final class RoundedOutlineProvider extends ViewOutlineProvider {
        private final float mRadius;

        RoundedOutlineProvider(float radius) {
            mRadius = Math.max(0f, radius);
        }

        @Override
        public void getOutline(@NonNull View view, @NonNull Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mRadius);
        }
    }

    private static final class Card {
        final SessionOverviewCardView root;
        final ImageView preview;
        final SessionOverviewTabTitleView tab;
        final ImageButton close;
        final TerminalSession session;
        Bitmap bitmap;
        GradientDrawable tabBackground;
        int tabBackgroundColor;
        /** Effective radius of the compact tab, reused by the thumbnail and its shadow. */
        float thumbnailCornerRadius;
        /** True while the terminal snapshot owns the shadow during an opening/closing flight. */
        boolean shadowSuppressed;
        String fullTitle = "";
        boolean selected;
        boolean stateValid;
        boolean running;
        int exitStatus;
        int textColor;
        boolean closing;

        Card(SessionOverviewCardView root, ImageView preview, SessionOverviewTabTitleView tab,
             ImageButton close, TerminalSession session) {
            this.root = root;
            this.preview = preview;
            this.tab = tab;
            this.close = close;
            this.session = session;
        }
    }
}
