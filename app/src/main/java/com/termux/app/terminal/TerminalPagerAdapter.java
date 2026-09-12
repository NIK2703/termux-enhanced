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

    /**
     * Base for placeholder-page ids. Chosen in the high range so it can never collide with a real
     * session id (which are sign-extended 32-bit {@code hashCode()} values).
     *
     * <p>A placeholder does not carry this constant directly: every time the trailing page is armed
     * it is handed a FRESH id ({@link #mPlaceholderId}), and the session that later takes the slot
     * over inherits that same id (see {@link #commitPlaceholder}). Both rules matter — two pages
     * must never report the same id, and a slot must not change id across the commit.
     */
    private static final long PLACEHOLDER_ID_BASE = 0x4000000000000000L;

    /** Id of the currently armed placeholder page. Bumped on every arming so ids stay unique. */
    private long mPlaceholderId = PLACEHOLDER_ID_BASE;

    /**
     * The session a placeholder slot was converted into, together with the id that slot still
     * reports. Kept because the ViewHolder on screen was bound while the slot was still the
     * placeholder, i.e. with that id — and RecyclerView compares ids when it re-validates a
     * scrapped holder. See {@link #getItemId}.
     */
    private TermuxSession mCommittedSession = null;
    private long mCommittedId = RecyclerView.NO_ID;

    private final TermuxActivity mActivity;
    private final TermuxTerminalViewClient mViewClient;
    private List<TermuxSession> mSessions;

    /**
     * Owns the right-swipe directory picker shown on the placeholder page. It outlives any single
     * binding (the placeholder page may be recycled mid-gesture), which is why the pick geometry —
     * not the view — is what the swipe resolves against.
     */
    private final DirectoryPickerController mDirectoryPicker;

    /** Whether the trailing placeholder page is currently appended. */
    private boolean mPlaceholderActive = false;

    /** Maps a page position to its currently-bound TerminalView.
     *  Kept in sync in onBindViewHolder / onViewRecycled so the activity can resolve the
     *  active page's view reliably even when RecyclerView.findViewHolderForAdapterPosition()
     *  returns null mid-swipe (ViewHolder not yet laid out). This is what makes the
     *  mTerminalView pointer and extra-keys target track the visible page instead of lagging
     *  a frame behind and routing input to the wrong session. A {@link android.util.SparseArray}
     *  avoids the {@code int→Integer} boxing of a HashMap and lets {@code onViewRecycled} drop an
     *  entry in O(1) via the bound position stored on the ViewHolder. */
    private final android.util.SparseArray<TerminalView> mAttachedViews = new android.util.SparseArray<>();

    /** The movable "New tab" hint content inside the placeholder page, or null if the placeholder
     *  is not currently bound. Translated horizontally during a drag so the hint stays centered in
     *  the visible slice of the placeholder page. */
    private View mPlaceholderHintContent = null;
    /** The ViewHolder currently displaying the placeholder page (so we can clear state on recycle). */
    private TerminalPageViewHolder mPlaceholderHolder = null;

    /**
     * The placeholder overlay container currently bound, or null.
     *
     * <p>Kept separately from {@link #mPlaceholderHintContent} so {@link #fadeOutPlaceholderOverlay()}
     * can still reach the overlay <em>after</em> the placeholder slot has been rebound to a real
     * session — which is precisely the moment the fade-out starts, and by which point the bind path
     * has already dropped the "this is the placeholder" bookkeeping.
     */
    private View mPlaceholderHintContainer = null;

    /**
     * True while the commit fade-out is running.
     *
     * <p>While set, both the bind path and the picker's hide() leave the overlay's visibility and
     * alpha alone: the animation owns them, and the rebind that lands a frame later would otherwise
     * cut the fade off by setting the container GONE.
     */
    private boolean mPlaceholderFadingOut = false;

    /**
     * Adapter position of the page that hosts the overlay currently fading out, or -1.
     *
     * <p>Set for the duration of the commit fade only. It has to be remembered because the page
     * stops being "the placeholder" the instant the session is committed — the live session count
     * moves on — yet the overlay is still sitting on it and must keep tracking the pager's settle
     * until the page is fully revealed. Without this the overlay freezes at whatever slice the
     * finger happened to be at and the content reads as shifted sideways once the page lands.
     */
    private int mFadingPage = -1;

    /**
     * The hint content group of the overlay being faded out, captured when the fade starts.
     *
     * <p>It has to be held separately: the trailing placeholder is re-armed about one frame after
     * the commit, and that bind repoints {@link #mPlaceholderHintContent} (and the picker) at the
     * NEW page. The overlay that is still on screen would then stop being driven — its reveal would
     * freeze at whatever slice the re-arm landed on, and it would drift left with the page for the
     * rest of the settle instead of completing the reveal. The fade owns these handles until it ends.
     */
    private View mFadingHintContent = null;

    /**
     * How long the placeholder overlay takes to leave once the swipe commits a new session.
     *
     * <p>Short enough to read as the tail of the swipe rather than a separate animation, long enough
     * that the freshly attached terminal does not appear to snap into place behind it. It also fits
     * inside the pager's settle, so the page is already still by the time the fade lands.
     */
    private static final long PLACEHOLDER_FADE_OUT_MS = 150L;

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

    /**
     * User-configured terminal background transparency in percent (setting
     * "terminal-background-transparency"): 0 = opaque, 50 = maximum. Applied to the TerminalView
     * of every page so a page created mid-swipe never appears opaque while the wallpaper is on.
     */
    private int mBackgroundTransparencyPercent = 0;

    public TerminalPagerAdapter(@NonNull TermuxActivity activity,
                                 @NonNull TermuxTerminalViewClient viewClient,
                                 @NonNull List<TermuxSession> sessions) {
        this.mActivity = activity;
        this.mViewClient = viewClient;
        this.mSessions = sessions;
        this.mDirectoryPicker = new DirectoryPickerController(activity);
        setHasStableIds(true);
    }

    /**
     * The RecyclerView this adapter is attached to (ViewPager2's inner one). Needed by
     * {@link #commitPlaceholder}, which must hand the new session to the ViewHolder that is
     * <em>currently on screen</em> rather than asking RecyclerView to re-resolve it — see the note
     * there for why a change notification cannot do that.
     */
    private RecyclerView mPagerRv = null;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mPagerRv = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (mPagerRv == recyclerView) mPagerRv = null;
    }

    /** @return the right-swipe directory picker owned by this adapter (never null). */
    @NonNull
    public DirectoryPickerController getDirectoryPicker() {
        return mDirectoryPicker;
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
                    if (mAttachedViews.size() > 0) {
                        android.util.SparseArray<TerminalView> shifted = new android.util.SparseArray<>();
                        int threshold = removeStart + removedCount;
                        for (int k = 0; k < mAttachedViews.size(); k++) {
                            int pos = mAttachedViews.keyAt(k);
                            if (pos >= threshold)
                                shifted.put(pos - removedCount, mAttachedViews.valueAt(k));
                            else if (pos < removeStart)
                                shifted.put(pos, mAttachedViews.valueAt(k));
                            // pos in [removeStart, threshold) was the removed item — dropped.
                        }
                        mAttachedViews.clear();
                        for (int k = 0; k < shifted.size(); k++)
                            mAttachedViews.put(shifted.keyAt(k), shifted.valueAt(k));
                    }

                    notifyItemRangeRemoved(removeStart, removedCount);
                    return;
                }
            }
            // No mismatch found in the shared portion → last item(s) were removed.
            mSessions = new java.util.ArrayList<>(serviceSessions);
            // Remove the old tail positions from mAttachedViews (they are gone).
            for (int k = mAttachedViews.size() - 1; k >= 0; k--) {
                if (mAttachedViews.keyAt(k) >= newSize) {
                    mAttachedViews.removeAt(k);
                }
            }
            notifyItemRangeRemoved(oldSize - removedCount, removedCount);
        }
        // Same size — no structural change; tab titles etc. handled by updateTabs().
    }

    @Override
    public long getItemId(int position) {
        if (position >= 0 && position < mSessions.size()) {
            // A slot that used to be the placeholder keeps reporting the placeholder's id. The
            // ViewHolder currently on screen was bound while the slot was still the placeholder —
            // i.e. with that id — and RecyclerView's stable-id validation
            // (validateViewHolderForOffsetPosition) compares holder id against getItemId() on every
            // layout. If the id changed here the on-screen page would be rejected and recycled on
            // the very next layout pass, which cuts the commit fade after a single frame.
            if (mSessions.get(position) == mCommittedSession) return mCommittedId;
            TerminalSession session = mSessions.get(position).getTerminalSession();
            return session == null ? RecyclerView.NO_ID : (long) session.mHandle.hashCode();
        }
        if (mPlaceholderActive && position == mSessions.size()) return mPlaceholderId;
        return RecyclerView.NO_ID;
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
        for (int i = 0; i < mAttachedViews.size(); i++) {
            applyTerminalMargins(mAttachedViews.valueAt(i));
        }
    }

    /** Apply the configured margins to one page's TerminalView (safe when null/not yet bound). */
    private void applyTerminalMargins(@androidx.annotation.Nullable TerminalView terminalView) {
        if (terminalView == null) return;
        ViewUtils.setLayoutMarginsInDp(terminalView,
                mMarginLeftDp, mMarginTopDp, mMarginRightDp, mMarginBottomDp);
    }

    /**
     * Push the configured background transparency to every attached page. Mirrors
     * {@link #setTerminalMargins(int, int, int, int)}: the value is owned here (not in the
     * individual views) so pages created later pick it up automatically.
     *
     * @param percent 0 (opaque, wallpaper disabled) .. 50 (maximum transparency).
     */
    public void setTerminalBackgroundTransparency(int percent) {
        mBackgroundTransparencyPercent = percent;
        for (int i = 0; i < mAttachedViews.size(); i++) {
            applyTerminalTransparency(mAttachedViews.valueAt(i));
        }
    }

    /** Apply the configured background transparency to one page (safe when null). */
    private void applyTerminalTransparency(@androidx.annotation.Nullable TerminalView terminalView) {
        if (terminalView != null)
            terminalView.setBackgroundTransparencyPercent(mBackgroundTransparencyPercent);
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
            // Carry the configured background transparency to every newly created page, the same
            // way the margins are carried — a page created mid-swipe must not appear opaque.
            applyTerminalTransparency(holder.mTerminalView);
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position) {
        boolean isPlaceholder = mPlaceholderActive && position == mSessions.size();

        // The placeholder layout carries a "New tab" hint overlay; show it only for the
        // placeholder page and hide it once this slot is rebound to a real session (commit).
        View hint = holder.mHintContainer;
        if (hint != null) {
            if (isPlaceholder) {
                // Tint the hint with the terminal foreground so an unbound (blank) terminal page
                // reads like a real one.
                //
                // The *background* is deliberately NOT painted here. The unbound TerminalView below
                // already fills the whole page with the scheme background in onDraw() (the
                // mEmulator == null placeholder branch), and that fill honours the configured
                // transparency: scheme colour at alpha A in SRC mode. This container is
                // match_parent, so a second full-page layer would either be opaque (wallpaper
                // hidden on this page) or, at the same alpha A, compose over that fill to
                // 2A-A^2 — i.e. double the transparency and tint it with the fill underneath.
                // One layer only: transparent container on top of the TerminalView's own fill.
                int fg = getCurrentTerminalColor(TextStyle.COLOR_INDEX_FOREGROUND);
                hint.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                ImageView plus = holder.mHintPlus;
                if (plus != null) plus.setColorFilter(fg);
                TextView hintText = holder.mHintText;
                if (hintText != null) hintText.setTextColor(fg);
                hint.setVisibility(View.VISIBLE);
                // The whole overlay fades with the drag (see setPlaceholderScrollOffset). Start it
                // transparent: the ViewHolder can be created mid-drag, and fading in from 0 costs at
                // worst one invisible frame, whereas starting at 1 would flash the full overlay.
                hint.setAlpha(0f);
                mPlaceholderHintContainer = hint;
            } else if (mPlaceholderFadingOut) {
                // This slot was JUST rebound to the session the swipe committed, and the overlay is
                // still fading out over it. Leave the container alone — the animation owns its alpha
                // and visibility, and setting GONE here would swallow the fade. Leaving it on top of
                // the new session is the whole point: the terminal appears to fade in from under the
                // placeholder rather than replacing it in one frame.
                hint.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            } else {
                hint.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                hint.setVisibility(View.GONE);
                hint.setAlpha(1f);
                // Drop the overlay handle ONLY when it refers to this holder's own overlay. Binding
                // some other real session page must not orphan the placeholder page's overlay: while
                // the user sits on an earlier tab the placeholder's ViewHolder stays in the view
                // cache and is NOT rebound when the drag brings it back on screen, so a handle
                // cleared from an unrelated bind is never restored. The commit then finds no
                // container to fade and hides the directory list in a single frame — and with no
                // overlay page to track, the reveal is left frozen at the finger's last slice.
                if (mPlaceholderHintContainer == hint) mPlaceholderHintContainer = null;
            }
        }

        View hintContent = holder.mHintContent;
        if (isPlaceholder) {
            mPlaceholderHintContent = hintContent;
            mPlaceholderHolder = holder;
            if (hintContent != null) {
                hintContent.setTranslationX(0);
                // The picker places the hint vertically itself; a stale offset from a previous
                // gesture must not survive into the freshly shown placeholder.
                hintContent.setTranslationY(0);
            }
            // Hand the picker its drawing surface for this binding. It stays invisible until a
            // gesture actually reveals the placeholder (DirectoryPickerController#show).
            mDirectoryPicker.bind(holder.mPickerView, hintContent);
            // Nothing to bind — the TerminalView is intentionally left unbound (no session) and the
            // page blends with the themed window background. We still keep a valid TerminalView in
            // the holder so a commit can rebind it to a real session in place (no ViewHolder churn).
            return;
        }
        if (mPlaceholderFadingOut) {
            // This slot was just committed and the overlay is still leaving over the session that
            // took it. Keep the hint content referenced and the picker bound: the pager is still
            // settling, and both are what the reveal tracking writes to (setPlaceholderScrollOffset
            // and DirectoryPickerController#setRevealedFraction). Dropping them here is what used to
            // freeze the overlay at the finger's last slice, so the content appeared to jump
            // sideways as the page finished sliding in. The fade's end action does the teardown.
        } else if (mPlaceholderHolder == holder) {
            // Same reasoning as the overlay handle above: only this holder's own rebind may drop the
            // placeholder bookkeeping. Any other real page being bound has nothing to do with the
            // placeholder, which can still be alive and cached off screen.
            mPlaceholderHintContent = null;
            mPlaceholderHolder = null;
            mDirectoryPicker.unbind();
        }

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
        // Re-apply the transparency too: the view may be a recycled holder whose renderer was
        // created (in setTextSize/setTypeface) after the last push.
        applyTerminalTransparency(terminalView);

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
        holder.boundPosition = position;
        mAttachedViews.put(position, terminalView);
    }

    /**
     * Payload-aware overload. RecyclerView always calls this (never coalesces) when a payload is
     * supplied, so a {@link #PAYLOAD_REBIND} notification reliably re-runs the full bind (and
     * re-attaches the session to the reused ViewHolder). Both branches perform the identical full
     * bind, so the payload branch is simply forwarded — a plain notifyItemChanged() on an already
     * bound ViewHolder may be skipped by RecyclerView, which is exactly what {@link #commitPlaceholder}
     * must avoid.
     */
    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        onBindViewHolder(holder, position);
    }

    /** @return the TerminalView currently bound to {@code position}, or null if not bound. */
    @androidx.annotation.Nullable
    public TerminalView getAttachedView(int position) {
        return mAttachedViews.get(position);
    }

    @Override
    public void onViewRecycled(@NonNull TerminalPageViewHolder holder) {
        super.onViewRecycled(holder);
        // The overlay is going away with this holder (e.g. the user swiped back off the placeholder
        // mid-fade): stop the animation before its end action can fire against a view that is about
        // to be rebound to a different page.
        if (holder.mHintContainer != null && holder.mHintContainer == mPlaceholderHintContainer) {
            holder.mHintContainer.animate().cancel();
            mPlaceholderFadingOut = false;
            mFadingPage = -1;
            mFadingHintContent = null;
            mPlaceholderHintContainer = null;
            mDirectoryPicker.endFadeOut();
        }
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
            // The placeholder page is going away: drop the picker's view references so a recycled
            // holder can never be written to. The computed geometry is kept — a gesture in flight
            // must still resolve its directory off the stored layout (see DirectoryPickerController).
            mDirectoryPicker.unbind();
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
        // recycled (detached) view for a position that has moved on. The ViewHolder carries the
        // last bound position, so a direct removal is O(1); the (rare) case where it drifted is
        // covered by the linear fallback.
        final int pos = holder.boundPosition;
        if (pos >= 0 && mAttachedViews.get(pos) == holder.mTerminalView) {
            mAttachedViews.remove(pos);
        } else {
            for (int k = 0; k < mAttachedViews.size(); k++) {
                if (mAttachedViews.valueAt(k) == holder.mTerminalView) {
                    mAttachedViews.removeAt(k);
                    break;
                }
            }
        }
        holder.boundPosition = -1;
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
     * The adapter position of the page that currently hosts the placeholder overlay: the trailing
     * placeholder page while it is armed, or — while the commit fade is still running — the page the
     * placeholder was just committed into.
     *
     * <p>It deliberately survives the commit. The pager's scroll callback uses it to work out how
     * much of that page is on screen, and the overlay must keep being driven by that until the page
     * is fully revealed; otherwise the reveal freezes at the slice the finger was at and the content
     * reads as shifted sideways once the page lands.
     *
     * @return the adapter position, or -1 when no overlay is present.
     */
    public int getPlaceholderOverlayPage() {
        if (mFadingPage >= 0) return mFadingPage;
        return mPlaceholderActive ? mSessions.size() : -1;
    }

    /**
     * Show or hide the trailing placeholder page. Inserting it makes a real "next page" exist to the
     * right of the last real tab, so a ViewPager2 right-swipe scrolls into it live (normal
     * tab-to-tab feel) rather than bouncing against a non-existent page.
     */
    public void setPlaceholderActive(boolean active) {
        if (active == mPlaceholderActive) return;
        mPlaceholderActive = active;
        if (active) {
            // A fresh id for every arming: the previous one now belongs to the session that took
            // that slot over (see commitPlaceholder), and two pages must never share an id.
            mPlaceholderId++;
            notifyItemInserted(mSessions.size());
        } else {
            notifyItemRemoved(mSessions.size());
        }
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
        // Hand the slot's id to the session that now occupies it, so the slot does not change id
        // across the commit (see getItemId). The placeholder is re-armed one frame later and gets a
        // fresh id, so this one remains unique.
        mCommittedSession = (placeholderIndex >= 0 && placeholderIndex < mSessions.size())
                ? mSessions.get(placeholderIndex) : null;
        mCommittedId = mPlaceholderId;
        mPlaceholderActive = false;

        // Hand the new session to the ViewHolder that is ALREADY on screen, by binding it directly.
        //
        // notifyItemChanged() cannot do this job, and using it was the bug: this adapter has stable
        // ids, and RecyclerView resolves a *changed* ViewHolder out of mChangedScrap BY ITEM ID
        // (getChangedScrapViewForPosition). The placeholder page carries PLACEHOLDER_ID, but the
        // session that replaces it carries its session-handle hash — so the lookup misses, the
        // on-screen page is recycled, and a DIFFERENT ViewHolder is pulled from the pool for the
        // slot. That has two visible consequences:
        //   1. the replacement page's overlay is freshly inflated (GONE, alpha 1), so the
        //      placeholder content is cut in a single frame;
        //   2. the 150 ms fade-out runs on the recycled view, off screen — which is why the fade
        //      was never seen, and why the placeholder and the new tab read as two different pages.
        //
        // Binding directly keeps the very same ViewHolder. RecyclerView only ever re-resolves it
        // from mAttachedScrap, which is matched BY POSITION (no id check), so the later layout pass
        // that re-arms the trailing placeholder keeps it too — and since it is still bound and not
        // flagged for update, it is not rebound again.
        TerminalPageViewHolder holder = (mPagerRv == null) ? null
                : (TerminalPageViewHolder) mPagerRv.findViewHolderForAdapterPosition(placeholderIndex);
        if (holder != null) {
            onBindViewHolder(holder, placeholderIndex,
                    java.util.Collections.singletonList(PAYLOAD_REBIND));
        } else {
            // The slot is not laid out (it should always be — the user just settled on it). Fall
            // back to a notification so the session still ends up attached to some page.
            notifyItemChanged(placeholderIndex, PAYLOAD_REBIND);
        }
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
     * <p>The fade is applied to the <em>whole</em> placeholder overlay, not just the hint, so every
     * layer of the page — the "+ new session" block and the directory list alike — arrives and
     * leaves together on one ramp. It goes through {@code View.setAlpha()}, which the render node
     * applies at composite time: the fade therefore costs no draw pass.
     *
     * @param pageOffset drag progress toward the placeholder, 0 = still on the last real tab,
     *                   1 = fully settled on the placeholder page.
     */
    public void setPlaceholderScrollOffset(float pageOffset) {
        // While the commit fade is running the overlay belongs to the fade, not to the placeholder
        // slot: the slot has already been re-armed onto a different page by then.
        final View hintContent = mPlaceholderFadingOut ? mFadingHintContent : mPlaceholderHintContent;
        if (hintContent == null) return;
        View parent = (View) hintContent.getParent();
        if (parent == null || parent.getWidth() <= 0) return;
        // At offset 0 the hint sits at the screen's right edge (just peeking in); at offset 1 it is
        // centred on screen. Linear interpolation between those two positions.
        hintContent.setTranslationX(parent.getWidth() * (pageOffset - 1f) / 2f);
        // The hint content is translated horizontally but NOT faded on its own any more — the whole
        // overlay fades, so a per-child alpha here would multiply into it.
        hintContent.setAlpha(1f);
        // The overlay's opacity IS the pull: invisible at offset 0, fully opaque at 1, linear in
        // between. Deliberately not a ramp that saturates early — committing needs the page pulled
        // past halfway, so anything reaching full opacity before 1 would mean the commit fade-out
        // always restarted from opaque instead of continuing from the value the pull had reached.
        //
        // Not re-asserted while the commit fade-out runs: the animation drives the same property,
        // and a scroll frame arriving mid-fade would snap the overlay back up. The horizontal
        // translation above is still applied — the page keeps sliding while it fades.
        if (!mPlaceholderFadingOut) parent.setAlpha(Math.min(1f, pageOffset));
    }

    /**
     * Fade the placeholder overlay out instead of hiding it outright, handing the page over to the
     * session being committed underneath it.
     *
     * <p>Called at the instant the swipe commits: the new session is attached to the very same
     * ViewHolder, so the overlay is not covering something that is not there yet — it merely has to
     * leave. Cutting it to {@code GONE} in that frame is what used to make the placeholder vanish
     * abruptly; see {@link #PLACEHOLDER_FADE_OUT_MS}.
     *
     * <p>The fade <em>continues</em> the opacity the pull reached rather than restarting from
     * opaque: {@code ViewPropertyAnimator} takes its start value from the view's current alpha, and
     * the reveal ramp sets that alpha to the pull progress (see {@link #setPlaceholderScrollOffset}).
     * A short pull therefore commits from a half-transparent placeholder and fades out from there.
     *
     * <p>The hard reset is deferred to the animation's end action, which drops the directory list
     * and restores the hint's offset — the state the picker needs for the next gesture.
     *
     * <p>Falls through to an immediate hide when there is nothing visible to fade (the overlay was
     * never revealed, or the placeholder was dropped while off-screen).
     */
    public void fadeOutPlaceholderOverlay() {
        // Already leaving: let the running animation finish rather than resetting the overlay here,
        // which would snap the list away mid-fade.
        if (mPlaceholderFadingOut) return;

        final View container = mPlaceholderHintContainer;
        if (container == null || container.getVisibility() != View.VISIBLE) {
            // Nothing visible to fade — the overlay was never revealed, or the placeholder was
            // dropped while off-screen. Hide outright so the picker state does not leak.
            mDirectoryPicker.hide();
            return;
        }

        mPlaceholderFadingOut = true;
        // Remember which page the overlay lives on for the duration of the fade: the session count
        // moves on the moment the commit lands, so the pager's scroll callback can no longer derive
        // this page from the live list. See getPlaceholderOverlayPage().
        mFadingPage = mSessions.size();
        // Take ownership of the overlay's handles for the duration of the fade — the re-arm that
        // lands a frame later repoints the placeholder bookkeeping at the new page.
        mFadingHintContent = mPlaceholderHintContent;
        mDirectoryPicker.beginFadeOut();
        // A reveal ramp from the same gesture may still be queued on this view; cancel() also drops
        // any pending end action, so the one below is the only one that can run.
        container.animate().cancel();
        container.animate()
                .alpha(0f)
                .setDuration(PLACEHOLDER_FADE_OUT_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    mPlaceholderFadingOut = false;
                    mFadingPage = -1;
                    mFadingHintContent = null;
                    mDirectoryPicker.endFadeOut();
                    // The trailing slot is re-armed one frame after the commit, so by the time this
                    // runs a NEW placeholder page may already be bound — to a different ViewHolder,
                    // with the picker re-bound to it. Only tear the overlay down when the one we
                    // faded is still the current binding; otherwise the reset would wipe the fresh
                    // page's state instead of this one's.
                    final boolean stillCurrent = (mPlaceholderHintContainer == container);
                    if (stillCurrent) {
                        mPlaceholderHintContainer = null;
                        // The bind path deliberately left these in place for the fade (see
                        // onBindViewHolder); the overlay is gone now, so drop them.
                        mPlaceholderHintContent = null;
                        mPlaceholderHolder = null;
                        mDirectoryPicker.hide();
                        mDirectoryPicker.unbind();
                    }
                    container.setVisibility(View.GONE);
                    // Back to fully opaque for the next time this holder serves as the placeholder;
                    // the placeholder bind sets 0 itself, but the non-placeholder path assumes 1.
                    container.setAlpha(1f);
                })
                .start();
    }

    /**
     * Hide the picker overlay, unless a commit fade-out is in flight — then the animation's end
     * action does it, and hiding now would cut the fade short (and snap the list away while the
     * hint is still visible).
     */
    public void hideDirectoryPicker() {
        if (mPlaceholderFadingOut) return;
        mDirectoryPicker.hide();
    }

    public static final class TerminalPageViewHolder extends RecyclerView.ViewHolder {
        public final TerminalView mTerminalView;
        /** Placeholder "New tab" hint overlay container (the whole-page dim). */
        public final View mHintContainer;
        /** The "+" glyph inside the placeholder hint. */
        public final ImageView mHintPlus;
        /** The "New tab" label inside the placeholder hint. */
        public final TextView mHintText;
        /** The movable hint content group (translated during a drag). */
        public final View mHintContent;
        /** Canvas surface the right-swipe directory list is painted onto (placeholder page only). */
        public final DirectoryPickerView mPickerView;
        /** The adapter position this ViewHolder was last bound to; lets onViewRecycled()
         *  drop the mAttachedViews entry in O(1) without a linear scan. -1 when unbound. */
        public int boundPosition = -1;

        TerminalPageViewHolder(@NonNull View itemView) {
            super(itemView);
            mTerminalView = itemView.findViewById(R.id.terminal_view_page);
            mHintContainer = itemView.findViewById(R.id.terminal_placeholder_hint_container);
            mHintPlus = itemView.findViewById(R.id.terminal_placeholder_hint_plus);
            mHintText = itemView.findViewById(R.id.terminal_placeholder_hint_text);
            mHintContent = itemView.findViewById(R.id.terminal_placeholder_hint_content);
            mPickerView = itemView.findViewById(R.id.terminal_directory_picker);
        }
    }
}
