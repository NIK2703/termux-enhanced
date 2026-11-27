package com.termux.app.terminal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.TermuxAppConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRenderSnapshot;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalPreviewRenderer;
import com.termux.view.TerminalPreviewRenderer.GridRaster;
import com.termux.view.TerminalPreviewRenderer.ViewportGrid;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the all-sessions overview: lifecycle, card construction, preview rendering, selection and
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
    /**
     * Floating add-session button. It is a sibling of the overlay, not a child: the root view
     * already keeps its content area clear of the navigation bar, and this way the button stays
     * out of the overlay's transition bookkeeping. It is visible only while the overview is open.
     */
    @Nullable private final ImageButton mAddButton;
    private final SessionOverviewGridView mGrid;
    private final LayoutInflater mInflater;
    /** Live terminal background used for the window surface, card outline and status-bar icons. */
    private int mOverlayBackgroundColor = Color.BLACK;
    /** Siblings of the overlay that are transparent while it is open. */
    private final List<View> mHiddenLiveViews = new ArrayList<>();
    private final List<Float> mHiddenLiveViewAlphas = new ArrayList<>();
    private final List<Card> mCards = new ArrayList<>();
    @Nullable
    private final SessionOverviewDragController mDragController;
    /** Owns the single card+scroll structural transition for add/remove while the grid is open. */
    @Nullable
    private final SessionOverviewLayoutTransitionController mLayoutTransition;
    private int mTabCornerRadius;
    /** Card stroke shared by resting cards and flying terminal frames. */
    private int mCardStrokeWidth;
    private int mCardStrokeColor;



    @Nullable
    private final FrameLayout mGhostLayer;
    /** Cached alpha of the overview backdrop, refreshed when terminal transparency can change. */
    private float mOverviewBackdropAlpha = 1f;
    /** Clears the decor background under the flying terminal during a transition. */
    @Nullable
    private OverviewBackdropHoleView mBackdropHole;
    /** Reused flight geometry; callees copy what they need to keep. */
    private final Rect mScratchGhostRect = new Rect();
    /** Reused window/content coordinates and target rects; no allocation per animation frame. */
    private final int[] mScratchOrigin = new int[2];
    private final int[] mScratchLocation = new int[2];
    private final Rect mScratchTargetRect = new Rect();
    private final Rect mScratchCloseRect = new Rect();
    private final Rect mScratchContentRect = new Rect();
    private final Rect mScratchWindowRect = new Rect();
    private final Rect mScratchHitRect = new Rect();
    private final Rect mScratchVisibleRect = new Rect();
    /** Scrollbar-thumb geometry of a session whose page may not be bound yet. */
    private final RectF mScratchThumbRect = new RectF();
    /** One terminal ghost is live at a time, so its layout params can be reused every frame. */
    private final FrameLayout.LayoutParams mScratchGhostParams =
            new FrameLayout.LayoutParams(0, 0);
    @Nullable
    private final ScrollView mScrollView;
    @Nullable
    private final View mContentArea;
    @Nullable
    private final View mTabPanel;
    @Nullable
    private final ViewGroup mRootView;
    /**
     * Always-on extra-keys panel whose temporary hide is animated by the activity instead of
     * being made transparent with the rest of the live content.
     */
    @Nullable
    private View mAnimatedExtraKeysPanel;

    /** Non-zero while the overlay is drawn behind the status bar, in pixels. */
    private int mStatusBarTopExtension;
    private boolean mStatusBarExtensionApplied;
    /** Non-zero while the overlay is drawn behind the navigation bar, in pixels. */
    private int mNavigationBarBottomExtension;
    private boolean mNavigationBarExtensionApplied;
    private int mOverlayOriginalTopMargin;
    private int mOverlayOriginalBottomMargin;
    private int mGridOriginalPaddingTop;
    private int mGridOriginalPaddingBottom;
    private boolean mRootOriginalClipChildren;
    private boolean mRootOriginalClipToPadding;

    @Nullable
    private Transition mTransition;
    @Nullable
    private ClosingTransition mClosingTransition;
    @Nullable
    private Card mPendingClosingCard;
    /** Card owned by the close-button fade/scale until its session is actually finished. */
    @Nullable
    private Card mClosingCardAnimation;
    @Nullable
    private ValueAnimator mCardCloseAnimator;

    /**
     * Add-button shrink-and-fade, shared by the session limit and by closing the overview when
     * there is no floating pencil to fly back to.
     */
    @Nullable
    private ValueAnimator mAddButtonHideAnimator;
    private boolean mAddButtonHiding;
    /**
     * The running hide belongs to the overview close. It must finish at GONE even while
     * {@link #mShowing} is still true during the 300 ms exit animation.
     */
    private boolean mAddButtonHideForOverview;

    /**
     * Direct ghost of the tab-strip + that travels between the strip and the overview add
     * button. It lives in the content area rather than the overlay's ghost layer: a close
     * without an expansion removes the overlay immediately, while this flight still has to land
     * on the restored strip.
     */
    @Nullable private ImageView mTabAddButtonFlightGhost;
    @Nullable private ValueAnimator mTabAddButtonFlightAnimator;
    @Nullable private View mTabAddButtonFlightTabButton;
    /** Fixed window-space start of the flight; the destination is read live every frame. */
    private final Rect mTabAddButtonFlightSource = new Rect();
    private final int[] mTabAddButtonFlightLocation = new int[2];
    private final int[] mTabAddButtonFlightParentLocation = new int[2];
    /** Layout size of the ghost, used to turn the interpolated rect into scale factors. */
    private int mTabAddButtonFlightGhostWidth;
    private int mTabAddButtonFlightGhostHeight;
    private boolean mTabAddButtonFlightReturning;
    private boolean mTabAddButtonFlightStartPosted;
    /** Invalidates posted starts from a previous prepared flight. */
    private int mTabAddButtonFlightGeneration;

    // ── Add-session flight from the overview "+" button ──────────────────────
    @Nullable private OverviewAddTabGhostView mAddTabFlightGhost;
    @Nullable private ValueAnimator mAddTabFlightAnimator;
    @Nullable private Card mAddTabFlightCard;
    /** Fixed window-space source of the add button; rebased to ghost-layer space each frame. */
    private final Rect mAddTabFlightSource = new Rect();
    /** Reused ghost-layer-space rects; no per-frame allocation. */
    private final RectF mAddTabFlightSourceLocal = new RectF();
    private final RectF mAddTabFlightTarget = new RectF();
    private final RectF mAddTabFlightCurrent = new RectF();
    private final int[] mAddTabFlightLocation = new int[2];
    private final int[] mAddTabFlightOrigin = new int[2];
    private boolean mAddTabFlightArmed;
    private boolean mAddTabFlightButtonRevealed;
    private int mAddTabFlightSessionCountBefore;
    private float mAddTabFlightSourceRadius;
    /** Exact live button paint captured before the real view is hidden. */
    private int mAddTabFlightButtonFill = Color.TRANSPARENT;
    private int mAddTabFlightButtonStroke = Color.TRANSPARENT;
    private float mAddTabFlightButtonStrokeWidth;
    private int mAddTabFlightButtonShadowColor = Color.TRANSPARENT;
    private int mAddTabFlightButtonElevation;
    private int mAddTabFlightGeneration;
    /** Cancel a pending/running flight if no stable card target is reached. */
    private final Runnable mAddTabFlightTimeout = this::cancelAddTabFlightAfterTimeout;
    /** Guard inside {@link #cancelAddTabFlightAfterTimeout()} against reentrancy. */
    private static final long ADD_TAB_FLIGHT_TIMEOUT_MS = 500L;

    // ── Release over the "+" button → new terminal ──────────────────────────
    /** Hover lift of the add button; the same 160 ms family as a card. */
    @Nullable private ValueAnimator mAddButtonHoverAnimator;
    private boolean mAddButtonHovered;
    /** Owns the source ghost and the new card between release and the closing hand-off. */
    @Nullable private TerminalAddFlight mTerminalAddFlight;
    private final Runnable mTerminalAddTimeout = this::cancelTerminalAddFlightAfterTimeout;
    private static final long TERMINAL_ADD_FLIGHT_TIMEOUT_MS = 500L;
    /**
     * True while the add-button closing holds every surviving card at its pre-add centre.
     *
     * <p>The new card never lands in the grid, so the closing flight treats the grid as a
     * static backdrop. The post-add settle must not slide the old centred rows into the new
     * top-aligned slots under the fading terminal.</p>
     */
    private boolean mTerminalAddGridHeld;
    /** True only for the hide() that finishes a consumed add-button expansion. */
    private boolean mTerminalAddConsumedClose;
    /** Reused capture geometry; the release path is rare enough to keep these off the heap. */
    private final RectF mAddButtonSourceVisual = new RectF();
    private final int[] mAddButtonSourceLocation = new int[2];

    /**
     * Mirrors {@code TermuxActivity.OVERVIEW_FLOATING_CONTROLS_FADE_MS} plus one frame. The
     * opening tab ghost waits the same amount as the pencil morph so both leave together.
     */
    private static final long TAB_ADD_FLIGHT_PANEL_FADE_DELAY_MS = 66L;

    /** How many frames to wait for the grid to lay out before starting with whatever exists. */
    private static final int MAX_START_ATTEMPTS = 20;

    /**
     * An activity recreate (theme/style change) destroys the controller instance. Remember the open
     * overview across that recreate so changing the device theme does not kick the user out of it.
     */
    private static boolean sRestoreOverviewAfterRecreate;
    private static int sRecreateScrollY = -1;
    private static final int MAX_RESTORE_ATTEMPTS = 30;
    private boolean mRestoreAfterRecreatePosted;

    /**
     * While the finger that opened the overview stays down, cards are not recognised until this
     * long has elapsed. A quick flick therefore ends inside the overview instead of switching to
     * whichever card happened to be under the finger.
     */
    private static final long GESTURE_SELECTION_DELAY_MS = 300L;


    /** The card shrinks slightly into its centre while it fades; the reveal starts from here. */
    private static final float CARD_CLOSE_SCALE =
            SessionOverviewTransitionMath.ADD_APPEAR_START_SCALE;

    /** 50% alpha for the compact pill so the terminal stays visible underneath it. */
    private static final int OVERVIEW_TAB_BACKGROUND_ALPHA = 0x80;

    /**
     * The overview backdrop is 20 percentage points more transparent than the live terminal.
     * A 20% terminal transparency therefore leaves the overview 60% opaque; 80% makes it fully
     * transparent. This is a fixed step, not a doubling of the terminal value.
     */
    private static final int OVERVIEW_BACKDROP_TRANSPARENCY_OFFSET = 20;
    /** Short hand-off ramp at each end of a flight, so the cut-out tint meets the decor smoothly. */
    private static final float OVERVIEW_BACKDROP_HANDOFF = 0.12f;

    /** Same tick as the overview-opening gesture, defined once in {@link TabPanelLayout}. */
    private static final int HOVER_FEEDBACK = TabPanelLayout.OVERVIEW_GESTURE_FEEDBACK;

    private boolean mShowing;
    /**
     * True from the moment {@link #show()} takes ownership of the window colour until the opening
     * transition owns it. During this window the decor must keep the normal terminal background;
     * an overview callback that repaints the more transparent backdrop here would make the first
     * animation frame start from the wrong alpha.
     */
    private boolean mOpeningBackgroundPending;
    /**
     * True after {@link #show()} has asked the IME to hide and before the terminal view has
     * reported the resulting full-size layout. The overview does not exist yet for the user: the
     * live terminal stays visible and is allowed to grow under the disappearing keyboard. The
     * opening frame is created only after this phase ends, so the flight never starts from the
     * compressed view and then jumps when the keyboard is gone.
     */
    private boolean mOpeningPreparationPending;
    /** Terminal content viewport captured before the IME was dismissed. */
    private int mOpeningTargetWidth;
    private int mOpeningTargetHeight;
    /** Terminal view used for the one-shot full-size layout hand-off. */
    @Nullable
    private TerminalView mOpeningPreparationTerminal;
    private final View.OnLayoutChangeListener mOpeningPreparationLayoutListener =
            (view, left, top, right, bottom,
             oldLeft, oldTop, oldRight, oldBottom) -> tryFinishOpeningPreparation();
    /** True between activity stop and resume while the overview stays open in the background. */
    private boolean mActivityStopped;
    /** Cached per-card interval; 0 disables the throttle. Refreshed on show/resume. */
    private long mPreviewIntervalMs;
    /** Serializes preview and flight-frame raster work off the main thread. */
    private final SessionOverviewRasterizer mRasterizer = new SessionOverviewRasterizer();
    private int mCardWidth;
    private int mCardHeight;
    /** Natural-metric raster shared by every preview in this overview. */
    @Nullable
    private GridRaster mGridRaster;
    /** Card currently under the finger of the gesture that opened the overview. */
    @Nullable
    private Card mHoverCard;
    /** True once the opening gesture is old enough to hit-test and select cards. */
    private boolean mGestureSelectionArmed;
    private final Runnable mArmGestureSelectionRunnable = this::armGestureSelection;
    /** Last hover point, in the window's coordinate space, reused after every auto-scroll step. */
    private float mHoverWindowX;
    private float mHoverWindowY;
    /** Finger release that arrived while the opening was still waiting for the full terminal. */
    private boolean mPendingGestureRelease;
    private float mPendingGestureReleaseX;
    private float mPendingGestureReleaseY;
    /** Edge auto-scroll of the grid while the drop finger is parked near its top/bottom. */
    private boolean mAutoScrollPosted;
    /** Timestamp of the previous scroll frame, for vsync-independent step sizing. */
    private long mAutoScrollLastTimeMs;
    /** Edge band and peak step resolved once per controller instead of once per frame. */
    private final int mAutoScrollBandPx;
    private final int mAutoScrollMaxStepPx;
    private final Runnable mAutoScrollRunnable = this::autoScrollStep;
    /** Reused [first, end) buffer for the visible child range; no allocation per render pass. */
    private final int[] mVisibleChildRange = new int[2];

    /** Last overview scroll offset left by the user; -1 until the first close. */
    private int mRememberedScrollY = -1;
    /** True while an opening should start near {@link #mRememberedScrollY}. */
    private boolean mRestoreScrollOnOpen;
    /**
     * Card appended while the opening restore owned the scroll. The reveal is retried as soon as
     * that restore finishes, so a fast add cannot leave the new row only partly on screen.
     */
    @Nullable
    private Card mPendingRevealCard;
    private int mPendingRevealAttempts;
    @Nullable
    private ViewTreeObserver mScrollRestoreObserver;
    @Nullable
    private ViewTreeObserver.OnGlobalLayoutListener mScrollRestoreLayoutListener;
    /** Latest configuration dispatched to the activity; authoritative during a rotation. */
    @Nullable
    private Configuration mLastConfiguration;
    /** Last viewport sampled by the post-rotation refresh; -1 means no sample yet. */

    @Nullable
    private View mPreviousFocus;
    private boolean mHadIme;
    /** True while a resume has put the window's IME into the overview's hidden mode. */
    private boolean mSoftKeyboardHiddenForResume;

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
        mAddButton = activity.findViewById(R.id.session_overview_add_button);
        mGrid = activity.findViewById(R.id.session_overview_grid);
        mGhostLayer = activity.findViewById(R.id.session_overview_ghost_layer);
        mScrollView = activity.findViewById(R.id.session_overview_scroll);
        mContentArea = activity.findViewById(R.id.activity_termux_root_relative_layout);
        mTabPanel = activity.findViewById(R.id.session_tabs_container);
        final View rootView = activity.findViewById(R.id.activity_termux_root_view);
        mRootView = rootView instanceof ViewGroup ? (ViewGroup) rootView : null;
        mInflater = LayoutInflater.from(activity);
        reloadDimensions();
        updateOverviewBackdropAlpha();
        mAutoScrollBandPx = TermuxActivityUtils.dpToPx(
                activity, SessionOverviewReorderMath.AUTO_SCROLL_BAND_DP);
        mAutoScrollMaxStepPx = TermuxActivityUtils.dpToPx(
                activity, SessionOverviewReorderMath.AUTO_SCROLL_MAX_STEP_DP);

        mDragController = mGrid != null
                ? new SessionOverviewDragController(mGrid,
                mScrollView instanceof SessionOverviewScrollView
                        ? (SessionOverviewScrollView) mScrollView : null,
                new SessionOverviewDragController.Host() {
                    @Override
                    public boolean canStartDrag(@NonNull SessionOverviewCardView card) {
                        if (!mShowing || mTransition != null || mClosingTransition != null
                                || mPendingClosingCard != null
                                || mClosingCardAnimation != null) {
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

        mLayoutTransition = mGrid != null
                ? new SessionOverviewLayoutTransitionController(mGrid,
                        mScrollView instanceof SessionOverviewScrollView
                                ? (SessionOverviewScrollView) mScrollView : null)
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
            if (mScrollView instanceof SessionOverviewScrollView) {
                final SessionOverviewScrollView overviewScrollView =
                        (SessionOverviewScrollView) mScrollView;
                final SessionOverviewLayoutTransitionController layoutTransition = mLayoutTransition;
                overviewScrollView.setOnScrollListener(scrollY -> onOverviewScrolled());
                overviewScrollView.setOnContentClampListener((before, after) -> {
                    if (layoutTransition != null) {
                        layoutTransition.onContentClamped(before, after);
                    }
                });
                overviewScrollView.setOnTouchDownListener(() -> {
                    // The gesture takes ownership of the viewport: land the structural plan,
                    // stop a reveal settle, and cancel a reveal that was waiting for layout.
                    if (layoutTransition != null) layoutTransition.finishNow();
                    overviewScrollView.finishContentTransition();
                    mPendingRevealCard = null;
                    mPendingRevealAttempts = 0;
                });
            }
        }
        if (mGrid != null) {
            mGrid.setOnGridLayoutListener(layout -> {
                if (mDragController != null && mDragController.isActive()) {
                    mDragController.cancelForModelChange();
                }
                mCardWidth = layout.cardWidth;
                mCardHeight = layout.cardHeight;
                if (!mShowing) return;
                scrollToActiveCard();
                // One render request per traversal: the layout listener below owns dirty marking.
            });
            // onGridLayoutListener above fires only when the slot dimensions or grid dimensions
            // change. A deletion can move a dirty card into the viewport without a scroll event,
            // so every real layout pass also rechecks bitmap size and requests one render pass.
            mGrid.addOnLayoutChangeListener((view, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> {
                if (!mShowing || mActivityStopped) return;
                // The slot size is known only after the first layout pass. Recompute the
                // natural-metric raster instead of keeping the aspect fallback forever.
                updateGridAspect();
                markResizedPreviewsDirty();
                requestVisibleDirtyPreviews();
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
                    if (show()) armGestureSelectionAfterDelay();
                }

                @Override
                public void onOverviewGestureHover(float windowX, float windowY) {
                    updateGestureHover(windowX, windowY);
                }

                @Override
                public void onOverviewGestureCancelled() {
                    if (mOpeningPreparationPending) cancelOpeningPreparation(true);
                    disarmGestureSelection();
                    stopAutoScroll();
                    resetGestureHover();
                }

                @Override
                public void onOverviewGestureReleased(float windowX, float windowY) {
                    selectTargetUnderFinger(windowX, windowY);
                }
            });
        }
    }

    public boolean isShowing() {
        return mShowing;
    }

    /** Called by the activity before a configuration-change recreate destroys this controller. */
    public static void saveForActivityRecreate(@NonNull SessionOverviewController controller) {
        sRestoreOverviewAfterRecreate = true;
        sRecreateScrollY = controller.mRememberedScrollY;
    }

    /**
     * Reopen the overview in the recreated activity. The service may still be connecting on the
     * first resume, so retry on the UI thread for a short while instead of losing the request.
     */
    public void restoreAfterActivityRecreate() {
        if (!sRestoreOverviewAfterRecreate || mRestoreAfterRecreatePosted) return;
        mRestoreAfterRecreatePosted = true;
        final int savedScrollY = sRecreateScrollY;

        final View decor = mActivity.getWindow() != null
                ? mActivity.getWindow().getDecorView() : null;
        if (decor == null) {
            mRestoreAfterRecreatePosted = false;
            return;
        }

        final Runnable attempt = new Runnable() {
            private int mAttempts;

            @Override
            public void run() {
                if (mShowing) {
                    sRestoreOverviewAfterRecreate = false;
                    sRecreateScrollY = -1;
                    mRestoreAfterRecreatePosted = false;
                    return;
                }
                if (mRememberedScrollY < 0) mRememberedScrollY = savedScrollY;
                if (show()) {
                    sRestoreOverviewAfterRecreate = false;
                    sRecreateScrollY = -1;
                    mRestoreAfterRecreatePosted = false;
                    return;
                }
                if (++mAttempts < MAX_RESTORE_ATTEMPTS) {
                    decor.postDelayed(this, 50L);
                } else {
                    sRestoreOverviewAfterRecreate = false;
                    sRecreateScrollY = -1;
                    mRestoreAfterRecreatePosted = false;
                }
            }
        };
        decor.post(attempt);
    }

    /** The overlay view, used by the activity's touch tracking so a tap cannot close the panel. */
    @Nullable
    public View getView() {
        return mOverlay;
    }

    /**
     * Show or hide the overview's add-session button. The activity wires its tap and swipe-up
     * gestures once; this only mirrors the overlay's visibility.
     */
    private void setAddButtonVisible(boolean visible) {
        if (mAddButton == null) return;
        if (!visible) cancelAddButtonHideAnimation();
        final int wanted = visible ? View.VISIBLE : View.GONE;
        if (mAddButton.getVisibility() != wanted) mAddButton.setVisibility(wanted);
    }

    /** True once the service cannot accept another session. */
    private boolean isAtSessionLimit() {
        final TermuxService service = mActivity.getTermuxService();
        return service != null
                && service.getTermuxSessionsSize() >= TermuxAppConstants.MAX_SESSIONS;
    }

    /** Drop an add-button hide and put the button back at its full visual values. */
    private void cancelAddButtonHideAnimation() {
        final ValueAnimator animator = mAddButtonHideAnimator;
        mAddButtonHideAnimator = null;
        mAddButtonHiding = false;
        mAddButtonHideForOverview = false;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
        if (mAddButton != null) {
            OverviewAppearanceAnimation.reset(mAddButton);
            mAddButton.setEnabled(true);
        }
    }

    /**
     * Keep the add button's availability in sync with the session count.
     *
     * <p>Reaching the limit uses the same shrink-and-fade as a card's X button; dropping back
     * below it uses that animation's exact reverse. Structural callbacks and non-structural
     * session-list updates both come through here, so a tab closed by a swipe, by the X button or
     * by the service all restore the button the same way.</p>
     */
    private void updateAddButtonForSessionLimit() {
        if (!mShowing || mAddButton == null) return;
        if (isAtSessionLimit()) {
            // A session can appear from another path while the finger is over the button. Drop
            // the hover and stop the edge loop before the button shrinks away.
            resetAddButtonHover();
            stopAutoScroll();
            if (mAddButtonHiding) return;
            if (mAddButton.getVisibility() == View.VISIBLE) {
                hideAddButtonWithAppearance(false);
            } else {
                setAddButtonVisible(false);
            }
            return;
        }
        if (mAddButtonHiding || mAddButton.getVisibility() != View.VISIBLE) {
            showAddButtonWithAppearance();
        }
    }

    /**
     * Shrink the add button into itself and fade it out. The same motion is used when the
     * session limit is reached and when the overview closes without a pencil to return to.
     *
     * @param forOverview true when the hide belongs to the overview close, so it must finish at
     *                    GONE even though {@link #mShowing} is still true during the exit.
     */
    private void hideAddButtonWithAppearance(boolean forOverview) {
        if (mAddButton == null) return;
        if (mAddButtonHiding) {
            // A limit-driven hide may already be running. Mark it so it ends at GONE even if
            // the overview is still marked as showing while its exit animation plays.
            if (forOverview) mAddButtonHideForOverview = true;
            return;
        }
        if (mAddButton.getVisibility() != View.VISIBLE) return;
        mActivity.cancelOverviewAddButtonMorph();
        if (mAddButton.getVisibility() != View.VISIBLE) return;
        mAddButton.animate().cancel();
        mAddButton.setEnabled(false);
        OverviewAppearanceAnimation.pivotToCenter(mAddButton);
        final ValueAnimator animator = OverviewAppearanceAnimation.hideAnimator(mAddButton);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAddButtonHideAnimator != animation) return;
                final boolean keepHidden = mAddButtonHideForOverview
                        || isAtSessionLimit() || !mShowing;
                mAddButtonHideAnimator = null;
                mAddButtonHiding = false;
                mAddButtonHideForOverview = false;
                if (keepHidden) {
                    mAddButton.setVisibility(View.GONE);
                } else {
                    // A tab closed while the button was fading out; bring it back instead of
                    // leaving a half-faded, untouchable plus on screen.
                    showAddButtonWithAppearance();
                }
            }
        });
        mAddButtonHideAnimator = animator;
        mAddButtonHiding = true;
        mAddButtonHideForOverview = forOverview;
        animator.start();
    }

    /**
     * Grow the add button from the hide animation's endpoint. Used when a tab closes below the
     * limit and when the overview opens with no floating pencil to morph from.
     */
    private void showAddButtonWithAppearance() {
        if (mAddButton == null) return;
        cancelAddButtonHideAnimation();
        mAddButton.setVisibility(View.VISIBLE);
        mAddButton.setEnabled(true);
        mAddButton.animate().cancel();
        OverviewAppearanceAnimation.arm(mAddButton);
        showAddButtonWithAppearanceWhenLaidOut(mAddButton, 20);
    }

    private void showAddButtonWithAppearanceWhenLaidOut(@NonNull ImageButton addButton,
                                                        int attemptsLeft) {
        if (mAddButton != addButton || addButton.getVisibility() != View.VISIBLE
                || mAddButtonHiding) {
            return;
        }
        if (addButton.getWidth() > 0 && addButton.getHeight() > 0 && addButton.isLaidOut()) {
            OverviewAppearanceAnimation.pivotToCenter(addButton);
            OverviewAppearanceAnimation.animator(addButton).start();
        } else if (attemptsLeft > 0) {
            addButton.postOnAnimation(() -> showAddButtonWithAppearanceWhenLaidOut(
                    addButton, attemptsLeft - 1));
        } else {
            OverviewAppearanceAnimation.reset(addButton);
        }
    }

    // ── Add-session flight from the overview "+" button ─────────────────────

    /** True while an add-session flight is armed or running. */
    private boolean isAddTabFlightActive() {
        return mAddTabFlightArmed || mAddTabFlightCard != null || mAddTabFlightAnimator != null;
    }

    /**
     * Capture the add-button rect, hide the real button and install a ghost before a session is
     * created. Returns false when the normal append reveal should be used.
     */
    public boolean prepareAddTabFlight() {
        if (!mShowing || mAddButton == null || mGhostLayer == null) return false;
        if (mAddButton.getVisibility() != View.VISIBLE || !mAddButton.isShown()
                || !mAddButton.isEnabled() || !mAddButton.isLaidOut()
                || mAddButton.getWidth() <= 0 || mAddButton.getHeight() <= 0) {
            return false;
        }
        if (mActivity.isOverviewAddButtonReturnMorphActive() || isAtSessionLimit()) return false;
        if (isAddTabFlightActive()) cancelAddTabFlight(false, false);

        final ImageButton button = mAddButton;
        button.animate().cancel();
        button.setPressed(false);
        button.setSelected(false);

        windowRectOf(button, mAddTabFlightLocation, mAddTabFlightSource);
        if (mAddTabFlightSource.isEmpty()) return false;
        mAddTabFlightSourceRadius =
                SessionOverviewAddTabMath.sourceRadius(
                        mAddTabFlightSource.width(), mAddTabFlightSource.height());
        // Copy the live button paint, not a theme approximation. The fill and stroke carry the
        // floating-control alpha (50%), and the shadow is the same 4dp/60% pair the button uses.
        mAddTabFlightButtonFill = mActivity.getFloatingButtonFill();
        mAddTabFlightButtonStroke = mActivity.getFloatingButtonStroke();
        final Resources res = mActivity.getResources();
        mAddTabFlightButtonStrokeWidth = Math.round(
                res.getDimension(R.dimen.terminal_text_input_stroke));
        mAddTabFlightButtonElevation = FloatingShadow.elevationPx(res);
        mAddTabFlightButtonShadowColor = FloatingShadow.color(
                res, mActivity.isCachedSchemeLight(), R.fraction.floating_shadow_alpha);

        final TermuxService service = mActivity.getTermuxService();
        mAddTabFlightSessionCountBefore =
                service != null ? service.getTermuxSessionsSize() : 0;

        final OverviewAddTabGhostView ghost = new OverviewAddTabGhostView(mActivity);
        ghost.setStroke(mAddTabFlightButtonStrokeWidth, mAddTabFlightButtonStroke);
        ghost.setFlightShadow(mAddTabFlightButtonElevation, mAddTabFlightButtonShadowColor);
        final int padding = button.getPaddingLeft() + button.getPaddingRight();
        final float iconSize = Math.max(1f,
                Math.min(mAddTabFlightSource.width(), mAddTabFlightSource.height()) - padding);
        final Drawable plus = cloneDrawable(button.getDrawable(), button.getImageTintList());
        if (plus != null) plus.setColorFilter(button.getColorFilter());
        ghost.setPlusIcon(plus, iconSize);
        mGhostLayer.addView(ghost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mAddTabFlightGhost = ghost;

        button.setEnabled(false);
        // INVISIBLE, not GONE: keep measured bounds for the existing closing pencil morph.
        button.setVisibility(View.INVISIBLE);

        mGhostLayer.getLocationInWindow(mAddTabFlightOrigin);
        ghost.setFlightFrame(
                mAddTabFlightSource.left - mAddTabFlightOrigin[0],
                mAddTabFlightSource.top - mAddTabFlightOrigin[1],
                mAddTabFlightSource.right - mAddTabFlightOrigin[0],
                mAddTabFlightSource.bottom - mAddTabFlightOrigin[1],
                mAddTabFlightSourceRadius, mAddTabFlightButtonFill, 1f, 0f);

        mAddTabFlightArmed = true;
        mAddTabFlightButtonRevealed = false;
        mAddTabFlightGeneration++;
        return true;
    }

    /**
     * Called after the synchronous session-creation call. If no single card was registered, drop
     * the ghost and restore the button; if a card was registered, let the flight start.
     */
    public void finishAddTabRequest() {
        if (!mAddTabFlightArmed && mAddTabFlightCard == null
                && mAddTabFlightAnimator == null) {
            return;
        }
        if (mAddTabFlightCard != null || mAddTabFlightAnimator != null) {
            postAddTabFlightTimeout();
            return;
        }
        final TermuxService service = mActivity.getTermuxService();
        final int after = service != null ? service.getTermuxSessionsSize() : 0;
        if (mAddTabFlightArmed && after > mAddTabFlightSessionCountBefore) {
            // Async callback case: wait for onSessionsChanged(), with a hard fallback timer.
            postAddTabFlightTimeout();
            return;
        }
        cancelAddTabFlight(true, true);
    }

    private void postAddTabFlightTimeout() {
        if (mOverlay == null) {
            cancelAddTabFlight(true, true);
            return;
        }
        removeAddTabFlightTimeout();
        mOverlay.postDelayed(mAddTabFlightTimeout, ADD_TAB_FLIGHT_TIMEOUT_MS);
    }

    private void removeAddTabFlightTimeout() {
        if (mOverlay != null) mOverlay.removeCallbacks(mAddTabFlightTimeout);
    }

    private void cancelAddTabFlightAfterTimeout() {
        if (!isAddTabFlightActive()) return;
        cancelAddTabFlight(true, true);
    }

    /** Keep a flight card hidden; surviving cards still animate from old centres. */
    private static void prepareFlightCard(@NonNull Card card) {
        card.appearing = true;
        card.root.setAlpha(0f);
        card.root.setScaleX(1f);
        card.root.setScaleY(1f);
        card.root.setEnabled(false);
        card.root.setTranslationX(0f);
        card.root.setTranslationY(0f);
    }

    /** Start the 300 ms morph once the real card is laid out and rendered. */
    private void beginAddTabFlight(@NonNull Card card) {
        if (mTerminalAddFlight != null && !mTerminalAddFlight.handedOff) {
            beginTerminalAddFlight(card);
            return;
        }
        if (mAddTabFlightCard != card || mAddTabFlightAnimator != null
                || mAddTabFlightGhost == null || mGhostLayer == null) {
            return;
        }
        if (!card.root.isAttachedToWindow() || card.root.getWidth() <= 0
                || card.root.getHeight() <= 0 || !mCards.contains(card)) {
            cancelAddTabFlight(true, true);
            return;
        }
        startAddTabFlightNow(card);
    }

    private void startAddTabFlightNow(@NonNull Card card) {
        final OverviewAddTabGhostView ghost = mAddTabFlightGhost;
        if (ghost == null || mAddTabFlightAnimator != null || mAddTabFlightCard != card) return;

        removeAddTabFlightTimeout();
        // The ghost owns the outline. Build an ordinary card copy without its border and draw
        // one continuous stroke that starts as the button's hairline and ends as the card's
        // outline. The preview keeps the same emulator raster the real card already owns.
        ghost.setCardContent(createCardGhostContent(card));

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        mAddTabFlightAnimator = animator;
        animator.setDuration(SessionOverviewAddTabMath.FLIGHT_DURATION_MS);
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(animation ->
                applyAddTabFlightProgress((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAddTabFlightAnimator != animation) return;
                finishAddTabFlight();
            }
        });
        applyAddTabFlightProgress(0f);
        animator.start();
    }

    /**
     * Direct card copy for the add morph. This is the same layout and binding as a real card,
     * with interaction and the card's own chrome removed because the morph container draws the
     * moving outline and shadow.
     */
    @Nullable
    private View createCardGhostContent(@NonNull Card card) {
        if (card.bitmap == null && card.session.getEmulator() != null) {
            renderCardNow(card, previewTextSize(), previewTypeface());
        }
        final SessionOverviewCardView root =
                (SessionOverviewCardView) mInflater.inflate(
                        R.layout.item_session_overview_card, mGhostLayer, false);
        final ImageView preview = root.findViewById(R.id.session_overview_preview);
        final SessionOverviewTabTitleView tab =
                root.findViewById(R.id.session_overview_tab);
        final ImageButton close = root.findViewById(R.id.session_overview_close);
        final Card ghostCard = new Card(root, preview, tab, close, card.session);
        applyTabMetrics(ghostCard);
        applyCardAppearance(ghostCard);
        bindTab(ghostCard, card.session == mActivity.getCurrentSession());

        preview.setScaleType(card.preview.getScaleType());
        preview.setImageBitmap(card.bitmap);
        preview.setAlpha(card.preview.getAlpha());
        // The morph container owns the rounded clip, border and elevation. Disable the card's
        // copies so the moving frame does not paint a second outline or shadow.
        root.setBorderSuppressed(true);
        root.setHoverShadow(0, Color.TRANSPARENT);
        root.setTranslationZ(0f);
        root.setOutlineProvider(null);
        root.setClipToOutline(false);
        preview.setOutlineProvider(null);
        preview.setClipToOutline(false);
        root.setClickable(false);
        root.setFocusable(false);
        root.setEnabled(false);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tab.setClickable(false);
        tab.setFocusable(false);
        tab.setEnabled(false);
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        close.setClickable(false);
        close.setFocusable(false);
        close.setEnabled(false);
        close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.setEnabled(false);

        final int width = card.root.getWidth() > 0 ? card.root.getWidth() : mCardWidth;
        final int height = card.root.getHeight() > 0 ? card.root.getHeight() : mCardHeight;
        if (width <= 0 || height <= 0) return null;
        root.setLayoutParams(new FrameLayout.LayoutParams(width, height));
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        return root;
    }

    private void applyAddTabFlightProgress(float progress) {
        final OverviewAddTabGhostView ghost = mAddTabFlightGhost;
        final Card card = mAddTabFlightCard;
        if (ghost == null || card == null || mGhostLayer == null) return;

        final float p = SessionOverviewTransitionMath.clamp01(progress);
        mGhostLayer.getLocationInWindow(mAddTabFlightOrigin);
        final int originX = mAddTabFlightOrigin[0];
        final int originY = mAddTabFlightOrigin[1];

        mAddTabFlightSourceLocal.set(
                mAddTabFlightSource.left - originX,
                mAddTabFlightSource.top - originY,
                mAddTabFlightSource.right - originX,
                mAddTabFlightSource.bottom - originY);

        if (!readAddTabFlightTarget(card, originX, originY)) return;

        SessionOverviewAddTabMath.lerpRect(
                mAddTabFlightCurrent, mAddTabFlightSourceLocal, mAddTabFlightTarget, p);

        final float radius = SessionOverviewAddTabMath.cornerRadius(
                p, mAddTabFlightSourceRadius, card.thumbnailCornerRadius);
        final float content = SessionOverviewAddTabMath.contentAlpha(p);
        final float plus = SessionOverviewAddTabMath.plusAlpha(p);
        // Preserve the button fill's own 50% alpha; the card copy supplies the opacity that
        // grows to fully opaque. Scaling the whole alpha to 255 here made the first frame much
        // more solid than the real button.
        final int fillColor = FloatingShadow.withAlphaFraction(
                mAddTabFlightButtonFill, 1f - content);
        final float strokeWidth = SessionOverviewAddTabMath.strokeWidth(
                p, mAddTabFlightButtonStrokeWidth, mCardStrokeWidth);
        final int strokeColor = SessionOverviewTransitionMath.blendColor(
                mAddTabFlightButtonStroke, mCardStrokeColor, p);

        ghost.setStroke(strokeWidth, strokeColor);
        ghost.setShadowAlpha(SessionOverviewAddTabMath.shadowAlpha(p));
        ghost.setFlightFrame(
                mAddTabFlightCurrent.left, mAddTabFlightCurrent.top,
                mAddTabFlightCurrent.right, mAddTabFlightCurrent.bottom,
                radius, fillColor, plus, content);

        if (SessionOverviewAddTabMath.shouldRevealAddButton(p)) {
            revealAddButtonDuringFlight();
        }
    }

    /**
     * Bring the real add button back at the halfway point without touching the flight animator.
     * It is disabled until the card lands so a second tap cannot cancel the morph that is still
     * running.
     */
    private void revealAddButtonDuringFlight() {
        if (mAddTabFlightButtonRevealed) return;
        mAddTabFlightButtonRevealed = true;
        if (!mShowing || mAddButton == null) return;
        if (isAtSessionLimit()) {
            setAddButtonVisible(false);
            return;
        }
        showAddButtonWithAppearance();
        mAddButton.setEnabled(false);
    }

    /** Live target rect in ghost-layer coordinates; keeps the last valid target on a miss. */
    private boolean readAddTabFlightTarget(@NonNull Card card, int originX, int originY) {
        if (!card.root.isAttachedToWindow() || card.root.getWidth() <= 0
                || card.root.getHeight() <= 0) {
            return !mAddTabFlightTarget.isEmpty();
        }
        card.root.getLocationInWindow(mAddTabFlightLocation);
        mAddTabFlightTarget.set(
                mAddTabFlightLocation[0] - originX,
                mAddTabFlightLocation[1] - originY,
                mAddTabFlightLocation[0] + card.root.getWidth() - originX,
                mAddTabFlightLocation[1] + card.root.getHeight() - originY);
        return !mAddTabFlightTarget.isEmpty();
    }

    private void finishAddTabFlight() {
        final Card card = mAddTabFlightCard;
        final boolean buttonRevealed = mAddTabFlightButtonRevealed;
        mAddTabFlightButtonRevealed = false;
        removeAddTabFlightTimeout();
        mAddTabFlightAnimator = null;
        mAddTabFlightCard = null;
        mAddTabFlightArmed = false;
        removeAddTabFlightGhost();
        mAddTabFlightGeneration++;

        if (card != null && mCards.contains(card)) {
            finishCardAppearance(card);
            card.root.setEnabled(true);
            card.root.setTranslationX(0f);
            card.root.setTranslationY(0f);
        }
        applyOverviewBackgroundAfterReveal();
        schedulePreviewRender();
        if (isAtSessionLimit()) {
            setAddButtonVisible(false);
        } else if (buttonRevealed) {
            // The button already grew in at the halfway point; do not replay that reveal.
            if (mAddButton != null) {
                if (mAddButton.getVisibility() != View.VISIBLE) {
                    showAddButtonWithAppearance();
                } else {
                    mAddButton.setEnabled(true);
                }
            }
        } else {
            showAddButtonWithAppearance();
        }
    }

    /** Drop a pending/running add-session flight without leaving a ghost or hidden button. */
    private void cancelAddTabFlight(boolean restoreButton, boolean animateRestore) {
        final boolean buttonRevealed = mAddTabFlightButtonRevealed;
        mAddTabFlightButtonRevealed = false;
        final ValueAnimator animator = mAddTabFlightAnimator;
        mAddTabFlightAnimator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
        removeAddTabFlightTimeout();

        final Card card = mAddTabFlightCard;
        mAddTabFlightCard = null;
        mAddTabFlightArmed = false;
        removeAddTabFlightGhost();
        mAddTabFlightGeneration++;

        if (card != null) {
            finishCardAppearance(card);
            card.root.setEnabled(true);
            card.root.setTranslationX(0f);
            card.root.setTranslationY(0f);
        }
        applyOverviewBackgroundAfterReveal();

        if (!restoreButton || !mShowing || mAddButton == null) return;
        if (isAtSessionLimit()) {
            setAddButtonVisible(false);
            return;
        }
        if (!animateRestore) {
            // The closing pencil morph needs a stable, fully visible button, even if the grow-in
            // had already started at the halfway point.
            cancelAddButtonHideAnimation();
            mAddButton.setVisibility(View.VISIBLE);
            mAddButton.setEnabled(true);
            OverviewAppearanceAnimation.reset(mAddButton);
            return;
        }
        if (buttonRevealed) {
            // Leave the already-running grow-in alone; only release the tap guard.
            if (mAddButton.getVisibility() != View.VISIBLE) {
                showAddButtonWithAppearance();
            } else {
                mAddButton.setEnabled(true);
            }
            return;
        }
        showAddButtonWithAppearance();
    }

    private void removeAddTabFlightGhost() {
        final OverviewAddTabGhostView ghost = mAddTabFlightGhost;
        mAddTabFlightGhost = null;
        if (ghost == null) return;
        ghost.clearCardContent();
        if (ghost.getParent() instanceof ViewGroup) {
            ((ViewGroup) ghost.getParent()).removeView(ghost);
        }
    }

    // ── Release over the add button → new terminal ──────────────────────────

    /** True while the source ghost is waiting for the new card or the closing hand-off. */
    private boolean isTerminalAddFlightActive() {
        return mTerminalAddFlight != null;
    }

    /** Cancel whichever add-card flight owns {@code flightCard}. */
    private void cancelCardFlight(boolean restoreButton, boolean animateRestore) {
        if (mTerminalAddFlight != null) {
            cancelTerminalAddFlight(restoreButton, animateRestore);
        } else {
            cancelAddTabFlight(restoreButton, animateRestore);
        }
    }

    /**
     * Create a session from a release over the add button and install the exact source ghost.
     * The new card stays hidden; {@link #beginTerminalAddFlight(Card)} hands it to the closing
     * expansion once it is laid out.
     */
    private void selectAddButtonUnderFinger() {
        if (!isAddButtonHoverTarget() || isAtSessionLimit()
                || mTerminalAddFlight != null || isAddTabFlightActive()) {
            resetAddButtonHover();
            return;
        }
        final TermuxTerminalSessionActivityClient client =
                mActivity.getTermuxTerminalSessionClient();
        if (client == null) {
            resetAddButtonHover();
            return;
        }
        final AddButtonSource source = captureAddButtonSource();
        if (source == null) {
            resetAddButtonHover();
            return;
        }
        final OverviewAddTabGhostView ghost = installAddButtonSourceGhost(source);
        if (ghost == null) {
            resetAddButtonHover();
            return;
        }
        final TermuxService service = mActivity.getTermuxService();
        final int before = service != null ? service.getTermuxSessionsSize() : 0;
        mTerminalAddFlight = new TerminalAddFlight(source, ghost, before);

        // The real button is now owned by the flight. Reset its transform first so a fallback
        // restore never leaves it mid-hover, then keep it measured but invisible.
        resetAddButtonHover();
        if (mAddButton != null) {
            mAddButton.setEnabled(false);
            mAddButton.setVisibility(View.INVISIBLE);
        }
        try {
            client.addNewSession(false, null);
        } catch (RuntimeException ignored) {
            cancelTerminalAddFlight(true, true);
            return;
        }
        finishTerminalAddRequest();
    }

    /** Capture the real button's visual state, including a hover that has not finished yet. */
    @Nullable
    private AddButtonSource captureAddButtonSource() {
        if (mAddButton == null) return null;
        final int[] origin = contentOrigin(mScratchOrigin);
        if (origin == null) return null;
        mAddButton.getLocationInWindow(mAddButtonSourceLocation);
        final float scaleX = mAddButton.getScaleX();
        final float scaleY = mAddButton.getScaleY();
        SessionOverviewAddButtonTerminalMath.visualRect(mAddButtonSourceVisual,
                mAddButtonSourceLocation[0] - origin[0],
                mAddButtonSourceLocation[1] - origin[1],
                mAddButton.getWidth(), mAddButton.getHeight(),
                mAddButton.getPivotX(), mAddButton.getPivotY(), scaleX, scaleY);
        if (mAddButtonSourceVisual.isEmpty()) return null;

        final Resources res = mActivity.getResources();
        final float scale = (Math.abs(scaleX) + Math.abs(scaleY)) / 2f;
        final float strokeWidth = Math.max(0f,
                res.getDimension(R.dimen.terminal_text_input_stroke) * scale);
        final int padding = mAddButton.getPaddingLeft() + mAddButton.getPaddingRight();
        final float iconSize = SessionOverviewAddButtonTerminalMath.scaledContentSize(
                mAddButton.getWidth(), mAddButton.getHeight(), padding, scale);
        final Drawable plus = cloneDrawable(
                mAddButton.getDrawable(), mAddButton.getImageTintList());
        if (plus != null) plus.setColorFilter(mAddButton.getColorFilter());

        final int elevation = Math.max(0, Math.round(mAddButton.getZ()));
        final int fallbackElevation = FloatingShadow.elevationPx(res);
        final int shadowColor;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final int ambient = mAddButton.getOutlineAmbientShadowColor();
            shadowColor = Color.alpha(ambient) > 0 ? ambient
                    : FloatingShadow.overviewCardShadowColor(
                            res, mActivity.isCachedSchemeLight());
        } else {
            shadowColor = FloatingShadow.color(res, mActivity.isCachedSchemeLight(),
                    R.fraction.floating_shadow_alpha);
        }
        final Rect visual = new Rect(
                Math.round(mAddButtonSourceVisual.left),
                Math.round(mAddButtonSourceVisual.top),
                Math.round(mAddButtonSourceVisual.right),
                Math.round(mAddButtonSourceVisual.bottom));
        if (visual.isEmpty()) return null;
        return new AddButtonSource(visual, scaleX, scaleY,
                mAddButton.getAlpha(), mAddButton.getImageAlpha() / 255f,
                SessionOverviewAddButtonTerminalMath.sourceRadius(
                        visual.width(), visual.height()),
                strokeWidth,
                mActivity.getFloatingButtonFill(), mActivity.getFloatingButtonStroke(),
                plus, iconSize,
                elevation > 0 ? elevation : fallbackElevation, shadowColor);
    }

    /** Install a full-overlay copy of the real button above the terminal ghost. */
    @Nullable
    private OverviewAddTabGhostView installAddButtonSourceGhost(@NonNull AddButtonSource source) {
        final OverviewAddTabGhostView ghost = new OverviewAddTabGhostView(mActivity);
        ghost.setStroke(source.strokeWidth, source.strokeColor);
        ghost.setFlightShadow(source.elevationPx, source.shadowColor);
        ghost.setPlusIcon(source.plusIcon, source.plusIconSize);
        ghost.setFlightFrame(source.visualRect.left, source.visualRect.top,
                source.visualRect.right, source.visualRect.bottom,
                source.radius, source.fillColor, source.imageAlpha, 0f);
        ghost.setAlpha(source.buttonAlpha);
        mOverlay.addView(ghost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ghost.bringToFront();
        return ghost;
    }

    /** Called after the synchronous session-creation call. */
    private void finishTerminalAddRequest() {
        final TerminalAddFlight flight = mTerminalAddFlight;
        if (flight == null || flight.handedOff) return;
        if (mAddTabFlightCard != null) {
            postTerminalAddTimeout();
            return;
        }
        final TermuxService service = mActivity.getTermuxService();
        final int after = service != null ? service.getTermuxSessionsSize() : 0;
        if (after > flight.sessionCountBefore) {
            // The callback has not appended the card yet; wait for onSessionsChanged().
            postTerminalAddTimeout();
            return;
        }
        cancelTerminalAddFlight(true, true);
    }

    private void postTerminalAddTimeout() {
        removeTerminalAddTimeout();
        mOverlay.postDelayed(mTerminalAddTimeout, TERMINAL_ADD_FLIGHT_TIMEOUT_MS);
    }

    private void removeTerminalAddTimeout() {
        mOverlay.removeCallbacks(mTerminalAddTimeout);
    }

    private void cancelTerminalAddFlightAfterTimeout() {
        final TerminalAddFlight flight = mTerminalAddFlight;
        if (flight == null || flight.handedOff) return;
        cancelTerminalAddFlight(true, true);
    }

    /**
     * Hand the laid-out new card to the normal closing expansion. The source ghost stays in the
     * overlay, above the terminal ghost, until the first quarter of the flight has elapsed.
     */
    private void beginTerminalAddFlight(@NonNull Card card) {
        final TerminalAddFlight flight = mTerminalAddFlight;
        if (flight == null || flight.handedOff || mAddTabFlightCard != card) return;
        if (!card.root.isAttachedToWindow() || card.root.getWidth() <= 0
                || card.root.getHeight() <= 0 || !mCards.contains(card)) {
            cancelTerminalAddFlight(true, true);
            return;
        }
        removeTerminalAddTimeout();
        flight.card = card;
        mAddTabFlightCard = null;
        mAddTabFlightArmed = false;
        mAddTabFlightButtonRevealed = false;

        final LiftStart liftStart = new LiftStart(
                flight.source.visualRect, flight.source.scaleX, flight.source.buttonAlpha,
                flight.source.elevationPx, flight.source.shadowColor, flight.source);
        flight.handedOff = true;
        if (!selectSession(card.session, liftStart)) {
            flight.handedOff = false;
            cancelTerminalAddFlight(true, true);
        }
    }

    /**
     * Drop the source flight and, unless the closing expansion now owns the button, restore it.
     *
     * @param restoreButton false when the window is stopping or the closing expansion is finishing
     * @param animateRestore true for the normal grow-in, false for a stable button under a morph
     */
    private void cancelTerminalAddFlight(boolean restoreButton, boolean animateRestore) {
        removeTerminalAddTimeout();
        final TerminalAddFlight flight = mTerminalAddFlight;
        mTerminalAddFlight = null;
        if (flight != null) {
            if (flight.overlayGhost.getParent() instanceof ViewGroup) {
                ((ViewGroup) flight.overlayGhost.getParent()).removeView(flight.overlayGhost);
            }
            if (flight.card != null && mCards.contains(flight.card)) {
                finishCardAppearance(flight.card);
                flight.card.root.setEnabled(true);
                flight.card.root.setTranslationX(0f);
                flight.card.root.setTranslationY(0f);
            }
            if (mAddTabFlightCard == flight.card) mAddTabFlightCard = null;
            mAddTabFlightArmed = false;
            mAddTabFlightButtonRevealed = false;
            applyOverviewBackgroundAfterReveal();
            schedulePreviewRender();
        }
        // The closing transition owns the held grid once it starts; its cleanup resets the
        // cards. Before that point an aborted flight must put the survivors back into the
        // post-add layout, otherwise the grid would stay offset and the next touch would use
        // stale visual positions.
        if (mClosingTransition == null) {
            releaseTerminalAddGridHold(animateRestore && mShowing);
        }
        if (!restoreButton || !mShowing || mAddButton == null) return;
        if (isAtSessionLimit()) {
            resetAddButtonHover();
            setAddButtonVisible(false);
            return;
        }
        if (!animateRestore) {
            cancelAddButtonHideAnimation();
            resetAddButtonHover();
            mAddButton.setVisibility(View.VISIBLE);
            mAddButton.setEnabled(true);
            OverviewAppearanceAnimation.reset(mAddButton);
            return;
        }
        resetAddButtonHover();
        showAddButtonWithAppearance();
    }

    /**
     * Release the pre-add card centres held for the add-button closing.
     *
     * <p>The grid's layout has already reflowed to the post-add slot geometry; only the card
     * translations keep the old snapshot on screen. The closing cleanup resets those
     * translations while the grid is invisible. If the flight is aborted while the overview
     * is still visible, settle them into the new layout so the grid stays interactive.</p>
     */
    private void releaseTerminalAddGridHold(boolean settle) {
        if (!mTerminalAddGridHeld) return;
        mTerminalAddGridHeld = false;
        if (!settle) {
            for (Card card : mCards) {
                card.root.animate().cancel();
                card.root.setTranslationX(0f);
                card.root.setTranslationY(0f);
            }
            // The closing cleanup is the last owner of the held snapshot. Any structural offset
            // that was part of that snapshot must be dropped with the translations.
            if (mScrollView instanceof SessionOverviewScrollView) {
                ((SessionOverviewScrollView) mScrollView).finishContentTransition();
            }
            return;
        }

        // The abort leaves the grid at the post-add layout with the old snapshot held by card
        // translations. Hand those translations to the same structural coordinator so the
        // abort settles with the normal 160 ms card-only path instead of a second animator.
        if (mLayoutTransition != null && !mCards.isEmpty()) {
            final int count = mCards.size();
            final List<View> views = new ArrayList<>(count);
            final float[] fromX = new float[count];
            final float[] fromY = new float[count];
            final float[] targetX = new float[count];
            final float[] targetY = new float[count];
            for (int i = 0; i < count; i++) {
                final View root = mCards.get(i).root;
                root.animate().cancel();
                views.add(root);
                fromX[i] = root.getLeft() + root.getWidth() / 2f + root.getTranslationX();
                fromY[i] = root.getTop() + root.getHeight() / 2f + root.getTranslationY();
                targetX[i] = root.getLeft() + root.getWidth() / 2f;
                targetY[i] = root.getTop() + root.getHeight() / 2f;
            }
            mLayoutTransition.seed(views, fromX, fromY, targetX, targetY,
                    mScrollView != null ? mScrollView.getScrollY() : 0, false);
            return;
        }

        for (Card card : mCards) {
            card.root.animate().cancel();
            card.root.setTranslationX(0f);
            card.root.setTranslationY(0f);
        }
    }

    @Nullable
    private static Drawable cloneDrawable(@Nullable Drawable source, @Nullable ColorStateList tint) {
        if (source == null) return null;
        Drawable result = source;
        if (source.getConstantState() != null) {
            result = source.getConstantState().newDrawable();
        }
        result = result.mutate();
        result.setTintList(tint);
        return result;
    }

    /**
     * Direct replacement for a captured tab-strip + bitmap: copy the button's drawable,
     * background, tint, padding and alpha into a plain {@link ImageView}.
     */
    @Nullable
    private ImageView createDirectTabPlusGhost(@NonNull ImageView source) {
        final ImageView ghost = new ImageView(mActivity);
        ghost.setScaleType(source.getScaleType());
        ghost.setImageDrawable(cloneDrawable(source.getDrawable(), source.getImageTintList()));
        ghost.setImageAlpha(source.getImageAlpha());
        final ColorFilter filter = source.getColorFilter();
        if (filter != null) ghost.setColorFilter(filter);
        final Drawable background = source.getBackground();
        if (background != null) {
            final Drawable backgroundClone = cloneDrawable(background, null);
            if (backgroundClone != null) {
                backgroundClone.setState(source.getDrawableState());
                ghost.setBackground(backgroundClone);
            }
        }
        ghost.setPadding(source.getPaddingLeft(), source.getPaddingTop(),
                source.getPaddingRight(), source.getPaddingBottom());
        ghost.setAlpha(source.getAlpha());
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ghost.setClickable(false);
        ghost.setFocusable(false);
        return ghost;
    }

    /**
     * Prepare the tab-strip + for the overview transition. The button is replaced by a ghost in
     * the content area immediately, so the strip copy can leave its slot empty without the
     * real button blinking out first.
     *
     * @param returning true when the flight goes from the overview add button back to the strip
     * @return true when a ghost was installed and a flight can start
     */
    private boolean prepareTabAddButtonFlight(boolean returning) {
        cancelTabAddButtonFlight();
        if (!mActivity.isTabPanelAtBottom() || mAddButton == null
                || !(mContentArea instanceof ViewGroup)) {
            return false;
        }

        final View tabButton = mActivity.findViewById(R.id.new_session_tab_button);
        if (tabButton == null || tabButton.getVisibility() != View.VISIBLE
                || tabButton.getWidth() <= 0 || tabButton.getHeight() <= 0
                || !tabButton.isShown()
                || !tabButton.getGlobalVisibleRect(mScratchVisibleRect)
                || mScratchVisibleRect.isEmpty()) {
            return false;
        }
        // The strip disables clipChildren, so its own visible rect is not enough: a tab scrolled
        // out of the horizontal viewport can still report a non-empty global rect. Intersect
        // with the content area so only a button that really lands on screen is animated.
        if (!mContentArea.getGlobalVisibleRect(mScratchContentRect)
                || !mScratchVisibleRect.intersect(mScratchContentRect)
                || mScratchVisibleRect.isEmpty()) {
            return false;
        }
        if (!returning && isAtSessionLimit()) return false;
        if (returning && (mAddButton.getVisibility() != View.VISIBLE
                || mAddButton.getWidth() <= 0 || mAddButton.getHeight() <= 0
                || !mAddButton.isLaidOut())) {
            return false;
        }

        final ImageView sourceButton = tabButton instanceof ImageView
                ? (ImageView) tabButton : null;
        if (sourceButton == null) return false;
        final Rect source = returning ? windowRectOf(mAddButton) : windowRectOf(tabButton);
        if (source.isEmpty()) return false;

        final ViewGroup parent = (ViewGroup) mContentArea;
        final int[] parentLocation = mTabAddButtonFlightParentLocation;
        parent.getLocationInWindow(parentLocation);
        // Lay the ghost out at the copied button's size, not at the flight rect. The flight
        // scales the whole ghost to that rect, so the plus icon keeps the same proportion to
        // its background as in the strip. Laying it out at the destination size instead made
        // the returning icon shrink with the view and land visibly smaller than the real one.
        final int ghostWidth = Math.max(1, sourceButton.getWidth());
        final int ghostHeight = Math.max(1, sourceButton.getHeight());
        final ImageView ghost = createDirectTabPlusGhost(sourceButton);
        if (ghost == null) return false;
        ghost.setPivotX(0f);
        ghost.setPivotY(0f);
        ghost.setScaleX(source.width() / (float) ghostWidth);
        ghost.setScaleY(source.height() / (float) ghostHeight);
        // Above the overlay and bottom strip, but below the floating add button and the
        // pencil ghost, so the two pluses hand off instead of stacking.
        ghost.setZ(Math.max(0f, mAddButton.getZ() - 1f));
        ghost.setAlpha(returning ? 0f : 1f);
        final RelativeLayout.LayoutParams params =
                new RelativeLayout.LayoutParams(ghostWidth, ghostHeight);
        // Insert before the real add button, which carries the floating-control elevation:
        // the ghost then paints above the overlay and strip but below the add button/pencil.
        final int addIndex = parent.indexOfChild(mAddButton);
        if (addIndex >= 0) {
            parent.addView(ghost, addIndex, params);
        } else {
            parent.addView(ghost, params);
        }
        ghost.setX(source.left - parentLocation[0]);
        ghost.setY(source.top - parentLocation[1]);

        mTabAddButtonFlightGhost = ghost;
        mTabAddButtonFlightTabButton = tabButton;
        mTabAddButtonFlightSource.set(source);
        mTabAddButtonFlightGhostWidth = ghostWidth;
        mTabAddButtonFlightGhostHeight = ghostHeight;
        mTabAddButtonFlightReturning = returning;
        // The strip copies are built after this point, so their add
        // slot must stay empty until the ghost lands.
        tabButton.setVisibility(View.INVISIBLE);
        return true;
    }

    /** Start the opening tab-strip + alongside the pencil-to-plus morph. */
    private void startTabAddButtonOpeningFlight(boolean morphStarted) {
        if (mTabAddButtonFlightGhost == null || mTabAddButtonFlightReturning) return;
        final View inputPanel = mActivity.findViewById(
                R.id.terminal_toolbar_text_input_container);
        final boolean waitForPanelFade = morphStarted && inputPanel != null
                && inputPanel.getVisibility() == View.VISIBLE;
        startTabAddButtonFlightWhenReady(true, waitForPanelFade, 20);
    }

    /** Start the returning tab-strip + alongside the plus-to-pencil flight. */
    private void startTabAddButtonClosingFlight() {
        if (mTabAddButtonFlightGhost == null || !mTabAddButtonFlightReturning) return;
        startTabAddButtonFlightWhenReady(false, false, 20);
    }

    /**
     * Wait until both ends have a real layout, then start the flight. The opening start mirrors
     * the activity's panel-fade delay so the tab ghost and the pencil leave on the same frame.
     */
    private void startTabAddButtonFlightWhenReady(boolean opening, boolean waitForPanelFade,
                                                  int attemptsLeft) {
        if (mTabAddButtonFlightGhost == null || mTabAddButtonFlightAnimator != null
                || mTabAddButtonFlightStartPosted
                || opening == mTabAddButtonFlightReturning) {
            return;
        }
        final ImageButton addButton = mAddButton;
        if (addButton == null) {
            cancelTabAddButtonFlight();
            return;
        }

        final int generation = mTabAddButtonFlightGeneration;
        mTabAddButtonFlightStartPosted = true;
        final Runnable attempt = () -> attemptTabAddButtonFlightStart(
                opening, attemptsLeft, generation);
        if (opening && waitForPanelFade) {
            addButton.postDelayed(attempt, TAB_ADD_FLIGHT_PANEL_FADE_DELAY_MS);
        } else {
            addButton.postOnAnimation(attempt);
        }
    }

    private void attemptTabAddButtonFlightStart(boolean opening, int attemptsLeft,
                                                int generation) {
        if (generation != mTabAddButtonFlightGeneration) return;
        mTabAddButtonFlightStartPosted = false;
        if (mTabAddButtonFlightGhost == null || mTabAddButtonFlightAnimator != null
                || opening == mTabAddButtonFlightReturning) {
            return;
        }

        if (opening) {
            // The strip can move while the input panel fades. Keep the source current until
            // the flight actually starts instead of leaving the ghost at a stale slot.
            final View tabButton = mTabAddButtonFlightTabButton;
            if (tabButton != null && tabButton.isAttachedToWindow()) {
                final Rect live = windowRectOf(tabButton);
                if (!live.isEmpty()) {
                    mTabAddButtonFlightSource.set(live);
                    applyTabAddButtonFlightProgress(0f);
                }
            }
        }

        final View target = mTabAddButtonFlightReturning
                ? mTabAddButtonFlightTabButton : mAddButton;
        final boolean ready = target != null && target.isAttachedToWindow()
                && target.getWidth() > 0 && target.getHeight() > 0 && target.isLaidOut();
        if (ready) {
            startTabAddButtonFlightNow();
            return;
        }
        if (attemptsLeft <= 0) {
            cancelTabAddButtonFlight();
            return;
        }
        final ImageButton addButton = mAddButton;
        if (addButton == null) {
            cancelTabAddButtonFlight();
            return;
        }
        mTabAddButtonFlightStartPosted = true;
        addButton.postOnAnimation(() -> attemptTabAddButtonFlightStart(
                opening, attemptsLeft - 1, generation));
    }

    private void startTabAddButtonFlightNow() {
        if (mTabAddButtonFlightGhost == null || mTabAddButtonFlightAnimator != null
                || mAddButton == null || mTabAddButtonFlightTabButton == null) {
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        mTabAddButtonFlightAnimator = animator;
        animator.setDuration(SessionOverviewTransitionMath.DURATION_MS);
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(animation ->
                applyTabAddButtonFlightProgress((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mTabAddButtonFlightAnimator != animation) return;
                finishTabAddButtonFlight();
            }
        });
        applyTabAddButtonFlightProgress(0f);
        animator.start();
    }

    /**
     * Move the ghost from its fixed start rect to the live destination rect. The opening
     * destination follows the add button (and therefore the pencil shell) every frame; the
     * closing destination follows the strip, which can move while the extra-keys panel returns.
     */
    private void applyTabAddButtonFlightProgress(float progress) {
        final ImageView ghost = mTabAddButtonFlightGhost;
        final ImageButton addButton = mAddButton;
        if (ghost == null || addButton == null) return;
        // A session-list refresh can restore the real strip button while the ghost is still on
        // its way back. Keep the slot empty until the ghost lands, or the plus would be drawn
        // twice for the last frames of the flight.
        if (mTabAddButtonFlightReturning && mTabAddButtonFlightTabButton != null
                && mTabAddButtonFlightTabButton.getVisibility() == View.VISIBLE) {
            mTabAddButtonFlightTabButton.setVisibility(View.INVISIBLE);
        }
        final View target = mTabAddButtonFlightReturning
                ? mTabAddButtonFlightTabButton : addButton;
        final ViewParent rawParent = ghost.getParent();
        if (target == null || !(rawParent instanceof ViewGroup)
                || target.getWidth() <= 0 || target.getHeight() <= 0
                || !target.isAttachedToWindow()
                || mTabAddButtonFlightSource.isEmpty()
                || mTabAddButtonFlightGhostWidth <= 0
                || mTabAddButtonFlightGhostHeight <= 0) {
            return;
        }

        final ViewGroup parent = (ViewGroup) rawParent;
        parent.getLocationInWindow(mTabAddButtonFlightParentLocation);
        target.getLocationInWindow(mTabAddButtonFlightLocation);
        final Rect source = mTabAddButtonFlightSource;
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        final float sourceLeft =
                source.left - mTabAddButtonFlightParentLocation[0];
        final float sourceTop =
                source.top - mTabAddButtonFlightParentLocation[1];
        final float targetLeft =
                mTabAddButtonFlightLocation[0] - mTabAddButtonFlightParentLocation[0];
        final float targetTop =
                mTabAddButtonFlightLocation[1] - mTabAddButtonFlightParentLocation[1];
        final float left = sourceLeft + (targetLeft - sourceLeft) * p;
        final float top = sourceTop + (targetTop - sourceTop) * p;
        final float width = source.width() + (target.getWidth() - source.width()) * p;
        final float height = source.height() + (target.getHeight() - source.height()) * p;

        ghost.setX(left);
        ghost.setY(top);
        ghost.setScaleX(width / mTabAddButtonFlightGhostWidth);
        ghost.setScaleY(height / mTabAddButtonFlightGhostHeight);
        ghost.setAlpha(mTabAddButtonFlightReturning ? p : 1f - p);
    }

    private void finishTabAddButtonFlight() {
        final ImageView ghost = mTabAddButtonFlightGhost;
        final View tabButton = mTabAddButtonFlightTabButton;

        mTabAddButtonFlightAnimator = null;
        mTabAddButtonFlightGhost = null;
        mTabAddButtonFlightTabButton = null;
        mTabAddButtonFlightReturning = false;
        mTabAddButtonFlightStartPosted = false;
        mTabAddButtonFlightGeneration++;
        mTabAddButtonFlightSource.setEmpty();
        mTabAddButtonFlightGhostWidth = 0;
        mTabAddButtonFlightGhostHeight = 0;

        if (ghost != null) {
            ghost.setImageDrawable(null);
            ghost.setBackground(null);
            if (ghost.getParent() instanceof ViewGroup) {
                ((ViewGroup) ghost.getParent()).removeView(ghost);
            }
        }
        restoreTabAddButtonVisibility(tabButton);
    }

    /** Drop the flight and put the real tab button back at its normal visibility. */
    private void cancelTabAddButtonFlight() {
        final ValueAnimator animator = mTabAddButtonFlightAnimator;
        mTabAddButtonFlightAnimator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
        finishTabAddButtonFlight();
    }

    private void restoreTabAddButtonVisibility(@Nullable View tabButton) {
        if (tabButton == null) return;
        tabButton.setVisibility(isAtSessionLimit() ? View.GONE : View.VISIBLE);
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
     * Paint the window surface behind the overview at rest. With the wallpaper visible the
     * backdrop transparency is the terminal's plus 20 percentage points; each card still supplies
     * the terminal background at the configured alpha. During a flight the animator owns this
     * colour, so a
     * change here only updates its destination.
     */
    private void applyOverviewBackground() {
        updateOverviewBackdropAlpha();
        final int target = terminalBackgroundColor();
        mOverlayBackgroundColor = target;
        final int backdrop = overviewBackdropColor(target);
        final Transition opening = mTransition;
        if (opening != null && opening.backgroundTransition) {
            // The opening flight owns the decor colour. Update its destination, but let the
            // animator paint the next frame instead of jumping to it now.
            opening.backgroundEnd = backdrop;
        } else if (mClosingTransition == null && !mOpeningBackgroundPending) {
            // show() is still assembling the first frame: leave the normal terminal background
            // in place until the opening transition takes over. Otherwise a palette callback in
            // this window would paint the more transparent backdrop before frame one.
            mActivity.applyOverviewWindowBackground(backdrop);
        }
        // The navigation bar belongs to the overview window too, whatever the status-bar
        // extension state: make it transparent on every entry, not only when the status bar is
        // extended. hide() restores the scheme colours through applySchemeColors().
        applyOverviewNavigationBarAppearance(target);
        if (mStatusBarExtensionApplied) {
            applyOverviewStatusBarAppearance(target);
        }
    }

    /** Keep the navigation bar transparent and its icons matched to the overview backdrop. */
    private void applyOverviewNavigationBarAppearance(int background) {
        final Window window = mActivity.getWindow();
        if (window == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        final View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ColorSchemeUtils.isColorLight(background)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    /**
     * Make every sibling of the overlay transparent while the overview is open. The overlay
     * itself is transparent, so without this the live terminal, strip and floating controls
     * would show through the grid and the window background.
     *
     * <p>Alpha, not visibility, is used deliberately: the strip must stay {@link View#isShown()}
     * and laid out for the closing tab flight, and the active terminal must still be drawable
     * for the closing frame.</p>
     *
     * <p>The always-on extra-keys panel is the one exception: its hide is a slide-and-fade
     * owned by the activity, so it is left out of this all-at-once pass. Its own animation
     * ends at alpha zero, and {@link #restoreLiveContentAfterOverview()} must not snap it back
     * to full strength before the reverse slide has run.</p>
     */
    /** Floating controls whose 50 ms fade is owned by {@link TermuxActivity}. */
    private static boolean isOverviewFadingControl(@NonNull View view) {
        // R.id fields are not compile-time constants in this build, so no switch here.
        final int id = view.getId();
        return id == R.id.terminal_toolbar_text_input_container
                || id == R.id.input_panel_actions
                || id == R.id.toggle_text_input_button;
    }

    private void hideLiveContentForOverview() {
        if (!(mContentArea instanceof ViewGroup)) return;
        final ViewGroup content = (ViewGroup) mContentArea;
        for (int i = 0; i < content.getChildCount(); i++) {
            final View child = content.getChildAt(i);
            if (child == mOverlay || child == mAddButton || child == mAnimatedExtraKeysPanel
                    || child == mTabAddButtonFlightGhost
                    || isOverviewFadingControl(child) || mHiddenLiveViews.contains(child)) {
                continue;
            }
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
        if (mShowing || mOpeningPreparationPending
                || mOverlay == null || mGrid == null) return false;
        mActivityStopped = false;
        refreshPreviewThrottleInterval();
        TermuxService service = mActivity.getTermuxService();
        if (service == null || service.getTermuxSessionsSize() == 0) return false;

        mPreviousFocus = mActivity.getCurrentFocus();
        mHadIme = mActivity.computeImeVisibility();

        if (mHadIme && beginOpeningPreparation()) return true;
        return showNow(service);
    }

    /**
     * Hide the IME before the source pixels are captured.
     *
     * <p>While the keyboard is up, an {@code adjustResize} window gives the terminal a shorter
     * view. The overview grid, however, is sized for the window without the keyboard. Capturing
     * the flight at that point animates a compressed terminal into a full-size card, and the live
     * terminal then jumps when the keyboard is gone. This phase keeps the live terminal visible,
     * asks the IME to hide, and waits for the real layout that follows. Only then does
     * {@link #showNow(TermuxService)} create the opening frame.</p>
     *
     * <p>The wait is event-driven: a layout listener on the active terminal and the activity's
     * IME callback both call {@link #tryFinishOpeningPreparation()}. There is no timer and no
     * arbitrary delay.</p>
     *
     * @return true when the opening is pending and the terminal is not full size yet
     */
    private boolean beginOpeningPreparation() {
        final TerminalView terminal = mActivity.getActiveTerminalView();
        final int[] target = mActivity.getTerminalContentViewportForOverview();
        if (terminal == null || !terminal.isAttachedToWindow()
                || target[0] <= 0 || target[1] <= 0) {
            return false;
        }

        mOpeningPreparationPending = true;
        mOpeningTargetWidth = target[0];
        mOpeningTargetHeight = target[1];
        mOpeningPreparationTerminal = terminal;
        terminal.addOnLayoutChangeListener(mOpeningPreparationLayoutListener);

        // Mark the overview as showing before dismissing popups or hiding the keyboard. Those
        // calls produce focus/IME churn, and the activity must treat that churn as part of this
        // transition instead of as a user closing the input panel. The live terminal remains
        // visible during the wait; only the frame is deferred.
        mOpeningBackgroundPending = true;
        mShowing = true;
        cancelAddButtonHideAnimation();
        mActivity.dismissAutoCompleteSuggestions();
        mActivity.dismissMessageHistoryPopup();
        final View focused = mActivity.getCurrentFocus();
        KeyboardUtils.hideSoftKeyboard(mActivity, focused != null ? focused : mOverlay);
        // The keyboard may already have finished hiding while this method was running. Check the
        // settled state once instead of waiting for a layout callback that has already fired.
        // This call may even complete the opening immediately; the request was accepted either
        // way, and the caller must arm gesture selection for the grid it is about to show.
        tryFinishOpeningPreparation();
        return true;
    }

    /**
     * Continue the opening as soon as the IME is gone and the active terminal has the full
     * viewport captured before the keyboard was dismissed.
     */
    private void tryFinishOpeningPreparation() {
        if (!mOpeningPreparationPending) return;
        if (mActivity.computeImeVisibility()) return;

        final TerminalView terminal = mOpeningPreparationTerminal;
        if (terminal == null || !terminal.isAttachedToWindow() || !terminal.isShown()
                || terminal.getWidth() <= 0 || terminal.getHeight() <= 0) {
            return;
        }
        // On API 30+ the target includes the IME inset, so this is the exact full terminal.
        // On older APIs the target is the compressed pager rectangle; the view has already
        // grown past it when the frame detector reports the IME hidden, so "at least" is the
        // correct comparison for both cases.
        if (terminal.getWidth() < mOpeningTargetWidth - 1
                || terminal.getHeight() < mOpeningTargetHeight - 1) {
            return;
        }

        // Make the active emulator match the view before the frame is drawn. This is
        // synchronous; the renderer reads the same emulator the view uses.
        final TerminalSession session = mActivity.getCurrentSession();
        final TerminalEmulator emulator = session != null ? session.getEmulator() : null;
        final ViewportGrid grid = TerminalPreviewRenderer.viewportGrid(
                terminal.getWidth(), terminal.getHeight(), previewTextSize(), previewTypeface());
        if (grid != null && emulator != null && !grid.matches(emulator)) {
            session.updateSize(grid.columns, grid.rows, grid.cellWidthPx, grid.cellHeightPx);
        }

        finishOpeningPreparation();
    }

    private void finishOpeningPreparation() {
        if (!mOpeningPreparationPending) return;
        mOpeningPreparationPending = false;
        releaseOpeningPreparationTerminal();
        final TermuxService service = mActivity.getTermuxService();
        if (service == null || service.getTermuxSessionsSize() == 0 || mActivityStopped) {
            cancelOpeningPreparation(true);
            return;
        }
        showNow(service);
    }

    private void releaseOpeningPreparationTerminal() {
        if (mOpeningPreparationTerminal != null) {
            mOpeningPreparationTerminal.removeOnLayoutChangeListener(
                    mOpeningPreparationLayoutListener);
            mOpeningPreparationTerminal = null;
        }
    }

    /**
     * Activity callback for the IME hand-off that runs while {@link #show()} waits for the
     * terminal's full-size layout. The layout listener covers the size change; this covers the
     * case where the terminal was already laid out and only the inset state changed.
     */
    public void onImeVisibilityChangedForOverview(boolean imeVisible) {
        if (!imeVisible) tryFinishOpeningPreparation();
    }

    /**
     * Abort an opening that has not reached {@link #showNow(TermuxService)} yet. Nothing of the
     * overview has been shown or moved in this phase, so there is no grid state to unwind; only
     * the focus/IME hand-off has to be reversed.
     */
    private void cancelOpeningPreparation(boolean restoreFocus) {
        if (!mOpeningPreparationPending && !mShowing) return;
        mOpeningPreparationPending = false;
        releaseOpeningPreparationTerminal();
        mOpeningTargetWidth = 0;
        mOpeningTargetHeight = 0;
        disarmGestureSelection();
        mPendingGestureRelease = false;
        resetGestureHover();
        mShowing = false;
        mOpeningBackgroundPending = false;
        mActivity.applySchemeColors();
        if (restoreFocus) {
            restorePreviousFocus();
        } else {
            mPreviousFocus = null;
            mHadIme = false;
        }
    }

    private boolean showNow(@NonNull TermuxService service) {
        Trace.beginSection("overview.open.show");
        // Create the live terminal frame and capture the visible tab rectangles after the
        // keyboard has been dismissed: those are the pixels the user will see when the overview
        // starts, and they are the only honest source for the flight. The grid targets are read
        // later, every frame.
        final Transition transition = prepareTransition(service);

        // Mark the overview as showing before dismissing popups or hiding the keyboard. Those
        // calls produce focus/IME churn, and the activity must treat that churn as part of this
        // transition instead of as a user closing the input panel. Until the opening transition
        // owns the decor, keep the normal terminal background in place: callbacks in this window
        // must not repaint the more transparent overview backdrop under the first frame.
        mOpeningBackgroundPending = true;
        mShowing = true;
        // A quick close/reopen can still have the previous shrink-and-fade in flight. Drop it
        // before the new opening decides between the pencil morph and the plain reveal.
        cancelAddButtonHideAnimation();
        mActivity.dismissAutoCompleteSuggestions();
        mActivity.dismissMessageHistoryPopup();
        // Capture the always-on extra-keys panel before the input panel hide changes which
        // bottom strip is visible. It gets a slide-and-fade of its own; every other live view
        // is still hidden in one frame.
        mAnimatedExtraKeysPanel = mActivity.prepareExtraKeysPanelForOverview();
        final View focused = mActivity.getCurrentFocus();
        // The input panel floats above the terminal with its own elevation, so it would punch
        // through the overview. Hide it for the duration of the overview, but save its
        // text/caret and leave the per-session visibility/focus memory intact: this is the same
        // preserve-and-restore contract a session switch uses.
        // Place the add button at its post-panel-hide rest position, then capture the floating
        // pencil: the add button will start from the pencil and replace its glyph while the
        // overview opens.
        mActivity.prepareOverviewAddButtonForOverview();
        mActivity.prepareOverviewAddButtonMorph();
        // Replace the visible strip + with a ghost before the live strip is faded out. Its slot
        // is then empty in the strip copies and only the flying copy paints.
        prepareTabAddButtonFlight(false);
        mActivity.hideTextInputPanelForOverview();
        KeyboardUtils.hideSoftKeyboard(mActivity,
                focused != null ? focused : mOverlay);

        // The card is the terminal, not the whole content area. Match the terminal grid before
        // the first measure so the opening flight already has exact thumbnail targets. The
        // window surface fades from the live terminal alpha to the +20-point overview alpha;
        // the decor cut-out under the flying terminal keeps those two layers from stacking.
        final int backgroundStart = mActivity.getWindowBackgroundColor();
        applyOverviewBackground();
        if (transition != null) {
            // Restore the live tint for the first frame. The hole installed with the ghost
            // removes it exactly under the terminal frame, which already carries the same
            // alpha itself.
            mActivity.applyWindowBackgroundColor(backgroundStart);
            transition.backgroundStart = backgroundStart;
            transition.backgroundEnd = overviewBackdropColor(mOverlayBackgroundColor);
            transition.backgroundTransition = true;
        }
        updateGridAspect();
        buildCards(service.getTermuxSessions());
        if (mCards.isEmpty()) {
            mOpeningBackgroundPending = false;
            mShowing = false;
            mPendingGestureRelease = false;
            disarmGestureSelection();
            releaseCards();
            discardTransition(transition);
            mActivity.cancelExtraKeysPanelOverview();
            mAnimatedExtraKeysPanel = null;
            mActivity.cancelOverviewAddButtonMorph();
            cancelTabAddButtonFlight();
            mActivity.restoreTextInputPanelAfterOverview();
            mActivity.updateToggleTextInputButtonIcon();
            setAddButtonVisible(false);
            // applyOverviewBackground() changed the window colour before the card list turned
            // out to be empty; put the normal scheme background back.
            mActivity.applySchemeColors();
            // The keyboard was already hidden above; restore the state we interrupted.
            restorePreviousFocus();
            Trace.endSection();
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

        // Let the overlay paint behind the transparent system bars. The grid gets the insets as
        // padding, so its resting layout is unchanged while scrolled rows can pass under both
        // bars instead of disappearing behind them.
        extendOverlayIntoSystemBars();
        if (mStatusBarExtensionApplied) applyOverviewStatusBarAppearance(mOverlayBackgroundColor);
        mOverlay.setVisibility(View.VISIBLE);
        if (isAtSessionLimit()) {
            // Nothing can be added, so there is no pencil to take over and no plus to reveal.
            mActivity.cancelOverviewAddButtonMorph();
            cancelTabAddButtonFlight();
            setAddButtonVisible(false);
        } else {
            setAddButtonVisible(true);
            final boolean morphStarted = mActivity.startOverviewAddButtonMorph(mAddButton);
            if (!morphStarted) {
                // No pencil to take over, usually because the input-panel toggle is disabled.
                // Reveal the resting plus with the same grow-and-fade used when a tab closes
                // below the session limit.
                showAddButtonWithAppearance();
            }
            // With a pencil, this waits out the same panel fade as the morph; without one it
            // leaves with the add button's ordinary reveal.
            startTabAddButtonOpeningFlight(morphStarted);
        }
        // The overlay is on screen, so the panel is no longer the user's live bottom strip.
        // Let it sink and fade behind the grid instead of vanishing on the first frame.
        mActivity.startExtraKeysPanelHideForOverview();
        mOverlay.requestFocus();
        mGrid.requestLayout();
        schedulePreviewRender();
        // The grid layout listener usually does this during the first traversal. Posting it as
        // well covers a reopening with the same session count, where the grid's cached geometry
        // means the listener may not fire again.
        mGrid.post(this::scrollToActiveCard);
        // The opening gesture can outlive the keyboard hand-off. If the finger was already old
        // enough for recognition when it lifted, select now that the cards have bounds. If it is
        // still down, apply the hover point that was collected while the grid did not exist.
        if (mPendingGestureRelease) {
            final float windowX = mPendingGestureReleaseX;
            final float windowY = mPendingGestureReleaseY;
            mPendingGestureRelease = false;
            mGrid.post(() -> {
                if (mShowing && !mOpeningPreparationPending) {
                    selectTargetUnderFinger(windowX, windowY);
                }
            });
        } else if (mGestureSelectionArmed) {
            mGrid.post(this::applyHoverTarget);
        }
        if (transition != null) {
            mTransition = transition;
            installTransitionVisuals(transition);
            mGrid.post(mStartTransitionRunnable);
            // Hard fallback for a grid that never reports a usable layout (for example a window
            // that is still being attached): never leave the flight stuck before its first frame.
            // Owned by the transition so cleanup can remove it before it ever fires.
            transition.startFallback = () -> {
                if (mTransition == transition && mShowing && !transition.started) {
                    startTransition(transition);
                }
            };
            mOverlay.postDelayed(transition.startFallback, 400L);
        }
        // The transition now owns the decor (or there is none to animate). From here callbacks
        // may repaint the overview backdrop; before this point they would have poisoned the
        // first frame with its higher transparency.
        mOpeningBackgroundPending = false;
        Trace.endSection();
        return true;
    }

    /** Close the overview and restore the focus target that was active when it opened. */
    public void hide() {
        hide(true);
    }

    /** Close without restoring focus, used while switching sessions or leaving the activity. */
    public void hide(boolean restoreFocus) {
        hide(restoreFocus, true);
    }

    /**
     * Close the overview because the user pressed Back. Back returns to the terminal that is
     * still active, so it uses the same card-to-terminal expansion as tapping that card in the
     * grid; snapping the grid away would make the two exits feel unrelated. The instant close
     * remains the fallback for states that cannot animate: the opening preparation before the
     * grid exists, an add-session flight that owns a card and the add button, a selection that
     * already owns the screen, and a current session with no card.
     */
    public void hideFromBack() {
        if (mOpeningPreparationPending || isAddTabFlightActive() || isTerminalAddFlightActive()) {
            hide();
            return;
        }
        final TerminalSession current = mActivity.getCurrentSession();
        if (current != null && selectSession(current, null)) return;
        hide();
    }

    /**
     * Close because the activity is leaving the foreground. The add button must not start a
     * return flight into a window that is about to stop drawing; {@code onStop} cancels it
     * anyway, but starting it first would flash the plus on its way out.
     */
    public void hideForActivityStop() {
        hide(false, false);
    }

    private void hide(boolean restoreFocus, boolean animateAddButton) {
        hide(restoreFocus, animateAddButton, null);
    }

    /**
     * @param returnTargetSession session this close is switching to, so the add-button flight can
     *                            use its scrollbar state before the pager applies it; null means
     *                            the current session is staying
     */
    private void hide(boolean restoreFocus, boolean animateAddButton,
                      @Nullable TerminalSession returnTargetSession) {
        if (mOpeningPreparationPending) {
            // The overview never became visible. Undo the focus/IME hand-off and leave the live
            // content untouched; none of the normal close path has any state to unwind.
            cancelOpeningPreparation(restoreFocus);
            return;
        }
        if (!mShowing) return;
        mOpeningBackgroundPending = false;
        final boolean consumedAddButton = mTerminalAddConsumedClose;
        // A flight into a window that is about to stop drawing would only be cancelled by
        // onStop; put the real tab button back before the live content is restored.
        if (!animateAddButton) cancelTabAddButtonFlight();
        // Drop any add-session flight; restore the button instantly so the existing closing
        // pencil morph can use it (no 160 ms grow competing with it). No grow when not animating.
        // The consumed add-button close has no button to restore: the terminal owns its pixels.
        if (consumedAddButton) {
            cancelTerminalAddFlight(false, false);
            cancelAddTabFlight(false, false);
        } else {
            cancelTerminalAddFlight(true, false);
            cancelAddTabFlight(animateAddButton, false);
        }
        disarmGestureSelection();
        stopAutoScroll();
        resetGestureHover();
        cancelCardCloseAnimation();

        // Keep the position the user left. If the opening restore has not run yet, the previous
        // remembered value is more truthful than the stale scroll offset still in the view.
        if (!mRestoreScrollOnOpen && mScrollView != null) {
            mRememberedScrollY = mScrollView.getScrollY();
        }
        mRestoreScrollOnOpen = false;
        mPendingRevealCard = null;
        clearScrollRestoreAfterLayout();

        // The flight refers to cards and tabs that are about to disappear. Put everything back
        // in its final place before the overlay is hidden or the card list is released.
        cancelTransition();
        cancelPendingClosingSelection();
        cancelClosingTransition();
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).finishContentTransition();
        }
        if (mLayoutTransition != null) mLayoutTransition.finishNow();

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
        // An early return flight is already running with the closing expansion. Do not cancel
        // it here, and do not start a second one after the expansion: the add button must stay
        // in the air until it reaches the pencil. A normal close starts the flight below.
        final boolean addButtonAlreadyReturning = !consumedAddButton && animateAddButton
                && mActivity.isOverviewAddButtonReturnMorphActive();
        if (!addButtonAlreadyReturning && !mAddButtonHiding) {
            // Leave a running add-button hide alone: it already owns the button's alpha and
            // scale, and resetting those values here would make it flash at full size.
            mActivity.cancelOverviewAddButtonMorph();
        }
        mActivity.restoreTextInputPanelAfterOverview(!consumedAddButton);
        if (mOverlay != null) mOverlay.setVisibility(View.GONE);
        restoreOverlayFromSystemBars();
        if (animateAddButton && !consumedAddButton) {
            // A quick close can interrupt the opening flight. Replace it with the reverse
            // before the strip becomes visible again. A closing flight that already belongs to
            // this exit (started by the expansion) is left alone.
            if (mTabAddButtonFlightGhost != null && !mTabAddButtonFlightReturning) {
                cancelTabAddButtonFlight();
            }
            if (mTabAddButtonFlightGhost == null) {
                prepareTabAddButtonFlight(true);
            }
        }
        // The overlay is gone, so the live content can paint again. The window background goes
        // back to the scheme colour after the content is restored, never before: a transparent
        // overview over live content would flash the terminal through the grid.
        restoreLiveContentAfterOverview();
        // The overview may have published the normal content grid while this page was not laid
        // out again (the keyboard was already hidden, so onSizeChanged never fired). Republish
        // the view's own geometry before it becomes interactive; updateSize() is a no-op when
        // the pty already matches.
        final TerminalView activeTerminal = mActivity.getActiveTerminalView();
        if (activeTerminal != null) activeTerminal.reassertSessionSize();
        mActivity.showExtraKeysPanelAfterOverview();
        mAnimatedExtraKeysPanel = null;
        mActivity.applySchemeColors();
        releaseCards();
        mActivity.updateToggleTextInputButtonIcon();

        // Every close that still has a visible window returns the plus to the floating pencil.
        // A selection started the flight with its expansion above; an outside tap or Back has
        // no exit animation, so it starts the flight now. The flight receives the session this
        // close is switching to and derives the pencil's horizontal position from that session's
        // scrollbar state; the live toggle still belongs to the leaving session until the pager
        // lands. Only activity teardown suppresses the flight.
        final boolean addButtonReturning = !consumedAddButton && animateAddButton
                && (addButtonAlreadyReturning
                        || mActivity.startOverviewAddButtonReturnMorph(mAddButton,
                                returnTargetSession));
        if (consumedAddButton) {
            // The source button became the terminal. Leave only the pencil for the activity's
            // post-overlay fade-in.
            setAddButtonVisible(false);
        } else if (!addButtonReturning) {
            if (animateAddButton) {
                // No pencil to fly back to. Use the same shrink-and-fade as the add button at
                // the session limit, whether the hide already started with the exit animation
                // or the overview is closing without one.
                hideAddButtonWithAppearance(true);
            } else {
                // The window is leaving the foreground; do not start an animation that would
                // only be cancelled by onStop.
                setAddButtonVisible(false);
            }
        }
        if (animateAddButton && !consumedAddButton) startTabAddButtonClosingFlight();

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
                        revealPendingCard();
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
     * Stretch the overlay into both system-bar insets while keeping the grid's resting layout
     * unchanged.
     *
     * <p>The window is already laid out edge-to-edge and the bars are transparent, but the
     * activity root consumes the insets as padding. Negative overlay margins let the overview draw
     * over those strips; the same amounts are added to the grid's padding, so its viewport grows
     * by exactly what it gained and the cards keep their size and resting position. Once the user
     * scrolls, the padding moves out of the way and rows slide under the status and navigation
     * bars instead of disappearing behind them.</p>
     *
     * @return true when at least one inset was claimed and the overlay must be allowed to paint
     *         outside the padded root.
     */
    private boolean extendOverlayIntoSystemBars() {
        if (mStatusBarExtensionApplied || mNavigationBarExtensionApplied) return true;
        if (mOverlay == null || mGrid == null || mRootView == null) return false;

        final int topInset = mRootView.getPaddingTop();
        final int bottomInset = mRootView.getPaddingBottom();
        if (topInset <= 0 && bottomInset <= 0) return false;

        final ViewGroup.LayoutParams layoutParams = mOverlay.getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams)) return false;

        final ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) layoutParams;
        mOverlayOriginalTopMargin = margins.topMargin;
        mOverlayOriginalBottomMargin = margins.bottomMargin;
        mGridOriginalPaddingTop = mGrid.getPaddingTop();
        mGridOriginalPaddingBottom = mGrid.getPaddingBottom();
        mRootOriginalClipChildren = mRootView.getClipChildren();
        mRootOriginalClipToPadding = mRootView.getClipToPadding();

        if (topInset > 0) margins.topMargin = mOverlayOriginalTopMargin - topInset;
        if (bottomInset > 0) margins.bottomMargin = mOverlayOriginalBottomMargin - bottomInset;
        mOverlay.setLayoutParams(margins);
        mGrid.setPadding(mGrid.getPaddingLeft(), mGridOriginalPaddingTop + topInset,
                mGrid.getPaddingRight(), mGridOriginalPaddingBottom + bottomInset);
        // The overlay lives inside the padded root and now paints beyond its edges; both parents
        // must allow that drawing or the bar strips are clipped away.
        mRootView.setClipChildren(false);
        mRootView.setClipToPadding(false);

        mStatusBarTopExtension = topInset;
        mNavigationBarBottomExtension = bottomInset;
        mStatusBarExtensionApplied = topInset > 0;
        mNavigationBarExtensionApplied = bottomInset > 0;
        return true;
    }

    /** Put the overlay back inside the content area and restore the normal system-bar chrome. */
    private void restoreOverlayFromSystemBars() {
        if (!mStatusBarExtensionApplied && !mNavigationBarExtensionApplied) return;

        if (mOverlay != null) {
            final ViewGroup.LayoutParams layoutParams = mOverlay.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                final ViewGroup.MarginLayoutParams margins =
                        (ViewGroup.MarginLayoutParams) layoutParams;
                margins.topMargin = mOverlayOriginalTopMargin;
                margins.bottomMargin = mOverlayOriginalBottomMargin;
                mOverlay.setLayoutParams(layoutParams);
            }
        }
        if (mGrid != null) {
            mGrid.setPadding(mGrid.getPaddingLeft(), mGridOriginalPaddingTop,
                    mGrid.getPaddingRight(), mGridOriginalPaddingBottom);
        }
        if (mRootView != null) {
            mRootView.setClipChildren(mRootOriginalClipChildren);
            mRootView.setClipToPadding(mRootOriginalClipToPadding);
        }

        mStatusBarTopExtension = 0;
        mNavigationBarBottomExtension = 0;
        mStatusBarExtensionApplied = false;
        mNavigationBarExtensionApplied = false;
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
        selectSession(session, null);
    }

    /**
     * @param liftStart visual state of a hovered card, so the expansion starts from the lifted
     *                  thumbnail instead of jumping back to its resting size first
     * @return true when the selection was accepted (including a fallback hide), false when a
     *         transition already owns the screen and the caller must restore its own source
     */
    private boolean selectSession(@NonNull TerminalSession session,
                                  @Nullable LiftStart liftStart) {
        if (!mShowing || mClosingTransition != null || mPendingClosingCard != null
                || mClosingCardAnimation != null) return false;
        // Keep the card where it is currently drawn so the closing ghost starts from the same
        // bounds; do not finish the transition and teleport the card first.
        if (mLayoutTransition != null) mLayoutTransition.cancelForModelChange();

        // Apply the target profile now, while the overview still hides the panel. Doing it at
        // selection time also lets the following layout pass size the panel before the closing
        // flight captures the terminal's final bounds, so the flight lands on the new profile.
        prepareExtraKeysForSelectedSession(session);

        // A tap can land while the opening flight is still in the air. Land that flight first:
        // the closing expansion must start from the grid's final card geometry, not from a ghost
        // that is still shrinking into the card.
        final Transition opening = mTransition;
        if (opening != null) {
            if (opening.started) applyTransitionProgress(opening, 1f);
            cleanupTransition(opening);
        }
        // Stop any remaining scroll settle without moving the grid. The closing flight must
        // capture the bounds that are actually drawn, not the final scroll position.
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).freezeContentTransition();
        }

        final Card card = findCard(session);
        if (card == null) {
            positionTabStripForSession(session);
            hide(false, true, session);
            switchToSession(session);
            return true;
        }
        beginClosingSelection(card, liftStart);
        return true;
    }

    /**
     * Apply the target tab's final selection and scroll while the overview still hides the
     * strip, then start the expansion. This gives a background card the same tab flight as the
     * active one: the ghost has a selected, on-screen target even when the old tab was inactive
     * or scrolled out of view.
     */
    private void beginClosingSelection(@NonNull Card card, @Nullable LiftStart liftStart) {
        // selectSession() may have just cleaned up the opening flight, which restores every
        // card to its resting alpha. The add-button source needs its new card invisible again
        // until the closing expansion owns it.
        if (liftStart != null && liftStart.addButtonSource != null) prepareFlightCard(card);
        final TermuxService service = mActivity.getTermuxService();
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        final int index = service != null ? service.getIndexOfSession(card.session) : -1;
        if (tabs == null || index < 0) {
            beginClosingExpansion(card, liftStart);
            return;
        }

        mPendingClosingCard = card;
        tabs.prepareSelectionForOverview(index, () -> {
            if (mPendingClosingCard != card || !mShowing) return;
            if (mClosingTransition != null) {
                mPendingClosingCard = null;
                if (liftStart != null && liftStart.addButtonSource != null) {
                    cancelTerminalAddFlight(true, true);
                }
                restoreTabSelectionToCurrentSession();
                return;
            }
            if (findCard(card.session) != card) {
                // Keep the pending flag for hide() to clear so the tab highlight and the
                // pre-applied extra-keys profile both go back to the still-current session.
                hide(false);
                return;
            }
            mPendingClosingCard = null;
            beginClosingExpansion(card, liftStart);
        });
    }

    /** Start the expansion after the strip is already in its final state behind the overview. */
    private void beginClosingExpansion(@NonNull Card card, @Nullable LiftStart liftStart) {
        if (!mShowing || mClosingTransition != null) return;
        // prepareSelectionForOverview() has already applied the final selection and scroll.
        // Freeze the strip before the panel copy so a late OSC title, scheme or session
        // update cannot invalidate the copy and the target tab ghosts.
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) {
            // prepareSelectionForOverview() already did this on the normal path. Repeat it here
            // for the fallback path that starts an expansion without that preparation, and to
            // drop any pin-release callback that could still scroll the strip mid-copy.
            tabs.finishOpenAnimationsForOverview();
            tabs.beginOverviewClosingFreeze();
        }
        final ClosingTransition closing;
        Trace.beginSection("overview.close.prepare");
        try {
            closing = prepareClosingTransition(card, liftStart);
        } catch (RuntimeException e) {
            if (tabs != null) tabs.endOverviewClosingFreeze();
            throw e;
        } finally {
            Trace.endSection();
        }
        if (closing == null) {
            if (tabs != null) tabs.endOverviewClosingFreeze();
            if (liftStart != null && liftStart.addButtonSource != null) {
                // The expansion could not be prepared. Put the real button and the new card
                // back, then use the ordinary close so the pencil morph still owns the exit.
                cancelTerminalAddFlight(true, false);
            }
            hide(false, true, card.session);
            switchToSession(card.session);
            return;
        }
        mClosingTransition = closing;
        startClosingTransition(closing);
    }

    /** End the strip freeze once the closing copy is no longer visible. */
    private void endClosingStripFreeze() {
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) tabs.endOverviewClosingFreeze();
    }

    /** Abort a selection that is still waiting for the strip's final layout. */
    private void cancelPendingClosingSelection() {
        if (mPendingClosingCard == null) return;
        mPendingClosingCard = null;
        restoreTabSelectionToCurrentSession();
    }

    /** Put the highlighted tab, strip scroll and extra-keys profile back on the current session. */
    private void restoreTabSelectionToCurrentSession() {
        final TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        if (client != null) client.cancelSessionExtraKeysForOverview();
        final TermuxService service = mActivity.getTermuxService();
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        final TerminalSession current = mActivity.getCurrentSession();
        if (service == null || tabs == null || current == null) return;
        final int index = service.getIndexOfSession(current);
        if (index >= 0) tabs.restoreSelectionForOverview(index);
    }

    /**
     * Apply the selected session's extra-keys profile while the overview still hides the panel.
     * The profile is installed at selection time so it is already current when the panel slides
     * back in with the expanding terminal, and so the closing flight measures the final height.
     */
    private void prepareExtraKeysForSelectedSession(@NonNull TerminalSession session) {
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        if (client != null) client.prepareSessionExtraKeysForOverview(session);
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
        return closeSession(card, false);
    }

    private boolean closeSession(@NonNull Card card, boolean closeAnimationFinished) {
        if (!mShowing || (!closeAnimationFinished && card.closing)
                || mClosingTransition != null || mPendingClosingCard != null
                || mClosingCardAnimation != null) return false;
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

    /**
     * Close a card from its X button. The whole root, including the compact tab and the cross,
     * fades and shrinks as one body. The session is finished only after the animation, so the
     * session-list update cannot remove the card halfway through its fade and reflow the grid
     * underneath it.
     */
    private void closeSessionFromButton(@NonNull Card card) {
        if (!mShowing || card.closing || mTransition != null
                || mClosingTransition != null || mPendingClosingCard != null
                || mClosingCardAnimation != null) {
            return;
        }
        if (mDragController != null) mDragController.cancelForModelChange();
        card.appearing = false;
        card.closing = true;
        mClosingCardAnimation = card;
        card.root.animate().cancel();
        card.root.setPivotX(card.root.getWidth() / 2f);
        card.root.setPivotY(card.root.getHeight() / 2f);

        // The button and the add-button limit share this exact shrink-and-fade curve.
        final ValueAnimator animator = OverviewAppearanceAnimation.hideAnimator(card.root);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCardCloseAnimator != animation || mClosingCardAnimation != card) return;
                mCardCloseAnimator = null;
                mClosingCardAnimation = null;
                finishCardClose(card);
            }
        });
        mCardCloseAnimator = animator;
        animator.start();
    }

    /** Finish a close after its card has already faded and shrunk. */
    private void finishCardClose(@NonNull Card card) {
        if (!mShowing || findCard(card.session) != card) {
            restoreCardAfterCloseAnimation(card);
            return;
        }
        // Keep the card at the visual endpoint while the session is being removed. If removal is
        // refused, put it back instead of leaving an invisible slot in the grid.
        card.root.setAlpha(0f);
        card.root.setScaleX(CARD_CLOSE_SCALE);
        card.root.setScaleY(CARD_CLOSE_SCALE);
        if (!closeSession(card, true)) {
            restoreCardAfterCloseAnimation(card);
        }
    }

    private void restoreCardAfterCloseAnimation(@NonNull Card card) {
        card.closing = false;
        card.root.animate().cancel();
        SessionOverviewCardView.resetVisual(card.root);
    }

    /** Stop a close-button animation without touching the session. */
    private void cancelCardCloseAnimation() {
        final ValueAnimator animator = mCardCloseAnimator;
        final Card card = mClosingCardAnimation;
        mCardCloseAnimator = null;
        mClosingCardAnimation = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
        if (card != null) restoreCardAfterCloseAnimation(card);
    }

    /** Called after the live session list changed. Keeps the grid in sync while it is open. */
    public void onSessionsChanged() {
        if (!mShowing) return;
        // The closing flight owns the selected card and its tab. A structural update in the
        // middle of the 300 ms expansion would replace those views under the ghost; let the
        // flight finish, then hide and switch in the usual way. The same applies to the short
        // X-button fade: the card must finish fading before its slot is removed.
        if (mClosingTransition != null || mPendingClosingCard != null
                || mClosingCardAnimation != null) {
            // The add-button source is owned by the closing flight; restoring it here would make
            // it reappear over the expanding terminal. Only a card-add flight needs unwinding.
            if (mClosingTransition != null) {
                if (isAddTabFlightActive()) cancelAddTabFlight(false, false);
            } else if (isAddTabFlightActive()) {
                cancelAddTabFlight(true, true);
            }
            return;
        }
        TermuxService service = mActivity.getTermuxService();
        if (service == null || service.getTermuxSessionsSize() == 0) {
            hide(false);
            return;
        }
        // The add button follows the same session list: hide with the card-close animation at
        // the limit, grow it back with the reverse as soon as any tab is closed. While an
        // add-session flight owns the button, defer this until the flight finishes.
        if (!isAddTabFlightActive() && !isTerminalAddFlightActive()) {
            updateAddButtonForSessionLimit();
        }

        final List<TermuxSession> sessions = service.getTermuxSessions();
        // Session-list callbacks also fire for non-structural updates (for example when a tab is
        // re-bound). Rebuilding in that case would cancel a flight that is already in the air.
        if (hasSameSessions(sessions)) {
            // A session switch can hand us a terminal with different columns, rows or background,
            // but a new card's reveal owns the first frames of its own shell attaching. Reading
            // the freshly selected terminal's palette here would repaint the whole backdrop in
            // the middle of the fade. The next callback after the reveal settles picks it up.
            if (!hasAppearingCards()) {
                applyOverviewBackground();
                updateGridAspect();
            }
            refreshSelection();
            // A non-structural list update (selection, rebind, title) does not change terminal
            // pixels. updateGridAspect() above already marks every card when the glyph pitch
            // actually changed, and resize callbacks dirty only the sessions they resized.
            return;
        }

        // Capture where every card is on screen before any structural change. An append can add
        // a row (the fifth tab) and reflow the survivors; animating from these centres keeps the
        // existing thumbnails from jumping to their new slots in one frame.
        final Map<TerminalSession, float[]> oldCenters = captureCardCenters(mCards);

        // A pure deletion keeps the surviving cards in their old relative order. Remember where
        // they were so they can slide into the vacated slot instead of appearing there
        // instantly. The surviving views and their preview bitmaps are kept: rebuilding the
        // whole grid would clear every thumbnail and force it through an empty frame.
        final boolean pureRemoval = isPureRemoval(sessions);
        // An opening flight still owns the cards and the terminal frame. Do not mix retained
        // cards with that transient state; cancel the flight first and fall back to a rebuild.
        final boolean reuseCards = pureRemoval
                && mTransition == null
                && mGrid != null
                && mGrid.getChildCount() == mCards.size();

        cancelTransition();
        if (mLayoutTransition != null) mLayoutTransition.cancelForModelChange();
        if (mDragController != null) mDragController.cancelForModelChange();

        // A structural change in the middle of an add-session flight would move/remove the
        // card under the ghost; drop the flight and let the normal path reconcile the grid.
        if (mAddTabFlightCard != null || mAddTabFlightAnimator != null) {
            cancelAddTabFlight(true, true);
        }

        // An addition keeps every existing card and its preview bitmap. Only the new cards are
        // inflated; they fade and grow into their slots instead of the whole grid being rebuilt
        // and every thumbnail being cleared for a frame.
        if (!pureRemoval) {
            final List<Card> added = appendCardsForPureAddition(sessions);
            if (added != null) {
                if (mTerminalAddFlight != null && !mTerminalAddFlight.handedOff) {
                    if (added.size() == 1) {
                        final Card flightCard = added.get(0);
                        mTerminalAddFlight.card = flightCard;
                        mAddTabFlightArmed = false;
                        mAddTabFlightCard = flightCard;
                        prepareFlightCard(flightCard);
                        // The grid's own layout listener re-fits the integer glyph pitch once the
                        // new child has been measured. Doing it here would resize every bitmap a
                        // frame early.
                        refreshTitles();
                        mGrid.requestLayout();
                        // The new terminal is flying out of the add button, not into this card
                        // slot. Scrolling the grid to reveal the hidden card would move the
                        // overview under the closing animation for no visible benefit. Keep the
                        // survivors at their pre-add centres too: the closing flight fades the
                        // grid as a snapshot, so settling it to the new top-aligned layout here
                        // would read as the grid being pulled to the top.
                        animateAddedCards(added, oldCenters, flightCard, false, true);
                        schedulePreviewRender();
                        postTerminalAddTimeout();
                        return;
                    }
                    cancelTerminalAddFlight(true, true);
                }
                if (mAddTabFlightArmed && added.size() == 1) {
                    final Card flightCard = added.get(0);
                    mAddTabFlightArmed = false;
                    mAddTabFlightCard = flightCard;
                    prepareFlightCard(flightCard);
                    // The grid's own layout listener re-fits the integer glyph pitch once the new
                    // child has been measured. Doing it here would resize every bitmap a frame early.
                    refreshTitles();
                    mGrid.requestLayout();
                    animateAddedCards(added, oldCenters, flightCard, true, false);
                    schedulePreviewRender();
                    postAddTabFlightTimeout();
                    return;
                }
                if (mAddTabFlightArmed) cancelAddTabFlight(true, true);
                // The grid's own layout listener re-fits the integer glyph pitch once the new
                // child has been measured. Doing it here would resize every bitmap a frame early.
                refreshTitles();
                mGrid.requestLayout();
                animateAddedCards(added, oldCenters, null, true, false);
                schedulePreviewRender();
                return;
            }
            if (mAddTabFlightArmed) cancelAddTabFlight(true, true);
        } else if (mAddTabFlightArmed) {
            cancelAddTabFlight(true, true);
        }
        if (mTerminalAddFlight != null && !mTerminalAddFlight.handedOff) {
            cancelTerminalAddFlight(true, true);
        }

        // Only a removal, reorder or rebuild needs to re-target the backdrop right now. For an
        // addition the active terminal has not changed yet, and repainting the window here is
        // what made the grid blink while the new card was fading in.
        for (Card card : mCards) card.appearing = false;
        applyOverviewBackground();

        if (!reuseCards || !retainCards(sessions)) {
            buildCards(sessions);
        } else {
            // Removing the active session can change the largest glyph grid and therefore the
            // reference slot; retained cards themselves keep their own emulator grids.
            updateGridAspect();
            refreshTitles();
        }
        if (mCards.isEmpty()) {
            hide(false);
            return;
        }
        // Seed the one structural transition before the new layout is drawn. The scroll clamp is
        // folded into the same plan, so no second settle is started after the cards begin to move.
        // The opening restore still owns the viewport during its first frame, so a removal there
        // simply snaps to the final layout.
        mGrid.requestLayout();
        final boolean seededRemoval = pureRemoval && !mRestoreScrollOnOpen
                && !oldCenters.isEmpty() && mLayoutTransition != null
                && animateRemovalSettle(oldCenters);
        if (!seededRemoval) {
            // A rebuild/reorder, an opening removal, or a removal with no usable old/new
            // geometry has no seeded plan. Snap the new layout and clear the old offset.
            resetStructuralVisuals();
        }
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

    /**
     * Keep the cards for every session that survived a pure removal and detach only the cards
     * that disappeared.
     *
     * <p>A surviving card keeps its {@link ImageView} and preview bitmap. Rebuilding the whole
     * grid after a swipe would clear every thumbnail and force it through an empty frame before
     * the posted preview render runs; retaining the cards avoids that flash.</p>
     *
     * <p>Nothing is changed until the entire new session list has been matched. If a session is
     * missing a card or the grid is out of sync, the caller can still fall back to a full
     * {@link #buildCards(List)}.</p>
     *
     * @return true when the removed cards were detached; false when a full rebuild is required
     */
    private boolean retainCards(@NonNull List<TermuxSession> sessions) {
        if (mGrid == null || mGrid.getChildCount() != mCards.size()) return false;
        for (int i = 0; i < mCards.size(); i++) {
            if (mGrid.getChildAt(i) != mCards.get(i).root) return false;
        }

        final Map<TerminalSession, Card> bySession = new IdentityHashMap<>();
        for (Card card : mCards) bySession.put(card.session, card);

        final List<Card> ordered = new ArrayList<>(mCards.size());
        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;
            final Card card = bySession.remove(session);
            if (card == null) return false;
            ordered.add(card);
        }
        // A pure removal leaves only the deleted cards in the map. An empty map means the list
        // did not actually shrink, which is the full-rebuild path's job.
        if (bySession.isEmpty() || ordered.isEmpty()) return false;

        for (Card removed : bySession.values()) {
            removed.root.animate().cancel();
            removed.preview.setImageDrawable(null);
            releaseBitmap(removed);
            mGrid.removeView(removed.root);
        }
        mCards.clear();
        mCards.addAll(ordered);
        return true;
    }

    /**
     * Keep every existing card and create cards only for sessions appended to the end of the
     * list.
     *
     * <p>New tabs are appended by the service, so this is the common add-session path. Retaining
     * the existing views keeps their preview bitmaps on screen; rebuilding the whole grid would
     * clear every thumbnail and force it through an empty frame before the posted preview render
     * runs. The new cards are returned so the caller can animate them into their slots.</p>
     *
     * <p>Nothing is changed until the entire list has been matched. A removal, reorder or
     * out-of-sync grid returns {@code null} and leaves the caller free to take the full-rebuild
     * path.</p>
     *
     * @return the newly created cards, or {@code null} when the change is not a pure append
     */
    @Nullable
    private List<Card> appendCardsForPureAddition(@NonNull List<TermuxSession> sessions) {
        if (mGrid == null || mCards.isEmpty() || mGrid.getChildCount() != mCards.size()) {
            return null;
        }
        for (int i = 0; i < mCards.size(); i++) {
            if (mGrid.getChildAt(i) != mCards.get(i).root) return null;
        }

        final List<TerminalSession> addedSessions = new ArrayList<>();
        int cardIndex = 0;
        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;
            if (cardIndex < mCards.size()) {
                if (mCards.get(cardIndex).session != session) return null;
                cardIndex++;
            } else {
                addedSessions.add(session);
            }
        }
        if (cardIndex != mCards.size() || addedSessions.isEmpty()) return null;

        final List<Card> added = new ArrayList<>(addedSessions.size());
        for (TerminalSession session : addedSessions) {
            final Card card = createCard(session);
            // Arm the visual before the view joins the hierarchy. addView() schedules a layout,
            // and a card that starts at full alpha/scale would be drawn once before the reveal
            // listener gets a chance to hide it.
            armCardAppearance(card);
            mGrid.addView(card.root);
            mCards.add(card);
            added.add(card);
        }
        return added;
    }

    /**
     * Put a new card into its hidden starting state before it is added to the grid.
     *
     * <p>Alpha and scale are set here rather than in the reveal listener so there is no frame
     * in which the view is laid out at its full size while still opaque. The pivot is set to the
     * measured centre in {@link #animateAddedCards(List, Map, Card, boolean, boolean)} just before the first drawn frame.
     */
    private static void armCardAppearance(@NonNull Card card) {
        card.appearing = true;
        OverviewAppearanceAnimation.arm(card.root);
    }

    /**
     * Clear the reveal flag and force the final alpha/scale values in case an animation was cut
     * short. Translation is deliberately not touched: after the layout-transition coordinator
     * exists it owns card positions, and an appearance end callback must not snap a card back to
     * its slot while that transition is still running.
     */
    private static void finishCardAppearance(@NonNull Card card) {
        card.appearing = false;
        OverviewAppearanceAnimation.reset(card.root);
    }

    /** True while a newly appended card is still fading and growing into its slot. */
    private boolean hasAppearingCards() {
        for (Card card : mCards) {
            if (card.appearing) return true;
        }
        return false;
    }

    /**
     * Adopt a background change that arrived while a new card was revealing.
     *
     * <p>{@link #onTerminalBackgroundChanged()} deliberately leaves the window alone during the
     * reveal so the backdrop cannot flash under the fading card. The change must not be lost,
     * though: the new session is already current and its palette is what the overview should show
     * once the reveal settles.</p>
     */
    private void applyOverviewBackgroundAfterReveal() {
        if (!mShowing || hasAppearingCards()) return;
        applyOverviewBackground();
    }

    /**
     * Reveal newly appended cards and hold the surviving cards at their old visual centres while
     * the grid reflows.
     *
     * <p>Adding a fifth card starts a new row, which changes the grid's centring. Without the
     * seeded translations every existing thumbnail would snap to its new slot in one frame while
     * the new card faded in. The new card itself only animates alpha and scale around its centre;
     * its translation stays at zero because its old and target centres are the same.</p>
     *
     * <p>When {@code flightCard} is non-null it stays hidden; the add-session ghost owns its
     * reveal. Surviving cards still start from their old centres, its preview is still rendered
     * for the copy, and no reveal animator is started for it. The flight starts after survivors
     * are armed.</p>
     *
     * <p>{@code revealNewest} scrolls the grid to the appended card. The tab-tap flight and an
     * ordinary add both want that. The add-button-to-terminal flight does not: its card never
     * lands in the grid, so any scroll would only move the backdrop under the closing expansion.</p>
     *
     * <p>{@code holdSurvivors} is set only by the add-button-to-terminal flight. That closing
     * fades the grid as a static snapshot of the pre-add overview, so the survivors keep the
     * seeded centres and their settle animator is not started. The hold is released when the
     * closing transition finishes or the add flight is cancelled.</p>
     */
    private void animateAddedCards(@NonNull List<Card> cards,
                                   @NonNull Map<TerminalSession, float[]> oldCenters,
                                   @Nullable Card flightCard,
                                   boolean revealNewest,
                                   boolean holdSurvivors) {
        if (mGrid == null || cards.isEmpty() || mCards.isEmpty()) {
            if (flightCard != null) cancelCardFlight(true, true);
            return;
        }
        final SessionOverviewGridView grid = mGrid;
        final int count = mCards.size();
        final boolean[] started = {false};
        final View.OnLayoutChangeListener[] listener = new View.OnLayoutChangeListener[1];

        final Runnable start = () -> {
            if (started[0]) return;
            for (Card card : cards) {
                if (card.root.getWidth() <= 0 || card.root.getHeight() <= 0) return;
            }
            started[0] = true;
            if (listener[0] != null) grid.removeOnLayoutChangeListener(listener[0]);
            if (!mShowing || grid.getChildCount() != count) {
                if (flightCard != null) {
                    cancelCardFlight(true, true);
                } else {
                    for (Card card : cards) finishCardAppearance(card);
                    applyOverviewBackgroundAfterReveal();
                }
                // No structural plan will consume the old offset in this path.
                resetStructuralVisuals();
                return;
            }

            // The add-button flight may already have been cancelled between arming this
            // runnable and the layout pass. In that case there is no closing snapshot to hold
            // for; fall through to the ordinary transition so the grid does not stay offset.
            final boolean hold = holdSurvivors && mTerminalAddFlight != null;

            final float[] fromX = new float[count];
            final float[] fromY = new float[count];
            final float[] targetX = new float[count];
            final float[] targetY = new float[count];
            final List<View> views = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final Card card = mCards.get(i);
                final View root = card.root;
                final float centerX = root.getLeft() + root.getWidth() / 2f;
                final float centerY = root.getTop() + root.getHeight() / 2f;
                final float[] oldCenter = oldCenters.get(card.session);
                fromX[i] = oldCenter != null ? oldCenter[0] : centerX;
                fromY[i] = oldCenter != null ? oldCenter[1] : centerY;
                targetX[i] = centerX;
                targetY[i] = centerY;
                views.add(root);
                // A card that is still fading in owns its alpha/scale animator. Cancel only
                // positional animators here; cancelling the shared ViewPropertyAnimator would
                // freeze an in-flight appearance at its current alpha/scale.
                if (!card.appearing) root.animate().cancel();
                if (card.appearing && card != flightCard) {
                    OverviewAppearanceAnimation.pivotToCenter(root);
                }
            }
            if (hold) {
                // The closing expansion fades the grid as one snapshot. Keep the survivors at
                // their old centres and finish appearance without touching their translation.
                mTerminalAddGridHeld = true;
                for (int i = 0; i < count; i++) {
                    final Card card = mCards.get(i);
                    final View root = card.root;
                    root.setTranslationX(fromX[i] - targetX[i]);
                    root.setTranslationY(fromY[i] - targetY[i]);
                    if (card.appearing) {
                        card.appearing = false;
                        OverviewAppearanceAnimation.reset(root);
                    }
                }
            }

            // Paint the new thumbnail before its first visible frame; the throttled preview
            // pass may still be a few frames away.
            final int textSize = previewTextSize();
            final Typeface typeface = previewTypeface();
            for (Card card : cards) renderCardNow(card, textSize, typeface);

            int scrollTarget = mScrollView != null ? mScrollView.getScrollY() : 0;
            if (!hold && revealNewest && mLayoutTransition != null) {
                if (mRestoreScrollOnOpen) {
                    mPendingRevealCard = cards.get(cards.size() - 1);
                    mPendingRevealAttempts = 0;
                } else {
                    scrollTarget = revealTargetFor(cards.get(cards.size() - 1));
                }
            }
            if (!hold && !mRestoreScrollOnOpen && mLayoutTransition != null) {
                mLayoutTransition.seed(views, fromX, fromY, targetX, targetY,
                        scrollTarget, false);
            } else if (!hold) {
                // Opening owns the viewport, or there is no coordinator: apply the final layout
                // immediately and drop any offset left by an interrupted transition.
                resetStructuralVisuals();
            }

            if (!hold) {
                for (Card card : cards) {
                    if (card == flightCard || !card.appearing) continue;
                    final ViewPropertyAnimator animator =
                            OverviewAppearanceAnimation.animator(card.root);
                    animator.withEndAction(() -> {
                        finishCardAppearance(card);
                        applyOverviewBackgroundAfterReveal();
                    });
                    animator.start();
                }
            }

            if (flightCard != null) beginAddTabFlight(flightCard);
        };

        listener[0] = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                start.run();
        grid.addOnLayoutChangeListener(listener[0]);
        // For a grid that is already laid out requestLayout() still schedules a pass, but the
        // post covers a view that reports no change because its geometry was cached.
        grid.post(start);
    }

    /** Scroll target that reveals a just-appended card, using its real laid-out rectangle. */
    private int revealTargetFor(@NonNull Card card) {
        final SessionOverviewGridMath.Layout layout = mGrid.getGridLayout();
        if (mScrollView == null || layout == null || !layout.hasCards()
                || card.root.getHeight() <= 0) {
            return mScrollView != null ? mScrollView.getScrollY() : 0;
        }
        final int viewportHeight = mScrollView.getHeight();
        if (viewportHeight <= 0) return mScrollView.getScrollY();
        final int topInset = mGrid.getPaddingTop();
        // The grid lays children at paddingTop + slot.top, so the child's own top already
        // includes the status-bar strip. Adding topInset here would double it and over-scroll.
        final int cardTop = card.root.getTop();
        final int maxScroll = Math.max(0, mGrid.getHeight() - viewportHeight);
        return SessionOverviewScrollMath.scrollForAppendedCard(
                mScrollView.getScrollY(), viewportHeight, topInset, maxScroll,
                cardTop, cardTop + card.root.getHeight(), layout.outerGap);
    }

    /**
     * Snap the grid out of any old structural offset when the current model change will not build
     * a new transition. The coordinator is cancelled before a rebuild/reorder, so its state is
     * gone; the scroll offset it left behind must not survive into the rebuilt layout.
     */
    private void resetStructuralVisuals() {
        if (mLayoutTransition != null) mLayoutTransition.finishNow();
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).finishContentTransition();
        }
        for (Card card : mCards) {
            card.root.setTranslationX(0f);
            card.root.setTranslationY(0f);
        }
    }

    /** Visual centres of the cards currently in the grid, keyed by their sessions. */
    @NonNull
    private Map<TerminalSession, float[]> captureCardCenters(@NonNull List<Card> cards) {
        final Map<TerminalSession, float[]> centers = new IdentityHashMap<>();
        for (Card card : cards) {
            final View root = card.root;
            if (root.getWidth() <= 0 || root.getHeight() <= 0) continue;
            // Content coordinates: the laid-out centre plus any card translation. The structural
            // scroll offset is deliberately excluded; the transition carries it separately.
            centers.put(card.session, new float[]{
                    root.getLeft() + root.getWidth() / 2f + root.getTranslationX(),
                    root.getTop() + root.getHeight() / 2f + root.getTranslationY()
            });
        }
        return centers;
    }

    /**
     * Start the surviving cards from their old centres and let the drag controller settle them
     * into the reflowed grid. The controller seeds the translations before the next layout and
     * re-applies them against the actual laid-out bounds before starting the animation.
     */
    private boolean animateRemovalSettle(@NonNull Map<TerminalSession, float[]> oldCenters) {
        if (mLayoutTransition == null || mGrid == null || mCards.isEmpty()) return false;
        final int count = mCards.size();
        final float[] targetCentres = mGrid.getSlotCenters(count);
        if (targetCentres == null || targetCentres.length != count * 2) return false;

        final List<View> views = new ArrayList<>(count);
        final float[] fromX = new float[count];
        final float[] fromY = new float[count];
        final float[] targetX = new float[count];
        final float[] targetY = new float[count];
        for (int i = 0; i < count; i++) {
            final Card card = mCards.get(i);
            final float[] oldCenter = oldCenters.get(card.session);
            if (oldCenter == null) return false;
            views.add(card.root);
            fromX[i] = oldCenter[0];
            fromY[i] = oldCenter[1];
            targetX[i] = targetCentres[i * 2];
            targetY[i] = targetCentres[i * 2 + 1];
        }
        // The platform's layout clamp supplies the scroll target. Folding it into this plan keeps
        // the removal from running a second settle after the cards have already started moving.
        mLayoutTransition.seed(views, fromX, fromY, targetX, targetY, 0, true);
        return true;
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
        // Do not scroll here. A drop can be committed while the grid is scrolled away from the
        // active card; re-centring on it would jump back to the opening position.
        mActivity.saveSessionSnapshot();
        schedulePreviewRender();
    }

    /** Repaint the card preview when a session produces output while the overview is open. */
    public void onSessionTextChanged(@NonNull TerminalSession session) {
        final Transition opening = mTransition;
        if (opening != null && opening.frame != null && opening.session == session) {
            opening.frame.markDirty();
        }
        final ClosingTransition closing = mClosingTransition;
        if (closing != null && closing.frame != null && closing.session == session) {
            closing.frame.markDirty();
        }
        if (!mShowing || mActivityStopped || mGrid == null) return;
        final Card card = findCard(session);
        if (card == null) return;
        card.previewDirty = true;
        // Offscreen cards keep their dirty flag. A scroll or layout requests only the cards that
        // actually entered the viewport.
        requestCardPreview(card);
    }

    /** Cheap refresh for high-frequency OSC title updates. */
    public void refreshTitles() {
        if (!mShowing) return;
        final TerminalSession current = mActivity.getCurrentSession();
        for (Card card : mCards) {
            // A running card can be waiting for its shell to die after a close gesture. If the
            // process has now exited but the session stays in the list because of its exit
            // status, the card is visible and closable again; the next tap removes it.
            if (card.closing && !card.session.isRunning()
                    && card != mClosingCardAnimation) {
                card.closing = false;
                card.root.resetSwipeState();
            }
            bindTab(card, card.session == current);
        }
    }

    /** Update the selected-card highlight after a page change. */
    public void refreshSelection() {
        refreshTitles();
        // Page-selection callbacks also arrive while a tab is being deleted or reordered. Moving
        // the viewport here would reset the grid to the position it had when it opened. The
        // selected-card highlight is the only thing a page change should refresh; opening and
        // the user's own scrolling own the viewport position.
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
        if (mOpeningPreparationPending) {
            cancelOpeningPreparation(false);
            return;
        }
        cancelTabAddButtonFlight();
        cancelAddTabFlight(false, false);
        // The closing transition owns the source ghost once it starts; only a flight that has
        // not handed off can be dropped here without pulling pixels out from under it.
        if (mClosingTransition == null) cancelTerminalAddFlight(false, false);
        if (!mShowing) return;
        mActivityStopped = true;
        disarmGestureSelection();
        resetAddButtonHover();
        mActivity.cancelOverviewAddButtonMorph();

        cancelCardCloseAnimation();
        cancelTransition();
        cancelPendingClosingSelection();
        cancelClosingTransition();
        // The overview stays open across a trip to the background. If a close-time hide was
        // still running, restore the button to a stable frame for the resume path.
        cancelAddButtonHideAnimation();
        if (isAtSessionLimit()) {
            setAddButtonVisible(false);
        } else {
            setAddButtonVisible(true);
            if (mAddButton != null) {
                mAddButton.setEnabled(true);
                OverviewAppearanceAnimation.reset(mAddButton);
            }
        }
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).finishContentTransition();
        }
        mActivity.keepExtraKeysPanelHiddenForOverview();
        if (mDragController != null) mDragController.cancelForModelChange();
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
        // A rotation can finish while the activity is stopped. On resume the resources describe
        // the window that is actually on screen; replace a snapshot taken before that rotation so
        // it cannot override the correct viewport.
        mLastConfiguration = new Configuration(mActivity.getResources().getConfiguration());
        // An opening that is still waiting for the full terminal has not shown the overlay yet.
        // Its layout/IME callbacks will finish it; the normal resume path must not reveal the
        // grid early.
        if (mOpeningPreparationPending) return;
        if (!mShowing || mOverlay == null) return;
        // The strip's tab height may have changed while this activity was stopped. Re-apply it
        // before the preview refresh so the cards and their tabs use the window's current shape.
        refreshCardTabMetrics();
        refreshPreviewThrottleInterval();

        // The activity may have restored the input panel and the terminal focus while the
        // overlay was in the background. Apply the same temporary suppression that show() uses.
        hideLiveContentForOverview();
        mActivity.hideTextInputPanelForOverview();
        mActivity.keepExtraKeysPanelHiddenForOverview();
        KeyboardUtils.setSoftKeyboardAlwaysHiddenAndAdjustResize(mActivity);
        mSoftKeyboardHiddenForResume = true;

        applyOverviewBackground();
        extendOverlayIntoSystemBars();
        if (mStatusBarExtensionApplied) {
            applyOverviewStatusBarAppearance(mOverlayBackgroundColor);
        }

        mOverlay.setVisibility(View.VISIBLE);
        updateAddButtonForSessionLimit();
        mActivity.syncOverviewAddButtonTranslation();
        mOverlay.requestFocus();
        KeyboardUtils.hideSoftKeyboard(mActivity, mOverlay);

        if (mGrid != null) {
            // The window shape or terminal viewport can have changed while stopped (multi-window,
            // a rotated device, an external display). Keep the cards matched to the new shape.
            updateGridAspect();
            markResizedPreviewsDirty();
            mGrid.requestLayout();
        }
        schedulePreviewRender();
    }

    /** Release overlay state when the activity is destroyed and will not be resumed. */
    public void onActivityDestroyed() {
        mRasterizer.shutdown();
        cancelTabAddButtonFlight();
        cancelAddTabFlight(false, false);
        if (mClosingTransition == null) cancelTerminalAddFlight(false, false);
        if (!mShowing) return;
        onActivityStopped();
        clearScrollRestoreAfterLayout();
        mOpeningBackgroundPending = false;
        mShowing = false;
        mActivity.cancelOverviewAddButtonMorph();
        setAddButtonVisible(false);
        mActivityStopped = false;
        mPreviousFocus = null;
        mHadIme = false;
        mSoftKeyboardHiddenForResume = false;
        releaseCards();
        mActivity.cancelExtraKeysPanelOverview();
        mAnimatedExtraKeysPanel = null;
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
     * the overlay clips a partially or fully scrolled tab against the same edge as the strip,
     * while the full width preserves the title's original truncation.</p>
     */
    @Nullable
    private Transition prepareTransition(@NonNull TermuxService service) {
        if (mOverlay == null || mGhostLayer == null || mContentArea == null) return null;

        Trace.beginSection("overview.open.prepare");
        final Transition transition = new Transition();

        final TerminalView terminal = mActivity.getActiveTerminalView();
        final Rect terminalContent = new Rect();
        if (terminal != null && terminal.isAttachedToWindow() && terminal.isShown()
                && terminal.getWidth() > 0 && terminal.getHeight() > 0
                && terminal.getTerminalContentBounds(terminalContent)) {
            final TerminalSession activeSession = mActivity.getCurrentSession();
            final TerminalEmulator activeEmulator = activeSession != null
                    ? activeSession.getEmulator() : null;
            if (activeEmulator != null) {
                try {
                    // The live source owns the same glyph-grid raster as the cards, so output
                    // produced during the flight is painted without a new bitmap allocation.
                    transition.frame = new TerminalFrameSource(activeSession, mRasterizer,
                            terminalContent.width(), terminalContent.height(),
                            previewTextSize(), previewTypeface(),
                            previewBackgroundTransparency(),
                            () -> scrollTopRow(activeSession));
                    transition.session = activeSession;
                    transition.terminalSource = windowRectOf(terminal, terminalContent);
                } catch (RuntimeException ignored) {
                    // A terminal that cannot be drawn (e.g. mid-detach) simply does not fly.
                    transition.frame = null;
                }
            }
        }

        // Capture the scrollbar thumb with the terminal pixels, before the keyboard and panel
        // hide can resize the view. The thumb is a sibling View, not part of the live frame.
        captureOpeningScrollbarThumbSource(transition, terminal);

        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) {
            // Capture every tab in the panel, not only the part currently scrolled into view.
            // The overview restores its own scroll position after the cards are laid out, so the
            // final viewport is not known yet; startTransition() installs the ghosts once it is.
            // Capturing now preserves the strip's pixels and title truncation before the overlay
            // and keyboard change the layout.
            for (TermuxSession termuxSession : service.getTermuxSessions()) {
                if (termuxSession == null) continue;
                final TerminalSession session = termuxSession.getTerminalSession();
                if (session == null) continue;
                final View tabView = tabs.findTabForSession(session);
                if (tabView == null || !tabView.isShown()) continue;

                // Use the full layout width even when the tab is partly or fully scrolled out.
                // The overlay clips the overhang at the window edge; using the visible
                // intersection would re-render the title for a narrower rectangle instead.
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
        Trace.endSection();
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

    /** Window bounds of a view written into {@code out}; {@code location} is reused. */
    private static void windowRectOf(@NonNull View view, @NonNull int[] location,
                                     @NonNull Rect out) {
        view.getLocationInWindow(location);
        out.set(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    /** Window bounds of a local rectangle written into {@code out}; {@code location} is reused. */
    private static void windowRectOf(@NonNull View view, @NonNull Rect localBounds,
                                     @NonNull int[] location, @NonNull Rect out) {
        view.getLocationInWindow(location);
        out.set(location[0] + localBounds.left, location[1] + localBounds.top,
                location[0] + localBounds.right, location[1] + localBounds.bottom);
    }

    private static Rect offsetRect(@NonNull Rect source, @NonNull int[] origin) {
        return new Rect(source.left - origin[0], source.top - origin[1],
                source.right - origin[0], source.bottom - origin[1]);
    }

    /** Rebased copy of {@code source} written into {@code out}, for per-frame callers. */
    private static void offsetRect(@NonNull Rect source, @NonNull int[] origin,
                                   @NonNull Rect out) {
        out.set(source.left - origin[0], source.top - origin[1],
                source.right - origin[0], source.bottom - origin[1]);
    }

    private static FrameLayout.LayoutParams frameParams(@NonNull Rect rect) {
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(0, rect.width()), Math.max(0, rect.height()));
        params.leftMargin = rect.left;
        params.topMargin = rect.top;
        return params;
    }

    /** Reuse the terminal ghost's layout params instead of allocating one per animation frame. */
    private void layoutTerminalGhost(@NonNull View ghost, @NonNull Rect rect) {
        layoutGhost(ghost, rect, mScratchGhostParams);
    }

    /** Reuse one LayoutParams object per view; a frame never allocates a new one. */
    private static void layoutGhost(@NonNull View ghost, @NonNull Rect rect,
                                    @NonNull FrameLayout.LayoutParams params) {
        params.width = Math.max(0, rect.width());
        params.height = Math.max(0, rect.height());
        params.leftMargin = rect.left;
        params.topMargin = rect.top;
        ghost.setLayoutParams(params);
    }

    /** Write the interpolated rect into {@code out} so the animator allocates nothing per frame. */
    private static void lerpRect(@NonNull Rect out, @NonNull Rect start,
                                 @NonNull Rect end, float progress) {
        out.set(
                SessionOverviewTransitionMath.lerp(start.left, end.left, progress),
                SessionOverviewTransitionMath.lerp(start.top, end.top, progress),
                SessionOverviewTransitionMath.lerp(start.right, end.right, progress),
                SessionOverviewTransitionMath.lerp(start.bottom, end.bottom, progress));
    }

    /**
     * Height the width-scaled source actually covers in {@code viewHeight}.
     *
     * <p>The card aspect is the terminal aspect rounded to integers, so this differs from the
     * view height by at most a pixel. Clipping the flight rect to this height removes the empty
     * bottom strip instead of painting a second translucent layer under it.</p>
     */
    private static float paintedTerminalHeight(int viewWidth, int viewHeight,
                                               @Nullable TerminalFrameSource frame) {
        if (frame == null || frame.getWidth() <= 0 || viewWidth <= 0) return viewHeight;
        return Math.min(viewHeight,
                frame.getHeight() * ((float) viewWidth / frame.getWidth()));
    }

    /** Shorten a flight rect to the live source's last fully covered pixel row. */
    private static void clipFlightRectToSource(@NonNull Rect rect,
                                               @Nullable TerminalFrameSource frame) {
        final float painted = paintedTerminalHeight(rect.width(), rect.height(), frame);
        final int usable = Math.max(1, (int) Math.floor(painted));
        if (usable < rect.height()) rect.bottom = rect.top + usable;
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
     * translated relative to that rect. The source rect may include a temporary translation the
     * card already carried (for example the add-reflow hold); subtracting the resting anchor
     * preserves that offset on the first frame instead of collapsing it to zero. All rects are
     * captured once, so re-reading the card's current translation never feeds back into the
     * path. Terminals that own the terminal frame (the active card on opening, the selected card
     * on closing) are deliberately not moved by this helper.</p>
     */
    private static void moveCardWithTab(@NonNull View cardRoot, @NonNull Rect anchorTab,
                                        @NonNull Rect sourceTab, @NonNull Rect targetTab,
                                        float fraction) {
        final float f = SessionOverviewTransitionMath.clamp01(fraction);
        final boolean rtl = cardRoot.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final float sourceStart = rtl ? sourceTab.right : sourceTab.left;
        final float targetStart = rtl ? targetTab.right : targetTab.left;
        final float anchorStart = rtl ? anchorTab.right : anchorTab.left;
        cardRoot.setTranslationX(SessionOverviewTransitionMath.cardCompanionTranslation(
                anchorStart, sourceStart, targetStart, f));
        cardRoot.setTranslationY(SessionOverviewTransitionMath.cardCompanionTranslation(
                anchorTab.centerY(), sourceTab.centerY(), targetTab.centerY(), f));
    }

    /**
     * Put a flying tab and its attached close cross at the same height in the ghost layer.
     *
     * <p>While the tab and its card thumbnail are both visible they belong to the grid and pass
     * under the shrinking or expanding terminal window. A tab moving alone — before its
     * thumbnail has started to fade in or after it has faded out — stays above the terminal, as
     * it did before. The terminal frame has zero Z and the ghosts are appended after it, so
     * the paired tab needs a negative Z to actually be drawn below it.</p>
     */
    private static void applyTabGhostZ(@NonNull View tabGhost, @Nullable View closeGhost,
                                       boolean thumbnailVisible, float terminalElevation) {
        final float z = SessionOverviewTransitionMath.tabGhostZ(
                thumbnailVisible, terminalElevation);
        // Z only changes at the thumbnail/endpoint hand-offs; avoid touching the render node
        // on every frame when it is unchanged.
        if (tabGhost.getTranslationZ() != z) tabGhost.setTranslationZ(z);
        if (closeGhost != null && closeGhost.getTranslationZ() != z) {
            closeGhost.setTranslationZ(z);
        }
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
    private boolean cardChildTarget(@NonNull Card card, @NonNull View child,
                                    @NonNull int[] origin, @NonNull Rect out) {
        final ViewParent parent = card.root.getParent();
        if (parent instanceof View) {
            final View parentView = (View) parent;
            if (localRect(parentView, origin, mScratchLocation, out)) {
                final float left = out.left - parentView.getScrollX()
                        + card.root.getLeft();
                final float top = out.top - parentView.getScrollY()
                        + card.root.getTop();
                out.set(
                        Math.round(left + child.getLeft()),
                        Math.round(top + child.getTop()),
                        Math.round(left + child.getRight()),
                        Math.round(top + child.getBottom()));
                return true;
            }
        }

        if (!localRect(card.root, origin, mScratchLocation, out) || out.isEmpty()) return false;
        final float left = out.left - card.root.getTranslationX();
        final float top = out.top - card.root.getTranslationY();
        out.set(
                Math.round(left + child.getLeft()),
                Math.round(top + child.getTop()),
                Math.round(left + child.getRight()),
                Math.round(top + child.getBottom()));
        return true;
    }

    private boolean cardTabTarget(@NonNull Card card, @NonNull int[] origin,
                                  @NonNull Rect out) {
        return cardChildTarget(card, card.tab, origin, out);
    }

    /**
     * Install the decor cut-out at the bottom of the ghost layer. It is only needed when the
     * window is translucent: the terminal ghost paints its own background, so the fading window
     * colour must not also sit underneath it.
     */
    private void installBackdropHole() {
        mBackdropHole = null;
        if (mGhostLayer == null || !mActivity.isWallpaperVisibleBehindTerminal()) return;
        final OverviewBackdropHoleView hole = new OverviewBackdropHoleView(mActivity);
        hole.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mGhostLayer.addView(hole, 0);
        mBackdropHole = hole;
    }

    /** Build the flying views at their captured source positions and hide what they replace. */
    private void installTransitionVisuals(@NonNull Transition transition) {
        if (mGhostLayer == null || mContentArea == null) return;
        mGhostLayer.removeAllViews();
        installBackdropHole();

        final int[] origin = contentOrigin(mScratchOrigin);
        if (origin == null) return;

        Trace.beginSection("overview.open.install");
        if (transition.frame != null && transition.terminalSource != null) {
            final Card active = findCard(mActivity.getCurrentSession());
            if (active != null) {
                transition.activeCard = active;
                // The live source, not the card sitting in its final slot, owns the visual for
                // the duration of the flight.
                setCardOutlineSuppressed(active, true);
                active.preview.setAlpha(0f);
                active.preview.setVisibility(View.INVISIBLE);

                final TopCropImageView ghost = new TopCropImageView(mActivity);
                ghost.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                ghost.setImageDrawable(new LiveTerminalDrawable(transition.frame));
                // At progress zero this is the live terminal, which is square and has no
                // outline. The radius grows with the flight toward the card.
                transition.terminalOutline = new RoundedOutlineProvider(0f);
                ghost.setOutlineProvider(transition.terminalOutline);
                ghost.setClipToOutline(true);

                transition.terminalSourceLocal = offsetRect(transition.terminalSource, origin);
                layoutTerminalGhost(ghost, transition.terminalSourceLocal);
                mGhostLayer.addView(ghost);
                transition.terminalGhost = ghost;
                // The flight may not have painted its first frame yet. Cut the hole now so the
                // restored live decor never sits under the already-installed frame.
                if (mBackdropHole != null) {
                    mBackdropHole.setContent(transition.terminalSourceLocal, 0f,
                            mOverlayBackgroundColor, 0);
                }
            } else {
                releaseFrame(transition);
            }
        }

        // Keep every tab in the first frame. The opening scroll is restored only after the grid
        // has a real layout, so the set of cards that will be visible is not known yet. Removing
        // a tab from this copy before its target is known would make it vanish prematurely.
        installOpeningTabStripGhost(transition, origin);
        // The scrollbar thumb is a sibling of the terminal, not part of its live frame. Give it a
        // flying copy above that copy so it slides out and fades with the opening flight
        // instead of disappearing in the frame the live content is hidden.
        installOpeningScrollbarThumbGhost(transition, origin);
        if (transition.activeCard != null) {
            // The terminal ghost owns the active slot from the first frame; its real pill must
            // not sit there while that ghost is still at the terminal source.
            hideCardPill(transition, transition.activeCard);
        }

        // The live terminal, strip and controls are already transparent (see
        // hideLiveContentForOverview()). The live terminal frame and strip copies are
        // therefore the only copies of those surfaces while the window background stays visible.
        applyOpeningCardAlpha(0f);
        Trace.endSection();
    }

    /**
     * Capture the live scrollbar thumb at the same moment as the terminal frame. The thumb is
     * a sibling View, not part of the bitmap, and the keyboard/panel hide that follows can resize
     * the terminal before the flight is installed. Keeping the window rectangle and the drag
     * state here makes the opening copy match the pixels that were on screen, not whatever the
     * layout becomes a frame later.
     */
    private void captureOpeningScrollbarThumbSource(@NonNull Transition transition,
                                                    @Nullable TerminalView terminal) {
        if (terminal == null) return;
        final View thumb = terminal.getScrollbarThumbView();
        if (thumb == null || thumb.getVisibility() != View.VISIBLE
                || !thumb.isAttachedToWindow()
                || thumb.getWidth() <= 0 || thumb.getHeight() <= 0) {
            return;
        }
        // A pager mid-drag can clip part of the thumb; copy only the part the user could see.
        final Rect visible = new Rect();
        if (!thumb.getGlobalVisibleRect(visible)) return;
        // The thumb View keeps the full rest height, but its drawable bounds are the actual
        // capsule: anchored to the end the over-drag came from and shortened by the squash.
        // Reading them keeps the copy pixel-identical even when the handle is not at rest.
        final Rect capsule = new Rect();
        final Drawable background = thumb.getBackground();
        if (background != null) {
            capsule.set(background.getBounds());
        }
        if (capsule.isEmpty()) {
            capsule.set(0, 0, thumb.getWidth(), thumb.getHeight());
        }
        if (capsule.isEmpty()) return;
        final Rect thumbWindow = windowRectOf(thumb);
        capsule.offset(thumbWindow.left, thumbWindow.top);
        if (!capsule.intersect(visible)) return;
        transition.scrollbarThumbSourceWindow = capsule;
        transition.scrollbarThumbDragging = terminal.isScrollbarDragging();
    }

    /**
     * Copy the captured scrollbar thumb into the opening flight. The terminal frame never
     * contains the sibling thumb View, so without this copy the handle would vanish in the frame
     * the live content is hidden. The copy sits above that frame and slides right and fades out
     * over exactly the opening animation.
     */
    private void installOpeningScrollbarThumbGhost(@NonNull Transition transition,
                                                   @NonNull int[] origin) {
        if (mOverlay == null || transition.scrollbarThumbSourceWindow == null) return;
        final View ghost = createScrollbarThumbGhost(
                offsetRect(transition.scrollbarThumbSourceWindow, origin),
                transition.scrollbarThumbDragging);
        // Add the copy directly to the overlay, above the ghost layer: the lifted terminal ghost
        // carries the overview card's much larger Z, and a child Z only competes inside its own
        // parent. As a sibling of the ghost layer the thumb stays on top of the expanding terminal
        // even while that terminal is lifted.
        mOverlay.addView(ghost);
        transition.scrollbarThumbGhost = ghost;
        transition.scrollbarThumbShiftPx = scrollbarThumbShiftPx();
    }

    /**
     * The resting scrollbar thumb of {@code session}, in ghost-layer coordinates. Resolves the
     * destination before the pager switches pages, so the closing flight can fade the handle in
     * on the terminal it is returning to even when that page is not bound yet.
     */
    private boolean resolveScrollbarThumbRect(@NonNull TerminalSession session,
                                              @NonNull int[] origin,
                                              @NonNull Rect out) {
        final TerminalView geometryView = mActivity.getActiveTerminalView();
        if (geometryView == null || geometryView.getWidth() <= 0
                || geometryView.getHeight() <= 0) {
            return false;
        }
        final TerminalEmulator emulator = session.getEmulator();
        if (emulator == null) return false;

        final TerminalView targetView = resolveBoundTerminalView(session);
        final int topRow;
        final int savedTranscriptRows;
        if (targetView != null && targetView.getWidth() > 0 && targetView.getHeight() > 0
                && targetView.getCurrentSession() == session) {
            // The page is already bound and holds the live scroll position; no remap applies.
            topRow = targetView.getTopRow();
            savedTranscriptRows = 0;
        } else {
            // The page will restore this stored position when it binds. Use the same values and
            // let computeScrollbarThumbBounds() apply the proportional transcript remap.
            topRow = mActivity.getTextInputState().getScrollTopRow(session);
            savedTranscriptRows = mActivity.getTextInputState().getScrollTranscriptRows(session);
        }
        if (!geometryView.computeScrollbarThumbBounds(emulator, topRow, savedTranscriptRows,
                mScratchThumbRect)) {
            return false;
        }
        final Rect terminalWindow = windowRectOf(geometryView);
        out.set(
                terminalWindow.left + Math.round(mScratchThumbRect.left),
                terminalWindow.top + Math.round(mScratchThumbRect.top),
                terminalWindow.left + Math.round(mScratchThumbRect.right),
                terminalWindow.top + Math.round(mScratchThumbRect.bottom));
        offsetRect(out, origin, out);
        return !out.isEmpty();
    }

    @Nullable
    private TerminalView resolveBoundTerminalView(@NonNull TerminalSession session) {
        final SessionPagerManager pager = mActivity.getSessionPagerManager();
        final TermuxService service = mActivity.getTermuxService();
        if (pager == null || service == null) return null;
        final int index = service.getIndexOfSession(session);
        return index >= 0 ? pager.getPagerPageView(index) : null;
    }

    /**
     * One styled copy of the terminal scrollbar thumb. Alpha and translation are driven by the
     * flight; the fill, stroke and shadow match {@code applyScrollbarStyleTo()} exactly, including
     * the brighter no-outline look while the handle is held.
     */
    @NonNull
    private View createScrollbarThumbGhost(@NonNull Rect rect, boolean dragging) {
        final View ghost = new View(mActivity);
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final float density = mActivity.getResources().getDisplayMetrics().density;
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(rect.width() / 2f);
        shape.setColor(dragging
                ? mActivity.getFloatingButtonStroke()
                : mActivity.getFloatingButtonFill());
        shape.setStroke(dragging ? 0 : Math.max(1, Math.round(0.5f * density)),
                mActivity.getFloatingButtonStroke());
        ghost.setBackground(shape);
        FloatingShadow.apply(ghost,
                FloatingShadow.elevationPx(mActivity.getResources()),
                FloatingShadow.color(mActivity.getResources(), mActivity.isCachedSchemeLight(),
                        R.fraction.floating_shadow_alpha));
        ghost.setLayoutParams(frameParams(rect));
        return ghost;
    }

    private int scrollbarThumbShiftPx() {
        return Math.round(SessionOverviewTransitionMath.SCROLLBAR_THUMB_SHIFT_DP
                * mActivity.getResources().getDisplayMetrics().density);
    }

    /** The thumb copy is a sibling of the ghost layer, so removing the layer does not remove it. */
    private static void removeScrollbarThumbGhost(@Nullable View ghost) {
        if (ghost == null) return;
        final ViewParent parent = ghost.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(ghost);
    }

    /**
     * Build one tab ghost for every strip tab whose session has a card in the grid.
     *
     * <p>This runs after {@link #scrollToActiveCard()}, not while the strip sources are captured:
     * the final overview viewport depends on the grid layout and on the remembered offset, both
     * of which are known only here. A card outside that viewport still owns a real grid slot, so
     * its tab flies to that slot as well and is clipped by the overlay at the same viewport edge
     * as the card. Only the thumbnail coupling depends on visibility; see
     * {@link #isCardThumbnailVisible}.</p>
     */
    private void installOpeningTabFlights(@NonNull Transition transition) {
        if (transition.tabFlightsInstalled) return;
        if (mGhostLayer == null || mTabPanel == null || mScrollView == null
                || mGrid == null || !mGrid.isLaidOut()) return;

        final int[] origin = contentOrigin(mScratchOrigin);
        if (origin == null) return;

        final List<SourceTab> flying = new ArrayList<>();
        for (SourceTab source : transition.tabSources) {
            final Card card = findCard(source.session);
            if (card == null || card.root.getWidth() <= 0 || card.root.getHeight() <= 0) {
                continue;
            }
            flying.add(source);
        }

        if (!flying.isEmpty()) {
            for (SourceTab source : flying) {
                source.tabView.setVisibility(View.INVISIBLE);
                transition.hiddenTabs.add(source.tabView);
            }
            // The direct strip copy keeps every non-flying tab. Remove only the copies whose
            // flying ghost is about to be added, so the source slot never draws twice.
            final SessionOverviewTabStripGhostView strip = transition.tabPanelGhost;
            for (SourceTab source : flying) {
                final Card card = findCard(source.session);
                if (card == null) continue;
                if (strip != null) strip.removeTab(source.tabView);
                addOpeningTabGhost(transition, source, card, origin);
                hideCardPill(transition, card);
            }
        }
        transition.tabFlightsInstalled = true;
    }

    /**
     * Align a ghost's text insets with the laid-out content rect of the title it represents.
     *
     * <p>The strip title is a child of the pill with its own measured width; the ghost is one
     * text view whose padding must reproduce that width. Deriving the insets from the child's
     * real left and right edges also covers RTL and a close button whose margin changed.</p>
     */
    private static void applyTitleInsets(@NonNull TextView ghost, @NonNull View tab,
                                         @Nullable TextView title,
                                         int fallbackLeft, int fallbackTop,
                                         int fallbackRight, int fallbackBottom) {
        final int left;
        final int right;
        if (title != null && tab.getWidth() > 0 && title.getWidth() > 0) {
            left = Math.max(0, title.getLeft());
            right = Math.max(0, tab.getWidth() - title.getRight());
        } else {
            left = fallbackLeft;
            right = fallbackRight;
        }
        ghost.setPadding(left, fallbackTop, right, fallbackBottom);
    }

    /** Borrow the already-rendered title so line breaks cannot drift at the same pill width. */
    private static void copyRenderedTitle(@NonNull SessionOverviewTabTitleView ghost,
                                          @Nullable TextView source) {
        if (source == null) return;
        if (source.getText() != null) ghost.setPreRenderedTitle(source.getText());
        ghost.setLineSpacing(source.getLineSpacingExtra(), source.getLineSpacingMultiplier());
        if (source.getTypeface() != null) ghost.setTypeface(source.getTypeface());
    }

    /** Create the pill and close-cross ghosts for one strip tab that is part of the flight. */
    private void addOpeningTabGhost(@NonNull Transition transition, @NonNull SourceTab source,
                                    @NonNull Card card, @NonNull int[] origin) {
        final TextView sourceTitle = source.tabView.findViewById(R.id.session_tab_title);
        final SessionOverviewTabTitleView ghost =
                new SessionOverviewTabTitleView(mActivity);
        ghost.setMaxLines(card.tab.getMaxLines());
        ghost.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        // Match the strip title and the card title exactly. A different includeFontPadding
        // shifts the baseline on the first and last frame, which reads as the tab jumping
        // even though its bounds are continuous.
        ghost.setIncludeFontPadding(true);
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ghost.setFullTitle(card.fullTitle);
        ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, source.textSize);
        ghost.setTextColor(source.textColor);
        ghost.setPaintFlags(card.tab.getPaintFlags());
        if (source.typeface != null) ghost.setTypeface(source.typeface);
        ghost.setPaddingRelative(source.padStart, source.padTop,
                source.padEnd, source.padBottom);
        applyTitleInsets(ghost, source.tabView, sourceTitle,
                ghost.getPaddingLeft(), ghost.getPaddingTop(),
                ghost.getPaddingRight(), ghost.getPaddingBottom());
        // The source half of the flight must show exactly the strip's wraps. Borrowing the
        // rendered text avoids a second soft-break decision for the same outer width.
        copyRenderedTitle(ghost, sourceTitle != null ? sourceTitle : card.tab);

        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(source.backgroundColor);
        background.setCornerRadius(source.cornerRadius);
        ghost.setBackground(background);

        final boolean cardVisible = isCardThumbnailVisible(card, origin);
        final Rect sourceRect = offsetRect(source.bounds, origin);
        ghost.setLayoutParams(frameParams(sourceRect));
        // The terminal ghost has no elevation, but keep the tab one pixel above it so child
        // ordering cannot slide the tab under the shrinking terminal despite being added
        // later.
        ghost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(0f));
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
        closeGhost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(0f));
        mGhostLayer.addView(closeGhost);

        transition.tabGhosts.add(new TabGhost(card, ghost, background, sourceRect,
                closeGhost, closeSourceLocal, cardVisible));
    }

    /**
     * Hide a card's pill and close cross for the duration of a flight. The card keeps its slot;
     * the real views come back in cleanup so a cancelled flight never leaves an empty pill.
     */
    private static void hideCardPill(@NonNull Transition transition, @NonNull Card card) {
        if (transition.hiddenPills.contains(card)) return;
        card.tab.setVisibility(View.INVISIBLE);
        card.close.setVisibility(View.INVISIBLE);
        transition.hiddenPills.add(card);
    }

    /**
     * Build the direct tab-strip copy for an opening flight. The live strip is transparent while
     * the overview is open, so without this copy the panel would disappear on the first frame.
     * Flying tabs are removed from this copy individually when their own ghost is installed.
     *
     * @return true when the copy was installed
     */
    private boolean installOpeningTabStripGhost(@NonNull Transition transition,
                                                @NonNull int[] origin) {
        if (mGhostLayer == null || mTabPanel == null) return false;
        final Rect panelWindow = windowRectOf(mTabPanel);
        if (panelWindow.isEmpty()) return false;
        final SessionOverviewTabStripGhostView ghost =
                createStripGhostContainer(panelWindow, origin);
        if (ghost == null) return false;

        for (SourceTab source : transition.tabSources) {
            final Rect bounds = new Rect(source.bounds);
            bounds.offset(-panelWindow.left, -panelWindow.top);
            final Rect closeBounds = source.closeBounds != null
                    ? new Rect(source.closeBounds) : null;
            if (closeBounds != null) {
                closeBounds.offset(-panelWindow.left, -panelWindow.top);
            }
            addStripTabGhost(ghost, source.tabView, bounds, closeBounds,
                    source.backgroundColor, source.cornerRadius);
        }
        addStripPlusGhost(ghost, mActivity.findViewById(R.id.new_session_tab_button),
                panelWindow);
        ghost.setAlpha(1f);
        transition.tabPanelGhost = ghost;
        return true;
    }

    /** Create the clipped strip container and place it over the real panel. */
    @Nullable
    private SessionOverviewTabStripGhostView createStripGhostContainer(
            @NonNull Rect panelWindow, @NonNull int[] origin) {
        if (mGhostLayer == null || mTabPanel == null) return null;
        final Rect panelLocal = offsetRect(panelWindow, origin);
        if (panelLocal.isEmpty()) return null;
        final SessionOverviewTabStripGhostView ghost =
                new SessionOverviewTabStripGhostView(mActivity);
        ghost.setLayoutParams(frameParams(panelLocal));
        final View scroll = mTabPanel.findViewById(R.id.session_tabs_scroll);
        Rect clip = null;
        if (scroll != null && scroll.getWidth() > 0 && scroll.getHeight() > 0) {
            clip = windowRectOf(scroll);
            if (!clip.isEmpty()) {
                clip.offset(-panelWindow.left, -panelWindow.top);
            } else {
                clip = null;
            }
        }
        if (clip == null) {
            clip = new Rect(0, 0, panelLocal.width(), panelLocal.height());
        }
        ghost.setClipRect(clip);
        mGhostLayer.addView(ghost);
        return ghost;
    }

    /** Add one direct tab copy to the strip ghost. */
    private void addStripTabGhost(@NonNull SessionOverviewTabStripGhostView strip,
                                  @NonNull View sourceTab, @NonNull Rect tabBounds,
                                  @Nullable Rect closeBounds, int backgroundColor,
                                  int cornerRadius) {
        strip.addTab(sourceTab, createStripTabGhostView(sourceTab, tabBounds, closeBounds,
                backgroundColor, cornerRadius));
    }

    /**
     * Build one direct tab copy. The title borrows the source's rendered text, so soft breaks
     * and fade spans stay identical without a second layout decision. The copy is also used as
     * the standalone ghost for the newly added tab, which keeps its final frame exactly aligned
     * with the strip copy.
     */
    @NonNull
    private FrameLayout createStripTabGhostView(@NonNull View sourceTab,
                                                @NonNull Rect tabBounds,
                                                @Nullable Rect closeBounds,
                                                int backgroundColor, int cornerRadius) {
        final FrameLayout tab = new FrameLayout(mActivity);
        tab.setClipChildren(false);
        tab.setClickable(false);
        tab.setFocusable(false);
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tab.setLayoutParams(frameParams(tabBounds));

        final TextView sourceTitle = sourceTab.findViewById(R.id.session_tab_title);
        if (sourceTitle != null) {
            final SessionOverviewTabTitleView title =
                    new SessionOverviewTabTitleView(mActivity);
            title.setMaxLines(sourceTitle.getMaxLines());
            title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            title.setIncludeFontPadding(sourceTitle.getIncludeFontPadding());
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sourceTitle.getTextSize());
            title.setTextColor(sourceTitle.getCurrentTextColor());
            title.setPaintFlags(sourceTitle.getPaintFlags());
            title.setPaddingRelative(sourceTab.getPaddingStart(), sourceTab.getPaddingTop(),
                    sourceTab.getPaddingEnd(), sourceTab.getPaddingBottom());
            applyTitleInsets(title, sourceTab, sourceTitle,
                    title.getPaddingLeft(), title.getPaddingTop(),
                    title.getPaddingRight(), title.getPaddingBottom());
            copyRenderedTitle(title, sourceTitle);
            title.setBackground(tabBackground(backgroundColor, cornerRadius));
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(title, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            tab.setBackground(tabBackground(backgroundColor, cornerRadius));
        }

        if (closeBounds != null) {
            final View sourceClose = sourceTab.findViewById(R.id.session_tab_close);
            final ImageView close = new ImageView(mActivity);
            close.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            close.setImageResource(R.drawable.ic_close);
            final ColorFilter closeFilter = sourceClose instanceof ImageView
                    ? ((ImageView) sourceClose).getColorFilter() : null;
            if (closeFilter != null) {
                close.setColorFilter(closeFilter);
            } else {
                int tint = sourceTitle != null ? sourceTitle.getCurrentTextColor() : Color.WHITE;
                if (sourceClose instanceof ImageButton) {
                    final ColorStateList tintList =
                            ((ImageButton) sourceClose).getImageTintList();
                    if (tintList != null) tint = tintList.getDefaultColor();
                }
                close.setImageTintList(ColorStateList.valueOf(tint));
            }
            close.setAlpha(sourceClose != null ? sourceClose.getAlpha() : 0.7f);
            close.setClickable(false);
            close.setFocusable(false);
            close.setEnabled(false);
            close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final Rect localClose = new Rect(closeBounds);
            localClose.offset(-tabBounds.left, -tabBounds.top);
            tab.addView(close, frameParams(localClose));
        }
        return tab;
    }

    /** Add the strip + as a direct drawable copy; it has no separate flight slot to remove. */
    private void addStripPlusGhost(@NonNull SessionOverviewTabStripGhostView strip,
                                   @Nullable View plus, @NonNull Rect panelWindow) {
        if (!(plus instanceof ImageView) || plus.getVisibility() != View.VISIBLE
                || plus.getWidth() <= 0 || plus.getHeight() <= 0) {
            return;
        }
        final Rect bounds = windowRectOf(plus);
        if (bounds.isEmpty()) return;
        bounds.offset(-panelWindow.left, -panelWindow.top);
        final ImageView ghost = createDirectTabPlusGhost((ImageView) plus);
        if (ghost == null) return;
        ghost.setLayoutParams(frameParams(bounds));
        strip.addView(ghost);
    }

    @NonNull
    private GradientDrawable tabBackground(int color, int cornerRadius) {
        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(color);
        background.setCornerRadius(cornerRadius);
        return background;
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
        if (transition.scrollbarThumbGhost != null
                && (!transition.scrollbarThumbGhost.isLaidOut()
                || transition.scrollbarThumbGhost.getWidth() <= 0
                || transition.scrollbarThumbGhost.getHeight() <= 0)) {
            return false;
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
        Trace.beginSection("overview.open.start");
        transition.started = true;
        if (mGrid != null) mGrid.removeCallbacks(mStartTransitionRunnable);

        // The active card may be in a row below the first 2x2 page. Put it on screen before the
        // first frame so the terminal does not fly toward a target outside the visible strip.
        // By now the grid has a real layout, so it is safe to retire the restore preference.
        scrollToActiveCard();
        mRestoreScrollOnOpen = false;
        revealPendingCard();
        // The overview viewport is final now, so every tab can be given its real grid-slot
        // destination. Cards outside the viewport still fly their tabs to/from that slot.
        installOpeningTabFlights(transition);
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
        Trace.endSection();
    }

    /**
     * Advance the opening flight. The terminal moves continuously; each tab moves and fades out
     * at source size, is transformed once while invisible, then moves the rest of the way and
     * fades in at card size.
     */
    private void applyTransitionProgress(@NonNull Transition transition, float progress) {
        if (mTransition != transition) return;
        Trace.beginSection("overview.open.apply");
        final float p = SessionOverviewTransitionMath.clamp01(progress);

        if (transition.backgroundTransition) {
            mActivity.applyWindowBackgroundColor(SessionOverviewTransitionMath.blendColor(
                    transition.backgroundStart, transition.backgroundEnd, p));
        }
        // The scrollbar thumb slides right and fades out over the same 300 ms as the flight.
        if (transition.scrollbarThumbGhost != null) {
            transition.scrollbarThumbGhost.setTranslationX(
                    SessionOverviewTransitionMath.openingScrollbarThumbTranslation(
                            p, transition.scrollbarThumbShiftPx));
            transition.scrollbarThumbGhost.setAlpha(
                    SessionOverviewTransitionMath.openingScrollbarThumbAlpha(p));
        }
        applyOpeningCardAlpha(p);
        // The active card's outline fades in over the second half of the opening flight instead
        // of snapping on when the ghost is removed.
        if (transition.activeCard != null) {
            transition.activeCard.root.setBorderAlpha(
                    SessionOverviewTransitionMath.clamp01((p - 0.5f) / 0.5f));
        }

        final int[] origin = contentOrigin(mScratchOrigin);
        Rect hole = null;
        float holeCornerRadius = 0f;

        Trace.beginSection("overview.open.geometry");
        if (transition.frame != null) transition.frame.syncTransientState();

        if (transition.terminalGhost != null && transition.activeCard != null && origin != null) {
            // The active card's preview is the terminal flight's destination, not a companion
            // of the active tab. Read it without the card's temporary translation so the
            // ghost always lands in the resting grid slot.
            final boolean targetReady = cardChildTarget(
                    transition.activeCard, transition.activeCard.preview, origin,
                    mScratchTargetRect);
            if (targetReady && transition.terminalSourceLocal != null) {
                lerpRect(mScratchGhostRect, transition.terminalSourceLocal,
                        mScratchTargetRect, p);
                hole = mScratchGhostRect;
                if (p < 1f) clipFlightRectToSource(hole, transition.frame);
                layoutTerminalGhost(transition.terminalGhost, hole);
            }
            // The frame starts square and grows into the rounded card. The card's own outline
            // stays suppressed until the frame lands, so there is never a second outline
            // parked in the grid slot.
            holeCornerRadius = SessionOverviewTransitionMath.lerp(
                    0f, transition.activeCard.thumbnailCornerRadius, p);
            if (transition.terminalOutline != null) {
                transition.terminalOutline.setRadius(holeCornerRadius);
                if (transition.terminalGhost != null) {
                    transition.terminalGhost.invalidateOutline();
                }
            }
            applyFlightBorder(transition.terminalGhost, holeCornerRadius,
                    SessionOverviewTransitionMath.clamp01((p - 0.5f) / 0.5f));
            final float ghostAlpha = SessionOverviewTransitionMath.ghostAlpha(p);
            transition.terminalGhost.setAlpha(ghostAlpha);
            // The hole stays until cleanup. Near the end the cut-out is tinted back to the
            // overview colour, so the real preview lands on the same backdrop the ghost had.
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

        if (mBackdropHole != null) {
            final int fillAlpha = Math.round(255f
                    * mOverviewBackdropAlpha
                    * overviewFillFraction(p, true));
            mBackdropHole.setContent(hole, holeCornerRadius,
                    mOverlayBackgroundColor, fillAlpha);
        }
        Trace.endSection();
        if (origin == null) {
            Trace.endSection();
            return;
        }
        Trace.beginSection("overview.open.tabs");
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

            // The active card belongs to the terminal frame, which is already shrinking into
            // its final slot. A card that is visible in the viewport follows its tab so the
            // thumbnail moves with it. An off-screen card stays in its slot: only its tab flies
            // to that slot, so an unseen thumbnail can never be dragged into view.
            final boolean companion = SessionOverviewTransitionMath.tabCarriesCard(
                    ghost.cardVisible, ghost.card == transition.activeCard);
            // From the moment the thumbnail starts to appear, the tab and card are one pair.
            // Let the terminal window cover that pair; a tab still travelling without its
            // thumbnail stays above the terminal. The active card's tab is never reparented.
            final boolean thumbnailVisible = companion
                    && SessionOverviewTransitionMath.openingCardAlpha(p) > 0f;
            applyTabGhostZ(ghost.view, ghost.closeGhost, thumbnailVisible, 0f);
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
                final boolean liveTargetReady = cardTabTarget(
                        ghost.card, origin, mScratchTargetRect);
                if (liveTargetReady) {
                    moveGhostAlongPath(ghost.view, ghost.sourceBounds,
                            ghost.sourceBounds, mScratchTargetRect, sourcePath);
                    if (companion) {
                        moveCardWithTab(ghost.card.root, mScratchTargetRect,
                                ghost.sourceBounds, mScratchTargetRect, sourcePath);
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
        Trace.endSection();
        Trace.endSection();
    }

    /**
     * Put one opening tab ghost at its final card position and style.
     *
     * <p>This runs at most once per ghost, while the ghost is invisible. The card's already
     * rendered title is borrowed verbatim instead of being rebuilt for the ghost's padding or
     * for an intermediate width. After this, the fade-in only changes the ghost's start edge,
     * vertical centre and alpha. Returns false when the card has not been laid out yet, in which
     * case the ghost stays invisible and the call is retried on the next frame.</p>
     */
    private boolean applyOpeningTabTarget(@NonNull TabGhost ghost, @NonNull int[] origin) {
        if (!cardTabTarget(ghost.card, origin, mScratchTargetRect)
                || mScratchTargetRect.isEmpty()) return false;

        ghost.targetBounds = new Rect(mScratchTargetRect);
        final TextView finalPill = ghost.card.tab;
        ghost.view.setLayoutParams(frameParams(mScratchTargetRect));
        ghost.view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                finalPill.getTextSize());
        ghost.view.setTextColor(finalPill.getCurrentTextColor());
        ghost.view.setPaintFlags(finalPill.getPaintFlags());
        ghost.background.setColor(ghost.card.tabBackgroundColor);
        ghost.background.setCornerRadius(mTabCornerRadius);
        ghost.view.setPadding(finalPill.getPaddingLeft(), finalPill.getPaddingTop(),
                finalPill.getPaddingRight(), finalPill.getPaddingBottom());
        // Borrow the card's rendered title verbatim. Re-rendering the full string at the ghost's
        // padding can choose different soft breaks even when the target pill is identical.
        copyRenderedTitle(ghost.view, finalPill);

        if (ghost.closeGhost != null
                && cardChildTarget(ghost.card, ghost.card.close, origin, mScratchCloseRect)
                && !mScratchCloseRect.isEmpty()) {
            ghost.closeGhost.setLayoutParams(frameParams(mScratchCloseRect));
            ghost.closeGhost.setImageTintList(ColorStateList.valueOf(ghost.card.textColor));
            ghost.closeTargetApplied = true;
        }
        return true;
    }

    private void finishTransition(@NonNull Transition transition) {
        if (mTransition != transition) return;
        Trace.beginSection("overview.open.finish");
        applyTransitionProgress(transition, 1f);
        final Card active = transition.activeCard;
        cleanupTransition(transition);
        // The live ghost may have shown output that the card bitmap did not have yet. Repaint
        // the slot from the same emulator now that the card is visible again.
        if (active != null && mCards.contains(active)) {
            active.previewDirty = true;
            requestCardPreview(active);
        }
        Trace.endSection();
    }

    /**
     * Fade the grid as one group instead of issuing an alpha change for every card. The active
     * card draws nothing during the flight: its preview, pill and border are suppressed while
     * the terminal frame owns its slot, so a single group alpha is visually identical to
     * fading only its neighbours.
     */
    private void applyOpeningCardAlpha(float progress) {
        if (mGrid == null) return;
        mGrid.setAlpha(SessionOverviewTransitionMath.openingCardAlpha(progress));
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
        Trace.beginSection("overview.open.cleanup");
        mTransition = null;
        if (mGrid != null) mGrid.removeCallbacks(mStartTransitionRunnable);
        if (mOverlay != null && transition.startFallback != null) {
            mOverlay.removeCallbacks(transition.startFallback);
            transition.startFallback = null;
        }

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
            SessionOverviewCardView.resetVisual(card.root);
        }
        if (mGrid != null) mGrid.setAlpha(1f);
        if (transition.activeCard != null) {
            transition.activeCard.preview.setAlpha(1f);
            transition.activeCard.preview.setVisibility(View.VISIBLE);
            setCardOutlineSuppressed(transition.activeCard, false);
        }
        if (transition.backgroundTransition) {
            // The flight may be cancelled before its first frame. The overview backdrop must
            // still be the terminal colour, not the app colour captured at the start.
            mActivity.applyOverviewWindowBackground(transition.backgroundEnd);
        }
        if (transition.terminalGhost != null) {
            transition.terminalGhost.setImageDrawable(null);
        }
        if (mGhostLayer != null) mGhostLayer.removeAllViews();
        mBackdropHole = null;
        releaseFrame(transition);
        transition.terminalGhost = null;
        transition.terminalOutline = null;
        transition.tabPanelGhost = null;
        removeScrollbarThumbGhost(transition.scrollbarThumbGhost);
        transition.scrollbarThumbGhost = null;
        Trace.endSection();
    }

    /** Release sources captured for a transition that was never installed. */
    private static void discardTransition(@Nullable Transition transition) {
        if (transition == null) return;
        releaseFrame(transition);
        transition.tabSources.clear();
    }

    private static void releaseFrame(@Nullable Transition transition) {
        if (transition == null || transition.frame == null) return;
        transition.frame.release();
        transition.frame = null;
        transition.session = null;
    }

    // ── Closing transition ─────────────────────────────────────────────────

    /**
     * Prepare the exact reverse of the opening terminal flight: the selected card grows back into
     * the terminal grid.
     *
     * <p>Both ends of the flight are the glyph grid, not the whole {@link TerminalView}. The
     * view's background margins are absent from the card, so including them would make the
     * expansion change aspect ratio and jump when the live terminal takes over. The frame is a
     * fixed-size {@link TerminalFrameSource} painted through {@link TerminalPreviewRenderer},
     * which keeps the terminal background at the configured alpha and lets output, cursor and
     * grid changes repaint the flight. The decor cut-out keeps the fading overview colour behind
     * it from stacking a second layer. A background session has no attached terminal view, so its
     * emulator is used directly; no second TerminalView is created and no bitmap fallback is
     * kept.</p>
     */
    @Nullable
    private ClosingTransition prepareClosingTransition(@NonNull Card card,
                                                       @Nullable LiftStart liftStart) {
        if (mOverlay == null || mGhostLayer == null || mContentArea == null) return null;
        if (!card.root.isLaidOut() || card.root.getWidth() <= 0 || card.root.getHeight() <= 0) {
            return null;
        }

        final int[] origin = contentOrigin(mScratchOrigin);
        if (origin == null) return null;
        final Rect cardStart = liftStart != null
                ? new Rect(liftStart.previewRect) : localRect(card.preview, origin);
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

        final TerminalEmulator targetEmulator = card.session.getEmulator();
        if (targetEmulator == null) return null;

        // A background page keeps the grid it had when it was last active. Bring it to the
        // viewport it is about to occupy before the source is created, so the live raster and
        // the terminal that takes over at progress 1 share one cell grid. This is the same
        // updateSize() the pager would run after the switch; doing it while the overview still
        // covers the terminal only removes the one-frame aspect jump at the hand-off.
        if (hasTerminalContent && terminal != null && card.session.isRunning()) {
            final ViewportGrid targetGrid = TerminalPreviewRenderer.viewportGrid(
                    terminal.getWidth(), terminal.getHeight(),
                    previewTextSize(), previewTypeface());
            if (targetGrid != null && !targetGrid.matches(targetEmulator)) {
                card.session.updateSize(targetGrid.columns, targetGrid.rows,
                        targetGrid.cellWidthPx, targetGrid.cellHeightPx);
            }
        }

        // The target terminal is live even when its page is not current. Output arriving during
        // the expansion repaints this raster through onSessionTextChanged(), and the fixed size
        // keeps the flight geometry stable.
        TerminalFrameSource frame;
        try {
            frame = new TerminalFrameSource(card.session, mRasterizer,
                    terminalTarget.width(), terminalTarget.height(),
                    previewTextSize(), previewTypeface(),
                    previewBackgroundTransparency(),
                    () -> scrollTopRow(card.session));
        } catch (RuntimeException ignored) {
            return null;
        }

        final ClosingTransition transition = new ClosingTransition(card, frame,
                cardStart, terminalTarget);
        transition.startCornerRadius = card.thumbnailCornerRadius;
        if (liftStart != null) {
            transition.liftedStart = true;
            transition.startAlpha = liftStart.alpha;
            transition.startScale = liftStart.scale;
            transition.startLiftZ = liftStart.liftZ;
            transition.shadowColor = liftStart.shadowColor;
            final android.content.res.Resources res = mActivity.getResources();
            transition.startElevationPx = FloatingShadow.overviewCardElevationPx(res);
            if (liftStart.addButtonSource != null) {
                final TerminalAddFlight flight = mTerminalAddFlight;
                if (flight == null || flight.overlayGhost.getParent() == null) {
                    transition.frame.release();
                    transition.frame = null;
                    return null;
                }
                transition.addButtonSource = liftStart.addButtonSource;
                transition.addButtonSourceGhost = flight.overlayGhost;
                transition.startCornerRadius = liftStart.addButtonSource.radius;
                transition.startElevationPx = liftStart.addButtonSource.elevationPx;
            }
        }
        if (!installClosingVisuals(transition)) {
            transition.frame.release();
            transition.frame = null;
            return null;
        }
        // The terminal frame has no thumb; reverse the opening copy above it during the
        // expansion. A target without a scrollbar has no thumb to bring back.
        installClosingScrollbarThumbGhost(transition, origin);
        // Install the flying pills first: each one hides its real target tab, so the panel
        // copy built afterwards has an empty slot for every ghost to land in instead of
        // drawing the returning tabs twice. The ghosts are brought back above the copy below.
        installClosingTabVisuals(transition, origin);
        // The add-button source has no card-tab flight, so its new strip tab needs its own
        // target-phase ghost. Install it after the other ghosts (which hide their real target
        // tabs) and before the panel copy, so the copy has an empty slot for it too.
        installClosingNewTabGhost(transition, origin);
        // Hide the strip + before the panel copy, exactly like a returning tab: the ghost
        // is the only copy until it lands in the empty slot. The add-button source has already
        // consumed the button, so the strip + stays in the panel copy instead.
        if (transition.addButtonSource == null) prepareTabAddButtonFlight(true);
        installClosingTabStripGhost(transition, origin);
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            ghost.view.bringToFront();
        }
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            if (ghost.closeGhost != null) ghost.closeGhost.bringToFront();
        }
        if (transition.newTabGhost != null) {
            transition.newTabGhost.view.bringToFront();
        }
        if (transition.addButtonSourceGhost != null) {
            // The source button owns the bottom-right corner for the first quarter. The thumb
            // ghost is a later direct child of the overlay, so bring the button back above it.
            transition.addButtonSourceGhost.bringToFront();
        }
        return transition;
    }

    private boolean installClosingVisuals(@NonNull ClosingTransition transition) {
        if (mGhostLayer == null) return false;
        mGhostLayer.removeAllViews();
        installBackdropHole();

        final TopCropImageView ghost = new TopCropImageView(mActivity);
        ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ghost.setImageDrawable(new LiveTerminalDrawable(transition.frame));
        final boolean addButtonSource = transition.addButtonSource != null;
        // The add-button source is square while the terminal frame has its own aspect. Cover the
        // square with the image so no transparent strip appears below it during the hand-off; the
        // scale converges to an exact fit when the ghost reaches the terminal rect.
        ghost.setCenterCrop(addButtonSource);
        // The terminal image is invisible on the first add-button frame; the source ghost owns
        // the pixels. The view itself stays opaque so the elevation shadow is already present.
        ghost.setAlpha(addButtonSource ? 1f : 0f);
        if (addButtonSource) ghost.setImageAlpha(0);
        // The card's own outline is suppressed while this frame expands; the frame starts
        // with the card's rounded shape so the hand-off at progress zero is invisible. The add
        // button source uses its own circular radius.
        transition.terminalOutline = new RoundedOutlineProvider(
                transition.startCornerRadius);
        ghost.setOutlineProvider(transition.terminalOutline);
        ghost.setClipToOutline(true);
        layoutTerminalGhost(ghost, transition.cardStartLocal);

        // The hover shadow transfers to the ghost so it does not vanish the moment the card's
        // preview is hidden. applyClosingProgress fades its colour alpha out with the expansion.
        // clipBounds would clip that elevation shadow to the bitmap rectangle; the rounded
        // outline already clips the scaled image, so let the shadow render outside it.
        if (transition.liftedStart) {
            ghost.setSkipClipBounds(true);
            FloatingShadow.apply(ghost, transition.startElevationPx,
                    transition.shadowColor);
        }

        mGhostLayer.addView(ghost);
        transition.terminalGhost = ghost;
        if (addButtonSource) {
            // The source ghost is a sibling above the whole ghost layer for the crossfade. Keep
            // its shadow until the terminal ghost is laid out; if that happens before the first
            // frame, applyClosingProgress hands the shadow over in the same synchronous pass, so
            // the first drawn frame still has exactly one shadow.
            transition.addButtonSourceGhost.bringToFront();
            // The animator callback runs before the traversal that lays the ghost out, so the
            // next applyClosingProgress() can still see isLaidOut()==false. Hand the shadow over
            // from the layout callback instead: it runs after layout and before the first draw,
            // which is the only moment where both shadows could otherwise be painted together.
            ghost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (ghost.getWidth() <= 0 || ghost.getHeight() <= 0) return;
                    ghost.removeOnLayoutChangeListener(this);
                    handOffAddButtonSourceShadow(transition);
                }
            });
        }
        // Do not cut the decor yet: the ghost is still transparent until the first progress
        // frame, and CLEAR would erase the card preview underneath it for one frame.
        setCardOutlineSuppressed(transition.card, true);
        transition.tabBaseAlpha = transition.card.tab.getAlpha();
        transition.closeBaseAlpha = transition.card.close.getAlpha();
        return true;
    }

    /**
     * The reverse of the opening thumb copy: start shifted right and transparent, land on the
     * target terminal's resting handle exactly when the expansion finishes. Only a target that
     * actually shows a scrollbar gets a copy, so closing into a short transcript has no orphan.
     */
    private void installClosingScrollbarThumbGhost(@NonNull ClosingTransition transition,
                                                   @NonNull int[] origin) {
        if (mOverlay == null) return;
        if (!resolveScrollbarThumbRect(transition.session, origin, mScratchWindowRect)) return;
        final TerminalView targetView = resolveBoundTerminalView(transition.session);
        final boolean dragging = targetView != null
                && targetView.getCurrentSession() == transition.session
                && targetView.isScrollbarDragging();
        final View ghost = createScrollbarThumbGhost(mScratchWindowRect, dragging);
        ghost.setAlpha(0f);
        ghost.setTranslationX(scrollbarThumbShiftPx());
        // Sibling of the ghost layer, so the lifted terminal ghost's larger Z cannot cover it.
        mOverlay.addView(ghost);
        transition.scrollbarThumbGhost = ghost;
        transition.scrollbarThumbShiftPx = scrollbarThumbShiftPx();
    }

    /**
     * Draw the final tab strip over the overview so it can fade in during the expansion.
     *
     * <p>The strip was already selected and scrolled behind the overlay before this copy was
     * built, so the first visible frame after the overlay disappears matches it exactly. Every
     * returning target tab is hidden by {@link #installClosingTabVisuals} before this point;
     * those slots stay empty so the flying pills are the only copies drawn there until they
     * land.</p>
     */
    private void installClosingTabStripGhost(@NonNull ClosingTransition transition,
                                             @NonNull int[] origin) {
        if (mGhostLayer == null || mTabPanel == null) return;
        final Rect panelWindow = windowRectOf(mTabPanel);
        if (panelWindow.isEmpty()) return;
        final SessionOverviewTabStripGhostView ghost =
                createStripGhostContainer(panelWindow, origin);
        if (ghost == null) return;

        final ViewGroup tabs = mTabPanel.findViewById(R.id.session_tabs);
        final TermuxSessionTabsController tabsController =
                mActivity.getTermuxSessionTabsController();
        if (tabs != null) {
            for (int i = 0; i < tabs.getChildCount(); i++) {
                final View child = tabs.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE
                        || child.getWidth() <= 0 || child.getHeight() <= 0) {
                    continue;
                }
                if (child.getId() == R.id.new_session_tab_button) {
                    addStripPlusGhost(ghost, child, panelWindow);
                    continue;
                }
                if (child.getId() != R.id.session_tab_root || tabsController == null) {
                    continue;
                }
                final Rect bounds = windowRectOf(child);
                if (bounds.isEmpty()) continue;
                bounds.offset(-panelWindow.left, -panelWindow.top);
                final View close = child.findViewById(R.id.session_tab_close);
                Rect closeBounds = null;
                if (close != null && close.getVisibility() == View.VISIBLE) {
                    closeBounds = windowRectOf(close);
                    if (!closeBounds.isEmpty()) {
                        closeBounds.offset(-panelWindow.left, -panelWindow.top);
                    } else {
                        closeBounds = null;
                    }
                }
                addStripTabGhost(ghost, child, bounds, closeBounds,
                        tabsController.getTabBackgroundColor(child),
                        tabsController.getTabCornerRadiusPx());
            }
        }
        ghost.setAlpha(0f);
        transition.tabPanelGhost = ghost;
    }

    /**
     * Reverse the opening tab flight for every card in the grid.
     *
     * <p>A tab whose card is scrolled outside the overview viewport still returns to its strip
     * slot from that card's real grid slot. The ghost starts off screen and is clipped by the
     * overlay at the same viewport edge as the card, so the panel never loses a tab that simply
     * had no visible card. Only a visible thumbnail is carried with its tab; an off-screen card
     * stays in its slot so it cannot be dragged into view by the flight.</p>
     *
     * <p>Each ghost moves and fades out on its card, is transformed once while invisible, then
     * moves into its strip slot and fades in. All target tabs are hidden before the panel
     * copy is built, so the copy and the ghosts never draw the same tab at once.</p>
     */
    private void installClosingTabVisuals(@NonNull ClosingTransition transition,
                                          @NonNull int[] origin) {
        if (mGhostLayer == null) return;
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs == null) return;
        // One pixel above the terminal ghost so returning tabs stay over the expanding frame.
        final float terminalZ = transition.liftedStart ? transition.startLiftZ + 1f : 0f;
        final float fallbackTextSize = mActivity.getResources()
                .getDimensionPixelSize(R.dimen.terminal_tab_text_size);

        for (Card card : mCards) {
            // The add-button source has no card tab in flight: the new tab is already selected
            // and scrolled into the strip copy, so it must stay visible there.
            if (transition.addButtonSource != null && card == transition.card) continue;
            // The tab always flies; this only decides whether its thumbnail travels with it.
            final boolean cardVisible = isCardThumbnailVisible(card, origin);
            // A card selected from the opening gesture is still lifted. Its tab ghost must start
            // from the enlarged, moved pill the finger was actually hovering, not the layout slot.
            final boolean lifted = card == transition.card && transition.liftedStart;
            final float scale = lifted ? transition.startScale : 1f;
            final Rect startBounds = lifted
                    ? visualChildRect(card, card.tab, origin)
                    : localRect(card.tab, origin);
            if (startBounds == null || startBounds.isEmpty()) continue;

            // moveCardWithTab() translates the card relative to its resting tab slot, not
            // relative to the captured visual start. A card that still carries the add-reflow
            // translation must keep it on the first closing frame, otherwise the path delta
            // would zero it and the whole grid would snap to its new top-aligned slots. The
            // ghost starts at the visual rect; the card path is anchored to the layout slot.
            final Rect anchorBounds = new Rect();
            if (!cardChildTarget(card, card.tab, origin, anchorBounds)
                    || anchorBounds.isEmpty()) {
                anchorBounds.set(startBounds);
            }

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
            ghost.setLineSpacing(card.tab.getLineSpacingExtra() * scale,
                    card.tab.getLineSpacingMultiplier());
            ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ghost.setFullTitle(card.fullTitle);
            ghost.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    card.tab.getTextSize() * scale);
            ghost.setTextColor(card.tab.getCurrentTextColor());
            ghost.setPaintFlags(targetTitle != null
                    ? targetTitle.getPaintFlags() : card.tab.getPaintFlags());
            final Typeface targetTypeface = targetTitle != null
                    ? targetTitle.getTypeface() : card.tab.getTypeface();
            if (targetTypeface != null) ghost.setTypeface(targetTypeface);
            // Physical insets mirror the card title's own content rect in both LTR and RTL.
            ghost.setPadding(
                    Math.round(card.tab.getPaddingLeft() * scale),
                    Math.round(card.tab.getPaddingTop() * scale),
                    Math.round(card.tab.getPaddingRight() * scale),
                    Math.round(card.tab.getPaddingBottom() * scale));
            // The source half of the closing flight is the card itself: keep its exact wraps.
            copyRenderedTitle(ghost, card.tab);
            // The copied breaks stay fixed, but the lifted start scales the text and its leading.
            ghost.setLineSpacing(card.tab.getLineSpacingExtra() * scale,
                    card.tab.getLineSpacingMultiplier());

            final GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setColor(card.tabBackgroundColor);
            background.setCornerRadius(mTabCornerRadius * scale);
            ghost.setBackground(background);
            ghost.setLayoutParams(frameParams(startBounds));
            // Keep the returning tab above the expanding terminal for the whole flight.
            ghost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(terminalZ));
            mGhostLayer.addView(ghost);

            final float targetTextSize = targetTitle != null
                    ? targetTitle.getTextSize() : fallbackTextSize;
            final int targetTextColor = targetTitle != null
                    ? targetTitle.getCurrentTextColor() : card.tab.getCurrentTextColor();
            final int targetBackgroundColor = tabs.getTabBackgroundColor(targetTab);
            final int targetCornerRadius = tabs.getTabCornerRadiusPx();
            // Use the strip title's measured content rect, not the pill's start padding plus a
            // reconstructed close footprint. The two are equal only while the close margin and
            // all child widths are already settled; the real title is the source of truth.
            final int targetPadStart = targetTitle != null && targetTab.getWidth() > 0
                    && targetTitle.getWidth() > 0
                    ? Math.max(0, targetTitle.getLeft()) : targetTab.getPaddingStart();
            final int targetPadTop = targetTab.getPaddingTop();
            final int targetPadEnd = targetTitle != null && targetTab.getWidth() > 0
                    && targetTitle.getWidth() > 0
                    ? Math.max(0, targetTab.getWidth() - targetTitle.getRight())
                    : targetTab.getPaddingEnd() + closeFootprint;
            final int targetPadBottom = targetTab.getPaddingBottom();

            final Rect startCloseBounds = lifted
                    ? visualChildRect(card, card.close, origin)
                    : localRect(card.close, origin);
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
                // startCloseBounds is already the scaled visual rect; CENTER_INSIDE fits the
                // drawable to it, so scaling the view again would enlarge the icon a second time.
                closeGhost.setAlpha(0.7f);
                closeGhost.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                closeGhost.setLayoutParams(frameParams(startCloseBounds));
                closeGhost.setTranslationZ(SessionOverviewTransitionMath.tabGhostZ(terminalZ));
                mGhostLayer.addView(closeGhost);
            }

            final ClosingTabGhost tabGhost = new ClosingTabGhost(card, ghost, background,
                    startBounds, anchorBounds, targetTextSize, targetTextColor,
                    targetBackgroundColor,
                    targetCornerRadius, targetPadStart, targetPadTop, targetPadEnd,
                    targetPadBottom,
                    targetTitle != null ? targetTitle.getPaintFlags() : card.tab.getPaintFlags(),
                    targetTab, targetBounds, closeGhost,
                    targetClose, targetCloseBounds, targetCloseTint);
            tabGhost.cardVisible = cardVisible;
            tabGhost.tabBaseAlpha = card.tab.getAlpha();
            tabGhost.closeBaseAlpha = card.close.getAlpha();
            tabGhost.targetTabVisibility = targetTab.getVisibility();
            transition.tabGhosts.add(tabGhost);

            // The real compact pill is hidden on the first animated frame, not here: a ghost has
            // not been measured yet at progress zero, so hiding the real pill now would leave the
            // card's top edge empty for one frame. Keep the strip slot hidden now, though — the
            // panel copy taken afterwards must have an empty landing spot for this ghost. The panel copy
            // taken after this method therefore has a gap at every landing spot, and cleanup
            // restores all of them before the overlay is removed.
            targetTab.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Reserve and animate the strip slot of the tab created by the add-button release.
     *
     * <p>The add-button source has no card-tab flight: its new card is the terminal source and
     * is hidden. The tab is already selected and scrolled into the final strip geometry before
     * the copy. This ghost occupies the empty slot in that copy and appears with the
     * same target fade as the returning tabs, so the strip reads as one coordinated return
     * rather than a static tab fading in with the panel.</p>
     *
     * <p>If the tab is not fully visible or not laid out, leave it in the copy. A ghost
     * drawn outside the panel would be worse than the ordinary hand-off.</p>
     */
    private void installClosingNewTabGhost(@NonNull ClosingTransition transition,
                                           @NonNull int[] origin) {
        if (mGhostLayer == null || mTabPanel == null
                || transition.addButtonSource == null) return;
        final TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs == null) return;

        final View targetTab = tabs.findTabForSession(transition.session);
        if (targetTab == null || !targetTab.isLaidOut() || !targetTab.isShown()
                || targetTab.getWidth() <= 0 || targetTab.getHeight() <= 0) return;
        final Rect targetBounds = localRect(targetTab, origin);
        if (targetBounds == null || targetBounds.isEmpty()) return;
        // Check the scroll viewport, not just the panel. A tab can be inside the panel but
        // clipped by the horizontal scroll container; a ghost drawn there would not match the
        // panel copy's clipped edge.
        final View tabsScroll = mTabPanel.findViewById(R.id.session_tabs_scroll);
        final View clip = tabsScroll != null ? tabsScroll : mTabPanel;
        final Rect clipWindow = windowRectOf(clip);
        if (clipWindow.isEmpty()) return;
        final Rect clipLocal = offsetRect(clipWindow, origin);
        if (!clipLocal.contains(targetBounds)) return;

        final View targetClose = targetTab.findViewById(R.id.session_tab_close);
        final boolean closeVisible = targetClose != null
                && targetClose.getVisibility() == View.VISIBLE;
        Rect closeBounds = null;
        if (closeVisible) {
            closeBounds = localRect(targetClose, origin);
            // A title-only ghost would lose the cross at progress 1. Leave the real tab in the
            // copy instead of accepting a visibly incomplete final frame.
            if (closeBounds == null || closeBounds.isEmpty()) return;
        }

        final float terminalZ = transition.liftedStart
                ? SessionOverviewTransitionMath.tabGhostZ(transition.startLiftZ + 1f)
                : SessionOverviewTransitionMath.tabGhostZ(0f);

        // Reuse the direct strip-tab copy. Scaling one container keeps the title, pill and cross
        // rigid around the same centre, and makes the p=1 frame exactly the strip copy.
        final FrameLayout ghost = createStripTabGhostView(targetTab, targetBounds, closeBounds,
                tabs.getTabBackgroundColor(targetTab), tabs.getTabCornerRadiusPx());
        ghost.setPivotX(targetBounds.width() / 2f);
        ghost.setPivotY(targetBounds.height() / 2f);
        ghost.setScaleX(SessionOverviewTransitionMath.NEW_TAB_REVEAL_SCALE);
        ghost.setScaleY(SessionOverviewTransitionMath.NEW_TAB_REVEAL_SCALE);
        ghost.setAlpha(0f);
        ghost.setTranslationZ(terminalZ);
        mGhostLayer.addView(ghost);

        final int targetTabVisibility = targetTab.getVisibility();
        targetTab.setVisibility(View.INVISIBLE);
        transition.newTabGhost = new ClosingNewTabGhost(ghost, targetTab,
                targetTabVisibility);
    }

    private void startClosingTransition(@NonNull ClosingTransition transition) {
        if (mClosingTransition != transition || transition.started) return;
        Trace.beginSection("overview.close.start");
        transition.started = true;
        // Bring the always-on extra-keys panel back with the expanding terminal instead of
        // leaving an empty bottom strip until the overlay is removed.
        mActivity.showExtraKeysPanelAfterOverview();
        final boolean addButtonSource = transition.addButtonSource != null;
        if (addButtonSource) {
            // The source ghost now belongs to the transition, not to the pending add flight.
            removeTerminalAddTimeout();
            mTerminalAddFlight = null;
            // The button is already invisible and its pixels are owned by the source ghost.
            // Do not start the pencil return or the strip-plus flight: the source becomes the
            // terminal, and the strip plus is part of the panel copy.
            mAddButtonHovered = false;
        } else {
            // The plus travels home during the expansion, not after it. This makes the toggle
            // layout-ready while keeping it unpainted, so the return flight has its destination
            // from the first frame of the exit animation. With no pencil to return to, the plus
            // shrinks and fades away over the same exit instead of lingering until the overlay is
            // removed.
            if (!mActivity.startOverviewAddButtonReturnMorphEarly(mAddButton, transition.session)) {
                hideAddButtonWithAppearance(true);
            }
            startTabAddButtonClosingFlight();
        }
        updateOverviewBackdropAlpha();
        // The decor fades from the +20-point overview alpha back to the terminal's alpha while
        // the frame expands. The cut-out under the ghost keeps the fading colour away from the
        // terminal itself, which already carries that alpha in its own live raster.
        transition.backgroundStart = overviewBackdropColor(
                mActivity.getWindowBackgroundColor());
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
        Trace.endSection();
    }

    /**
     * Advance the closing flight. The terminal expands continuously; each tab moves and fades
     * out at card size, is transformed once while invisible, then moves the rest of the way and
     * fades in at strip size.
     */
    private void applyClosingProgress(@NonNull ClosingTransition transition, float progress) {
        if (mClosingTransition != transition) return;
        Trace.beginSection("overview.close.apply");
        final float p = SessionOverviewTransitionMath.clamp01(progress);

        if (transition.backgroundTransition) {
            mActivity.applyWindowBackgroundColor(SessionOverviewTransitionMath.blendColor(
                    transition.backgroundStart, transition.backgroundEnd, p));
        }
        // The reverse thumb copy slides back left and fades in over the same 300 ms expansion.
        if (transition.scrollbarThumbGhost != null) {
            transition.scrollbarThumbGhost.setTranslationX(
                    SessionOverviewTransitionMath.closingScrollbarThumbTranslation(
                            p, transition.scrollbarThumbShiftPx));
            transition.scrollbarThumbGhost.setAlpha(
                    SessionOverviewTransitionMath.closingScrollbarThumbAlpha(p));
        }
        // The selected card's outline fades out over the first half of the expansion instead of
        // disappearing in the frame the ghost is installed.
        transition.card.root.setBorderAlpha(
                SessionOverviewTransitionMath.clamp01(1f - p / 0.5f));
        Trace.beginSection("overview.close.geometry");
        if (transition.frame != null) transition.frame.syncTransientState();
        final int[] origin = contentOrigin(mScratchOrigin);
        if (origin == null) {
            Trace.endSection();
            Trace.endSection();
            return;
        }

        if (transition.tabPanelGhost != null) {
            transition.tabPanelGhost.setAlpha(
                    SessionOverviewTransitionMath.closingTabPanelAlpha(p));
        }

        // A newly added ghost is not painted until it has been through layout: TopCropImageView
        // has an identity matrix until onSizeChanged(), and animation callbacks run before the
        // traversal that would give it a size. Keep the card preview and the decor intact until
        // the ghost can really take its place, otherwise the card blinks out for a frame.
        final boolean terminalGhostReady = transition.terminalGhost != null
                && transition.terminalGhost.isLaidOut()
                && transition.terminalGhost.getWidth() > 0
                && transition.terminalGhost.getHeight() > 0;
        if (terminalGhostReady) applyClosingCardAlpha(p);

        // Use the rect captured when the selection began. A hovered card freezes its lifted
        // visual size there; re-reading the layout slot would snap the flight back to the
        // unscaled thumbnail on the very first frame.
        final Rect start = transition.cardStartLocal;
        final Rect target = closingTerminalTarget(origin, transition.terminalTargetLocal);
        final boolean addButtonSource = transition.addButtonSource != null;
        Rect hole = null;
        float holeCornerRadius = transition.startCornerRadius;
        if (terminalGhostReady && target != null) {
            lerpRect(mScratchGhostRect, start, target, p);
            hole = mScratchGhostRect;
            // The normal card source already has the terminal's aspect. The add-button source is
            // square and covers the image, so clipping it to the painted height would shrink the
            // shadow away from the source circle on the first frame.
            if (p < 1f && !addButtonSource) clipFlightRectToSource(hole, transition.frame);
            layoutTerminalGhost(transition.terminalGhost, hole);
            // Reverse of the opening: the rounded frame expands and becomes square by the
            // time it reaches the live terminal. The card's own outline stays suppressed for the
            // whole flight. The hole follows the same radius so the corners keep the decor tint.
            // The add-button source starts from its own circular radius instead of the card's.
            holeCornerRadius = SessionOverviewTransitionMath.lerp(
                    transition.startCornerRadius, 0f, p);
            if (transition.terminalOutline != null) {
                transition.terminalOutline.setRadius(holeCornerRadius);
                if (transition.terminalGhost != null) {
                    transition.terminalGhost.invalidateOutline();
                }
            }
            if (transition.addButtonSource != null) {
                applyFlightBorder(transition.terminalGhost,
                        SessionOverviewAddButtonTerminalMath.strokeWidth(p,
                                transition.addButtonSource.strokeWidth, mCardStrokeWidth),
                        holeCornerRadius,
                        SessionOverviewAddButtonTerminalMath.terminalBorderAlpha(p));
            } else {
                applyFlightBorder(transition.terminalGhost, holeCornerRadius,
                        SessionOverviewTransitionMath.clamp01(1f - p / 0.5f));
            }
        }
        if (mBackdropHole != null) {
            final int fillAlpha = Math.round(255f
                    * mOverviewBackdropAlpha
                    * overviewFillFraction(p, false));
            mBackdropHole.setContent(hole, holeCornerRadius,
                    mOverlayBackgroundColor, fillAlpha);
        }
        Trace.endSection();

        // Keep the copy transparent until it can replace the card in the same frame.
        // A layout pass can give the ghost a size between two animator callbacks; if its alpha
        // had already been raised, it would draw over the still-visible preview and stack two
        // translucent terminal backgrounds for that frame.
        final float ghostAlpha = addButtonSource
                ? 1f
                : terminalGhostReady
                        ? SessionOverviewTransitionMath.closingHoverAlpha(
                                transition.startAlpha, p)
                        : 0f;
        if (transition.terminalGhost != null) {
            transition.terminalGhost.setAlpha(ghostAlpha);
            if (addButtonSource) {
                // The terminal image starts fully transparent while the source button owns the
                // pixels, then becomes opaque exactly when the source disappears.
                ((TopCropImageView) transition.terminalGhost).setImageAlpha(
                        Math.round(255f
                                * SessionOverviewAddButtonTerminalMath.terminalContentAlpha(p)));
            }
        }
        if (transition.liftedStart) {
            // The ghost carries the inherited hover shadow. Fade the colour's alpha, not its
            // elevation: the lift stays the same size while the card grows, so the shadow
            // follows the ghost and reaches zero opacity halfway through the expansion.
            // The card's Z is restored in cleanup, when the shadow is already fully transparent.
            final float shadowFade = SessionOverviewTransitionMath.closingShadowAlpha(p);
            final int fadedColor = FloatingShadow.withAlphaFraction(
                    transition.shadowColor, shadowFade);
            transition.card.root.setShadowColor(fadedColor);
            FloatingShadow.setShadowColors(transition.terminalGhost, fadedColor);
            if (terminalGhostReady) {
                // Hand the shadow over completely. The ghost is now at the same rect and has
                // the same inherited alpha, so leaving the card's own elevation in place would
                // stack two shadows and make the grid shadow darker than the hover state.
                transition.card.root.setTranslationZ(0f);
            }
        }
        if (addButtonSource && terminalGhostReady) {
            handOffAddButtonSourceShadow(transition);
        }

        if (addButtonSource) {
            applyAddButtonSourceProgress(transition, hole, holeCornerRadius, p);
        }

        final float previewAlpha = addButtonSource
                ? 0f
                : terminalGhostReady
                        ? SessionOverviewTransitionMath.closingPreviewAlpha(p) : 1f;
        transition.card.preview.setAlpha(previewAlpha);
        transition.card.preview.setVisibility(previewAlpha <= 0f
                ? View.INVISIBLE : View.VISIBLE);
        Trace.beginSection("overview.close.tabs");
        boolean selectedTabHasGhost = false;
        for (ClosingTabGhost ghost : transition.tabGhosts) {
            if (!ghost.cardChromeHidden && ghost.view.isLaidOut()
                    && ghost.view.getWidth() > 0) {
                ghost.cardChromeHidden = true;
                ghost.card.tab.setVisibility(View.INVISIBLE);
                ghost.card.close.setVisibility(View.INVISIBLE);
            }
            // One pixel above the terminal ghost so returning tabs stay over the expanding frame.
            final float terminalZ = transition.liftedStart ? transition.startLiftZ + 1f : 0f;
            applyClosingTabProgress(transition, ghost, origin, p, terminalZ);
            if (ghost.card == transition.card) selectedTabHasGhost = true;
        }
        if (transition.newTabGhost != null) {
            applyClosingNewTabProgress(transition.newTabGhost, p);
        }
        if (!selectedTabHasGhost) {
            transition.card.tab.setAlpha(transition.tabBaseAlpha * previewAlpha);
            transition.card.close.setAlpha(transition.closeBaseAlpha * previewAlpha);
        }
        Trace.endSection();
        Trace.endSection();
    }

    /**
     * Drop the source ghost's shadow once the terminal ghost paints the same elevation at the
     * same rect. Idempotent because the layout callback and the progress callback can both reach
     * this during the first frame.
     */
    private void handOffAddButtonSourceShadow(@NonNull ClosingTransition transition) {
        if (transition.addButtonSource == null
                || transition.addButtonSourceShadowHandedOff) return;
        if (transition.addButtonSourceGhost != null) {
            transition.addButtonSourceGhost.setShadowAlpha(0f);
        }
        transition.addButtonSourceShadowHandedOff = true;
    }

    /**
     * Drive the source-button ghost for the first quarter of the expansion. It stays in the
     * overlay above the terminal ghost; its shadow already belongs to the terminal, so only the
     * body, plus and stroke are animated here.
     */
    private void applyAddButtonSourceProgress(@NonNull ClosingTransition transition,
                                             @Nullable Rect frame, float radius, float p) {
        final OverviewAddTabGhostView ghost = transition.addButtonSourceGhost;
        final AddButtonSource source = transition.addButtonSource;
        if (ghost == null || source == null) return;
        final float alpha = SessionOverviewAddButtonTerminalMath.sourceAlpha(
                source.buttonAlpha, p);
        if (alpha <= 0f) {
            if (ghost.getParent() instanceof ViewGroup) {
                ((ViewGroup) ghost.getParent()).removeView(ghost);
            }
            transition.addButtonSourceGhost = null;
            return;
        }
        final Rect bounds = frame != null ? frame : source.visualRect;
        ghost.setStroke(SessionOverviewAddButtonTerminalMath.strokeWidth(
                        p, source.strokeWidth, mCardStrokeWidth),
                source.strokeColor);
        ghost.setFlightFrame(bounds.left, bounds.top, bounds.right, bounds.bottom,
                radius, source.fillColor,
                SessionOverviewAddButtonTerminalMath.sourcePlusAlpha(source.imageAlpha, p),
                0f);
        ghost.setAlpha(alpha);
    }

    /**
     * Fade the grid as one group while the selected terminal expands. The selected card is owned
     * by the expanding frame (its preview, pill and border are suppressed), so fading the
     * group is visually identical to fading only its neighbours.
     *
     * <p>Called only once the ghost is laid out: before that the real card is still visible and
     * must keep alpha 1, exactly as it did when only the neighbours were faded.</p>
     */
    private void applyClosingCardAlpha(float progress) {
        if (mGrid == null) return;
        mGrid.setAlpha(SessionOverviewTransitionMath.closingCardAlpha(progress));
    }

    private void applyClosingTabProgress(@NonNull ClosingTransition transition,
                                         @NonNull ClosingTabGhost ghost, @NonNull int[] origin,
                                         float p, float terminalZ) {
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

        // While the unselected card's thumbnail is still on screen it moves as one body with
        // its tab, and the expanding terminal window covers both. Once the thumbnail has faded
        // out the returning tab travels alone and keeps the old above-terminal z-order. A tab
        // whose card was never visible travels alone from its off-screen grid slot for the whole
        // flight, so the terminal frame never hides it. The selected card's own tab is the
        // terminal frame's partner and is left untouched.
        final boolean companion = SessionOverviewTransitionMath.tabCarriesCard(
                ghost.cardVisible, ghost.card == transition.card);
        final boolean thumbnailVisible = companion
                && SessionOverviewTransitionMath.closingCardAlpha(p) > 0f;
        applyTabGhostZ(ghost.view, ghost.closeGhost, thumbnailVisible, terminalZ);

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

        // The selected card owns the expanding terminal frame, so it stays out of this
        // coupling. A visible thumbnail travels with its returning tab so it does not stay
        // behind while the tab fades out; an off-screen card stays in its slot.
        if (companion && ghost.targetBounds != null) {
            moveCardWithTab(ghost.card.root, ghost.anchorBounds, ghost.startBounds,
                    ghost.targetBounds, ghost.targetApplied ? targetPath : sourcePath);
        }
    }

    /**
     * Apply the target-phase reveal to the newly added tab. The target geometry and style were
     * captured once while the strip was frozen; only opacity and a small shared scale change.
     */
    private void applyClosingNewTabProgress(@NonNull ClosingNewTabGhost ghost, float p) {
        ghost.view.setAlpha(SessionOverviewTransitionMath.tabTargetAlpha(p));
        final float scale = SessionOverviewTransitionMath.newTabRevealScale(p);
        ghost.view.setScaleX(scale);
        ghost.view.setScaleY(scale);
    }

    /**
     * Put one returning tab ghost at its final strip position and style.
     *
     * <p>Like the opening target, this runs once while the ghost is invisible. The strip's
     * already rendered title is borrowed verbatim, so its line breaks are the ones the real tab
     * will use after the hand-off. The only per-frame work left is moving the start edge and
     * fading while the vertical centre follows the line.</p>
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
        final TextView liveTitle = ghost.targetTabView.findViewById(R.id.session_tab_title);
        applyTitleInsets(ghost.view, ghost.targetTabView, liveTitle,
                ghost.targetPadStart, ghost.targetPadTop,
                ghost.targetPadEnd, ghost.targetPadBottom);
        // Borrow the strip's rendered text so the ghost lands with exactly the same line breaks
        // the real tab will show when the overlay is removed.
        copyRenderedTitle(ghost.view, liveTitle);

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
                && terminal.getWidth() > 0 && terminal.getHeight() > 0
                && terminal.getTerminalContentBounds(mScratchContentRect)) {
            windowRectOf(terminal, mScratchContentRect, mScratchLocation, mScratchWindowRect);
            // source and destination are the same rect here; all four coordinates are read by
            // set() before it mutates anything.
            offsetRect(mScratchWindowRect, origin, mScratchWindowRect);
            if (!mScratchWindowRect.isEmpty()) return mScratchWindowRect;
        }
        if (mContentArea != null
                && localRect(mContentArea, origin, mScratchLocation, mScratchWindowRect)
                && !mScratchWindowRect.isEmpty()) {
            return mScratchWindowRect;
        }
        return fallback;
    }

    private void finishClosingTransition(@NonNull ClosingTransition transition) {
        if (mClosingTransition != transition) return;
        Trace.beginSection("overview.close.finish");
        applyClosingProgress(transition, 1f);
        final TerminalSession session = transition.session;
        final boolean consumedAddButton = transition.addButtonSource != null;
        cleanupClosingTransition(transition);

        // The tab strip was already selected and scrolled behind the overview before the
        // expansion started. Only the visible hand-off is left. Pass the target session so a
        // return flight that has to start here still uses its scrollbar state, not the leaving
        // session's.
        if (consumedAddButton) mTerminalAddConsumedClose = true;
        hide(false, true, session);
        // Move the pager first. The strip is still frozen, so the landing cannot move or repaint
        // it; the pre-applied target selection and scroll stay exactly as the copy captured
        // them. Once the target is current, reconcile deferred updates so updateTabs() resolves
        // the target (not the page being left) as the selected tab.
        switchToSession(session);
        endClosingStripFreeze();
        if (consumedAddButton) {
            mTerminalAddConsumedClose = false;
            // The add button was consumed by the morph; the pencil is the only floating control
            // left and must fade in after the overlay has stopped painting.
            mActivity.fadeInFloatingControlsAfterConsumedAddButton();
        }
        Trace.endSection();
    }

    /** Abort the expansion and put the card's own preview and tab back in place. */
    private void cancelClosingTransition() {
        final ClosingTransition transition = mClosingTransition;
        if (transition == null) return;
        final boolean addButtonSource = transition.addButtonSource != null;
        cancelTabAddButtonFlight();
        cleanupClosingTransition(transition);
        // Restore the strip's live updates before putting the highlight back; otherwise the
        // restore selection would be swallowed by the closing freeze.
        endClosingStripFreeze();
        if (addButtonSource) {
            // A cancelled add-button expansion returns to the overview. The new session is
            // already current, but the button must be visible again for the next gesture.
            restoreAddButtonAfterTerminalAdd();
        }
        // The cancelled flight may have left the decor halfway to the app background. The
        // overview is still open, so put its terminal backdrop back before restoring the strip.
        applyOverviewBackground();
        // The panel may already be returning with the cancelled expansion. The overview still
        // owns the screen, so pin it back to its hidden state.
        mActivity.keepExtraKeysPanelHiddenForOverview();
        // The strip was pre-positioned for the target before the flight. A cancelled flight must
        // leave the highlight on the session that is still current.
        restoreTabSelectionToCurrentSession();
        requestVisibleDirtyPreviews();
    }

    /** Put the real add button back after a cancelled or failed terminal-add flight. */
    private void restoreAddButtonAfterTerminalAdd() {
        if (mAddButton == null) return;
        cancelAddButtonHideAnimation();
        resetAddButtonHover();
        if (isAtSessionLimit()) {
            setAddButtonVisible(false);
            return;
        }
        setAddButtonVisible(true);
        mAddButton.setEnabled(true);
        OverviewAppearanceAnimation.reset(mAddButton);
    }

    private void cleanupClosingTransition(@NonNull ClosingTransition transition) {
        if (mClosingTransition != transition) return;
        Trace.beginSection("overview.close.cleanup");
        mClosingTransition = null;

        final ValueAnimator animator = transition.animator;
        transition.animator = null;
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            if (animator.isRunning()) animator.cancel();
        }

        // The add-button closing held the survivors at their pre-add centres. The closing
        // owns the grid only while it is on screen; drop that snapshot before restoring the
        // final card geometry. The reset below then leaves the grid in its post-add layout.
        releaseTerminalAddGridHold(false);
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).finishContentTransition();
        }

        transition.card.preview.setAlpha(1f);
        transition.card.preview.setVisibility(View.VISIBLE);
        for (Card card : mCards) {
            SessionOverviewCardView.resetVisual(card.root);
        }
        if (mGrid != null) mGrid.setAlpha(1f);
        // resetVisual() above also restores a hovered card frozen in its lifted transform.
        setCardOutlineSuppressed(transition.card, false);
        transition.card.tab.setAlpha(transition.tabBaseAlpha);
        transition.card.close.setAlpha(transition.closeBaseAlpha);
        transition.card.tab.setVisibility(View.VISIBLE);
        transition.card.close.setVisibility(View.VISIBLE);
        if (transition.addButtonSource != null) {
            // The new card was armed and hidden by the add flight; cleanup must leave it in a
            // normal, interactive resting state for a cancelled expansion.
            transition.card.appearing = false;
            transition.card.root.setEnabled(true);
            transition.card.root.setTranslationX(0f);
            transition.card.root.setTranslationY(0f);
        }
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
        if (transition.newTabGhost != null) {
            transition.newTabGhost.targetTabView.setVisibility(
                    transition.newTabGhost.targetTabVisibility);
            transition.newTabGhost = null;
        }

        if (transition.addButtonSourceGhost != null) {
            final OverviewAddTabGhostView sourceGhost = transition.addButtonSourceGhost;
            transition.addButtonSourceGhost = null;
            if (sourceGhost.getParent() instanceof ViewGroup) {
                ((ViewGroup) sourceGhost.getParent()).removeView(sourceGhost);
            }
        }
        if (transition.terminalGhost != null) {
            // The live drawable is released below; reset the image alpha so a reused view cannot
            // keep the add-button hand-off's zero opacity.
            ((TopCropImageView) transition.terminalGhost).setImageAlpha(255);
            transition.terminalGhost.setImageDrawable(null);
        }
        if (mGhostLayer != null) mGhostLayer.removeAllViews();
        mBackdropHole = null;
        if (transition.frame != null) {
            transition.frame.release();
            transition.frame = null;
        }
        transition.terminalGhost = null;
        transition.terminalOutline = null;
        transition.tabPanelGhost = null;
        removeScrollbarThumbGhost(transition.scrollbarThumbGhost);
        transition.scrollbarThumbGhost = null;
        Trace.endSection();
    }

    @Nullable
    private int[] contentOrigin(@NonNull int[] out) {
        if (mContentArea == null || !mContentArea.isAttachedToWindow()) return null;
        mContentArea.getLocationInWindow(out);
        // The ghost layer moves up with the extended overlay, so everything measured in window
        // coordinates has to be rebased by the same amount while the extension is active.
        out[1] -= mStatusBarTopExtension;
        return out;
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

    /** Rebased bounds of a view written into {@code out}; {@code location} is reused. */
    private static boolean localRect(@NonNull View view, @NonNull int[] origin,
                                     @NonNull int[] location, @NonNull Rect out) {
        if (!view.isAttachedToWindow()) {
            out.setEmpty();
            return false;
        }
        view.getLocationInWindow(location);
        final int left = location[0] - origin[0];
        final int top = location[1] - origin[1];
        out.set(left, top, left + view.getWidth(), top + view.getHeight());
        return true;
    }

    /** Visible bounds rebased into content coordinates without allocating a rectangle. */
    private static boolean visibleLocalRect(@NonNull View view, @NonNull int[] origin,
                                            @NonNull Rect out) {
        if (!view.getGlobalVisibleRect(out) || out.isEmpty()) return false;
        // All four coordinates are read before set() mutates the same rect.
        offsetRect(out, origin, out);
        return true;
    }

    /**
     * True when any part of the card's thumbnail is inside the overview viewport.
     *
     * <p>Every tab flies, including a tab whose card is scrolled out of view: its ghost still
     * starts from or lands on the card's real grid slot. Only the thumbnail can travel with it,
     * so this visibility decides whether the card follows the tab instead of staying in its
     * off-screen slot. The preview fills the card, so its clipped rectangle is the card's.</p>
     */
    private boolean isCardThumbnailVisible(@NonNull Card card, @NonNull int[] origin) {
        return visibleLocalRect(card.preview, origin, mScratchVisibleRect);
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
     * Select the card under the release point of the gesture that opened the overview.
     *
     * <p>The card never received this gesture's {@code ACTION_DOWN}, so its own click handler
     * cannot be used. The point and the card bounds are both read in the window's coordinate
     * space ({@link View#getGlobalVisibleRect}), so window insets, card translations during the
     * opening flight, RTL layouts and a window that does not fill the screen, such as a bubble,
     * keep the hit-test correct.</p>
     */
    private void selectTargetUnderFinger(float windowX, float windowY) {
        if (mActivityStopped) return;
        if (mOpeningPreparationPending) {
            // The finger can lift while the keyboard is still hiding. Remember the point only
            // if the normal recognition delay had already elapsed; otherwise this is the same
            // quick flick that would not select in the immediately-shown overview either.
            if (mGestureSelectionArmed) {
                mPendingGestureRelease = true;
                mPendingGestureReleaseX = windowX;
                mPendingGestureReleaseY = windowY;
                if (mGrid != null) {
                    mGrid.removeCallbacks(mArmGestureSelectionRunnable);
                }
            } else {
                disarmGestureSelection();
                mPendingGestureRelease = false;
            }
            return;
        }
        if (!mShowing) return;
        // Capture the state before clearing it: disarmGestureSelection() sets the flag to false,
        // so testing the field afterwards would reject every legitimate selection.
        final boolean armed = mGestureSelectionArmed;
        // The gesture is over whether or not recognition had begun. Cancel a pending arm so a
        // fast flick cannot produce a hover shadow (or a selection) after the finger is gone.
        disarmGestureSelection();
        if (!armed) return;
        if (mClosingTransition != null
                || mPendingClosingCard != null || mClosingCardAnimation != null) {
            resetGestureHover();
            return;
        }
        // The button paints above the grid, so it wins the hit-test even when a card lies
        // underneath it. The hover flag also accepts a release a pixel outside the layout
        // bounds after the finger has already lifted the button.
        final boolean addTarget = isAddButtonHoverTarget()
                && (mAddButtonHovered || findAddButtonAtWindowPoint(windowX, windowY));
        if (addTarget) {
            stopAutoScroll();
            if (mHoverCard != null) {
                final Card previous = mHoverCard;
                mHoverCard = null;
                previous.root.setHoverHighlight(false);
            }
            selectAddButtonUnderFinger();
            return;
        }
        if (mAddButtonHovered) resetAddButtonHover();
        final Card hit = mHoverCard != null ? mHoverCard : findCardAtWindowPoint(windowX, windowY);
        stopAutoScroll();
        // A lift from the opening gesture can still be animating at release. Stop it without
        // applying its endpoint, then capture the whole visual state so the ghost, its tabs and
        // its shadow all start from exactly what the finger was hovering.
        final boolean hovered = hit != null && hit == mHoverCard;
        if (hovered) {
            hit.root.cancelHoverLift();
            mHoverCard = null;
        } else {
            clearGestureHover();
        }
        final LiftStart liftStart = hovered ? captureLiftStart(hit) : null;
        if (hovered && liftStart == null) {
            // A preview without a usable rect cannot define a flight; return the frozen card to
            // rest instead of leaving it lifted under an unpaired normal selection.
            SessionOverviewCardView.resetVisual(hit.root);
        }
        if (hit == null) return;
        if (containsWindowPoint(hit.close, windowX, windowY)) {
            closeSessionFromButton(hit);
            return;
        }
        selectSession(hit.session, liftStart);
    }

    /**
     * Start the recognition delay for the gesture that just opened the overview. Until it expires
     * the grid is deliberately inert to that finger, so a fast flick cannot drop onto a card.
     */
    private void armGestureSelectionAfterDelay() {
        disarmGestureSelection();
        if (mGrid == null) return;
        mGrid.postDelayed(mArmGestureSelectionRunnable, GESTURE_SELECTION_DELAY_MS);
    }

    private void armGestureSelection() {
        if (!mShowing) return;
        mGestureSelectionArmed = true;
        // The finger may have been stationary during the delay; hit-test the last point now.
        applyHoverTarget();
    }

    /** Drop any pending or active recognition of the gesture that opened the overview. */
    private void disarmGestureSelection() {
        if (mGrid != null) mGrid.removeCallbacks(mArmGestureSelectionRunnable);
        mGestureSelectionArmed = false;
    }

    /** Track the finger, highlight the drop target and scroll the list at its edges. */
    private void updateGestureHover(float windowX, float windowY) {
        if (!mShowing || mActivityStopped || mClosingTransition != null
                || mPendingClosingCard != null || mClosingCardAnimation != null) return;
        // Remember the point even while recognition is delayed, so the first eligible frame can
        // highlight the card the finger is actually over.
        mHoverWindowX = windowX;
        mHoverWindowY = windowY;
        // The grid does not exist yet while the terminal is growing. Keep the point; showNow()
        // applies it once the cards have been laid out.
        if (mOpeningPreparationPending) return;
        if (!mGestureSelectionArmed) return;
        Trace.beginSection("overview.grid.hover");
        updateAutoScrollEdge(windowX, windowY);
        applyHoverTarget();
        Trace.endSection();
    }

    /** Re-run only the hit-test after the grid has moved under a stationary finger. */
    private void applyHoverTarget() {
        // The button is painted above the grid, so it is the first target. Its hover stops edge
        // auto-scroll: the button is fixed in the corner and must not slide under the finger.
        if (findAddButtonAtWindowPoint(mHoverWindowX, mHoverWindowY)) {
            if (mHoverCard != null) {
                final Card previous = mHoverCard;
                mHoverCard = null;
                previous.root.setHoverHighlight(false);
            }
            setAddButtonHovered(true);
            stopAutoScroll();
            return;
        }
        setAddButtonHovered(false);
        final Card card = findCardAtWindowPoint(mHoverWindowX, mHoverWindowY);
        if (card == mHoverCard) return;
        clearGestureHover();
        mHoverCard = card;
        if (card != null) {
            card.root.setHoverHighlight(true);
            // Only the armed gesture produces feedback: one tick when the finger lands on a new
            // card, not one per hover frame and nothing during the initial recognition delay.
            if (mGestureSelectionArmed) {
                card.root.performHapticFeedback(HOVER_FEEDBACK);
            }
        }
    }

    /**
     * True when the add button can be the drop target right now. The checks mirror the card
     * hover path and exclude every flight that already owns the button's pixels.
     */
    private boolean isAddButtonHoverTarget() {
        return mShowing
                && !mActivityStopped
                && !mOpeningPreparationPending
                && mAddButton != null
                && mAddButton.getVisibility() == View.VISIBLE
                && mAddButton.isShown()
                && mAddButton.isEnabled()
                && mAddButton.isLaidOut()
                && mAddButton.getWidth() > 0
                && mAddButton.getHeight() > 0
                && !mAddButtonHiding
                && !isAtSessionLimit()
                && !isAddTabFlightActive()
                && !isTerminalAddFlightActive()
                && !mActivity.isOverviewAddButtonMorphActive()
                && !mActivity.isOverviewAddButtonReturnMorphActive();
    }

    /** True when the finger is inside the add button's visible, clipped bounds. */
    private boolean findAddButtonAtWindowPoint(float windowX, float windowY) {
        return isAddButtonHoverTarget() && containsWindowPoint(mAddButton, windowX, windowY);
    }

    /**
     * Lift the add button exactly like a card: 1.05 scale, 16 dp total shadow and the same
     * 254/255 opacity, over 160 ms. The animator starts from the current values so an interrupted
     * reveal or a quick reversal never snaps.
     */
    private void setAddButtonHovered(boolean hovered) {
        if (mAddButtonHovered == hovered) return;
        mAddButtonHovered = hovered;
        if (mAddButton == null) return;
        final ImageButton button = mAddButton;
        cancelAddButtonHoverAnimator();
        button.animate().cancel();

        final Resources res = mActivity.getResources();
        final float fromZ = button.getTranslationZ();
        final float fromScale = button.getScaleX();
        final float fromAlpha = button.getAlpha();
        final float targetZ = hovered
                ? Math.max(0f, FloatingShadow.overviewCardElevationPx(res)
                        - button.getElevation())
                : 0f;
        final float targetScale = hovered ? FloatingShadow.OVERVIEW_CARD_LIFT_SCALE : 1f;
        final float targetAlpha = hovered ? FloatingShadow.OVERVIEW_CARD_HOVER_ALPHA : 1f;
        final int hoverShadow = FloatingShadow.overviewCardShadowColor(
                res, mActivity.isCachedSchemeLight());
        final int restShadow = FloatingShadow.color(
                res, mActivity.isCachedSchemeLight(), R.fraction.floating_shadow_alpha);
        if (hovered) FloatingShadow.setShadowColors(button, hoverShadow);

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        mAddButtonHoverAnimator = animator;
        animator.setDuration(FloatingShadow.OVERVIEW_SHADOW_ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            final float f = (float) animation.getAnimatedValue();
            button.setTranslationZ(fromZ + (targetZ - fromZ) * f);
            button.setScaleX(fromScale + (targetScale - fromScale) * f);
            button.setScaleY(fromScale + (targetScale - fromScale) * f);
            button.setAlpha(fromAlpha + (targetAlpha - fromAlpha) * f);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAddButtonHoverAnimator != animation) return;
                mAddButtonHoverAnimator = null;
                if (!mAddButtonHovered) FloatingShadow.setShadowColors(button, restShadow);
            }
        });
        animator.start();
        if (hovered && mGestureSelectionArmed) {
            button.performHapticFeedback(HOVER_FEEDBACK);
        }
    }

    private void cancelAddButtonHoverAnimator() {
        final ValueAnimator animator = mAddButtonHoverAnimator;
        mAddButtonHoverAnimator = null;
        if (animator == null) return;
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        animator.cancel();
    }

    /** Return the add button to its resting transform and shadow without touching visibility. */
    private void resetAddButtonHover() {
        cancelAddButtonHoverAnimator();
        mAddButtonHovered = false;
        if (mAddButton == null || mAddButtonHiding) return;
        final ImageButton button = mAddButton;
        button.setTranslationZ(0f);
        button.setScaleX(1f);
        button.setScaleY(1f);
        button.setAlpha(1f);
        FloatingShadow.setShadowColors(button, FloatingShadow.color(
                mActivity.getResources(), mActivity.isCachedSchemeLight(),
                R.fraction.floating_shadow_alpha));
    }

    /** Cancel both hover targets instantly; used when the overlay closes or is torn down. */
    private void resetGestureHover() {
        resetAddButtonHover();
        clearCardHover();
    }

    private void clearCardHover() {
        if (mHoverCard == null) return;
        final Card previous = mHoverCard;
        mHoverCard = null;
        previous.root.setHoverHighlight(false);
    }

    /**
     * Start the edge auto-scroll loop at most once per gesture. The loop decides on every frame
     * whether the finger is still inside an edge band, exactly like the input-history popup.
     */
    private void updateAutoScrollEdge(float windowX, float windowY) {
        // The button is fixed in the corner and paints above the grid; scrolling the grid under
        // it would move the drop target out from under the finger.
        if (findAddButtonAtWindowPoint(windowX, windowY)) {
            stopAutoScroll();
            return;
        }
        if (mScrollView == null || !mScrollView.isAttachedToWindow()
                || mScrollView.getHeight() <= 0) {
            stopAutoScroll();
            return;
        }
        if (mAutoScrollPosted) return;
        mAutoScrollPosted = true;
        mAutoScrollLastTimeMs = SystemClock.uptimeMillis();
        mScrollView.postOnAnimation(mAutoScrollRunnable);
    }

    /**
     * One frame of the edge auto-scroll loop. Band, step and frame-time scaling come from the
     * same {@link SessionOverviewReorderMath} helpers the drag-reorder loop uses, so both
     * gestures scroll at exactly the same speed. Reschedules on the next vsync while the finger
     * stays in the band; stops as soon as it leaves.
     */
    private void autoScrollStep() {
        if (!mShowing || mActivityStopped || mClosingTransition != null
                || mPendingClosingCard != null || mScrollView == null) {
            stopAutoScroll();
            return;
        }
        if (findAddButtonAtWindowPoint(mHoverWindowX, mHoverWindowY)) {
            stopAutoScroll();
            return;
        }

        Trace.beginSection("overview.grid.autoScroll");
        mScrollView.getLocationInWindow(mScratchLocation);
        final int viewportHeight = mScrollView.getHeight();
        final long now = SystemClock.uptimeMillis();
        final float frameRatio = SessionOverviewReorderMath.frameRatio(
                now, mAutoScrollLastTimeMs);
        mAutoScrollLastTimeMs = now;

        final int step = SessionOverviewReorderMath.autoScrollStep(
                mHoverWindowY - mScratchLocation[1], viewportHeight,
                mAutoScrollBandPx, mAutoScrollMaxStepPx, frameRatio);
        if (step == 0) {
            stopAutoScroll();
            return;
        }

        mScrollView.scrollBy(0, step);
        applyHoverTarget();
        mScrollView.postOnAnimation(mAutoScrollRunnable);
        Trace.endSection();
    }

    private void stopAutoScroll() {
        if (mScrollView != null) mScrollView.removeCallbacks(mAutoScrollRunnable);
        mAutoScrollPosted = false;
        mAutoScrollLastTimeMs = 0L;
    }

    /** Animate the add button back to rest and restore the previously hovered card. */
    private void clearGestureHover() {
        setAddButtonHovered(false);
        clearCardHover();
    }

    /**
     * Current on-screen rect of a card's preview, including the hover scale.
     *
     * <p>{@link View#getLocationInWindow(int[])} already maps the view's origin through its
     * ancestors' transforms, so the top-left is lifted. Only the layout width/height still need
     * the scale applied; scaling the mapped rect a second time would overshoot.</p>
     */
    @Nullable
    private Rect visualPreviewRect(@NonNull Card card) {
        final int[] origin = contentOrigin(mScratchOrigin);
        return origin == null ? null : visualChildRect(card, card.preview, origin);
    }

    /** Freeze the full visual state of a hovered card before its lift animator is released. */
    @Nullable
    private LiftStart captureLiftStart(@NonNull Card card) {
        final Rect preview = visualPreviewRect(card);
        if (preview == null || preview.isEmpty()) return null;
        final Resources res = mActivity.getResources();
        // Hover-to-select only applies the 254/255 alpha step. Freeze that value explicitly so a
        // stale alpha from an interrupted gesture cannot leak into the opening terminal flight.
        return new LiftStart(preview, card.root.getScaleX(),
                FloatingShadow.OVERVIEW_CARD_HOVER_ALPHA,
                card.root.getTranslationZ(),
                FloatingShadow.overviewCardShadowColor(res, mActivity.isCachedSchemeLight()));
    }

    /** On-screen rect of a card child, including the root's hover scale. */
    @Nullable
    private Rect visualChildRect(@NonNull Card card, @NonNull View child,
                                 @NonNull int[] origin) {
        if (!child.isAttachedToWindow() || child.getWidth() <= 0
                || child.getHeight() <= 0) return null;
        final int[] location = new int[2];
        child.getLocationInWindow(location);
        final float scaleX = card.root.getScaleX();
        final float scaleY = card.root.getScaleY();
        final int left = location[0] - origin[0];
        final int top = location[1] - origin[1];
        return new Rect(left, top,
                left + Math.round(child.getWidth() * scaleX),
                top + Math.round(child.getHeight() * scaleY));
    }

    /** Topmost card whose preview contains the given window point, or null. */
    @Nullable
    private Card findCardAtWindowPoint(float windowX, float windowY) {
        for (int i = mCards.size() - 1; i >= 0; i--) {
            final Card card = mCards.get(i);
            if (card.root.getVisibility() != View.VISIBLE) continue;
            // The preview is temporarily hidden while the opening flight covers it, so the card
            // root is the stable hit target for the whole gesture.
            if (containsWindowPoint(card.root, windowX, windowY)) return card;
        }
        return null;
    }

    /** True when the view is visible and the window point falls inside its clipped bounds. */
    private boolean containsWindowPoint(@NonNull View view, float windowX, float windowY) {
        if (!view.getGlobalVisibleRect(mScratchHitRect) || mScratchHitRect.isEmpty()) return false;
        return windowX >= mScratchHitRect.left && windowX < mScratchHitRect.right
                && windowY >= mScratchHitRect.top && windowY < mScratchHitRect.bottom;
    }


    /**
     * Glide just far enough to show a newly appended card in full.
     *
     * <p>The opening restore owns the scroll offset until it has run. A card added in that first
     * frame is remembered and revealed as soon as the restore finishes, instead of being left
     * only partly on screen at the inherited position. Otherwise the closest offset that keeps
     * the card clear of the status bar and the viewport bottom is chosen, so a fifth tab only
     * scrolls the height of the new row instead of recentring the whole grid. The final offset
     * continues by one outer gutter below the card, so the new row is not pinned to the viewport
     * edge; that extra travel is clamped to the content end.</p>
     *
     * <p>The movement uses {@link SessionOverviewScrollView#settleScrollTo(int)}, the same
     * 250 ms decelerating settle a removal uses when the grid shrinks under the viewport. A
     * platform smooth scroll would use a different curve and could be cut short by the next
     * layout-driven {@code scrollTo}.</p>
     */
    private void smoothScrollToCard(@NonNull Card card) {
        if (mScrollView == null || mGrid == null || !mShowing) return;
        // The closing snapshot already captured the viewport. A pending reveal that fires
        // after the snapshot would scroll the grid under the expanding terminal.
        if (mClosingTransition != null || mPendingClosingCard != null) return;
        if (mLayoutTransition != null && mLayoutTransition.isActive()) return;
        // The add-button release opens a terminal outside the grid. Keep the viewport exactly
        // where the gesture left it until the expansion owns the screen; a reveal scroll here
        // would move the whole grid in the first frames of the closing animation.
        if (mTerminalAddFlight != null) return;
        if (mRestoreScrollOnOpen) {
            // The opening restore still owns scrollY. Remember the target and reveal it as soon
            // as that restore finishes; without this the new row can stay only partly on screen.
            mPendingRevealCard = card;
            mPendingRevealAttempts = 0;
            return;
        }
        final SessionOverviewGridMath.Layout layout = mGrid.getGridLayout();
        if (layout == null || !layout.hasCards() || layout.cardHeight <= 0) {
            mPendingRevealCard = card;
            if (++mPendingRevealAttempts <= 20) mGrid.post(this::revealPendingCard);
            else mPendingRevealCard = null;
            return;
        }
        final int viewportHeight = mScrollView.getHeight();
        if (viewportHeight <= 0) {
            mPendingRevealCard = card;
            if (++mPendingRevealAttempts <= 20) mGrid.post(this::revealPendingCard);
            else mPendingRevealCard = null;
            return;
        }
        final int index = mCards.indexOf(card);
        if (index < 0) {
            if (mPendingRevealCard == card) mPendingRevealCard = null;
            return;
        }
        final SessionOverviewGridMath.Slot slot = layout.slot(index);
        if (slot == null) {
            mPendingRevealCard = card;
            if (++mPendingRevealAttempts <= 20) mGrid.post(this::revealPendingCard);
            else mPendingRevealCard = null;
            return;
        }
        mPendingRevealCard = null;
        mPendingRevealAttempts = 0;

        final int topInset = mGrid.getPaddingTop();
        final int maxScroll = Math.max(0, mGrid.getHeight() - viewportHeight);
        final int cardTop = topInset + slot.top;
        // Stop one outer gutter past the card's bottom edge, not flush with the viewport. The
        // larger top/bottom spacing is part of the layout now, so the extra travel uses the same
        // outer gutter; the helper still clamps it to the real content end.
        final int target = SessionOverviewScrollMath.scrollForAppendedCard(
                mScrollView.getScrollY(), viewportHeight, topInset, maxScroll,
                cardTop, cardTop + layout.cardHeight, layout.outerGap);
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).settleScrollTo(target);
        } else if (target != mScrollView.getScrollY()) {
            mScrollView.smoothScrollTo(0, target);
        }
    }

    /** Retry an appended-card reveal that was deferred until the opening restore finished. */
    private void revealPendingCard() {
        final Card card = mPendingRevealCard;
        if (card == null) return;
        if (!mShowing || !mCards.contains(card)) {
            mPendingRevealCard = null;
            return;
        }
        smoothScrollToCard(card);
    }

    /**
     * Restore the grid's opening scroll position while keeping the active card fully visible.
     *
     * <p>This is an opening-only operation: the preferred offset is the one saved when the
     * overview last closed, shifted by the smallest amount needed to keep the active card
     * together with its top and bottom outer gutters between the status-bar strip and the bottom
     * of the viewport. With up to four sessions the scroll range is zero. Deletions, reorders and
     * other model updates must not call it: they leave the viewport where the user scrolled it.</p>
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
        // The add-button release opens a terminal that never occupies a grid slot. Keep the
        // viewport frozen under the source ghost; the closing expansion starts from the button,
        // so a reveal scroll here would read as the grid jumping in the first animation frame.
        if (mTerminalAddFlight != null) return;
        // A newly appended card owns the scroll until its reveal has finished. A layout pass in
        // that window (the new session's terminal geometry can resize every card) would otherwise
        // call scrollTo() and teleport the grid to the active card in one frame.
        if (mLayoutTransition != null && mLayoutTransition.isActive()) return;
        if (mScrollView instanceof SessionOverviewScrollView
                && ((SessionOverviewScrollView) mScrollView).isContentSettling()) {
            return;
        }
        final SessionOverviewGridMath.Layout layout = mGrid.getGridLayout();
        if (layout == null || !layout.hasCards() || layout.cardHeight <= 0) return;
        final int viewportHeight = mScrollView.getHeight();
        if (viewportHeight <= 0) return;

        final int topInset = mGrid.getPaddingTop();
        final int maxScroll = Math.max(0, mGrid.getHeight() - viewportHeight);
        final int currentScroll = mScrollView.getScrollY();
        final Card active = findCard(mActivity.getCurrentSession());
        final int index = active != null ? mCards.indexOf(active) : -1;
        int target;
        if (index < 0) {
            final int preferred = mRestoreScrollOnOpen
                    ? Math.max(0, mRememberedScrollY) : currentScroll;
            target = Math.max(0, Math.min(maxScroll, preferred));
        } else {
            final SessionOverviewGridMath.Slot slot = layout.slot(index);
            if (slot == null) return;
            final int preferred = mRestoreScrollOnOpen
                    ? (mRememberedScrollY >= 0 ? mRememberedScrollY : slot.top)
                    : currentScroll;
            final int cardTop = topInset + slot.top;
            // While a new card is revealing, this layout-driven path must land on the same
            // offset as the explicit append scroll: one outer gutter below the card, clamped to
            // the content end. Otherwise a layout pass could leave the card flush with the edge.
            target = !mRestoreScrollOnOpen && hasAppearingCards()
                    ? SessionOverviewScrollMath.scrollForAppendedCard(
                            preferred, viewportHeight, topInset, maxScroll,
                            cardTop, cardTop + layout.cardHeight, layout.outerGap)
                    : SessionOverviewScrollMath.scrollForActiveCard(
                            preferred, viewportHeight, topInset, maxScroll,
                            cardTop - layout.outerGap,
                            cardTop + layout.cardHeight + layout.outerGap);
        }
        if (currentScroll != target) {
            // A layout pass can arrive after a new card was appended but before the explicit
            // reveal scroll. That pass must glide like the append, not snap to the target; this
            // is the same fallback path that used to teleport the grid to the new terminal.
            if (!mRestoreScrollOnOpen && hasAppearingCards()
                    && mScrollView instanceof SessionOverviewScrollView) {
                ((SessionOverviewScrollView) mScrollView).settleScrollTo(target);
            } else {
                mScrollView.scrollTo(0, target);
            }
        }
    }

    private void buildCards(@NonNull List<TermuxSession> sessions) {
        Trace.beginSection("overview.grid.build");
        releaseCards();
        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;

            final Card card = createCard(session);
            mGrid.addView(card.root);
            mCards.add(card);
        }
        // The cards were created after the pre-build updateGridAspect() call; give every new
        // card its own exact glyph grid and refresh the reference slot.
        updateGridAspect();
        mGrid.requestLayout();
        Trace.endSection();
    }

    /**
     * Inflate and wire one overview card. The caller owns adding it to the grid and to
     * {@link #mCards}; this keeps the initial build and the append path from duplicating the
     * card setup.
     */
    @NonNull
    private Card createCard(@NonNull TerminalSession session) {
        Trace.beginSection("overview.grid.createCard");
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
        close.setOnClickListener(v -> closeSessionFromButton(card));
        applyTabMetrics(card);
        applyCardAppearance(card);
        bindTab(card, session == mActivity.getCurrentSession());
        Trace.endSection();
        return card;
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
        // This is the authoritative new shape. Resources and view bounds can still describe the
        // previous orientation for one or more frames, so remember the dispatched value before
        // any callback below can read them.
        mLastConfiguration = new Configuration(config);
        reloadDimensions();
        // Do not cancel the add-button morph here. A keyboardHidden configuration change is part
        // of the opening transition itself, and the morph's layout listener follows any bottom-
        // strip shift, so the flight can retarget instead of ending on the first IME frame.
        cancelCardCloseAnimation();
        if (mLayoutTransition != null) mLayoutTransition.finishNow();
        // A viewport change cancels every positional animation, including the independent
        // opening/reveal scroll settle, before the new geometry is applied.
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).finishContentTransition();
        }
        if (mDragController != null) mDragController.cancelForModelChange();
        // Notify the grid explicitly: no polling of a layout pass is needed to discover the new
        // viewport shape. Card sizing keeps the stable height; centring follows the visible one.
        if (mGrid != null && mScrollView != null) {
            mGrid.onOverviewViewportChanged(mScrollView.getHeight(), mScrollView.getHeight());
        }
        // The add-session source was captured in the old geometry; drop the ghost and reveal
        // the card normally in the new layout.
        cancelAddTabFlight(true, true);
        cancelTerminalAddFlight(true, true);
        // A closing expansion also captured old geometry. Cancel it before the new window
        // shape is applied, so the overview stays open with a real button and real cards.
        cancelClosingTransition();
        if (!mShowing) return;
        applyOverviewBackground();
        updateGridAspect();
        markResizedPreviewsDirty();
        refreshCardTabMetrics();
        schedulePreviewRender();
    }

    /**
     * Re-apply the strip's current tab height and line count to every card.
     *
     * <p>Used after a configuration change and on resume: a rotation can finish while the
     * activity is stopped, so the overview may still hold cards measured in the old window
     * shape even though {@link TermuxActivity#applyTabHeightMode()} has since updated the strip.
     */
    private void refreshCardTabMetrics() {
        final TerminalSession current = mActivity.getCurrentSession();
        for (Card card : mCards) {
            applyTabMetrics(card);
            applyCardAppearance(card);
            if (card.tabBackground != null) {
                card.tabBackground.setCornerRadius(mTabCornerRadius);
            }
            bindTab(card, card.session == current);
        }
    }

    /**
     * Adopt the corrected window shape after the activity's late reconciliation.
     *
     * <p>This is the late counterpart to {@link #onConfigurationChanged(Configuration)}: if the
     * first dispatch carried the old screen dimensions, the viewport guard and the cards were
     * refreshed from the old strip mode. Keeping the corrected configuration lets the measured
     * overlay and the card tabs agree without waiting for the next rotation.</p>
     */
    public void onWindowShapeCorrected(@NonNull Configuration config) {
        mLastConfiguration = new Configuration(config);
        if (!mShowing || mOverlay == null) return;
        updateGridAspect();
        markResizedPreviewsDirty();
        refreshCardTabMetrics();
        schedulePreviewRender();
    }

    /**
     * Repaint the window background and the card outlines after the live terminal background
     * changes. This runs for OSC palette updates and scheme swaps while the overview is open.
     */
    public void onTerminalBackgroundChanged() {
        if (!mShowing) return;
        // A newly created session reports its palette before the reveal has finished. Repainting
        // the window here would flash the backdrop under the fading card; the reveal end action
        // applies the final colour without cutting the animation.
        if (!hasAppearingCards()) applyOverviewBackground();
        for (Card card : mCards) {
            applyCardAppearance(card);
        }
        // Palette changes can arrive without a text event (for example an OSC colour update).
        // Previews paint the emulator's current palette, so every bitmap has to be re-baked.
        schedulePreviewRender();
    }

    /**
     * Give the card the same system shadow family as the floating terminal controls: four times
     * their elevation with its own alpha fraction. The colour follows the scheme so the shadow
     * stays visible on both light and dark terminals. Resting elevation is left at zero; only
     * {@link SessionOverviewCardView#setHoverHighlight(boolean)} raises it.
     */
    private void applyCardShadow(@NonNull Card card) {
        final android.content.res.Resources res = mActivity.getResources();
        card.root.setHoverShadow(
                FloatingShadow.overviewCardElevationPx(res),
                FloatingShadow.overviewCardShadowColor(res, mActivity.isCachedSchemeLight()));
    }

    /**
     * Cards carry no fill of their own: the terminal frame is the card, with the compact tab
     * floating over its top edge. Removing the background and press ripple keeps the thumbnail
     * from pulsing under the tab when the card is tapped. A card outline in the floating
     * controls' colour is the only chrome; the thumbnail itself casts no shadow.
     */
    private void applyCardAppearance(@NonNull Card card) {
        card.root.setBackground(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            card.root.setForeground(null);
        }
        // Clipping is owned by applyThumbnailCornerRadius(): the root outline is the same
        // rounded rectangle as the preview, so both the frame and the tab stay inside the
        // card's corners. Do not reset it to BOUNDS or disable clipping here.
        // The outline is installed by applyTabMetrics() so the stroke follows the thumbnail's
        // rounded corners. Do not replace it with BOUNDS here; this method also runs when only
        // the terminal background changes.
        // The thumbnail has no resting elevation: its chrome is the outline below. System
        // elevation is added only while the card is the hovered drop target (see
        // applyCardShadow()). The card stroke is intentionally thicker than the text-input
        // stroke; both sit over different content.
        applyCardShadow(card);
        final float strokeWidth = mActivity.getResources().getDimension(
                R.dimen.session_overview_card_stroke);
        mCardStrokeWidth = Math.round(strokeWidth);
        mCardStrokeColor = mActivity.getFloatingButtonStroke();
        card.root.setBorder(mCardStrokeWidth, mCardStrokeColor,
                card.thumbnailCornerRadius);
        // The flight animates the border's alpha instead of toggling it; outlineSuppressed is
        // only the resting state it returns to after the transition.
        card.root.setBorderSuppressed(false);
        card.root.setBorderAlpha(card.outlineSuppressed ? 0f : 1f);
        card.preview.setBackground(null);
    }

    /** Drive the outline painted on the flying frame itself, so it stays visible over the grid. */
    private void applyFlightBorder(@Nullable ImageView ghost, float radius, float alpha) {
        applyFlightBorder(ghost, mCardStrokeWidth, radius, alpha);
    }

    /** Width-aware variant for the add-button source, whose hairline grows into the card stroke. */
    private void applyFlightBorder(@Nullable ImageView ghost, float width,
                                   float radius, float alpha) {
        if (ghost instanceof TopCropImageView) {
            ((TopCropImageView) ghost).setFlightBorder(
                    width, mCardStrokeColor, radius, alpha);
        }
    }

    /**
     * Hide the card's outline while its terminal frame is in flight, and show it again once
     * the frame has landed or the transition is cancelled. Otherwise the empty grid slot would
     * keep a stationary outline while the thumbnail is somewhere between the terminal and the card.
     */
    private void setCardOutlineSuppressed(@Nullable Card card, boolean suppressed) {
        if (card == null || card.outlineSuppressed == suppressed) return;
        card.outlineSuppressed = suppressed;
        applyCardAppearance(card);
    }

    /**
     * Round the preview and the outline with the compact tab's effective radius. The tab height
     * changes with the single/double-line mode and Android clamps a background's corner radius to
     * half the tab height, so a fixed 18dp thumbnail would no longer match the tab. The radius
     * below mirrors that clamp exactly.
     */
    private void applyThumbnailCornerRadius(@NonNull Card card) {
        final float radius = card.thumbnailCornerRadius;
        // The preview outline rounds the bitmap itself. The root outline and clip are the
        // second line of defence: some ImageView implementations and hardware layers do not
        // apply a child outline to the bitmap, while a ViewGroup clipToOutline always clips
        // every child to the rounded card. Both use the same radius, so the outline, preview
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
        card.tab.setMaxLines(maxLines);
        // The overview never uses a platform ellipsis in either line-count mode.
        TextFade.applyFadeContract(card.tab);
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
        final boolean running = session.isRunning();
        final int exitStatus = session.getExitStatus();
        // OSC titles can arrive at 10-30 Hz. Re-tint the cross and re-style the pill only when the
        // colour or run state actually changed, instead of allocating a ColorStateList and
        // invalidating the cross on every title tick.
        if (!card.stateValid || card.running != running
                || card.exitStatus != exitStatus || card.textColor != textColor) {
            card.close.setImageTintList(ColorStateList.valueOf(textColor));
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

    /** Read the user-facing FPS cap once per overview show/resume and cache its interval. */
    private void refreshPreviewThrottleInterval() {
        final TermuxAppSharedPreferences prefs = mActivity.getPreferences();
        final int fps = prefs != null
            ? prefs.getSessionOverviewPreviewFps()
            : TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_SESSION_OVERVIEW_PREVIEW_FPS;
        mPreviewIntervalMs = SessionOverviewPreviewThrottle.intervalMs(fps);
    }

    /**
     * Mark every card for a full preview repaint.
     *
     * <p>This is used for geometry, palette, configuration and session-list changes. Content
     * updates use the much narrower {@link #onSessionTextChanged} path instead. Each card still
     * owns its own request, throttle and raster job; this only marks the state that changed.</p>
     */
    private void schedulePreviewRender() {
        if (!mShowing || mActivityStopped || mGrid == null) return;
        for (Card card : mCards) card.previewDirty = true;
        requestVisibleDirtyPreviews();
    }

    /** Ask every dirty card that intersects the viewport to schedule its own raster. */
    private void requestVisibleDirtyPreviews() {
        if (!mShowing || mActivityStopped || mGrid == null || mScrollView == null
                || mCardWidth <= 0 || mCardHeight <= 0) {
            return;
        }
        // A layout/scroll can land in the tap-to-flight gap. Do not swap a thumbnail under the
        // closing flight; the next output or the post-flight schedule picks it up.
        if (mPendingClosingCard != null || mClosingTransition != null) return;
        final int count = Math.min(mCards.size(), mGrid.getChildCount());
        if (count == 0) return;
        final int scrollY = mScrollView.getScrollY();
        final int viewportHeight = mScrollView.getHeight();
        mGrid.getVisibleChildRange(scrollY, viewportHeight, mVisibleChildRange);
        final int first = Math.min(count, mVisibleChildRange[0]);
        final int end = Math.min(count, mVisibleChildRange[1]);
        for (int i = first; i < end; i++) {
            requestCardPreview(mCards.get(i));
        }
    }

    /**
     * Schedule one card's next preview. This is the only entry point that submits preview work;
     * it never touches another card.
     */
    private void requestCardPreview(@NonNull Card card) {
        if (!mShowing || mActivityStopped || mGrid == null) return;
        if (!mCards.contains(card) || card.closing) return;
        // The add morph borrows this card's bitmap for its direct preview copy. A swap would
        // replace the pixels the ghost is still drawing; wait until the flight has landed.
        if (card == mAddTabFlightCard) return;
        if (mPendingClosingCard != null || mClosingTransition != null) return;
        if (card.previewInFlight) {
            if (card.previewDirty) card.previewPending = true;
            return;
        }
        if (!card.previewDirty) return;
        if (!isCardPreviewVisibleNow(card)) return;

        final TerminalEmulator emulator = card.session.getEmulator();
        if (emulator == null) {
            card.preview.setImageDrawable(null);
            releaseBitmap(card);
            card.previewDirty = false;
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final long delay = SessionOverviewPreviewThrottle.remainingDelayMs(
                card.previewLastSubmitMs, now, mPreviewIntervalMs);
        if (delay > 0L) {
            postCardPreviewRetry(card, delay);
            return;
        }

        final int textSize = previewTextSize();
        final Typeface typeface = previewTypeface();
        final GridRaster raster = mGridRaster;
        final int viewWidth = card.preview.getWidth();
        final int viewHeight = card.preview.getHeight();
        final int bitmapWidth = raster != null
                ? raster.widthFor(emulator.mColumns) : (viewWidth > 0 ? viewWidth : mCardWidth);
        final int bitmapHeight = raster != null
                ? raster.heightFor(emulator.mRows) : (viewHeight > 0 ? viewHeight : mCardHeight);
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return;

        final int backgroundTransparency = previewBackgroundTransparency();
        // Keep 8-bit channels: RGB_565 turns neutral greys green. See
        // TerminalPreviewRenderer.BITMAP_CONFIG.
        final Bitmap target = obtainPreviewBack(card, bitmapWidth, bitmapHeight,
                TerminalPreviewRenderer.BITMAP_CONFIG);
        if (target == null) return;

        final int topRow = scrollTopRow(card.session);
        final TerminalRenderSnapshot snapshot;
        try {
            snapshot = TerminalRenderSnapshot.capture(emulator, topRow);
        } catch (RuntimeException ignored) {
            // The emulator may have been resized between the size read and the copy. The next
            // output/layout callback will request again.
            return;
        }

        card.previewDirty = false;
        card.previewPending = false;
        card.previewInFlight = true;
        card.previewLastSubmitMs = now;
        final int generation = ++card.previewGeneration;
        Trace.beginSection("overview.preview.card.submit");
        mRasterizer.submitPreview(snapshot, target, textSize, typeface,
                backgroundTransparency, generation,
                completedGeneration -> onCardPreviewRendered(card, target, completedGeneration));
        Trace.endSection();
    }

    private void postCardPreviewRetry(@NonNull Card card, long delayMs) {
        if (card.previewRetryPosted || mGrid == null) return;
        card.previewRetryPosted = true;
        final Runnable retry = () -> {
            card.previewRetryPosted = false;
            card.previewRetryRunnable = null;
            if (mCards.contains(card)) requestCardPreview(card);
        };
        card.previewRetryRunnable = retry;
        mGrid.postDelayed(retry, delayMs);
    }

    /** Select the offscreen buffer for the next render. It is never the bitmap on screen. */
    @Nullable
    private Bitmap obtainPreviewBack(@NonNull Card card, int width, int height,
                                     @NonNull Bitmap.Config config) {
        final Bitmap back = card.previewBack;
        if (back != null && !back.isRecycled()
                && back.getWidth() == width && back.getHeight() == height
                && back.getConfig() == config) {
            return back;
        }
        if (back != null && !back.isRecycled()) recycleBitmap(back);
        try {
            final Bitmap allocated = Bitmap.createBitmap(width, height, config);
            card.previewBack = allocated;
            return allocated;
        } catch (OutOfMemoryError ignored) {
            card.previewBack = null;
            return null;
        }
    }

    private void onCardPreviewRendered(@NonNull Card card, @NonNull Bitmap target,
                                       int generation) {
        Trace.beginSection("overview.preview.card.attach");
        try {
            if (!mCards.contains(card) || card.previewGeneration != generation) {
                card.previewInFlight = false;
                if (card.previewBack == target) card.previewBack = null;
                recycleBitmap(target);
                return;
            }

            // The ghost still holds the old front bitmap. Recycle the finished back buffer and
            // keep the card untouched until the morph has landed.
            if (card == mAddTabFlightCard) {
                card.previewInFlight = false;
                if (card.previewBack == target) card.previewBack = null;
                recycleBitmap(target);
                return;
            }

            final Bitmap oldFront = card.bitmap;
            card.bitmap = target;
            card.previewBack = card.previewSpare;
            card.previewSpare = oldFront;
            card.previewInFlight = false;
            if (!card.bitmapAttached || oldFront != target) {
                card.preview.setImageBitmap(target);
                card.bitmapAttached = true;
            } else {
                card.preview.invalidate();
            }
            if (card.previewPending || card.previewDirty) {
                card.previewPending = false;
                requestCardPreview(card);
            }
        } finally {
            Trace.endSection();
        }
    }

    private boolean isCardPreviewVisibleNow(@NonNull Card card) {
        if (mGrid == null || mScrollView == null) return false;
        return isCardPreviewVisible(card, mScrollView.getScrollY(),
                mScrollView.getHeight(), mGrid.getTop());
    }

    /**
     * Whether a card intersects the actual overview viewport.
     *
     * <p>A card without bounds has not been through the current layout pass yet. It is skipped
     * here; the grid's layout-change listener issues another request as soon as the pass assigns
     * real bounds.</p>
     */
    private boolean isCardPreviewVisible(@NonNull Card card, int scrollY, int viewportHeight,
                                         int gridTop) {
        if (mGrid == null) return true;
        if (card.root.getWidth() <= 0 || card.root.getHeight() <= 0) return false;
        return SessionOverviewPreviewPolicy.isVisible(
                gridTop + card.root.getTop(), gridTop + card.root.getBottom(),
                scrollY, viewportHeight);
    }

    /** A scroll asks only the visible dirty cards to schedule their own raster. */
    private void onOverviewScrolled() {
        if (!mShowing || mActivityStopped || mGrid == null) return;
        Trace.beginSection("overview.grid.scroll");
        requestVisibleDirtyPreviews();
        Trace.endSection();
    }

    /**
     * Background transparency used by every overview thumbnail and flight frame.
     *
     * <p>The overview backdrop is only slightly opaque, so each thumbnail still paints the
     * terminal background itself at the same alpha the live terminal uses. At 0% the historical
     * opaque bitmap path is kept.</p>
     */
    private int previewBackgroundTransparency() {
        return mActivity.getEffectiveBackgroundTransparency();
    }

    /**
     * Decor colour behind the overview grid.
     *
     * <p>With the wallpaper feature on the backdrop transparency is the terminal's plus a fixed
     * 20 percentage points, so the wallpaper stays visible while the grid is dimmed more than the
     * terminal itself. The hole under a flying terminal is painted back with the terminal colour
     * and alpha at the flight endpoints instead of being fully clear; that endpoint tint is what
     * keeps the rounded corners from flickering. At 0% the terminal colour stays opaque.</p>
     */
    private int overviewBackdropColor(int color) {
        if (!mActivity.isWallpaperVisibleBehindTerminal()) return color;
        return withAlpha(color, Math.round(255f * mOverviewBackdropAlpha));
    }

    /** Refresh the cached backdrop alpha from the live terminal transparency. */
    private void updateOverviewBackdropAlpha() {
        if (!mActivity.isWallpaperVisibleBehindTerminal()) {
            mOverviewBackdropAlpha = 1f;
            return;
        }
        final int transparency = Math.min(100,
                OVERVIEW_BACKDROP_TRANSPARENCY_OFFSET
                        + mActivity.getEffectiveBackgroundTransparency());
        mOverviewBackdropAlpha = (100f - transparency) / 100f;
    }

    /** Fraction of the overview tint to draw back inside the cut-out at a flight endpoint. */
    private static float overviewFillFraction(float progress, boolean opening) {
        final float p = SessionOverviewTransitionMath.clamp01(progress);
        final float t = opening
                ? (p - (1f - OVERVIEW_BACKDROP_HANDOFF)) / OVERVIEW_BACKDROP_HANDOFF
                : 1f - p / OVERVIEW_BACKDROP_HANDOFF;
        return SessionOverviewTransitionMath.clamp01(t);
    }

    private int previewTextSize() {
        final TermuxAppSharedPreferences prefs = mActivity.getPreferences();
        return prefs != null
                ? prefs.getFontSizePx()
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
     * <p>The terminal may resize from an IME-compressed height to its current height after the
     * grid has already been built. The normal grid is recomputed from the viewport that exists
     * now and published to stale sessions before the cards are measured again.</p>
     */
    public void onTerminalViewportChanged() {
        if (mOpeningPreparationPending) {
            // Do not build the grid from a viewport that is still growing. The same callback
            // is the event that tells the opening phase when the terminal layout is final.
            tryFinishOpeningPreparation();
            return;
        }
        if (!mShowing || mClosingTransition != null || mPendingClosingCard != null) return;
        // The activity reports this after the real terminal viewport has been laid out. Apply the
        // new shape immediately and drop any positional animation instead of polling frames.
        if (mGrid != null && mScrollView != null) {
            final int visibleHeight = mScrollView.getHeight();
            mGrid.onOverviewViewportChanged(visibleHeight, visibleHeight);
        }
        if (mLayoutTransition != null) mLayoutTransition.finishNow();
        if (mScrollView instanceof SessionOverviewScrollView) {
            ((SessionOverviewScrollView) mScrollView).finishContentTransition();
        }
        // Reaching MAX_SESSIONS hides the tab-strip "+", which shrinks the tab panel and changes
        // the terminal viewport. That can land in the middle of the add-button morph. The flight
        // card must stay at alpha 0 until the ghost lands; skip only its visual reset here.
        if (mDragController != null) {
            mDragController.cancelForModelChange(
                    mAddTabFlightCard != null ? mAddTabFlightCard.root : null);
        }
        updateGridAspect();
        markResizedPreviewsDirty();
        schedulePreviewRender();
    }

    /**
     * Viewport the card grid should be sized for: the terminal's glyph-grid viewport, not the
     * full-bleed pager.
     *
     * <p>{@link TermuxActivity#getTerminalContentViewportForOverview()} subtracts the per-page
     * {@code terminal-margin-*} layout margins and adds the IME inset back. The overlay and the
     * dispatched configuration are used only as orientation guards: after a rotation the pager
     * (and the active page) can still describe the previous shape for a frame. Do not invent
     * exact dimensions from them; the overlay includes the tab strip, toolbar and system bars,
     * and {@code screenWidthDp/screenHeightDp} describe the whole window, so either would give
     * the cards the wrong aspect. Returning zero lets {@link #updateGridAspect()} fall back to
     * the active emulator until layout catches up.</p>
     *
     * @return packed {@code {width, height}}, either axis may be zero before the first layout
     */
    private int[] currentGridViewport() {
        final int[] viewport = mActivity.getTerminalContentViewportForOverview();
        final int width = viewport[0];
        final int height = viewport[1];
        if (width <= 0 || height <= 0) return new int[]{0, 0};

        // The overlay is this window's actual surface. Its orientation is authoritative in
        // split-screen and floating windows, where the activity's Configuration orientation can
        // disagree with the window's aspect.
        if (mScrollView != null && mGrid != null && width != height) {
            final int overlayWidth = mScrollView.getWidth()
                    - mGrid.getPaddingLeft() - mGrid.getPaddingRight();
            final int overlayHeight = mScrollView.getHeight()
                    - mGrid.getPaddingTop() - mGrid.getPaddingBottom();
            if (overlayWidth > 0 && overlayHeight > 0) {
                if ((width > height) != (overlayWidth > overlayHeight)) return new int[]{0, 0};
                return viewport;
            }
        }

        // No measured overlay yet (cold start). Configuration is a weaker guard, but it is only
        // used while the window surface cannot answer for itself.
        final Configuration config = mLastConfiguration != null
                ? mLastConfiguration
                : mActivity.getResources().getConfiguration();
        if (width != height
                && (config.orientation == Configuration.ORIENTATION_LANDSCAPE
                    || config.orientation == Configuration.ORIENTATION_PORTRAIT)) {
            final boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
            if ((width > height) != landscape) return new int[]{0, 0};
        }
        return viewport;
    }

    /**
     * Choose the glyph grid the overview uses for every card and publish it to stale sessions.
     *
     * <p>The grid is computed once from the terminal content viewport (pager minus the
     * per-page margins, with the IME height added back), or from the active emulator's own grid
     * when there is no measurable view yet. Both entry points return the same
     * {@link ViewportGrid}, so the columns and rows the sessions are resized to and the
     * reference grid the shared raster is fitted for cannot disagree.</p>
     */
    private void updateGridAspect() {
        if (mGrid == null) return;
        Trace.beginSection("overview.grid.raster");
        final int textSize = previewTextSize();
        final Typeface typeface = previewTypeface();
        final int[] viewport = currentGridViewport();
        final int viewportWidth = viewport[0];
        final int viewportHeight = viewport[1];
        final TerminalSession session = mActivity.getCurrentSession();
        final TerminalEmulator emulator = session != null ? session.getEmulator() : null;
        final GridRaster previousRaster = mGridRaster;

        // The content viewport knows the new window shape before the active terminal page has
        // been laid out. The active emulator remains a fallback for the first frames of a cold
        // start, when neither view has a size yet.
        ViewportGrid grid = viewportWidth > 0 && viewportHeight > 0
                ? TerminalPreviewRenderer.viewportGrid(
                        viewportWidth, viewportHeight, textSize, typeface)
                : null;
        if (grid == null && emulator != null) {
            grid = TerminalPreviewRenderer.emulatorGrid(emulator, textSize, typeface);
        }
        if (grid != null) {
            refreshSessionSizesToCurrentWindow(grid);
            final int[] slot = mGrid.getMaxCardSlotSize();
            mGridRaster = TerminalPreviewRenderer.fitGridRaster(grid.columns, grid.rows,
                    slot[0], slot[1], textSize, typeface);
            if (mGridRaster != null) {
                mGrid.setCardSize(mGridRaster.widthFor(grid.columns),
                        mGridRaster.heightFor(grid.rows));
            } else {
                // Degenerate metrics: fall back to the aspect path, which renders uniformly.
                mGrid.setCardSize(0, 0);
                mGrid.setCardAspect(grid.aspect());
            }
        } else {
            mGridRaster = null;
            mGrid.setCardSize(0, 0);
            mGrid.setCardAspect(0);
        }
        // The raster is cached by its inputs, so a different instance means the glyph pitch
        // changed. The card may keep the same pixel size (for example when the width limits both
        // orientations), which would leave markResizedPreviewsDirty() with nothing to mark; every
        // preview still has to be repainted with the new pitch.
        if (mGridRaster != previousRaster) {
            for (Card card : mCards) card.previewDirty = true;
        }
        Trace.endSection();
    }

    /**
     * Bring every session that still carries a stale terminal size to the window's normal grid.
     *
     * <p>Only the pager's active page and its neighbours publish their view size to their
     * sessions. A session several tabs away can therefore keep the compressed grid it had when
     * the keyboard was last up: its preview was painted at that smaller grid while its card was
     * sized for a normal terminal, and the grid only became normal after the card was selected.
     * Publishing here makes the thumbnail and the live terminal agree from the first frame.</p>
     *
     * <p>{@link TerminalEmulator#hasSize} keeps this idempotent: an already-normal session costs
     * one comparison and no pty ioctl. A session with no emulator is skipped on purpose —
     * resizing it would fork a shell just to draw a thumbnail. Every caller of
     * {@link #updateGridAspect()} schedules a full preview render, so no per-card dirty marking
     * is needed here.</p>
     */
    private void refreshSessionSizesToCurrentWindow(@NonNull ViewportGrid grid) {
        final TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        // updateSize() fires session callbacks; iterate a snapshot in case one of them binds a
        // page and changes the live list while we are publishing.
        for (TermuxSession termuxSession : new ArrayList<>(service.getTermuxSessions())) {
            if (termuxSession == null) continue;
            final TerminalSession session = termuxSession.getTerminalSession();
            if (session == null) continue;
            final TerminalEmulator emulator = session.getEmulator();
            if (emulator == null || grid.matches(emulator)) continue;
            session.updateSize(grid.columns, grid.rows, grid.cellWidthPx, grid.cellHeightPx);
        }
    }

    private boolean renderCardNow(@NonNull Card card, int textSize, @NonNull Typeface typeface) {
        final TerminalEmulator emulator = card.session.getEmulator();
        if (emulator == null) {
            card.preview.setImageDrawable(null);
            releaseBitmap(card);
            card.previewDirty = false;
            return false;
        }

        // The raster fixes only the bitmap rectangle. The ImageView is CENTER, so a card whose
        // grid is smaller than the reference slot is centred at 1:1, never stretched to the card
        // edges. TerminalPreviewRenderer paints at the user's integer ppem with one uniform
        // canvas scale: no width-derived textScaleX and no fractional-size subpixel glyph edges,
        // which is what produced hairline gaps between neighbouring cells.
        Trace.beginSection("overview.preview.card");
        final GridRaster raster = mGridRaster;
        final int viewWidth = card.preview.getWidth();
        final int viewHeight = card.preview.getHeight();
        final int bitmapWidth = raster != null
                ? raster.widthFor(emulator.mColumns) : (viewWidth > 0 ? viewWidth : mCardWidth);
        final int bitmapHeight = raster != null
                ? raster.heightFor(emulator.mRows) : (viewHeight > 0 ? viewHeight : mCardHeight);
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            Trace.endSection();
            return false;
        }

        final int backgroundTransparency = previewBackgroundTransparency();
        // Same 8-bit format as the async path: RGB_565 gives light greys a green cast.
        final Bitmap.Config bitmapConfig = TerminalPreviewRenderer.BITMAP_CONFIG;
        final Bitmap oldBitmap = card.bitmap;
        final boolean sizeChanged = oldBitmap != null
                && (oldBitmap.getWidth() != bitmapWidth
                || oldBitmap.getHeight() != bitmapHeight);
        final boolean configChanged = oldBitmap != null
                && oldBitmap.getConfig() != bitmapConfig;
        Trace.beginSection("overview.preview.alloc");
        if (oldBitmap == null || sizeChanged || configChanged) {
            card.bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, bitmapConfig);
        }
        Trace.endSection();
        final boolean newBitmap = oldBitmap != card.bitmap;

        final int topRow = scrollTopRow(card.session);
        // One uniform scale for every card, the same path the flight frames use. The bitmap
        // already has the raster's exact grid size, so the ImageView never scales it again.
        Trace.beginSection("overview.preview.render");
        TerminalPreviewRenderer.render(emulator, card.bitmap, textSize, typeface, topRow,
                backgroundTransparency);
        Trace.endSection();
        // Keep the old preview visible until the replacement has been fully rendered. Clearing
        // the ImageView first would expose a blank card for a frame whenever the grid aspect
        // changes, for example after closing the active session.
        // setImageBitmap() creates a new BitmapDrawable even for the same Bitmap, so only do it
        // when the bitmap object actually changed. Most repaints reuse the bitmap and only need
        // an invalidation.
        Trace.beginSection("overview.preview.attach");
        if (!card.bitmapAttached || newBitmap) {
            card.preview.setImageBitmap(card.bitmap);
            card.bitmapAttached = true;
        } else {
            card.preview.invalidate();
        }
        if (newBitmap && oldBitmap != null) {
            recycleBitmap(oldBitmap);
        }
        Trace.endSection();
        card.previewDirty = false;
        Trace.endSection();
        return true;
    }

    /** Mark a stale bitmap dirty after a layout pass; the throttle agent reuses this flag. */
    private void markResizedPreviewsDirty() {
        final GridRaster raster = mGridRaster;
        for (Card card : mCards) {
            final Bitmap bitmap = card.bitmap;
            final TerminalEmulator emulator = card.session.getEmulator();
            // With a raster the bitmap is the exact natural-metric grid, not the view rectangle.
            // A smaller session is centred in its card by the ImageView, so comparing it to the
            // view size would mark every such card dirty on every pass.
            final int width;
            final int height;
            if (raster != null && emulator != null) {
                width = raster.widthFor(emulator.mColumns);
                height = raster.heightFor(emulator.mRows);
            } else {
                width = card.preview.getWidth();
                height = card.preview.getHeight();
            }
            if (width <= 0 || height <= 0) continue;
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                card.previewDirty = true;
            }
        }
    }

    private int scrollTopRow(@NonNull TerminalSession session) {
        final TerminalView active = mActivity.getActiveTerminalView();
        if (active != null && active.getCurrentSession() == session) {
            return active.getTopRow();
        }
        return mActivity.getTextInputState().getScrollTopRow(session);
    }

    private void releaseCards() {
        cancelAddTabFlight(false, false);
        cancelTerminalAddFlight(false, false);
        mTerminalAddGridHeld = false;
        mPendingRevealCard = null;
        disarmGestureSelection();
        stopAutoScroll();
        resetGestureHover();
        if (mDragController != null) mDragController.cancelForModelChange();
        for (Card card : mCards) {
            card.root.animate().cancel();
            card.appearing = false;
            card.previewGeneration++;
            card.previewRetryPosted = false;
            if (mGrid != null && card.previewRetryRunnable != null) {
                mGrid.removeCallbacks(card.previewRetryRunnable);
            }
            card.previewRetryRunnable = null;
            card.preview.setImageDrawable(null);
            releaseBitmap(card);
        }
        mCards.clear();
        if (mGrid != null) mGrid.removeAllViews();
    }

    private static void releaseBitmap(@NonNull Card card) {
        final Bitmap front = card.bitmap;
        final Bitmap back = card.previewBack;
        final Bitmap spare = card.previewSpare;
        card.bitmap = null;
        card.bitmapAttached = false;
        card.previewBack = null;
        card.previewSpare = null;
        // An in-flight raster owns `back` until its completion runs on the main thread. The
        // generation bump makes that completion discard it and recycle it safely.
        recycleBitmap(front);
        if (spare != front) recycleBitmap(spare);
        if (!card.previewInFlight && back != front && back != spare) recycleBitmap(back);
        card.previewInFlight = false;
        card.previewPending = false;
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
     * The flying copy of one strip tab. It moves and fades out at its source size, is placed at
     * its final card size and style while invisible, then moves the rest of the way and fades
     * in. Size and title are laid out at most twice, never once per animation frame.
     *
     * <p>The close-cross ghost shares the title ghost's translation in both phases. It therefore
     * stays attached to the pill's end instead of interpolating toward the target cross on its
     * own; the target cross simply fades in with the target pill.</p>
     *
     * <p>{@link #cardVisible} is captured from the overview viewport before the flight. A tab
     * whose card is off screen still flies to that card's real grid slot, but its thumbnail stays
     * put and the ghost travels alone.</p>
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
        final boolean cardVisible;
        @Nullable
        Rect targetBounds;
        boolean targetApplied;
        boolean closeTargetApplied;

        TabGhost(Card card, SessionOverviewTabTitleView view, GradientDrawable background,
                 Rect sourceBounds, @Nullable ImageView closeGhost,
                 @Nullable Rect closeSourceBounds, boolean cardVisible) {
            this.card = card;
            this.view = view;
            this.background = background;
            this.sourceBounds = new Rect(sourceBounds);
            this.closeGhost = closeGhost;
            this.closeSourceBounds = closeSourceBounds != null
                    ? new Rect(closeSourceBounds) : null;
            this.cardVisible = cardVisible;
        }
    }

    /**
     * The reverse of one opening tab flight. Every card tab owns one of these, including tabs
     * whose cards are outside the overview viewport. It moves and fades out at card size, is
     * placed at strip size and style while invisible, then moves the rest of the way and fades
     * in. Size and title are laid out at most twice, never once per animation frame.
     *
     * <p>{@link #cardVisible} is captured before the expansion. An off-screen card keeps its tab
     * ghost travelling alone from the card's real grid slot; only a visible thumbnail is carried
     * with the tab.</p>
     *
     * <p>When the target strip tab has a visible cross, the cross ghost copies the title
     * ghost's translation and stays attached to the pill end in both phases.</p>
     */
    private static final class ClosingTabGhost {
        final Card card;
        final SessionOverviewTabTitleView view;
        final GradientDrawable background;
        final Rect startBounds;
        /** Resting tab rect in content coordinates, excluding the card's temporary translation. */
        final Rect anchorBounds;
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
        /** True when the card thumbnail was inside the viewport before the expansion. */
        boolean cardVisible;
        boolean targetApplied;
        boolean closeTargetApplied;
        /** True once the real card pill has been covered by the laid-out ghost. */
        boolean cardChromeHidden;
        /** Visibility to put back on the strip tab after the flight. */
        int targetTabVisibility = View.VISIBLE;
        /** Alpha of the real card pill and cross before they were hidden for this ghost. */
        float tabBaseAlpha = 1f;
        float closeBaseAlpha = 1f;

        ClosingTabGhost(Card card, SessionOverviewTabTitleView view,
                        GradientDrawable background, Rect startBounds, Rect anchorBounds,
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
            this.anchorBounds = new Rect(anchorBounds);
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

    /**
     * The target-phase ghost for the strip tab created by the add-button release. Unlike the
     * other returning tabs it has no card source: the slot is already reserved in the panel
     * copy and the ghost only materialises there during the last third of the flight.
     */
    private static final class ClosingNewTabGhost {
        final FrameLayout view;
        final View targetTabView;
        final int targetTabVisibility;

        ClosingNewTabGhost(@NonNull FrameLayout view,
                           @NonNull View targetTabView,
                           int targetTabVisibility) {
            this.view = view;
            this.targetTabView = targetTabView;
            this.targetTabVisibility = targetTabVisibility;
        }
    }

    /**
     * Visual state captured from the overview "+" button at release. The source ghost and the
     * closing transition both start from these exact values, including a hover that has not
     * finished yet.
     */
    private static final class AddButtonSource {
        final Rect visualRect;
        final float scaleX;
        final float scaleY;
        final float buttonAlpha;
        final float imageAlpha;
        final float radius;
        final float strokeWidth;
        final int fillColor;
        final int strokeColor;
        @Nullable final Drawable plusIcon;
        final float plusIconSize;
        final int elevationPx;
        final int shadowColor;

        AddButtonSource(@NonNull Rect visualRect, float scaleX, float scaleY,
                        float buttonAlpha, float imageAlpha, float radius,
                        float strokeWidth, int fillColor, int strokeColor,
                        @Nullable Drawable plusIcon, float plusIconSize,
                        int elevationPx, int shadowColor) {
            this.visualRect = new Rect(visualRect);
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.buttonAlpha = buttonAlpha;
            this.imageAlpha = imageAlpha;
            this.radius = radius;
            this.strokeWidth = strokeWidth;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.plusIcon = plusIcon;
            this.plusIconSize = plusIconSize;
            this.elevationPx = elevationPx;
            this.shadowColor = shadowColor;
        }
    }

    /** Source ghost and the pending new card between release and the closing hand-off. */
    private static final class TerminalAddFlight {
        final AddButtonSource source;
        final OverviewAddTabGhostView overlayGhost;
        final int sessionCountBefore;
        @Nullable Card card;
        boolean handedOff;

        TerminalAddFlight(@NonNull AddButtonSource source,
                          @NonNull OverviewAddTabGhostView overlayGhost,
                          int sessionCountBefore) {
            this.source = source;
            this.overlayGhost = overlayGhost;
            this.sessionCountBefore = sessionCountBefore;
        }
    }

    /**
     * Visual state captured from a hovered card at the instant a closing selection starts.
     * A hover animation can still be in flight on release, so geometry must be frozen together;
     * re-reading the live view would let the expansion start from a different scale. Opacity is
     * always full: hover selection does not dim the card, and the opening flight must not start
     * from a translucent preview.
     */
    private static final class LiftStart {
        final Rect previewRect;
        final float scale;
        final float alpha;
        final float liftZ;
        final int shadowColor;
        /** Non-null when the source is the overview "+" button, not a card. */
        @Nullable final AddButtonSource addButtonSource;

        LiftStart(@NonNull Rect previewRect, float scale, float alpha, float liftZ,
                  int shadowColor) {
            this(previewRect, scale, alpha, liftZ, shadowColor, null);
        }

        LiftStart(@NonNull Rect previewRect, float scale, float alpha, float liftZ,
                  int shadowColor, @Nullable AddButtonSource addButtonSource) {
            this.previewRect = previewRect;
            this.scale = scale;
            this.alpha = alpha;
            this.liftZ = liftZ;
            this.shadowColor = shadowColor;
            this.addButtonSource = addButtonSource;
        }
    }

    /** Everything owned by one closing expansion, released as a unit. */
    private static final class ClosingTransition {
        final Card card;
        final TerminalSession session;
        @Nullable TerminalFrameSource frame;
        final Rect cardStartLocal;
        final Rect terminalTargetLocal;
        ImageView terminalGhost;
        /** Reused across frames so the flight does not allocate a provider per frame. */
        RoundedOutlineProvider terminalOutline;
        /** Window surface blend from the overview backdrop back to the app background. */
        int backgroundStart;
        int backgroundEnd;
        boolean backgroundTransition;
        SessionOverviewTabStripGhostView tabPanelGhost;
        final List<ClosingTabGhost> tabGhosts = new ArrayList<>();
        @Nullable ClosingNewTabGhost newTabGhost;
        ValueAnimator animator;
        boolean started;
        /** True when the expansion was selected from a lifted card. */
        boolean liftedStart;
        /** Transform/opacity frozen from that card; 1/0 for a normal tap. */
        float startAlpha = 1f;
        float startScale = 1f;
        float startLiftZ;
        /** Elevation hung on the ghost itself for the whole hover-handoff flight. */
        int startElevationPx;
        int shadowColor;
        /** Non-null when the expansion starts from the overview "+" button. */
        @Nullable AddButtonSource addButtonSource;
        float startCornerRadius;
        /** The exact source ghost installed at release; it lives in the overlay above the terminal. */
        @Nullable OverviewAddTabGhostView addButtonSourceGhost;
        /** True once the source shadow has been handed to the terminal ghost. */
        boolean addButtonSourceShadowHandedOff;
        float tabBaseAlpha;
        float closeBaseAlpha;
        /** Flying copy of the target scrollbar thumb; slides back and fades in with the expansion. */
        @Nullable View scrollbarThumbGhost;
        int scrollbarThumbShiftPx;

        ClosingTransition(Card card, @Nullable TerminalFrameSource frame,
                         @NonNull Rect cardStartLocal, @NonNull Rect terminalTargetLocal) {
            this.card = card;
            this.session = card.session;
            this.frame = frame;
            this.cardStartLocal = new Rect(cardStartLocal);
            this.terminalTargetLocal = new Rect(terminalTargetLocal);
        }
    }

    /** Everything owned by one opening flight, released as a unit. */
    private static final class Transition {
        @Nullable TerminalFrameSource frame;
        @Nullable TerminalSession session;
        Rect terminalSource;
        Rect terminalSourceLocal;
        ImageView terminalGhost;
        /** Reused across frames so the flight does not allocate a provider per frame. */
        RoundedOutlineProvider terminalOutline;
        /** Window surface blend from the app background into the overview backdrop. */
        int backgroundStart;
        int backgroundEnd;
        boolean backgroundTransition;
        SessionOverviewTabStripGhostView tabPanelGhost;
        Card activeCard;
        final List<SourceTab> tabSources = new ArrayList<>();
        final List<TabGhost> tabGhosts = new ArrayList<>();
        final List<View> hiddenTabs = new ArrayList<>();
        final List<Card> hiddenPills = new ArrayList<>();
        ValueAnimator animator;
        /** Hard start fallback posted by show(); removed as soon as this flight is cleaned up. */
        Runnable startFallback;
        boolean started;
        /** True once the final overview viewport has selected the tab sources for the flight. */
        boolean tabFlightsInstalled;
        int startAttempts;
        /** Flying copy of the live scrollbar thumb; slides right and fades out with the opening. */
        @Nullable View scrollbarThumbGhost;
        int scrollbarThumbShiftPx;
        /** Thumb capsule captured with the terminal frame, in window coordinates. */
        @Nullable Rect scrollbarThumbSourceWindow;
        boolean scrollbarThumbDragging;
    }

    /** Rounds a view to the tab's effective corner radius. */
    private static final class RoundedOutlineProvider extends ViewOutlineProvider {
        private float mRadius;

        RoundedOutlineProvider(float radius) {
            mRadius = Math.max(0f, radius);
        }

        /** Update the flight radius in place instead of allocating a provider every frame. */
        void setRadius(float radius) {
            radius = Math.max(0f, radius);
            if (mRadius == radius) return;
            mRadius = radius;
            // ViewOutlineProvider has no invalidation method; the owning View must be told to
            // re-query getOutline(). The controller does that on the ghost after this call.
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
        /** True while {@link #preview} holds a drawable created from {@link #bitmap}. */
        boolean bitmapAttached;
        /** Exact size of this session's glyph grid; 0 when it has no emulator. */
        GradientDrawable tabBackground;
        int tabBackgroundColor;
        /** Effective radius of the compact tab, reused by the thumbnail and its outline. */
        float thumbnailCornerRadius;
        /** True while the terminal frame owns the visual during an opening/closing flight. */
        boolean outlineSuppressed;
        String fullTitle = "";
        boolean selected;
        boolean stateValid;
        boolean running;
        int exitStatus;
        int textColor;
        boolean closing;
        /** True while the card is fading and growing into a newly appended slot. */
        boolean appearing;
        /** True until a snapshot for the latest content has been submitted. */
        boolean previewDirty = true;
        /** True while a snapshot is being rasterized on the preview thread. */
        boolean previewInFlight;
        /** New output arrived while the current snapshot was in flight. */
        boolean previewPending;
        /** A per-card throttle retry is already posted. */
        boolean previewRetryPosted;
        @Nullable Runnable previewRetryRunnable;
        /** Submission time used by the per-card FPS cap. */
        long previewLastSubmitMs;
        /** Invalidates completions after a release or a newer snapshot. */
        int previewGeneration;
        /** Target for the next background render; never the bitmap on screen. */
        @Nullable Bitmap previewBack;
        /** Third buffer, rotated so a detached front is not reused for at least one render. */
        @Nullable Bitmap previewSpare;

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
