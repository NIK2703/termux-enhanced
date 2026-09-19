package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.termux.R;

import java.util.function.Consumer;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;

import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * Owns all ViewPager2 horizontal session-pager logic that used to live in {@link TermuxActivity}.
 * <p/>
 * One page per {@link TerminalSession}, each page hosting its own {@link TerminalView}. A horizontal
 * finger drag pages between adjacent sessions; because ViewPager2 keeps both pages attached during
 * the drag, the neighbouring session is visible mid-swipe. The active page's {@link TerminalView} is
 * re-pointed into the activity whenever the user settles on a page (see {@link #onTerminalPageSelected(int)})
 * so the rest of the codebase that calls {@code TermuxActivity.getTerminalView()} keeps working unchanged.
 */
public final class SessionPagerManager {

    /** The owning activity. Cross-cutting concerns (extra keys, tabs strip, text-input state, IME guard) still live there. */
    private final TermuxActivity mActivity;

    /** The horizontal session pager (ViewPager2). */
    private final ViewPager2 mTerminalPager;

    /**
     * Elastic over-drag at the first/last page ("rubber band"): the edge terminal screen
     * follows the finger past the boundary with damped resistance and springs back on release.
     * Null only if the pager had no inner RecyclerView when {@link #setup()} ran.
     */
    @Nullable
    private PagerOverscrollController mOverscroll;

    /** Adapter backing {@link #mTerminalPager}. */
    private TerminalPagerAdapter mTerminalPagerAdapter;

    /**
     * Session that should become the selected pager page once the pager is first populated with
     * sessions (i.e. when the service connects after {@code onStart} already asked to restore the
     * stored/last session). Avoids a race where {@code setCurrentSession} is requested before the
     * adapter has any items.
     */
    @Nullable
    private TerminalSession mPendingInitialSession;

    public void setPendingInitialSession(@Nullable TerminalSession session) {
        mPendingInitialSession = session;
    }

    /**
     * The index of the page the user is on — <b>the single authority for "which session is
     * active"</b> for everything that is not a finger gesture.
     *
     * <p>Everything else is a view of this number:
     * <ul>
     *   <li>a gesture writes it from {@code onPageSelected};</li>
     *   <li>the activity's {@code mTerminalView} is a <em>cache of the view</em> for it, and is
     *       allowed to be null (the page may not be bound yet);</li>
     *   <li>the tab strip highlights the session it resolves to.</li>
     * </ul>
     *
     * <p>It is deliberately <b>not</b> derived from {@code mTerminalView.getCurrentSession()}: that
     * cache has no liveness check, so after a tab close it happily keeps returning the session that
     * was just killed (the "still showing the closed terminal with the signal 9 line" bug). Reading
     * the index and resolving it against the live service list makes a dead active session
     * impossible by construction.
     *
     * <p>-1 means "not resolved yet"; {@link #getActiveIndex()} self-heals in that case.
     */
    private int mActiveIndex = -1;

    /**
     * Resolve the active page index, repairing it if it drifted out of range (a session was closed
     * or the pager has not been populated yet).
     *
     * @return a valid index into the live session list, or -1 when there are no sessions.
     */
    public int getActiveIndex() {
        TermuxService service = mActivity.getTermuxService();
        int size = (service == null) ? 0 : service.getTermuxSessionsSize();
        if (size == 0) {
            mActiveIndex = -1;
            return -1;
        }
        if (mActiveIndex < 0 || mActiveIndex >= size) {
            // Self-heal. Prefer the pager's own parked index (it is what the user is looking at),
            // then the session of the view the activity currently points at. Never fall back to a
            // raw index without checking it, and never leave the old out-of-range value in place.
            int idx = (mTerminalPager != null) ? mTerminalPager.getCurrentItem() : -1;
            if (idx < 0 || idx >= size) {
                TerminalView view = mActivity.getTerminalView();
                TerminalSession shown = (view != null) ? view.getCurrentSession() : null;
                idx = (shown != null) ? service.getIndexOfSession(shown) : -1;
            }
            mActiveIndex = (idx >= 0 && idx < size) ? idx : size - 1;
        }
        return mActiveIndex;
    }

    /** Record the active page index. The only way anything may move the active page. */
    public void setActiveIndex(int index) {
        mActiveIndex = index;
    }

    /**
     * @return true while {@code session} is still in the service's live session list.
     *
     * <p>Used to tell a still-valid cached view apart from one that is showing a session that has
     * just been killed. That distinction is the whole difference between "the tab strip highlights
     * the wrong tab" and "the tab strip highlights nothing".
     */
    public boolean isSessionLive(@Nullable TerminalSession session) {
        if (session == null) return false;
        TermuxService service = mActivity.getTermuxService();
        return service != null && service.getIndexOfSession(session) >= 0;
    }

    /**
     * @return the session the user is on, or null when there is none.
     *
     * <p>Always a <b>live</b> session: it is resolved from {@link #getActiveIndex()} against the
     * service's current list, never read off the cached {@code mTerminalView}.
     */
    @Nullable
    public TerminalSession getActiveSession() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) {
            // Pager/service not ready yet — fall back to the view cache, exactly like before.
            TerminalView view = mActivity.getTerminalView();
            return (view != null) ? view.getCurrentSession() : null;
        }
        int index = getActiveIndex();
        if (index >= 0) {
            TermuxSession termuxSession = service.getTermuxSession(index);
            if (termuxSession != null) return termuxSession.getTerminalSession();
        }
        // Last resort: the view cache, but only while the session it shows is still alive.
        TerminalView view = mActivity.getTerminalView();
        TerminalSession shown = (view != null) ? view.getCurrentSession() : null;
        if (shown != null && service.getIndexOfSession(shown) >= 0) return shown;
        return null;
    }

    /**
     * Move the pager onto {@code session} <b>before</b> the page it is currently showing is removed
     * from the adapter, and without any animation.
     *
     * <h2>Why this exists</h2>
     * Removing the page the pager is currently anchored on is the one case {@code ViewPager2} +
     * {@code RecyclerView} handle badly. Measured on the device (trace {@code PAGERDBG}, 3 tabs,
     * closing the active one):
     * <pre>
     *   SLNU old=3 new=2 heir=… restore=1 curBefore=1
     *   SLNU afterSync cur=1 (setCurrentItem target was 1)   &lt;- setCurrentItem() was a NO-OP
     *   OTPS done pos=1 tv=&lt;heir&gt;                            &lt;- our state landed correctly…
     *   PAGEDUMP … |child0 pos=0 x=-1080 |child1 pos=-1 x=0 |child2 pos=1 x=1080
     * </pre>
     * {@code pos=-1} is the removed page's ViewHolder, still attached and still occupying the
     * middle of the screen, with the pager's own page pushed off to the right: the layout kept the
     * dead child as its anchor and never corrected itself. The activity's state was right, the
     * screen was wrong — exactly the reported bug (the closed terminal stays visible, the tab strip
     * highlights the closed tab, and the first scroll snaps everything into place at once).
     *
     * <p>{@code setCurrentItem()} cannot repair that afterwards: it returns early when the target
     * equals {@code mCurrentItem} and the pager is idle (verified in the ViewPager2 1.1.0 bytecode,
     * {@code setCurrentItemInternal}), which is precisely the case after a close — the parked index
     * is still the dead page's index.
     *
     * <p>Moving off the doomed page first turns the removal into the well-behaved case: the page
     * under the viewport is one that survives, so the adapter change is a plain "content shifted
     * under a stable anchor" and no {@code ViewHolder} is left behind. It also guarantees the
     * following {@code setCurrentItem(restoreIndex, false)} is <em>not</em> a silent no-op, because
     * the target is a different page than the one just parked on.
     *
     * <p>No bookkeeping runs here on purpose: the caller removes the session immediately afterwards
     * and the normal landing ({@link #onTerminalPageSelected}) does the re-pointing, once, against
     * the new list.
     */
    public void parkOnSessionBeforeRemoval(@Nullable TerminalSession session) {
        if (session == null || mTerminalPager == null) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        final int index = service.getIndexOfSession(session);
        if (index < 0 || index == mTerminalPager.getCurrentItem()) return;
        mTerminalPager.setCurrentItem(index, false);
    }

    @Nullable
    private RecyclerView getPagerRecyclerView() {
        if (mTerminalPager == null) return null;
        return (RecyclerView) mTerminalPager.getChildAt(0);
    }

    /**
     * Run {@code action} with the {@link TermuxSessionTabsController}, if one is available, so
     * callers do not each have to repeat the null-safe {@code getTermuxSessionTabsController()} dance.
     */
    private void withTabsController(Consumer<TermuxSessionTabsController> action) {
        TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) action.accept(tabs);
    }

    /**
     * True while a cold-start session is being initialized on a background thread
     * (emulator subprocess creation, which involves a blocking fork()).  When set,
     * {@link #syncTerminalPagerToService()} skips {@code setCurrentItem()} so the pager layout pass
     * does not call {@code JNI.createSubprocess()} on the UI thread.  Cleared once the background
     * init completes and the page is selected normally.
     */
    private volatile boolean mColdStartSessionPending = false;

    public void setColdStartSessionPending(boolean pending) {
        mColdStartSessionPending = pending;
    }

    /**
     * Apply the user-configured terminal margins (from the "terminal-margin-left" /
     * "terminal-margin-top" / "terminal-margin-right" / "terminal-margin-bottom" settings) to
     * every terminal page inside the pager.
     * <p/>
     * The margins are applied to the TerminalView of each page — NOT to the pager container.
     * With the margins on the container, the pager itself is inset from the screen edges and
     * a horizontal swipe clips the neighbouring page at the container boundary instead of
     * revealing it edge-to-edge. Applying them per-page keeps the pager full-bleed while each
     * terminal screen keeps its own inset from the screen edges.
     *
     * @param leftDp   left margin in dp.
     * @param topDp    top margin in dp.
     * @param rightDp  right margin in dp.
     * @param bottomDp bottom margin in dp.
     */
    public void setTerminalMargins(int leftDp, int topDp, int rightDp, int bottomDp) {
        if (mTerminalPagerAdapter != null)
            mTerminalPagerAdapter.setTerminalMargins(leftDp, topDp, rightDp, bottomDp);
    }

    /**
     * Push the terminal background transparency (0 = opaque, 50 = maximum) to every page.
     *
     * @param percent transparency percentage.
     */
    public void setTerminalBackgroundTransparency(int percent) {
        if (mTerminalPagerAdapter != null)
            mTerminalPagerAdapter.setTerminalBackgroundTransparency(percent);
    }

    /**
     * True while the pager is being dragged by the user (a real swipe gesture), as opposed to a
     * programmatic {@code setCurrentItem()} triggered by the "+" button, an (instant) tab click or a
     * keyboard shortcut. Neither a programmatic smooth scroll nor an instant tab-click jump ever
     * passes through DRAGGING; for the instant jump the IME-suppression guard
     * {@code mTerminalPageSwitchInProgress} is still raised in {@code setCurrentSession()} before
     * the switch and lowered in {@code onTerminalPageSelected()}. The trailing placeholder page is
     * only meant to be committed into a real session when the user SWIPES onto it — never when a
     * programmatic scroll happens to land on its index (which is exactly what {@code addNewSession}
     * does after appending a session at the end, whose index coincides with the placeholder index).
     * Guarded by this flag so a programmatic scroll onto the placeholder slot does not spawn a
     * phantom duplicate session.
     */
    private boolean mUserScrollInProgress = false;

    /**
     * Last scroll position/offset forwarded to the tab strip during a drag (P2-3). Cached in
     * instance fields so the onPageScrolled forwarder can be a single non-capturing lambda instead
     * of allocating a fresh Consumer + boxing the float on every swipe frame (60–120/s).
     */
    private int mLastScrollPos;
    private float mLastScrollOffset;

    /** Single non-capturing lambda reused every swipe frame to forward scroll progress. */
    private final Consumer<TermuxSessionTabsController> mScrollForwarder =
            tabs -> tabs.onPageScrolled(mLastScrollPos, mLastScrollOffset);

    // ── right-swipe directory picker ────────────────────────────────────────────────────────
    //
    // The picker needs two things the pager does not otherwise expose: the finger's VERTICAL
    // position over the whole gesture, and the exact moment the placeholder page starts to appear.
    // Both are gathered here, because this is the only place that owns the pager's touch stream
    // (see mPickerTouchListener) and its scroll callback at the same time.

    /** Last raw Y seen on the pager, px. Kept up to date on every ACTION_MOVE. */
    private float mFingerRawY;
    /** True between ACTION_DOWN and ACTION_UP/CANCEL of a pager gesture. */
    private boolean mFingerDown;
    /**
     * True once the anchor has been latched for the current gesture. The anchor is the finger's
     * vertical position at the moment the placeholder appears, and it is captured ONCE: the whole
     * point of the gesture is that the list stays put while the finger moves over it, so that the
     * release position — not the touch-down position — is what selects a directory.
     */
    private boolean mAnchorLatched;
    /** Directory resolved on ACTION_UP, or null when the release was outside the list rows. */
    @Nullable
    private String mPendingPickDirectory;
    /** True once ACTION_UP resolved a pick for the current gesture. */
    private boolean mPendingPickReady;
    /**
     * Directory picked from a history row whose pick must survive the gesture's bookkeeping.
     *
     * <p>{@link #mPendingPickDirectory} is cleared by {@link #endPickerGesture()}, and on a slow
     * release the pager dispatches IDLE <em>before</em> it starts the snap that decides where the
     * page goes — so the natural pending pick is gone by the time the settle is under way. A pick
     * taken over a row must outlive that, because it is what forces the commit through. Cleared as
     * soon as the commit consumes it, on the next {@code ACTION_DOWN}, or when the forced scroll is
     * abandoned.
     */
    @Nullable
    private String mForcedPickDirectory;
    /** True when the release happened over a history row and the commit is therefore mandatory. */
    private boolean mForcedPickPending;
    /** Reused location buffer for {@link #rawToPageY(float)} — avoids an int[2] per touch event. */
    private final int[] mPagerLocation = new int[2];
    /**
     * True once {@link #mPagerLocation} has been latched for the current gesture.
     *
     * <p>{@code getLocationOnScreen()} walks the whole parent chain, and it was being called on every
     * {@code ACTION_MOVE}. The pager's position on screen cannot change during a horizontal swipe
     * (the elastic over-drag displaces the RecyclerView's content, not the pager itself), so one
     * lookup per gesture is enough. Cleared on {@code ACTION_DOWN}, which is also what re-latches it
     * after anything that could have moved the pager between gestures (IME, toolbar).
     */
    private boolean mPagerLocationValid;

    /**
     * The pager's touch stream, used to track the finger's vertical position and to resolve the
     * selection on release.
     *
     * <p>An {@link RecyclerView.OnItemTouchListener} on the pager's inner RecyclerView is the only
     * hook that sees BOTH ends of the gesture. {@code PagerOverscrollController} works off
     * unconsumed scroll deltas and never sees a coordinate; a plain {@code OnTouchListener} on the
     * pager is only consulted once the RecyclerView has taken over the stream, so it misses
     * ACTION_DOWN. The interception this listener rides on is not suppressed for a horizontal drag:
     * {@code TerminalView} raises {@code requestDisallowInterceptTouchEvent(true)} only when it
     * decides the gesture is a vertical history scroll, and explicitly ignores the horizontal axis
     * so the ViewPager2 can page.
     */
    private final RecyclerView.OnItemTouchListener mPickerTouchListener =
            new RecyclerView.OnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mFingerDown = true;
                            mAnchorLatched = false;
                            mPendingPickReady = false;
                            mPendingPickDirectory = null;
                            mForcedPickPending = false;
                            mForcedPickDirectory = null;
                            // Re-latch the pager's screen position for this gesture: everything that
                            // follows (the anchor and every updateFinger) reads it, and one lookup per
                            // gesture is enough while the pager itself is not moving.
                            mPagerLocationValid = false;
                            mFingerRawY = e.getRawY();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            mFingerRawY = e.getRawY();
                            if (mAnchorLatched) {
                                DirectoryPickerController picker = getDirectoryPicker();
                                if (picker != null) picker.updateFinger(rawToPageY(mFingerRawY));
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                            mFingerRawY = e.getRawY();
                            if (mAnchorLatched) {
                                DirectoryPickerController picker = getDirectoryPicker();
                                final float pageY = rawToPageY(mFingerRawY);
                                // null = "not on a row" = the default working directory. Resolved
                                // here, on release, because onPageSelected (which commits) only runs
                                // on the settle's first frame — too late to read the finger.
                                mPendingPickDirectory =
                                        (picker != null) ? picker.resolvePick(pageY) : null;
                                mPendingPickReady = true;
                                // Released over a row: the new tab opens whatever the drag distance
                                // was, and the unselected rows fade out while it does. See
                                // armForcedPick() — the fade belongs to the opening, not to the
                                // release, so a swipe that creates nothing leaves the list alone.
                                if (mPendingPickDirectory != null) {
                                    armForcedPick(mPendingPickDirectory);
                                }
                            }
                            mFingerDown = false;
                            break;
                        case MotionEvent.ACTION_CANCEL:
                            mFingerDown = false;
                            mPendingPickReady = false;
                            mPendingPickDirectory = null;
                            mForcedPickPending = false;
                            mForcedPickDirectory = null;
                            break;
                        default:
                            break;
                    }
                    return false;
                }

                @Override
                public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) { }

                @Override
                public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) { }
            };

    /**
     * Cached value of the "swipe rightmost tab for new session" preference (P1). Read once and kept
     * fresh via a SharedPreferences listener, so onPageSelected() no longer hits disk on every
     * settle. The separate MAX_SESSIONS check stays live (it depends on the session count).
     */
    private boolean mSwipeRightmostNewTabEnabled = true;
    /** SharedPreferences holding the placeholder preference; registered for change events. */
    private SharedPreferences mPrefs;
    /** Keeps {@link #mSwipeRightmostNewTabEnabled} fresh without re-reading disk on every settle. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (sp, key) -> {
                if ("swipe_rightmost_new_tab".equals(key)) {
                    mSwipeRightmostNewTabEnabled = sp.getBoolean("swipe_rightmost_new_tab", true);
                }
            };

    public boolean isColdStartSessionPending() {
        return mColdStartSessionPending;
    }

    /**
     * @param activity The owning {@link TermuxActivity}.
     * @param pager    The {@link ViewPager2} hosting the session pages (resolved by the activity via
     *                {@code findViewById(R.id.terminal_view_pager)} before construction).
     */
    public SessionPagerManager(@NonNull TermuxActivity activity, @NonNull ViewPager2 pager) {
        mActivity = activity;
        mTerminalPager = pager;
    }

    /**
     * Initialise the horizontal session pager. The adapter starts empty; sessions are pushed in
     * once the {@link TermuxService} is connected (see {@link TermuxActivity#onServiceConnected}).
     */
    public void setup() {
        if (mActivity.getTermuxService() != null) {
            mTerminalPagerAdapter = new TerminalPagerAdapter(mActivity, mActivity.getTermuxTerminalViewClient(),
                    mActivity.getTermuxService().getTermuxSessions());
        } else {
            // No sessions yet — an empty backing list; onServiceConnected repopulates it.
            mTerminalPagerAdapter = new TerminalPagerAdapter(mActivity, mActivity.getTermuxTerminalViewClient(),
                    new java.util.ArrayList<>());
        }
        mTerminalPager.setAdapter(mTerminalPagerAdapter);
        // Event-driven re-point of the activity's active TerminalView: whenever a page gets bound,
        // the manager re-asserts the active page if that is the page being bound. This replaces the
        // old "wait for attach with a post and a 300 ms safety net" recovery, which could never fire
        // for an already-attached view and therefore stranded the activity on a dead session.
        mTerminalPagerAdapter.setOnPageBoundListener(this::onPageBound);
        // With fewer than two sessions there is nothing to swipe between, so disable user input
        // to suppress the stretch/bounce edge-effect animation on a horizontal drag.
        updatePagerUserInputEnabled();
        // Keep the neighbouring page bound so a horizontal swipe reveals the adjacent
        // session LIVE (the original goal: "see the intermediate paging between
        // two adjacent screens"). With the default limit 0 the neighbour is
        // only created mid-drag and shows up empty, which reads as an abrupt snap.
        mTerminalPager.setOffscreenPageLimit(1);

        // Disable the RecyclerView item animator so the trailing placeholder page (inserted/removed
        // as the user lands on / leaves the last tab) appears and disappears instantly rather than
        // sliding in with a default animation — it must read as a normal tab page, not a popup.
        final RecyclerView pagerRv = getPagerRecyclerView();
        if (pagerRv != null) {
            pagerRv.setItemAnimator(null);
            // Finger tracking for the right-swipe directory picker (see mPickerTouchListener).
            pagerRv.addOnItemTouchListener(mPickerTouchListener);
            // Elastic over-drag on the first/last page. NOTE: this REPLACES the old
            // setOverScrollMode(OVER_SCROLL_NEVER) — that switch also disabled the plumbing the
            // rubber band is measured with (RecyclerView#scrollByInternal skips pullGlows() and
            // ViewFlinger skips absorbGlows() when the mode is NEVER).
            //
            // The stock edge animation it used to suppress cannot come back: the controller
            // installs an EdgeEffect spy that never calls super.onPull(), so the edge-effect
            // distance stays 0 — and every stock visual (the pre-12 glow, the 12+ stretch and the
            // extra composite pass the stretch added over the translucent terminal surface, which
            // visibly halved the transparency) keys off a non-zero distance or off draw().
            mOverscroll = PagerOverscrollController.install(mTerminalPager);
        }

        // Apply the user-configured terminal margins to the pages. TermuxActivity.setMargins()
        // cannot do this on first launch — it runs in onCreate() before this manager exists — so
        // the margins are (re)applied here from the live properties. Later changes arrive via
        // TermuxActivity.setMargins() -> SessionPagerManager.setTerminalMargins().
        setTerminalMargins(mActivity.getProperties().getTerminalMarginLeft(),
                mActivity.getProperties().getTerminalMarginTop(),
                mActivity.getProperties().getTerminalMarginRight(),
                mActivity.getProperties().getTerminalMarginBottom());

        // Same reason as the margins above: on a cold start TermuxActivity.setMargins() ran
        // before this manager existed, so the transparency is (re)applied here from the live
        // properties. Later changes arrive via TermuxActivity.applyTerminalTransparency().
        setTerminalBackgroundTransparency(mActivity.getProperties().getTerminalBackgroundTransparency());

        mTerminalPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                // Track whether the user is physically dragging the pager (a real swipe gesture).
                // A programmatic setCurrentItem() never passes through DRAGGING, so this flag lets
                // onPageSelected() tell a user swipe onto the placeholder apart from a programmatic
                // scroll that merely lands on the placeholder's index.
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    mUserScrollInProgress = true;
                    // A user-initiated swipe starts a genuine tab navigation: release the reserved
                    // end-scroll so onPageScrolled()'s finger-follow instant scroll is re-enabled and
                    // the strip can move with the swipe. The end-scroll (if any) already fired once
                    // the label was set; a manual swipe means the user is taking over.
                    withTabsController(tabs -> tabs.setEndScrollReserved(false));
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    mUserScrollInProgress = false;
                    // The gesture is over, whichever way it ended: drop the picker overlay. Safe to
                    // clear the pending pick here — commitPlaceholderToSession() already consumed it,
                    // because onPageSelected() is dispatched on the settle's FIRST frame and IDLE
                    // only arrives at its end.
                    endPickerGesture();
                }

                // Suppress IME hide/show churn for the ENTIRE swipe gesture, not just around
                // onPageSelected(). Note that onPageSelected() does NOT run at the end of the
                // settle — ScrollEventAdapter dispatches it on the first scroll frame AFTER the
                // DRAGGING->SETTLING transition, i.e. at the very START of the settle animation
                // (mDispatchSelected is armed on the state change and consumed on the next
                // onScrolled). So the guard must be tied to the scroll states themselves, not to
                // onPageSelected(): raise it on DRAGGING and SETTLING (the whole transition) and
                // lower it on IDLE (posted so it does not clear while a late focus event is still
                // in flight). Otherwise the old page's focus listener hides the keyboard mid-swipe
                // — that is the keyboard flicker when switching tabs/sessions.
                if (state == ViewPager2.SCROLL_STATE_DRAGGING
                        || state == ViewPager2.SCROLL_STATE_SETTLING) {
                    mActivity.setTerminalPageSwitchInProgress(true);
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    // Not the end of the gesture when a forced commit is armed: stopping the pager's
                    // own scroll to re-issue it towards the placeholder emits an IDLE on the way, and
                    // dropping the IME guard there would let the old page hide the keyboard mid-settle
                    // — exactly the flicker the guard exists for. The real IDLE (after the commit has
                    // consumed the pick) still lowers it.
                    if (mForcedPickPending) {
                        mActivity.setTerminalPageSwitchInProgress(true);
                    } else {
                        mTerminalPager.post(() -> mActivity.setTerminalPageSwitchInProgress(false));
                    }
                    // Hand the floating button's margin back to the settled state. onPageScrolled()
                    // is the sole owner of it while the pager scrolls (updateFloatingButtonMargin()
                    // early-returns during a scroll), so without this the button would stay parked
                    // at whatever interpolated value the last scroll frame produced — and a
                    // cancelled swipe would leave it at the wrong offset entirely.
                    mTerminalPager.post(() -> mActivity.updateFloatingButtonMargin());
                    // If the swipe was cancelled (released back to the same page),
                    // onPageSelected never fires and the tab strip may be left in an
                    // intermediate blended state. Reset to clean selection state here.
                    withTabsController(tabs -> tabs.resetPageSelection(mTerminalPager.getCurrentItem()));
                }
            }

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                // Forward the intermediate scroll progress to the tab strip so the
                // selection highlight and scroll position follow the user's finger
                // smoothly rather than snapping at the end of the settle. The values are
                // cached in instance fields and the forwarder is a single non-capturing
                // lambda (P2-3) — no Consumer/Float allocation per swipe frame.
                mLastScrollPos = position;
                mLastScrollOffset = positionOffset;
                withTabsController(mScrollForwarder);
                // Update floating button margin for intermediate scroll state
                updateFloatingButtonMarginForScroll(position, positionOffset);

                // How much of the placeholder page is on screen, 0…1, or -1 when there is no
                // overlay page to drive. Derived ONCE and shared by the two consumers below: it is
                // a pure function of the scroll callback, and both of them used to recompute it
                // (including the getPlaceholderOverlayPage() read inside revealFor()).
                final float reveal = revealFor(position, positionOffset);

                // Keep the placeholder content tracking the page it lives on: the hint stays centered
                // in the slice that is currently visible, and the directory rows are revealed
                // together with the page.
                //
                // Applied SYNCHRONOUSLY, on every callback, and unconditionally while an overlay page
                // exists. All three properties matter, because the settle that follows the finger lift
                // is driven by these very callbacks: deferring the write by a frame leaves the overlay
                // a frame behind the page, and skipping it when the reveal happens to read 0 leaves the
                // overlay sitting at its previous value. Either way the menu no longer tracks the page
                // it is drawn on — it snaps at the end of the gesture, or stays half-revealed over a
                // cancelled swipe. Both were observed with the deferred/guarded variant.
                applyOverlayReveal(reveal);

                // First frame that reveals the placeholder — the moment the user first sees
                // the menu. The finger's Y right now becomes the anchor, and it is latched
                // once per gesture: the list must stay put while the finger travels over it,
                // otherwise the release position could never select anything but the row the
                // finger started on.
                //
                // Runs AFTER the reveal above so the picker is laid out against the width it
                // currently has. Gated by shouldLatchAnchor(): mUserScrollInProgress keeps a
                // programmatic scroll that merely passes over the last tab's index from popping the
                // menu; mFingerDown keeps a FLING from popping it (the finger is already up when the
                // placeholder flies in, so there is no finger to anchor on, and the menu would be a
                // flash of unreachable UI during the settle). Both cases fall through to the default
                // working directory, which is exactly what a release outside the rows means.
                if (shouldLatchAnchor(positionOffset, reveal)) {
                    latchAnchor();
                }
            }

            @Override
            public void onPageSelected(int position) {
                // If the gesture settled onto the trailing placeholder page, replace it with a real
                // new session (and keep the pager parked there — no jump). This must only happen for
                // a genuine USER SWIPE (mUserScrollInProgress). A programmatic setCurrentItem() — e.g.
                // addNewSession() appending a session whose index coincides with the placeholder
                // index — also lands here, but must NOT commit a (duplicate) session.
                if (mTerminalPagerAdapter != null && mTerminalPagerAdapter.isPlaceholderActive()
                        && position == mTerminalPagerAdapter.getPlaceholderIndex()) {
                    // mForcedPickPending: the release was over a history row, so the tab opens even
                    // though the drag itself did not reach the placeholder. The settle that brings
                    // the page in is the pager's own (see forceCommitOntoPlaceholder), and it reports
                    // IDLE before it starts — hence mUserScrollInProgress is already false here.
                    if (mUserScrollInProgress || mForcedPickPending) {
                        commitPlaceholderToSession();
                    } else {
                        // Programmatic scroll onto the placeholder slot — e.g. addNewSession()
                        // appended a session whose index coincides with the placeholder index.
                        // Do NOT create a (duplicate) session, but DO resync the adapter with the
                        // live session list first: while the placeholder was active, getItemCount()
                        // already accounted for it, so the normal sync-on-size-change was a no-op and
                        // the adapter's backing list never learned about the new real session. Without
                        // this resync the new session would never get a ViewHolder bound to it (it
                        // would stay an uninitialised "Terminal" page and the UI would hang).
                        cancelPlaceholder();
                        TermuxService service = mActivity.getTermuxService();
                        if (service != null) {
                            mTerminalPagerAdapter.syncWithServiceList(service.getTermuxSessions());
                        }
                        // Re-arm the placeholder if we landed on the last real tab and the feature
                        // is enabled, so a subsequent right-swipe can still add a session.
                        managePlaceholderForPosition(position);
                        onTerminalPageSelected(position);
                    }
                    return;
                }
                // Otherwise manage the placeholder: keep it while on the last real tab, drop it
                // when leaving, so a right-swipe always has a real "next page" to scroll into.
                managePlaceholderForPosition(position);
                onTerminalPageSelected(position);
            }
        });

        // Cache the "swipe rightmost tab for new session" preference (P1): read it once here and
        // keep it fresh via a listener so onPageSelected() never reads SharedPreferences on every
        // settle. Default matches the preference's default (true).
        mPrefs = mActivity.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
        mSwipeRightmostNewTabEnabled = mPrefs.getBoolean("swipe_rightmost_new_tab", true);
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
    }

    /**
     * Populate the pager with the live session list and select the initial page. Called from
     * {@link TermuxActivity#onServiceConnected} once sessions exist. Honours a pending session requested
     * earlier by {@code setPendingInitialSession}, otherwise restores the stored/last session.
     */
    public void syncTerminalPagerToService() {
        TermuxService service = mActivity.getTermuxService();
        if (mTerminalPager == null || mTerminalPagerAdapter == null || service == null) return;

        // If a cold-start session is being initialized on a background thread, defer the
        // ENTIRE pager sync (both adapter population and page selection).  The emulator
        // subprocess (JNI.createSubprocess / fork) runs off the UI thread; once it completes,
        // the callback will call this method again with the flag cleared.  The adapter then
        // gets its items via syncWithServiceList() and the RecyclerView creates/binds the
        // ViewHolder for page 0.  At that point the session already has a running emulator,
        // so attachSession() → updateSize() will only resize, not fork again.
        if (mColdStartSessionPending) return;

        mTerminalPagerAdapter.syncWithServiceList(service.getTermuxSessions());

        int index;
        if (mPendingInitialSession != null) {
            index = service.getIndexOfSession(mPendingInitialSession);
            mPendingInitialSession = null;
        } else {
            TerminalSession stored = mActivity.getTermuxTerminalSessionClient().getCurrentStoredSessionOrLast();
            index = (stored != null) ? service.getIndexOfSession(stored) : 0;
        }
        if (index < 0) index = 0;
        if (index >= service.getTermuxSessionsSize()) index = service.getTermuxSessionsSize() - 1;

        if (index >= 0) {
            mTerminalPager.setCurrentItem(index, false);
            // ViewPager2 does NOT fire onPageSelected() for the initially-selected
            // page, so the active-view pointer, extra-keys target and IME focus would
            // stay uninitialised until the first manual swipe. Trigger the same
            // bookkeeping explicitly for the startup page.
            onTerminalPageSelected(index);
            // Mirror the onPageSelected() behaviour: if we land on the last tab with the
            // feature enabled, present the trailing placeholder page so a right-swipe works.
            if (!mColdStartSessionPending) managePlaceholderForPosition(index);
        }

        // With fewer than two sessions there is nothing to swipe between, so disable
        // user input to suppress the stretch/bounce edge-effect animation on drag.
        updatePagerUserInputEnabled();
    }

    /**
     * Enable/disable horizontal pager swipe based on how many pages are currently present.
     * With a single real session (and no placeholder) there is nothing to swipe between, so
     * stretch/bounce edge effects must be suppressed. The trailing placeholder page counts as a
     * page, so when the setting is on a single real session still has a "next page" to reveal.
     */
    private void updatePagerUserInputEnabled() {
        if (mTerminalPager == null) return;
        int count = (mTerminalPagerAdapter != null) ? mTerminalPagerAdapter.getItemCount() : 0;
        mTerminalPager.setUserInputEnabled(count >= 2);
    }

    /**
     * @return true if the "swipe rightmost tab for new session" feature is enabled and the user is
     *         not already at the {@link TermuxTerminalSessionActivityClient#MAX_SESSIONS} limit.
     */
    private boolean isAtMaxSessions() {
        TermuxService service = mActivity.getTermuxService();
        return service != null && service.getTermuxSessionsSize() >= TermuxTerminalSessionActivityClient.MAX_SESSIONS;
    }

    private boolean isPlaceholderEnabled() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return false;
        if (isAtMaxSessions()) return false;
        return mSwipeRightmostNewTabEnabled;
    }

    /**
     * Show or hide the trailing placeholder page based on which page the user settled on. The
     * placeholder is appended only while the user is on the last real tab (so a right-swipe always
     * has a real "next page" to scroll into, like a normal tab-to-tab transition) and removed as
     * soon as they move to any other tab.
     */
    private void managePlaceholderForPosition(int position) {
        if (mTerminalPagerAdapter == null) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        int realLast = service.getTermuxSessionsSize() - 1;
        if (mTerminalPagerAdapter.isPlaceholderActive()) {
            if (position < realLast) {
                // Left the last tab — drop the placeholder page.
                mTerminalPagerAdapter.setPlaceholderActive(false);
                withTabsController(tabs -> tabs.setPlaceholderActive(false));
            }
            // position == realLast → keep it.
        } else if (position == realLast && isPlaceholderEnabled()) {
            mTerminalPagerAdapter.setPlaceholderActive(true);
            withTabsController(tabs -> tabs.setPlaceholderActive(true));
        }
    }

    /**
     * True while {@link #commitPlaceholderToSession()} is creating the session that fills the
     * placeholder slot.
     *
     * <p>The commit <b>owns</b> the adapter update for the session it creates — it calls
     * {@link TerminalPagerAdapter#commitPlaceholder} itself, in place, precisely so the page the
     * user is watching becomes the new terminal without the pager moving. But creating the session
     * makes the service fire {@link #termuxSessionListNotifyUpdated()} from inside the commit, and
     * that path is the opposite of what the commit needs: it drops the placeholder page, stops the
     * scroll and jumps with {@code setCurrentItem(..., false)}. Doing it mid-commit removes the
     * page the settle is travelling to and replaces the animated arrival with an instant jump —
     * the "new tab opens with no animation" regression.
     *
     * <p>So the sync is muted for the duration of the create call. It is not a "skip because the
     * size happens to match" heuristic (the old guard relied on the placeholder inflating
     * {@code getItemCount()}, which also silently swallowed the sync for a tab added by the "+"
     * button); it is an explicit statement that the commit is the only writer of the adapter in
     * that window.
     */
    private boolean mPlaceholderCommitInFlight = false;

    /**
     * The swipe settled onto the placeholder page: replace it with a real new session and keep the
     * pager parked on that slot (no jump). Re-arms the placeholder afterwards if still eligible so
     * the gesture is repeatable.
     */
    private void commitPlaceholderToSession() {
        if (mTerminalPagerAdapter == null) { cancelPlaceholder(); return; }
        TermuxTerminalSessionActivityClient client = mActivity.getTermuxTerminalSessionClient();
        TermuxService service = mActivity.getTermuxService();
        int placeholderIndex = mTerminalPagerAdapter.getPlaceholderIndex();

        if (service == null || client == null) { cancelPlaceholder(); return; }
        if (isAtMaxSessions()) {
            cancelPlaceholder();
            return;
        }

        // Append a new session at placeholderIndex. createTermuxSession() fires
        // termuxSessionListNotifyUpdated(), but because the adapter still reports
        // getItemCount() == service size (the placeholder is counted), that sync is a no-op — we
        // update the adapter ourselves below so the placeholder slot is rebound in place.
        //
        // The directory comes from the release position: the row the finger was over, or — for every
        // position outside the rows, including the neutral zone at the anchor and the hint band —
        // the working directory configured in Settings. The gesture therefore always creates a
        // session, never nothing.
        final String directory = resolvePickDirectory();
        // Consume the pick now: endPickerGesture() at the end of the settle must be free to release
        // the overlay, and the posted forceCommitOntoPlaceholder() must see the job as done.
        clearForcedPick();
        // Mute the session-list sync for the create call only: it notifies synchronously, and the
        // commit owns the adapter update (see mPlaceholderCommitInFlight). Nothing after this line
        // can re-enter, so no try/finally is needed to keep the flag balanced.
        mPlaceholderCommitInFlight = true;
        TermuxSession newSession = client.createSessionForPlaceholder(false, null, directory);
        mPlaceholderCommitInFlight = false;
        if (newSession == null) { cancelPlaceholder(); return; }

        // The rows the gesture did not pick start leaving here, on the settle's first frame: 50 ms,
        // so the list is gone long before the page lands and long before the overlay's own 150 ms
        // fade finishes. The row the finger was on is kept — and when the release was outside the
        // rows (which resolves to the default working directory) nothing is highlighted, so the whole
        // list goes, which is exactly the "no directory was chosen" reading.
        //
        // Started here rather than on the finger lift so that EVERY tab a swipe opens fades the list
        // the same way — a natural full drag as well as a forced pick — while a release that creates
        // nothing never touches it. Both paths reach this line on the same frame: the forced one
        // through the posted setCurrentItem(), which dispatches onPageSelected synchronously.
        //
        // Before the overlay's teardown below, so the highlight is still the one the finger left;
        // and the teardown itself resets the fade if there was no visible overlay to fade.
        final DirectoryPickerController picker = getDirectoryPicker();
        if (picker != null) picker.beginRowFadeOut();

        // Start the overlay leaving BEFORE the rebind is scheduled: the fade flag has to be up by the
        // time onBindViewHolder() runs for the committed slot, otherwise the bind path would set the
        // container GONE and the placeholder would disappear in a single frame instead of fading.
        mTerminalPagerAdapter.fadeOutPlaceholderOverlay();

        mTerminalPagerAdapter.commitPlaceholder(service.getTermuxSessions(), placeholderIndex);
        withTabsController(tabs -> {
            tabs.setPlaceholderActive(false);
            // Reserve the end-scroll NOW (before the deferred post() bookkeeping runs) so that every
            // onPageScrolled() instant scrollTo() during the pager settle is suppressed by the
            // mEndScrollActive guard — only the single END smooth scroll (fired after the label is set)
            // will drive the strip. Also arm the label-triggered scroll: the right-end scroll fires only
            // once the new session's title is actually set (onTitleChanged), with a 250ms fallback.
            tabs.setEndScrollReserved(true);
        });
        if (newSession.getTerminalSession() != null) {
            client.markPendingEndScrollSession(newSession.getTerminalSession());
        }

        // NOTE: do NOT re-arm the placeholder synchronously here. Re-inserting the page during the
        // commit (notifyItemInserted) makes ViewPager2's DataSetChangeObserver snapToPage() and
        // rewind the pager (the old 6->0 cascade). The placeholder is instead re-appended on the
        // next frame by managePlaceholderForPosition(idx) inside the post() block below — inserting
        // the page to the RIGHT of the settled current page, which is safe.

        // commitPlaceholder() triggers an in-place rebind of the (reused) ViewHolder carrying the
        // new session — via a payloaded notifyItemChanged so RecyclerView does NOT skip the bind.
        // Re-arm the placeholder immediately (next frame, safe — inserts to the right of the
        // settled page) so a subsequent right-swipe can still create a session, then run the
        // standard per-page bookkeeping on the next frame, once the ViewHolder is rebound: this
        // re-points the activity's active TerminalView at the new page and highlights its tab.
        final int idx = placeholderIndex;
        mTerminalPager.post(() -> {
            // Re-arm the trailing placeholder page. Inserts at idx+1 (right of current) — does NOT
            // move the current page, so it cannot trigger the ViewPager2 snapToPage rewind. The
            // normal swipe path (onPageSelected -> managePlaceholderForPosition) does exactly this
            // on every settle; the commit path must too, otherwise the placeholder stays dropped
            // forever and the next right-swipe just edge-bounces.
            managePlaceholderForPosition(idx);
            updatePagerUserInputEnabled();
            // Re-point the active view + tab highlight at the newly-committed page. By now the
            // payloaded rebind has attached the new session to the page view, so getPagerPageView()
            // resolves the correct TerminalView (not the previous tab). The tab strip scrolls to
            // the right edge when updateTabs() adds the new tab (see TermuxSessionTabsController).
            onTerminalPageSelected(idx);
        });
    }

    /** Drop the placeholder page without creating a session and restore a clean tab-strip state. */
    private void cancelPlaceholder() {
        // A forced pick is being abandoned: release it before endPickerGesture() so the overlay is
        // cleared rather than left on screen waiting for a commit that will not happen.
        clearForcedPick();
        if (mTerminalPagerAdapter != null && mTerminalPagerAdapter.isPlaceholderActive()) {
            mTerminalPagerAdapter.setPlaceholderActive(false);
        }
        withTabsController(tabs -> {
            tabs.setPlaceholderActive(false);
            tabs.resetPageSelection(mTerminalPager.getCurrentItem());
        });
        endPickerGesture();
    }

    // ── right-swipe directory picker helpers ────────────────────────────────────────────────

    /** @return the picker owned by the adapter, or null before the adapter exists. */
    @Nullable
    private DirectoryPickerController getDirectoryPicker() {
        return (mTerminalPagerAdapter != null) ? mTerminalPagerAdapter.getDirectoryPicker() : null;
    }

    /**
     * Re-apply the terminal palette to the placeholder page's overlay (the "+ new session" block and
     * the directory menu). Called when the colour scheme changes — see
     * {@link TerminalPagerAdapter#applyPlaceholderColors()} for why the normal bind path is not
     * enough.
     */
    public void applyPlaceholderColors() {
        if (mTerminalPagerAdapter != null) mTerminalPagerAdapter.applyPlaceholderColors();
    }

    /**
     * Convert a raw (screen) Y into the placeholder page's own coordinate space. The pager is
     * full-bleed and the elastic over-drag displaces the RecyclerView along X only, so the page's
     * top edge is the pager's top edge and no other correction is needed.
     */
    private float rawToPageY(float rawY) {
        if (mTerminalPager == null) return rawY;
        if (!mPagerLocationValid) {
            mTerminalPager.getLocationOnScreen(mPagerLocation);
            mPagerLocationValid = true;
        }
        return rawY - mPagerLocation[1];
    }

    /**
     * Drop the picker overlay and any pick resolved for the gesture that just ended.
     *
     * <p>Goes through the adapter rather than the controller directly so a commit fade-out that is
     * still running keeps ownership of the overlay: hiding here would cut the fade and snap the
     * list away while the hint is still visible.
     *
     * <p>Skipped entirely while a forced pick is still pending — see {@link #armForcedPick(String)}.
     */
    private void endPickerGesture() {
        mPendingPickReady = false;
        mPendingPickDirectory = null;
        // The forced pick outlives this call by design: it is the only thing that still knows a row
        // was chosen once the pager has reported IDLE. Hiding the rows here would empty the page the
        // forced scroll is about to slide in.
        if (mForcedPickPending) return;
        if (mTerminalPagerAdapter != null) mTerminalPagerAdapter.hideDirectoryPicker();
    }

    /**
     * Release over a directory-history row: the new tab opens no matter how far the page was
     * dragged — and it opens with the same animation a full drag produces.
     *
     * <h2>Why it has to be done this way</h2>
     * The "new tab opening" animation is not a separate animation of ours: it is the pager settling
     * forward onto the placeholder page, with {@link #commitPlaceholderToSession()} running on the
     * settle's first frame (the overlay fades out over 150 ms while the page finishes travelling).
     * So the only way to reproduce it for a short drag is to make the pager actually travel to the
     * placeholder page.
     *
     * <h2>Why here, and not on the release itself</h2>
     * On {@code ACTION_UP} the pager has not yet decided where to go — that happens inside the
     * RecyclerView's own up-handling (a fling into {@code PagerSnapHelper}, or a snap-back once the
     * state falls to IDLE). Issuing {@code setCurrentItem} from the release handler itself races
     * with that decision and either gets overridden by it or gets killed by the IDLE's
     * {@code stopScrollersInternal()}.
     *
     * <p>Posting instead lands the call after the whole up-handling and <em>before the settle's
     * first animation frame</em>: the settle can only move on the next vsync, while a {@code post()}
     * runs in the current message-loop pass. Nothing has moved yet, so replacing the pager's own
     * scroll with ours is invisible — it is the same {@code smoothScrollToPosition} over the same
     * distance from the same position, and {@code onPageSelected} fires at the same point of the
     * settle, so the commit and the fade-out run exactly as they do for a full drag.
     */
    private void armForcedPick(@NonNull String directory) {
        if (mTerminalPagerAdapter == null || !mTerminalPagerAdapter.isPlaceholderActive()) return;
        if (mTerminalPager == null) return;
        mForcedPickDirectory = directory;
        mForcedPickPending = true;
        // NOTE: the row fade is deliberately NOT started here. It belongs to the commit — see
        // commitPlaceholderToSession() — which is the one point every tab opened by a swipe passes
        // through, forced pick or natural settle alike, and which never runs for a swipe that ends
        // up creating nothing.
        final int placeholderIndex = mTerminalPagerAdapter.getPlaceholderIndex();
        mTerminalPager.post(() -> forceCommitOntoPlaceholder(placeholderIndex));
    }

    /**
     * Drive the placeholder page in, so the commit runs through the ordinary settle.
     *
     * @param placeholderIndex the index captured on release; the runnable bails out if the page is
     *                         no longer the placeholder (a rebind or a session-count change in
     *                         between would make scrolling to it wrong).
     */
    private void forceCommitOntoPlaceholder(int placeholderIndex) {
        if (!mForcedPickPending) return;  // the natural settle already committed with this pick
        if (mTerminalPagerAdapter == null || !mTerminalPagerAdapter.isPlaceholderActive()
                || placeholderIndex != mTerminalPagerAdapter.getPlaceholderIndex()) {
            // Nothing to commit into any more — drop the pick so the overlay (and with it the row
            // fade that was started for this opening) is released normally.
            clearForcedPick();
            endPickerGesture();
            return;
        }
        // Whatever the pager started on its own (the fling's snap, or the snap-back from a slow
        // release) has not drawn a single frame yet, but it is armed. Clear it, or the two scrollers
        // would fight and the page would crawl — OverScroller and SmoothScroller are both driven by
        // ViewFlinger.
        final RecyclerView pagerRv = getPagerRecyclerView();
        if (pagerRv != null) pagerRv.stopScroll();
        // Same settle ViewPager2 performs after a drag that carried past the threshold: a smooth
        // scroll onto the page, which dispatches onPageSelected(placeholderIndex) right away — the
        // commit and its 150 ms fade-out start on this very frame.
        mTerminalPager.setCurrentItem(placeholderIndex, true);
    }

    /** Forget a forced pick without touching the overlay. */
    private void clearForcedPick() {
        mForcedPickPending = false;
        mForcedPickDirectory = null;
    }

    /**
     * How much of the placeholder page is on screen, 0…1 — or {@code -1} when there is no overlay
     * page to drive at all (the commit fade has finished, or the placeholder was dropped).
     *
     * <p>The page is taken from the adapter rather than derived from the live session count. At the
     * instant the swipe commits, a session is added and the page stops being "the placeholder" as far
     * as the list is concerned — but the overlay is still sitting on it and must go on tracking the
     * settle. Otherwise the reveal freezes at the slice the finger happened to be at, and the content
     * reads as shifted sideways once the page lands, instead of looking as if the finger had been
     * dragged all the way to the edge.
     */
    private float revealFor(int position, float positionOffset) {
        if (mTerminalPagerAdapter == null) return -1f;
        final int overlayPage = mTerminalPagerAdapter.getPlaceholderOverlayPage();
        if (overlayPage < 0) return -1f;
        // 0 while the pager sits on the page before it, 1 once it is fully revealed.
        final float reveal = (position + positionOffset) - (overlayPage - 1);
        if (reveal < 0f) return 0f;
        if (reveal > 1f) return 1f;
        return reveal;
    }

    /**
     * Drive the placeholder overlay to the current reveal: the hint's horizontal centring, the
     * overlay's fade ramp, and the directory rows' width.
     *
     * <p>Driven unconditionally on every scroll callback, exactly as before the optimisations: the
     * overlay has to track the page through the whole settle — including the return leg of a
     * cancelled swipe, where the reveal ramps back down to 0 — otherwise the menu stands still while
     * the page slides out from under it.
     *
     * @param reveal the value {@link #revealFor(int, float)} already produced for this callback, or
     *               a negative value when there is no overlay page to drive.
     */
    private void applyOverlayReveal(float reveal) {
        if (reveal < 0f) return;

        // Runs before the anchor latch, so the very first frame the menu appears already has the
        // width this callback produced.
        final DirectoryPickerController picker = getDirectoryPicker();
        if (picker != null) {
            // The page being fully off screen is the one moment that always precedes a gesture that
            // can open the menu, so it is where a leftover row fade from the previous gesture is
            // dropped — the menu must never open on rows that are already dimmed. Free unless there
            // is something to undo.
            if (reveal <= 0f) picker.clearRowFade();
            picker.setRevealedFraction(reveal);
        }
        mTerminalPagerAdapter.setPlaceholderScrollOffset(reveal);
    }

    /**
     * Whether this scroll callback is the one that has to open the menu.
     *
     * <p>First frame that reveals the placeholder — the moment the user first sees the menu. The
     * finger's Y right then becomes the anchor, and it is latched once per gesture: the list must stay
     * put while the finger travels over it, otherwise the release position could never select
     * anything but the row the finger started on.
     *
     * <p>The {@code reveal > 0} part is what ties the latch to the placeholder actually being on
     * screen, so a drag from an earlier tab (where the placeholder is armed but pages away) can no
     * longer latch an anchor for a menu nobody can see. {@code mUserScrollInProgress} keeps a
     * programmatic scroll that merely passes over the last tab's index from popping the menu.
     * {@code mFingerDown} keeps a FLING from popping it: the finger is already up when the placeholder
     * flies in, so there is no finger to anchor on, and the menu would be a flash of unreachable UI
     * during the settle. Both cases fall through to the default working directory, which is exactly
     * what a release outside the rows means. {@code isPlaceholderActive()} also keeps the latch off
     * the committed page, whose overlay is only finishing its fade.
     *
     * @param positionOffset the raw callback offset — {@code 0} is the "nothing is transitioning"
     *                       frame, which must never open the menu.
     * @param reveal         the value {@link #revealFor(int, float)} already produced for this
     *                       callback, so it is not recomputed here.
     */
    private boolean shouldLatchAnchor(float positionOffset, float reveal) {
        if (mAnchorLatched || positionOffset <= 0f) return false;
        if (!mUserScrollInProgress || !mFingerDown) return false;
        if (mTerminalPagerAdapter == null || !mTerminalPagerAdapter.isPlaceholderActive()) return false;
        return reveal > 0f;
    }

    /** Open the menu, anchoring the list at the finger's current vertical position. */
    private void latchAnchor() {
        mAnchorLatched = true;
        final DirectoryPickerController picker = getDirectoryPicker();
        if (picker != null) {
            picker.show(rawToPageY(mFingerRawY), mTerminalPager.getHeight());
        }
    }

    /**
     * The directory the swipe selected: the row the finger was over on release, or the default
     * working directory for every other release position (neutral zone at the anchor, the hint band,
     * and the empty space above and below the list).
     */
    @NonNull
    private String resolvePickDirectory() {
        if (mForcedPickPending && mForcedPickDirectory != null) return mForcedPickDirectory;
        if (mPendingPickReady && mPendingPickDirectory != null) return mPendingPickDirectory;
        return mActivity.getProperties().getDefaultWorkingDirectory();
    }

    /**
     * Run the "we are now on page {@code position}" bookkeeping: fix the active index, re-point the
     * activity's active {@link TerminalView}, move the tab highlight and run the per-session setup.
     *
     * <p><b>Total and idempotent.</b> It must produce a consistent state for any input — including
     * an index that is out of range because a session was closed under us — and it must be safe to
     * run twice for the same page. Both properties are load-bearing:
     * <ul>
     *   <li>after a tab close {@code ViewPager2.setCurrentItem()} is a <b>silent no-op</b> when the
     *       target equals the current item (verified in the 1.1.0 bytecode:
     *       {@code if (item == mCurrentItem && isIdle()) return;}), so no {@code onPageSelected}
     *       arrives and this manual call is the <em>only</em> thing that moves the active state.
     *       The old {@code if (selected == null) return;} turned exactly that call into a no-op
     *       and left the activity pointing at the killed session. (The close path now also parks
     *       the pager on the heir before removing the page — see
     *       {@link #parkOnSessionBeforeRemoval} — but that is about which page is <em>laid out</em>,
     *       not about the active state.)</li>
     *   <li>{@code ViewPager2} may resolve the settled page one layout pass later
     *       ({@code mCurrentItemDirty} + {@code updateCurrentItem()} in {@code onLayout}) and then
     *       deliver {@code onPageSelected} late — so a repeat run has to be harmless.</li>
     * </ul>
     *
     * <p>There is deliberately <b>no recovery path with a delay</b>. If the page's view is not bound
     * yet, the active view is set to null (input routing falls back to
     * {@link TermuxActivity#getActiveTerminalView()}) and {@link #onPageBound} re-points it the
     * moment the page is bound. The old code instead waited on a child-attach listener with a
     * {@code post} fallback and a 300 ms safety net — and both of those waited for something that
     * provably could not happen for a view that was already attached.
     */
    private void onTerminalPageSelected(int position) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        final int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // No session left to be "on": clear every derived view of the active state instead of
            // leaving them pointing at whatever was last selected.
            mActiveIndex = -1;
            mActivity.setTerminalView(null);
            withTabsController(TermuxSessionTabsController::clearSelection);
            mActivity.setTerminalPageSwitchInProgress(false);
            return;
        }
        // Clamp instead of bailing out. An out-of-range index means the list shrank under us — the
        // one case where landing on a live page matters most.
        if (position < 0) position = 0;
        if (position >= size) position = size - 1;

        TermuxSession termuxSession = service.getTermuxSession(position);
        if (termuxSession == null) return;
        final TerminalSession selected = termuxSession.getTerminalSession();
        if (selected == null) return;

        // The session we are LEAVING, captured before the active index moves. It may be a session
        // that was just killed — persisting its state is then a harmless no-op, which is vastly
        // better than skipping the whole landing because of it.
        final TerminalSession leaving = getActiveSession();

        // Persist the scroll position of the page we are LEAVING while its TerminalView is still
        // live. Keyed by session, so a repeat run for the same page is a no-op (leaving == selected).
        if (leaving != null && leaving != selected) {
            TerminalView leavingView = mActivity.getTerminalView();
            if (leavingView != null && leavingView.getCurrentSession() == leaving) {
                mActivity.getTextInputState().setScrollState(leaving,
                        leavingView.getTopRow(), leavingView.getScrollTranscriptRows());
            }
        }

        // Mark a page switch in progress so the per-page focus listener
        // (registerTerminalViewFocusListener) suppresses IME hide/show churn while the old page
        // loses focus and the new one gains it during a swipe / tab / hotkey switch. Cleared at the
        // end of this method. This fixes the keyboard flicker (hide+show) reported when switching
        // tabs/sessions — without it, the focus listener of the page being left would pop the IME
        // and the freshly-landed page would re-show it a frame later.
        mActivity.setTerminalPageSwitchInProgress(true);

        // Preserve the panel text of the session we are LEAVING (still pointed to by the activity's
        // terminal view / getCurrentSession at this moment) BEFORE we re-point it at the incoming
        // page and onSessionPageSelected() overwrites the single shared EditText with the new
        // session's saved text. The programmatic setCurrentSession() path already saves here, but a
        // plain swipe goes straight through onTerminalPageSelected() and would otherwise drop the
        // leaving session's in-progress input (#InputPanel8).
        // NOTE: still called while the active index points at the LEAVING session, so the shared
        // field's content is persisted to that session (guarded internally by mTiBoundSession).
        mActivity.saveTextInputForCurrentSession();

        // The active page moves here — before anything reads it back. Everything below reads state,
        // so a late, duplicate onPageSelected is harmless.
        mActiveIndex = position;

        // If the text input panel currently holds focus, carry that focus intent over to the
        // incoming session so applyTextInputVisibilityForSession() restores focus onto the panel
        // (not the terminal). Without this, the terminal page would steal focus and a long-press on
        // the input panel would hit the terminal's context menu instead of selecting a word
        // (regression: long-press on the input panel opened the terminal context menu).
        final EditText currentTextInput = mActivity.findViewById(R.id.terminal_toolbar_text_input);
        if (currentTextInput != null && currentTextInput.hasFocus()) {
            mActivity.getTextInputState().setFocusOnInput(selected, true);
        }

        // Point the shared "active terminal view" at this page's view so that getTerminalView()
        // (used by IME, extra keys, context menu, selection, etc.) routes to the visible session.
        //
        // null is a legitimate, deliberate outcome: the page may not be bound yet (a jump of two or
        // more pages with offscreenPageLimit == 1). Leaving the activity pointing at the PREVIOUS
        // session's view is what routed input, IME and extra keys into a dead terminal, so we clear
        // it instead — callers that need a view go through
        // TermuxActivity.getActiveTerminalView(), and onPageBound() re-points it as soon as the
        // page is bound. Recovery is an event, not a timer.
        mActivity.setTerminalView(getPagerPageView(position));

        // NOTE: no defensive clear of the shared text-input EditText here anymore. The
        // field is bound per-session by restoreTextInputForSession() (converged to the
        // store), and the write-through watcher persists every change into the BOUND
        // session's record, so a stale field can neither leak text into the incoming
        // session nor wipe it — there is no text-bearing save path left to race with.

        // Refresh the tab highlight for the page we landed on. We call setCurrentSession(position)
        // (NOT updateTabs()) because updateTabs() does removeAllViews() + recreate every tab,
        // which would thrash on every swipe; setCurrentSession() only flips the selection
        // state / close-button visibility on the EXISTING tab views.
        //
        // The final copy exists only because this method clamps {@code position} (an out-of-range
        // index means the list shrank under us); the call itself is exactly the one above.
        final int landedIndex = position;
        withTabsController(tabs -> tabs.setCurrentSession(landedIndex));

        // Mirror the existing setCurrentSession() side effects for the newly-visible session so
        // per-session text input, tab highlight and background colour stay consistent. We avoid
        // calling setCurrentSession() itself (that would re-trigger a pager scroll / toast loop).
        // applyTextInputVisibilityForSession() (called inside onSessionPageSelected) is the SINGLE
        // authority for focus + IME here, so we must NOT also requestFocus()/showSoftInput() below —
        // doing both caused the keyboard to flicker (hide+show) when switching tabs/sessions.
        mActivity.getTermuxTerminalSessionClient().onSessionPageSelected(selected);

        // Page switch bookkeeping done. The IME-suppression guard (mTerminalPageSwitchInProgress)
        // must stay raised until the focus change requested inside onSessionPageSelected()/applyTextInputVisibilityForSession
        // (via TerminalView/EditText.requestFocus()) is actually DELIVERED — requestFocus() posts
        // the focus transition to the main looper, so it runs AFTER this method returns. If we cleared
        // the guard synchronously here, the old page's onFocusChange(false) would fire with the guard
        // already false and hide the keyboard mid-switch (the keyboard flicker). So defer the clear to
        // a posted runnable: it lands in the looper AFTER the requestFocus() focus event, so the guard
        // is still true while the focus listener processes the switch, then drops. onPageScrollStateChanged(IDLE)
        // also posts a clear (harmless, idempotent) for the swipe path; the explicit/startup path relies on this one.
        mTerminalPager.post(() -> mActivity.setTerminalPageSwitchInProgress(false));

        // A keyboard restore may have been deferred until an active page exists
        // (cold start: onResume ran before the service connected and bound page 0).
        mActivity.consumePendingKeyboardRestoreIfReady();
    }

    /**
     * Interpolate the floating button's right margin during a ViewPager2 scroll
     * between two adjacent pages. When scrolling from a page with scrollbar to
     * one without (or vice versa), the button margin smoothly transitions between
     * the two states so the visual position tracks the user's finger instead of
     * snapping only after the page settles.
     *
     * <p>Every write here goes through
     * {@link TermuxActivity#setFloatingButtonMarginEndForScroll(int)}, which applies the value as a
     * translation against the margin the layout already holds. The previous version wrote a real
     * {@code marginEnd} per frame, i.e. a full measure+layout pass over the activity on every frame
     * of the swipe; the geometry the user sees is identical (the translation produces exactly the
     * same effective margin), only the way it is applied changed. The settled margin is restored —
     * and the translation cleared — by {@code updateFloatingButtonMargin()} on the scroll's IDLE.
     */
    private void updateFloatingButtonMarginForScroll(int position, float positionOffset) {
        if (mActivity == null) return;

        // position is the page being left, position+1 is the page being entered (for a right-swipe).
        // positionOffset goes from 0 (fully on position) to 1 (fully on position+1).
        TerminalView leftView = getPagerPageView(position);
        TerminalView rightView = getPagerPageView(position + 1);
        if (leftView == null && rightView == null) return;

        if (leftView == null) {
            // Only right page available — use its margin directly
            mActivity.setFloatingButtonMarginEndForScroll(computeMarginEnd(rightView));
            return;
        }
        if (rightView == null) {
            // Only left page available — use its margin directly
            mActivity.setFloatingButtonMarginEndForScroll(computeMarginEnd(leftView));
            return;
        }

        // Calculate margin for each page
        int leftMargin = computeMarginEnd(leftView);
        int rightMargin = computeMarginEnd(rightView);

        // If both have the same margin, no interpolation needed
        if (leftMargin == rightMargin) return;

        // Interpolate between the two margins based on scroll progress
        int interpolatedMargin = Math.round(leftMargin * (1f - positionOffset) + rightMargin * positionOffset);
        mActivity.setFloatingButtonMarginEndForScroll(interpolatedMargin);
    }

    /**
     * Compute the button's right marginEnd in pixels for a page at rest, based on that page's
     * scrollbar visibility: the terminal's own right inset is only added when THIS page shows a
     * scrollbar — that is when the button must clear the terminal edge/scrollbar. Without a
     * scrollbar the button keeps its standard margin. Because the margin is computed per page and
     * interpolated during a swipe, the button animates smoothly between the two pages' positions.
     *
     * Delegates to {@link TermuxActivity#computeSettledFloatingButtonMarginEnd(TerminalView)} —
     * the single implementation. This used to carry its own copy of the formula; two copies drift,
     * and every difference is a visible jump at the seam where the scroll hands the margin back to
     * the settled state.
     */
    private int computeMarginEnd(@Nullable TerminalView view) {
        if (mActivity == null) return 0;
        return mActivity.computeSettledFloatingButtonMarginEnd(view);
    }

    /**
     * Returns the {@link TerminalView} for the pager page at {@code position}, or null if not bound.
     *
     * <p>Resolved through the session at that position ({@link TerminalPagerAdapter#getViewForSession}),
     * not through a position-keyed cache. That matters after a structural change: when a middle tab
     * is closed the following pages shift down <em>without</em> being rebound, so the view showing
     * the session that is now at {@code position} is found by asking for that session — no manual
     * key shifting, and no possibility of resolving to a page that is no longer on screen.
     */
    @Nullable
    public TerminalView getPagerPageView(int position) {
        if (mTerminalPager == null || mTerminalPagerAdapter == null) return null;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSession(position);
            if (termuxSession != null) {
                TerminalView bySession = mTerminalPagerAdapter.getViewForSession(
                        termuxSession.getTerminalSession());
                if (bySession != null) return bySession;
            }
        }
        // Fallback for the rare case the map entry was dropped but the holder exists.
        RecyclerView rv = getPagerRecyclerView();
        if (rv == null) return null;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        if (vh instanceof TerminalPagerAdapter.TerminalPageViewHolder) {
            return ((TerminalPagerAdapter.TerminalPageViewHolder) vh).mTerminalView;
        }
        return null;
    }

    /**
     * Called by the adapter the moment a page's {@link TerminalView} is bound to a session
     * ({@link TerminalPagerAdapter.OnPageBoundListener}).
     *
     * <p>If that session is the active one, re-point the activity's active view now. This is what
     * covers the case the old code could not: a page beyond {@code offscreenPageLimit} that gets
     * bound a frame (or several) after the switch was requested. It is a plain callback from the
     * bind — no timers, no posted retries, nothing that can be missed.
     */
    private void onPageBound(@NonNull TerminalSession session, @NonNull TerminalView view) {
        TerminalSession active = getActiveSession();
        if (active == session) {
            mActivity.setTerminalView(view);
        }
    }

    /** No specific target session: keep the active page (or land on the newly added one). */
    public void termuxSessionListNotifyUpdated() {
        termuxSessionListNotifyUpdated((TerminalSession) null);
    }

    /**
     * Sync the pager adapter with the live session list and land on the right page.
     *
     * @param heir The session that must become active after a removal — chosen by the caller
     *             <b>before</b> the removal and resolved to an index here, <b>after</b> it. Passing
     *             a stale "index in the old list" instead is what made post-close landing wrong: it
     *             is meaningless once the list has shifted, and every clamp applied to it was a
     *             guess. null for non-removal updates (add, restore, title changes).
     */
    public void termuxSessionListNotifyUpdated(@Nullable TerminalSession heir) {
        // Keep the horizontal pager in sync with the live session list. Re-point the adapter at the
        // current list and refresh. We preserve the selected page by re-selecting the index of the
        // pending/active session afterwards, so adding/removing a tab does not snap the user to
        // page 0.
        //
        // IMPORTANT: the adapter rebuild + setCurrentItem(..., false) must ONLY run when the session
        // list actually changed (add/remove). On a plain swipe the list is unchanged, and rebuilding
        // the adapter there destroys the page ViewHolder mid-animation and the setCurrentItem(false)
        // snaps without the smooth settle — that is what read as an "abrupt" page switch.
        TermuxService service = mActivity.getTermuxService();
        if (mTerminalPager == null || mTerminalPagerAdapter == null || service == null) return;
        // A placeholder commit is mid-flight and owns the adapter update for the session it just
        // created — see mPlaceholderCommitInFlight. Running the structural sync here would drop the
        // placeholder page out from under the settle and jump the pager without the animation.
        if (mPlaceholderCommitInFlight) return;

        int newSize = service.getTermuxSessionsSize();
        // Compare REAL session counts — getItemCount() also counts the trailing placeholder page,
        // so comparing it against the live session count is wrong in both directions (it can skip a
        // sync that is needed and run one that is not).
        int oldSize = mTerminalPagerAdapter.getSessionCount();
        if (oldSize == newSize && mTerminalPagerAdapter.sameSessions(service.getTermuxSessions())) {
            return;
        }

        int restoreIndex;
        if (mPendingInitialSession != null) {
            restoreIndex = service.getIndexOfSession(mPendingInitialSession);
            mPendingInitialSession = null;
        } else if (newSize > oldSize) {
            // A session was just added at the end of the list. Jump to its index so the new tab
            // becomes active immediately. Previously we restored the index of the *current*
            // session, leaving the pager parked on the old page with the terminal view still
            // pointing at the old session, so the new tab read as un-switchable.
            restoreIndex = newSize - 1;
        } else if (heir != null) {
            // A tab was just removed. Resolve the heir's index in the NEW list — correct by
            // construction, and immune to the clamping mistakes the old stale-index logic needed.
            restoreIndex = service.getIndexOfSession(heir);
        } else {
            // Non-removal update: keep the active page.
            restoreIndex = getActiveIndex();
        }
        if (restoreIndex < 0) restoreIndex = mTerminalPager.getCurrentItem();
        if (restoreIndex < 0) restoreIndex = 0;
        // Clamp to the new upper bound (e.g. closing the last tab should select the new last tab,
        // not leave the pager on a stale out-of-range position).
        if (restoreIndex >= newSize) restoreIndex = newSize - 1;

        // Sync the adapter with the live session list using incremental
        // notifications (notifyItemRangeInserted / notifyItemRangeRemoved)
        // instead of notifyDataSetChanged.  Incremental notifications properly
        // update RecyclerView's internal state (including GapWorker prefetch
        // tasks), so there is no race with ViewFlinger or the GapWorker —
        // no more "Inconsistency detected" / "Invalid item position" crashes.
        // stopScroll() + setUserInputEnabled(false) still fire as a safety net
        // to suppress touch and smooth-scroll animations during the update.
        final RecyclerView pagerRv = getPagerRecyclerView();
        if (pagerRv != null) pagerRv.stopScroll();
        mTerminalPager.setUserInputEnabled(false);
        // stopScroll() does not release the edge effects, so an over-drag held at this
        // moment would survive the rebuild with the pager still displaced. The session
        // list is about to change shape anyway — drop the displacement.
        if (mOverscroll != null) mOverscroll.reset();

        mTerminalPagerAdapter.syncWithServiceList(service.getTermuxSessions());

        if (restoreIndex >= 0 && restoreIndex < service.getTermuxSessionsSize()) {
            // On a close this is normally a SILENT NO-OP — the caller already parked the pager on
            // the heir before the removal (parkOnSessionBeforeRemoval) and, with the left-neighbour
            // policy, the heir's index does not shift, so the parked item IS the target. It only
            // does real work when the heir was to the RIGHT of the removed page (closing the first
            // tab), where the target index is one lower than the parked one. Either way the
            // explicit landing call below is what moves the active state.
            mTerminalPager.setCurrentItem(restoreIndex, false);
        }

        // Re-enable swipe only when there are ≥2 sessions — with a single tab a
        // horizontal drag should not show the stretch/bounce edge-effect animation.
        updatePagerUserInputEnabled();

        // Re-point the active page: fix the active index, the active view, the tab highlight and
        // the per-session bookkeeping. Needed precisely because setCurrentItem() above may have
        // done nothing at all.
        int activeIndex = mTerminalPager.getCurrentItem();
        onTerminalPageSelected(activeIndex);

        // syncWithServiceList() above drops the trailing placeholder page (it must, so the
        // diff operates on a clean real-session list). On a foreground-from-background this
        // path runs from onStart() WITHOUT a follow-up managePlaceholderForPosition() (the
        // re-selected page is the same index, so onPageSelected never fires — that callback
        // is what normally re-arms the placeholder). Without re-arming here, the rightmost
        // tab's right-swipe stays dead (the last real tab just stretches) until the user
        // switches tabs. Re-arm based on where we settled.
        managePlaceholderForPosition(activeIndex);

        // managePlaceholderForPosition() may have (re-)inserted the placeholder page, so the
        // effective page count is now one higher than the real-session count used by the
        // updatePagerUserInputEnabled() call above (which ran while the placeholder was still
        // dropped). With a single real session this left setUserInputEnabled(false) even
        // though the placeholder now provides a real "next page" to swipe into, killing the
        // right-swipe entirely after resume. Re-evaluate input now that the placeholder is
        // back in the count.
        updatePagerUserInputEnabled();

        // Arming/dropping the placeholder changes the page count, and dropping it while parked on it
        // moves the parked index. Re-land if that happened — onTerminalPageSelected is idempotent.
        final int settledIndex = mTerminalPager.getCurrentItem();
        if (settledIndex != activeIndex) onTerminalPageSelected(settledIndex);
    }

    /**
     * Debug oracle: one machine-parseable snapshot of every piece of "which page is active" state.
     * Logged by the {@code pagedump} debug command (tag {@code TIPanelCmd}).
     *
     * <p>The invariant to check after any add/close is that {@code active}, the session of
     * {@code tv}, the highlighted tab and the life session list all agree. None of this is visible
     * in {@code uistate} or {@code dumpsys input_method} — see docs/tab-close-architectural-fix.md.
     */
    public String dumpPageState() {
        StringBuilder sb = new StringBuilder("PAGEDUMP");
        TermuxService service = mActivity.getTermuxService();
        int sessions = (service == null) ? 0 : service.getTermuxSessionsSize();
        sb.append(" sessions=").append(sessions);
        sb.append(" pager=").append(mTerminalPager != null ? mTerminalPager.getCurrentItem() : -1);
        sb.append(" active=").append(getActiveIndex());
        sb.append(" adapterSessions=").append(mTerminalPagerAdapter != null
                ? mTerminalPagerAdapter.getSessionCount() : -1);
        sb.append(" adapterItems=").append(mTerminalPagerAdapter != null
                ? mTerminalPagerAdapter.getItemCount() : -1);
        sb.append(" placeholder=").append(mTerminalPagerAdapter != null
                && mTerminalPagerAdapter.isPlaceholderActive()
                ? mTerminalPagerAdapter.getPlaceholderIndex() : -1);
        TerminalSession activeSession = getActiveSession();
        sb.append(" activeSession=").append(id(activeSession));
        TerminalView tv = mActivity.getTerminalView();
        sb.append(" tv=").append(tv == null ? "null" : id(tv.getCurrentSession()));
        sb.append(" tvAttached=").append(tv != null && tv.isAttachedToWindow() ? 1 : 0);
        TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        sb.append(" tabSel=").append(tabs != null ? tabs.getCurrentSessionIndex() : -2);
        sb.append(" tabSelSession=").append(tabs != null ? id(tabs.getSelectedSession()) : "null");
        // What the RecyclerView is ACTUALLY showing: scroll offset and, for every attached page
        // child, its adapter position and the session its TerminalView displays. This is the only
        // way to see "the closed session's page is still the one on screen" — every field above
        // reports what the app BELIEVES is active.
        RecyclerView rv = getPagerRecyclerView();
        sb.append(" scrollX=").append(mTerminalPager != null ? mTerminalPager.getScrollX() : -1);
        if (rv != null) {
            for (int i = 0; i < rv.getChildCount(); i++) {
                android.view.View child = rv.getChildAt(i);
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
                int pos = (vh == null) ? RecyclerView.NO_POSITION : vh.getAdapterPosition();
                TerminalView ctv = (vh instanceof TerminalPagerAdapter.TerminalPageViewHolder)
                        ? ((TerminalPagerAdapter.TerminalPageViewHolder) vh).mTerminalView : null;
                sb.append(" |child").append(i).append(" pos=").append(pos)
                  .append(" x=").append(child.getLeft())
                  .append(" s=").append(ctv == null ? "null" : id(ctv.getCurrentSession()));
            }
        }
        for (int i = 0; i < sessions; i++) {
            TermuxSession ts = service.getTermuxSession(i);
            TerminalSession s = (ts == null) ? null : ts.getTerminalSession();
            sb.append(" |s").append(i).append("=").append(id(s));
            sb.append(" view=").append(mTerminalPagerAdapter != null
                    && mTerminalPagerAdapter.getViewForSession(s) != null ? 1 : 0);
        }
        return sb.toString();
    }

    private static String id(@Nullable TerminalSession session) {
        return session == null ? "null" : Integer.toHexString(System.identityHashCode(session));
    }


    /** Resolve the {@link TerminalView} of the currently active pager page, resolving it live. */
    @Nullable
    public TerminalView getActiveTerminalView() {
        if (mActivity.getTerminalView() != null) return mActivity.getTerminalView();
        if (mTerminalPager != null) {
            TerminalView pageView = getPagerPageView(mTerminalPager.getCurrentItem());
            if (pageView != null) return pageView;
        }
        return null;
    }

    public ViewPager2 getTerminalPager() {
        return mTerminalPager;
    }

    /**
     * Release resources held by the manager. Called from the owning activity's onDestroy so the
     * SharedPreferences listener registered in {@link #setup()} does not leak the activity across
     * recreations (each new activity builds a fresh manager).
     */
    public void destroy() {
        if (mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
            mPrefs = null;
        }
        // Cancel any in-flight spring-back so it cannot outlive the activity (it holds the pager's
        // RecyclerView through the animator's update listener).
        if (mOverscroll != null) {
            mOverscroll.destroy();
            mOverscroll = null;
        }
        // Unhook the finger tracker so nothing outlives the activity.
        RecyclerView pagerRv = getPagerRecyclerView();
        if (pagerRv != null) pagerRv.removeOnItemTouchListener(mPickerTouchListener);
    }
}
