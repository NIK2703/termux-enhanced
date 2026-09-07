package com.termux.app.terminal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.io.SessionUiStateStore;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * RecyclerView adapter backing the horizontal session pager (ViewPager2).
 *
 * <p>Each page is its own {@link TerminalView} bound to exactly one {@link TerminalSession}.
 * Because every session gets a dedicated view, a horizontal swipe reveals the neighbouring
 * session live (the ViewPager2 keeps both pages attached during the drag), which is what gives
 * the smooth "intermediate" paging feel between adjacent terminals.
 *
 * <p>Additionally, when the user is on the rightmost real tab and the opt-in "swipe rightmost tab
 * for new session" setting is on, a trailing <b>placeholder</b> page is appended (see
 * {@link #setPlaceholderActive}). It is a real, adjacent ViewPager2 page containing an (unbound)
 * {@code TerminalView} plus a "New tab" hint, so a right-swipe from the last tab scrolls into it
 * exactly like a normal tab-to-tab transition. Releasing on it commits a real session in its place
 * (the placeholder slot is rebound to the new session — no jump).
 *
 * <p>All pages share the single {@link TermuxTerminalViewClient} instance owned by the activity,
 * so input, IME, gestures and theming behave identically to the previous single-view setup. The
 * activity keeps its {@code mTerminalView} field pointed at the <em>currently selected</em> page's
 * TerminalView (updated in {@code onPageSelected}), so the rest of the codebase that calls
 * {@link TermuxActivity#getTerminalView()} keeps working unchanged.
 */
public final class TerminalPagerAdapter extends RecyclerView.Adapter<TerminalPagerAdapter.TerminalPageViewHolder> {

    /** Stable id for the placeholder page — chosen in the high range so it can never collide with a
     *  real session id (which are sign-extended 32-bit hashCode() values). */
    private static final long PLACEHOLDER_ID = 0x4000000000000000L;

    private final TermuxActivity mActivity;
    private final TermuxTerminalViewClient mViewClient;
    private List<TermuxSession> mSessions;

    /** Whether the trailing placeholder page is currently appended. */
    private boolean mPlaceholderActive = false;

    /** Maps a page position to its currently-bound TerminalView.
     *  Kept in sync in onBindViewHolder / onViewRecycled so the activity can resolve the
     *  active page's view reliably even when RecyclerView.findViewHolderForAdapterPosition()
     *  returns null mid-swipe (ViewHolder not yet laid out). This is what makes the
     *  mTerminalView pointer and extra-keys target track the visible page instead of lagging
     *  a frame behind and routing input to the wrong session. */
    private final Map<Integer, TerminalView> mAttachedViews = new HashMap<>();

    /** The movable "New tab" hint content inside the placeholder page, or null if the placeholder
     *  is not currently bound. Translated horizontally during a drag so the hint stays centered in
     *  the visible slice of the placeholder page. */
    private View mPlaceholderHintContent = null;
    /** The ViewHolder currently displaying the placeholder page (so we can clear state on recycle). */
    private TerminalPageViewHolder mPlaceholderHolder = null;

    /**
     * User-configured terminal margins in dp (settings "terminal-margin-left" / "terminal-margin-top" /
     * "terminal-margin-right" / "terminal-margin-bottom"). Applied to the TerminalView of EVERY page
     * so the pager itself stays full-bleed (a swipe reveals the neighbouring page edge-to-edge) while
     * each terminal screen keeps its own inset from the screen edges.
     */
    private int mMarginLeftDp = 0;
    private int mMarginTopDp = 0;
    private int mMarginRightDp = 0;
    private int mMarginBottomDp = 0;

    public TerminalPagerAdapter(@NonNull TermuxActivity activity,
                                 @NonNull TermuxTerminalViewClient viewClient,
                                 @NonNull List<TermuxSession> sessions) {
        this.mActivity = activity;
        this.mViewClient = viewClient;
        this.mSessions = sessions;
        setHasStableIds(true);
    }

    /**
     * Sync the adapter with the live service session list, using incremental
     * notifications ({@link #notifyItemRangeInserted}/{@link #notifyItemRangeRemoved})
     * instead of {@link #notifyDataSetChanged()} so that RecyclerView's internals
     * (GapWorker prefetch, ViewFlinger) never see stale positions after a remove
     * operation.  A full {@link #notifyDataSetChanged()} invalidates EVERY pending
     * ViewHolder and any GapWorker Handler callback that fires after that will crash
     * with {@code IndexOutOfBoundsException} ("Invalid item position" / "Inconsistency detected").
     * Incremental notifications properly update the state and GapWorker's cached tasks.
     */
    public void syncWithServiceList(@NonNull List<TermuxSession> serviceSessions) {
        // If a placeholder page was present, drop it first so the diff below operates on a clean
        // real-session list (getItemCount() must reflect only the real sessions during the diff).
        if (mPlaceholderActive) {
            mPlaceholderActive = false;
            notifyItemRemoved(mSessions.size());
        }

        int oldSize = mSessions.size();
        int newSize = serviceSessions.size();

        if (newSize > oldSize) {
            // One or more items appended — new tab.
            mSessions = new java.util.ArrayList<>(serviceSessions);
            notifyItemRangeInserted(oldSize, newSize - oldSize);
        } else if (newSize < oldSize) {
            // One or more items removed — tab closed.
            // Find the first mismatch by reference equality to determine the removed index.
            int removedCount = oldSize - newSize;
            for (int i = 0; i < newSize; i++) {
                if (mSessions.get(i) != serviceSessions.get(i)) {
                    mSessions = new java.util.ArrayList<>(serviceSessions);
                    int removeStart = i;

                    // Shift mAttachedViews keys for items that moved down.
                    // notifyItemRangeRemoved causes RecyclerView to update its own position tracking,
                    // but our mAttachedViews map — keyed by position — is NOT updated when an
                    // existing ViewHolder shifts to a lower slot.  Without this shift,
                    // getAttachedView() returns null for the slot that the shifted page now occupies,
                    // which makes onTerminalPageSelected() exit via the "pageView == null" deferred
                    // path — and that deferred path's fallback (post + getAttachedView) never
                    // recovers, because the stale key is never fixed.  The result: mTerminalView is
                    // never re-pointed after closing a non-last tab, and updateTabs() finds the
                    // dead session (currentSessionIndex = -1), so no tab is highlighted.
                    if (!mAttachedViews.isEmpty()) {
                        java.util.Map<Integer, TerminalView> shifted = new java.util.HashMap<>();
                        int threshold = removeStart + removedCount;
                        for (java.util.Map.Entry<Integer, TerminalView> e : mAttachedViews.entrySet()) {
                            int pos = e.getKey();
                            if (pos >= threshold)
                                shifted.put(pos - removedCount, e.getValue());
                            else if (pos < removeStart)
                                shifted.put(pos, e.getValue());
                            // pos in [removeStart, threshold) was the removed item — dropped.
                        }
                        mAttachedViews.clear();
                        mAttachedViews.putAll(shifted);
                    }

                    notifyItemRangeRemoved(removeStart, removedCount);
                    return;
                }
            }
            // No mismatch found in the shared portion → last item(s) were removed.
            mSessions = new java.util.ArrayList<>(serviceSessions);
            // Remove the old tail positions from mAttachedViews (they are gone).
            if (!mAttachedViews.isEmpty()) {
                java.util.Map.Entry<Integer, TerminalView>[] entries =
                    mAttachedViews.entrySet().toArray(new java.util.Map.Entry[0]);
                for (java.util.Map.Entry<Integer, TerminalView> e : entries) {
                    if (e.getKey() >= newSize)
                        mAttachedViews.remove(e.getKey());
                }
            }
            notifyItemRangeRemoved(oldSize - removedCount, removedCount);
        }
        // Same size — no structural change; tab titles etc. handled by updateTabs().
    }

    @Override
    public long getItemId(int position) {
        if (mPlaceholderActive && position == mSessions.size()) return PLACEHOLDER_ID;
        if (position < 0 || position >= mSessions.size()) return RecyclerView.NO_ID;
        TerminalSession session = mSessions.get(position).getTerminalSession();
        return session == null ? RecyclerView.NO_ID : (long) session.mHandle.hashCode();
    }

    /**
     * Update the per-page terminal margins (settings "terminal-margin-left" / "terminal-margin-top" /
     * "terminal-margin-right" / "terminal-margin-bottom"). Applied to the TerminalView of every
     * attached page so the pager container itself stays full-bleed and a swipe reveals the
     * neighbouring page edge-to-edge; each terminal screen keeps its own inset from the screen edges.
     *
     * @param leftDp   left margin in dp.
     * @param topDp    top margin in dp.
     * @param rightDp  right margin in dp.
     * @param bottomDp bottom margin in dp.
     */
    public void setTerminalMargins(int leftDp, int topDp, int rightDp, int bottomDp) {
        mMarginLeftDp = leftDp;
        mMarginTopDp = topDp;
        mMarginRightDp = rightDp;
        mMarginBottomDp = bottomDp;
        for (TerminalView terminalView : mAttachedViews.values()) {
            applyTerminalMargins(terminalView);
        }
    }

    /** Apply the configured margins to one page's TerminalView (safe when null/not yet bound). */
    private void applyTerminalMargins(@androidx.annotation.Nullable TerminalView terminalView) {
        if (terminalView == null) return;
        ViewUtils.setLayoutMarginsInDp(terminalView,
                mMarginLeftDp, mMarginTopDp, mMarginRightDp, mMarginBottomDp);
    }

    // NOTE: the placeholder page uses the SAME view type (and layout) as a normal terminal page.
    // This is deliberate — committing the placeholder is an in-place rebind of the same ViewHolder
    // (notifyItemChanged reuses it), so there is no ViewHolder recreation / flash and the activity's
    // active TerminalView pointer stays valid, exactly like a normal tab-to-tab settle.

    @NonNull
    @Override
    public TerminalPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View page = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_terminal_page, parent, false);
        TerminalPageViewHolder holder = new TerminalPageViewHolder(page);
        // Bind the shared client immediately on creation — including for the placeholder page, which
        // returns early from onBindViewHolder() before the per-session client wiring. Without this,
        // the placeholder TerminalView has mClient == null and any IME/gesture access (e.g.
        // onCreateInputConnection) crashes with a NullPointerException. onCreateViewHolder runs once
        // per ViewHolder, so this is cheap and keeps every page safe to display.
        if (holder.mTerminalView != null) {
            holder.mTerminalView.setTerminalViewClient(mViewClient);
            // Apply the per-page terminal margins right away so even the not-yet-bound
            // placeholder page carries the configured inset (the pager container itself
            // stays full-bleed, so a swipe is never clipped at the container boundary).
            applyTerminalMargins(holder.mTerminalView);
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position) {
        boolean isPlaceholder = mPlaceholderActive && position == mSessions.size();

        // The placeholder layout carries a "New tab" hint overlay; show it only for the
        // placeholder page and hide it once this slot is rebound to a real session (commit).
        View hint = holder.itemView.findViewById(R.id.terminal_placeholder_hint_container);
        if (hint != null) {
            if (isPlaceholder) {
                // Match the placeholder page to the live terminal look: paint the container with the
                // current terminal background colour and tint the hint with the terminal foreground,
                // so an unbound (blank) terminal page reads exactly like a real one.
                int bg = getCurrentTerminalColor(TextStyle.COLOR_INDEX_BACKGROUND);
                int fg = getCurrentTerminalColor(TextStyle.COLOR_INDEX_FOREGROUND);
                hint.setBackgroundColor(bg);
                ImageView plus = holder.itemView.findViewById(R.id.terminal_placeholder_hint_plus);
                if (plus != null) plus.setColorFilter(fg);
                TextView hintText = holder.itemView.findViewById(R.id.terminal_placeholder_hint_text);
                if (hintText != null) hintText.setTextColor(fg);
                hint.setVisibility(View.VISIBLE);
            } else {
                hint.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                hint.setVisibility(View.GONE);
            }
        }

        View hintContent = holder.itemView.findViewById(R.id.terminal_placeholder_hint_content);
        if (isPlaceholder) {
            mPlaceholderHintContent = hintContent;
            mPlaceholderHolder = holder;
            if (hintContent != null) hintContent.setTranslationX(0);
            // Nothing to bind — the TerminalView is intentionally left unbound (no session) and the
            // page blends with the themed window background. We still keep a valid TerminalView in
            // the holder so a commit can rebind it to a real session in place (no ViewHolder churn).
            return;
        }
        mPlaceholderHintContent = null;
        mPlaceholderHolder = null;

        TermuxSession termuxSession = mSessions.get(position);
        TerminalSession session = termuxSession.getTerminalSession();
        if (session == null) return;

        TerminalView terminalView = holder.mTerminalView;
        // Bind the shared client so input/IME/gestures route here.
        terminalView.setTerminalViewClient(mViewClient);
        // (Re)apply the per-page terminal margins: the view may be a recycled holder
        // whose margins were last set for a different configuration, and the pager
        // container itself stays full-bleed so swipes are never clipped.
        applyTerminalMargins(terminalView);

        // Attach the focus-change listener that drives the soft keyboard to THIS page's view.
        // Done here (not in TermuxTerminalViewClient.setSoftKeyboardState) because the shared
        // active view may be null during early lifecycle, while the page view is always non-null here.
        mViewClient.registerTerminalViewFocusListener(terminalView);

        // Register the terminal context menu on THIS page's TerminalView (not the activity root).
        // If it were on the root, a long-press on the sibling text-input panel would bubble up to
        // the root's context menu and show the terminal menu over the input field instead of
        // letting the EditText select a word (regression). With it here, only presses on an actual
        // terminal surface open the terminal menu; the input panel keeps its normal word-select.
        mActivity.registerForContextMenu(terminalView);

        // Mirror the global terminal view configuration onto this page.
        terminalView.setTextSize(mActivity.getPreferences().getFontSize());
        terminalView.setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn());

        // Attach the session. attachSession() reuses the session's existing TerminalEmulator
        // (history/scrollback live inside the session, not the view), so switching pages never
        // loses transcript and re-binding is cheap.
        terminalView.attachSession(session);

        // Restore this session's scroll position. Applied once the emulator + view
        // size are ready (see TerminalView.queueScrollRestore); idempotent.
        SessionUiStateStore store = mActivity.getTextInputState();
        terminalView.queueScrollRestore(
                store.getScrollTopRow(session),
                store.getScrollTranscriptRows(session));

        // Re-apply the active color scheme + typeface so the freshly bound page matches the
        // current theme (covers both initial bind and re-bind after the adapter was rebuilt).
        mActivity.getTermuxTerminalSessionClient().checkForFontAndColorsForView(terminalView);

        // Remember this position->view mapping so the activity can resolve the active page's
        // view even when RecyclerView.findViewHolderForAdapterPosition() is still null mid-swipe.
        mAttachedViews.put(position, terminalView);
    }

    /**
     * Payload-aware overload. RecyclerView always calls this (never coalesces) when a payload is
     * supplied, so a {@link #PAYLOAD_REBIND} notification reliably re-runs the full bind (and
     * re-attaches the session to the reused ViewHolder). With no payload we still defer to the
     * standard 2-arg bind.
     */
    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            onBindViewHolder(holder, position);
        }
    }

    /** @return the TerminalView currently bound to {@code position}, or null if not bound. */
    @androidx.annotation.Nullable
    public TerminalView getAttachedView(int position) {
        return mAttachedViews.get(position);
    }

    @Override
    public void onViewRecycled(@NonNull TerminalPageViewHolder holder) {
        super.onViewRecycled(holder);
        // The page view is about to be detached/recycled — persist its live scroll
        // position so the next bind (possibly after a multi-page jump) restores it.
        // Must run BEFORE attachSession(null) drops the emulator below.
        if (holder.mTerminalView != null) {
            TerminalSession recycledSession = holder.mTerminalView.getCurrentSession();
            if (recycledSession != null) {
                mActivity.getTextInputState().setScrollState(recycledSession,
                        holder.mTerminalView.getTopRow(),
                        holder.mTerminalView.getScrollTranscriptRows());
            }
        }
        if (holder == mPlaceholderHolder) {
            mPlaceholderHintContent = null;
            mPlaceholderHolder = null;
        }
        // Detach the emulator but keep the session alive. The view may be reused for a different
        // position; re-attaching on the next bind restores the correct emulator from the session.
        if (holder.mTerminalView != null) {
            holder.mTerminalView.attachSession(null);
            // Drop the per-page context menu registration so a recycled page leaves no dangling
            // listener and only the active page serves the terminal menu.
            mActivity.unregisterForContextMenu(holder.mTerminalView);
        }
        // Drop any stale position->view entry so getAttachedView() never returns a
        // recycled (detached) view for a position that has moved on.
        Integer pos = null;
        for (Map.Entry<Integer, TerminalView> e : mAttachedViews.entrySet()) {
            if (e.getValue() == holder.mTerminalView) { pos = e.getKey(); break; }
        }
        if (pos != null) mAttachedViews.remove(pos);
    }

    @Override
    public int getItemCount() {
        return mSessions.size() + (mPlaceholderActive ? 1 : 0);
    }

    /** @return true if the trailing placeholder page is currently appended. */
    public boolean isPlaceholderActive() {
        return mPlaceholderActive;
    }

    /** @return the adapter position of the placeholder page (only valid while {@link #isPlaceholderActive()}). */
    public int getPlaceholderIndex() {
        return mSessions.size();
    }

    /**
     * Show or hide the trailing placeholder page. Inserting it makes a real "next page" exist to the
     * right of the last real tab, so a ViewPager2 right-swipe scrolls into it live (normal
     * tab-to-tab feel) rather than bouncing against a non-existent page.
     */
    public void setPlaceholderActive(boolean active) {
        if (active == mPlaceholderActive) return;
        mPlaceholderActive = active;
        if (active) notifyItemInserted(mSessions.size());
        else notifyItemRemoved(mSessions.size());
    }

    /**
     * Payload that forces {@link #onBindViewHolder(TerminalPageViewHolder, int, List)} to run even
     * when RecyclerView would otherwise skip the rebind (same ViewHolder already bound to that
     * position). Used by {@link #commitPlaceholder} so the freshly-created session is actually
     * attached to the view occupying the placeholder slot.
     */
    public static final Object PAYLOAD_REBIND = new Object();

    /**
     * Replace the trailing placeholder page with the real session list: the slot at
     * {@code placeholderIndex} is rebound to the newly-created session (which now sits there),
     * keeping the pager parked on that page (no jump). Called when the swipe settles on the
     * placeholder and a new session is committed.
     */
    public void commitPlaceholder(@NonNull List<TermuxSession> serviceSessions, int placeholderIndex) {
        mSessions = new java.util.ArrayList<>(serviceSessions);
        mPlaceholderActive = false;
        // Use a payload so onBindViewHolder() is GUARANTEED to run and re-attach the new session to
        // the (reused) ViewHolder. A plain notifyItemChanged() can be skipped by RecyclerView when
        // the ViewHolder is already bound to that position, which would leave the view without its
        // session and the activity pointing at a blank page.
        notifyItemChanged(placeholderIndex, PAYLOAD_REBIND);
    }

    /**
     * Resolve a terminal indexed colour (e.g. {@link TextStyle#COLOR_INDEX_BACKGROUND}) from the
     * currently displayed session when available, falling back to the active colour scheme. This is
     * what makes the placeholder page match the live terminal (including OSC 4/11 dynamic colours).
     */
    private int getCurrentTerminalColor(int index) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            return session.getEmulator().mColors.mCurrentColors[index];
        }
        return TerminalColors.COLOR_SCHEME.mDefaultColors[index];
    }

    /**
     * Translate the placeholder hint horizontally while the user drags from the last real tab toward
     * the placeholder page, so the hint stays centered in the <em>visible slice</em> of the
     * placeholder page — i.e. between the right edge of the last real tab (the drag split point) and
     * the screen's right edge — instead of being pinned to the centre of the (partly off-screen)
     * placeholder page.
     *
     * @param pageOffset drag progress toward the placeholder, 0 = still on the last real tab,
     *                   1 = fully settled on the placeholder page.
     */
    public void setPlaceholderScrollOffset(float pageOffset) {
        if (mPlaceholderHintContent == null) return;
        View parent = (View) mPlaceholderHintContent.getParent();
        if (parent == null || parent.getWidth() <= 0) return;
        // At offset 0 the hint sits at the screen's right edge (just peeking in); at offset 1 it is
        // centred on screen. Linear interpolation between those two positions.
        mPlaceholderHintContent.setTranslationX(parent.getWidth() * (pageOffset - 1f) / 2f);
        mPlaceholderHintContent.setAlpha(Math.min(1f, pageOffset * 1.5f));
    }

    public static final class TerminalPageViewHolder extends RecyclerView.ViewHolder {
        public final TerminalView mTerminalView;

        TerminalPageViewHolder(@NonNull View itemView) {
            super(itemView);
            mTerminalView = itemView.findViewById(R.id.terminal_view_page);
        }
    }
}
