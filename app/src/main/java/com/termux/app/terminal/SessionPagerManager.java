package com.termux.app.terminal;

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
        if (pagerRv != null) pagerRv.setItemAnimator(null);

        // Apply the user-configured terminal margins to the pages. TermuxActivity.setMargins()
        // cannot do this on first launch — it runs in onCreate() before this manager exists — so
        // the margins are (re)applied here from the live properties. Later changes arrive via
        // TermuxActivity.setMargins() -> SessionPagerManager.setTerminalMargins().
        setTerminalMargins(mActivity.getProperties().getTerminalMarginLeft(),
                mActivity.getProperties().getTerminalMarginTop(),
                mActivity.getProperties().getTerminalMarginRight(),
                mActivity.getProperties().getTerminalMarginBottom());

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
                    mTerminalPager.post(() -> mActivity.setTerminalPageSwitchInProgress(false));
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
                // smoothly rather than snapping at the end of the settle.
                withTabsController(tabs -> tabs.onPageScrolled(position, positionOffset));
                // Update floating button margin for intermediate scroll state
                updateFloatingButtonMarginForScroll(position, positionOffset);

                // Keep the placeholder "New tab" hint centered in the slice of the placeholder page
                // that is currently visible (between the last real tab's right edge and the screen
                // edge) as the user drags toward it.
                if (mTerminalPagerAdapter != null && mTerminalPagerAdapter.isPlaceholderActive()) {
                    TermuxService service = mActivity.getTermuxService();
                    int realLast = (service != null) ? service.getTermuxSessionsSize() - 1 : -1;
                    if (position == realLast) {
                        mTerminalPagerAdapter.setPlaceholderScrollOffset(positionOffset);
                    } else if (position == realLast + 1) {
                        mTerminalPagerAdapter.setPlaceholderScrollOffset(1f);
                    }
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
                    if (mUserScrollInProgress) {
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
        return mActivity.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("swipe_rightmost_new_tab", true);
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
        TermuxSession newSession = client.createSessionForPlaceholder(false, null);
        if (newSession == null) { cancelPlaceholder(); return; }

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
        if (mTerminalPagerAdapter != null && mTerminalPagerAdapter.isPlaceholderActive()) {
            mTerminalPagerAdapter.setPlaceholderActive(false);
        }
        withTabsController(tabs -> {
            tabs.setPlaceholderActive(false);
            tabs.resetPageSelection(mTerminalPager.getCurrentItem());
        });
    }

    /**
     * Called when the user settles on a pager page (swipe or tab/keyboard switch). Re-points the
     * activity's active {@link TerminalView} to the selected page's TerminalView and runs the
     * per-session bookkeeping (text input restore, tab highlight, toasts) that the rest of the app
     * expects.
     */
    private void onTerminalPageSelected(int position) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession selected = null;
        TermuxSession termuxSession = service.getTermuxSession(position);
        if (termuxSession != null) {
            selected = termuxSession.getTerminalSession();
        }
        if (selected == null) return;

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
        mActivity.saveTextInputForCurrentSession();

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
        TerminalView pageView = getPagerPageView(position);
        if (pageView == null) {
            // The pager has not bound the ViewHolder for this position yet. This is expected when a
            // keyboard shortcut jumps two or more pages in a single smooth scroll: with
            // offscreenPageLimit == 1 (see setup) only the neighbouring pages are attached, so the
            // destination (e.g. page 2 when starting from page 0) is not created until the pager
            // scrolls far enough to bind it. We must NOT leave the activity's active terminal view /
            // the extra-keys target pointing at the previous session, otherwise input, IME and extra
            // keys would be routed to the wrong terminal for the whole animation — and, on a quick
            // back-and-forth switch, the old `getCurrentItem() == pos` guard could cancel the
            // pending update and strand the old view forever. Wait for the destination page to
            // actually attach, then run the real bookkeeping, keying off the adapter position
            // (not just the pager's target index) so a superseded switch does not re-assert it.
            final int pos = position;
            if (mTerminalPager != null) {
                final RecyclerView rv = getPagerRecyclerView();
                if (rv != null) {
                    // Single-fire guard: either the attach listener OR the fallback post may run,
                    // never both, so the per-session bookkeeping below runs exactly once.
                    final boolean[] done = { false };
                    final RecyclerView.OnChildAttachStateChangeListener listener =
                        new RecyclerView.OnChildAttachStateChangeListener() {
                            @Override
                            public void onChildViewAttachedToWindow(@NonNull android.view.View view) {
                                if (!done[0] && rv.getChildAdapterPosition(view) == pos
                                        && mTerminalPager.getCurrentItem() == pos) {
                                    done[0] = true;
                                    rv.removeOnChildAttachStateChangeListener(this);
                                    onTerminalPageSelected(pos);
                                }
                            }
                            @Override
                            public void onChildViewDetachedFromWindow(@NonNull android.view.View view) {}
                        };
                    rv.addOnChildAttachStateChangeListener(listener);
                    // Fallback: if the page is already attached by the time we register (the
                    // listener will not re-fire for an already-attached view), re-check next frame.
                    mTerminalPager.post(() -> {
                        if (!done[0] && mTerminalPager.getCurrentItem() == pos
                                && getPagerPageView(pos) != null) {
                            done[0] = true;
                            rv.removeOnChildAttachStateChangeListener(listener);
                            onTerminalPageSelected(pos);
                        }
                    });
                    // Safety net: if neither the attach listener nor the single-frame post recovers
                    // (e.g. the page was already attached before the listener was registered and the
                    // post ran one frame too early), force the switch flag down so we never strand
                    // IME suppression / input routing on the old session.
                    mTerminalPager.postDelayed(() -> {
                        if (!done[0]) {
                            rv.removeOnChildAttachStateChangeListener(listener);
                            mActivity.setTerminalPageSwitchInProgress(false);
                        }
                    }, 300);
                }
            }
            return;
        }

        // Keep the activity's active-view pointer in sync with the page the user is actually
        // looking at, otherwise input / IME / context menu would hit the previously selected
        // session after a swipe. TermuxTerminalExtraKeys resolves the active view lazily via
        // TermuxActivity.getActiveTerminalView(), so re-pointing mTerminalView is all that is
        // needed for extra keys to follow along.
        mActivity.setTerminalView(pageView);

        // Defensive clear: after re-pointing the active TerminalView, wipe any leftover
        // text in the shared EditText so it cannot leak into the incoming session's
        // text-input state if saveTextInputForCurrentSession() is ever called by another
        // code path between here and the restore in applyTextInputVisibilityForSession().
        EditText et = mActivity.findViewById(R.id.terminal_toolbar_text_input);
        if (et != null) et.setText("");

        // Refresh the tab highlight for the page we landed on. We call setCurrentSession(position)
        // (NOT updateTabs()) because updateTabs() does removeAllViews() + recreate every tab,
        // which would thrash on every swipe; setCurrentSession() only flips the selection
        // state / close-button visibility on the EXISTING tab views.
        withTabsController(tabs -> tabs.setCurrentSession(position));

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
    }

    /**
     * Interpolate the floating button's right margin during a ViewPager2 scroll
     * between two adjacent pages. When scrolling from a page with scrollbar to
     * one without (or vice versa), the button margin smoothly transitions between
     * the two states so the visual position tracks the user's finger instead of
     * snapping only after the page settles.
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
            mActivity.setFloatingButtonMarginEnd(computeMarginEnd(rightView));
            return;
        }
        if (rightView == null) {
            // Only left page available — use its margin directly
            mActivity.setFloatingButtonMarginEnd(computeMarginEnd(leftView));
            return;
        }

        // Calculate margin for each page
        int leftMargin = computeMarginEnd(leftView);
        int rightMargin = computeMarginEnd(rightView);

        // If both have the same margin, no interpolation needed
        if (leftMargin == rightMargin) return;

        // Interpolate between the two margins based on scroll progress
        int interpolatedMargin = Math.round(leftMargin * (1f - positionOffset) + rightMargin * positionOffset);
        mActivity.setFloatingButtonMarginEnd(interpolatedMargin);
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

    /** Returns the {@link TerminalView} for the pager page at {@code position}, or null if not bound. */
    @Nullable
    public TerminalView getPagerPageView(int position) {
        if (mTerminalPager == null || mTerminalPagerAdapter == null) return null;
        // Prefer the adapter's own position->view map: it is populated in onBindViewHolder and
        // survives the window where RecyclerView.findViewHolderForAdapterPosition() still
        // returns null (ViewHolder not yet laid out during a swipe). This is what keeps
        // the activity's terminal view / extra-keys / input routing locked onto the visible page
        // instead of lagging a frame behind and hitting the wrong session.
        TerminalView attached = mTerminalPagerAdapter.getAttachedView(position);
        if (attached != null) return attached;
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
     * Sync the pager adapter and tab strip with the live session list.
     *
     * @param preferredIndex When a tab has just been removed, the position of the removed tab in
     *                       the OLD list; selects the session that shifted into this slot (the RIGHT
     *                       neighbor).  Pass -1 for non-removal updates, which falls back to
     *                       restoring the current session's position.  After the pager sync, the
     *                       activity refreshes the tab strip and saves the session snapshot.
     */
    public void termuxSessionListNotifyUpdated(int preferredIndex) {
        // Keep the horizontal pager in sync with the live session list. Re-point the adapter at the
        // current list and refresh. We preserve the selected page by re-selecting the index of the
        // pending/active session afterwards, so adding/removing a tab does not snap the user to
        // page 0. onPageSelected() then keeps the activity's terminal view (and extra keys) pointed
        // at the active session.
        //
        // IMPORTANT: notifyDataSetChanged() + setCurrentItem(..., false) must ONLY run when the
        // number of sessions actually changed (add/remove). On a plain swipe the size is unchanged,
        // and rebuilding the adapter there destroys the page ViewHolder mid-animation and the
        // setCurrentItem(false) snaps without the smooth settle — that is what read as an
        // "abrupt" page switch. So we skip the adapter rebuild on a same-size update.
        //
        // NOTE: pager sync is done BEFORE updateTabs() so that onTerminalPageSelected — which fires
        // during the sync — re-points the activity's terminal view to the correct page. If
        // updateTabs() ran first, getCurrentSession() would still return the closed session and no
        // tab would be highlighted.
        TermuxService service = mActivity.getTermuxService();
        if (mTerminalPager != null && mTerminalPagerAdapter != null && service != null) {
            int newSize = service.getTermuxSessionsSize();
            if (mTerminalPagerAdapter.getItemCount() != newSize) {
                int oldSize = mTerminalPagerAdapter.getItemCount();
                int restoreIndex;
                if (mPendingInitialSession != null) {
                    restoreIndex = service.getIndexOfSession(mPendingInitialSession);
                    mPendingInitialSession = null;
                } else if (newSize > oldSize) {
                    // A session was just added at the end of the list. Jump to its index so the
                    // new tab becomes active immediately. Previously we restored the index of the
                    // *current* session, leaving the pager parked on the old page with the terminal
                    // view still pointing at the old session. Because the freshly-added page sits
                    // beyond offscreenPageLimit(1) it is not yet bound, so a later click/swipe on
                    // the new tab hit the pageView==null early-return in onTerminalPageSelected() and
                    // — if its attach never lined up with the recovery guard — never re-pointed the
                    // terminal view, making the new tab appear un-switchable.
                    restoreIndex = newSize - 1;
                } else if (preferredIndex >= 0) {
                    // A tab was just removed — select the session at the removed tab's old position.
                    // Everything after it shifted left by 1, so this slot now holds the RIGHT
                    // neighbour of the closed tab. For the last tab (no right neighbour), the
                    // caller (removeFinishedSession) already clamped index to newSize - 1, so this
                    // selects the new last tab (left neighbour, which is the only option).
                    restoreIndex = preferredIndex;
                } else {
                    TerminalSession current = mActivity.getCurrentSession();
                    restoreIndex = (current != null) ? service.getIndexOfSession(current) : mTerminalPager.getCurrentItem();
                }
                if (restoreIndex < 0) restoreIndex = mTerminalPager.getCurrentItem();
                // Clamp to the new upper bound (e.g. closing the last tab should select the
                // new last tab, not leave the pager on a stale out-of-range position).
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

                mTerminalPagerAdapter.syncWithServiceList(service.getTermuxSessions());

                if (restoreIndex >= 0 && restoreIndex < service.getTermuxSessionsSize()) {
                    mTerminalPager.setCurrentItem(restoreIndex, false);
                }

                // Re-enable swipe only when there are ≥2 sessions — with a single tab a
                // horizontal drag should not show the stretch/bounce edge-effect animation.
                updatePagerUserInputEnabled();

                // Re-point the active page after adapter rebuild (same-index guard).
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
            }
        }
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
}
