package com.termux.app.terminal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.function.Consumer;

/**
 * RecyclerView adapter backing the horizontal session pager (ViewPager2).
 *
 * <p>Each page is its own {@link TerminalView} bound to exactly one {@link TerminalSession}, so a
 * horizontal swipe reveals the neighbouring session live (the classic paging feel). When the
 * opt-in "swipe rightmost tab for new session" setting is on, a trailing <b>placeholder</b> page
 * is appended (see {@link #setPlaceholderActive}): a real adjacent page with an unbound
 * TerminalView plus a "New tab" hint; releasing on it commits a real session in its place.
 *
 * <p>All pages share the single {@link TermuxTerminalViewClient} owned by the activity, and the
 * activity keeps its {@code mTerminalView} field pointed at the currently selected page (updated
 * in {@code onPageSelected}), so {@link TermuxActivity#getTerminalView()} keeps working unchanged.
 */
public final class TerminalPagerAdapter extends RecyclerView.Adapter<TerminalPagerAdapter.TerminalPageViewHolder> {

    /**
     * Stable ids are deliberately <b>not</b> enabled: nothing needs item identity (pages are
     * addressed by position, the item animator is off, notifyDataSetChanged() is never used), and
     * with them on, RecyclerView's id validation would recreate any holder whose id no longer
     * matches — fatal on the placeholder-commit path, where the on-screen holder is bound directly
     * and keeps its id. A recreated holder means a detached focused view, i.e. the IME drops and
     * re-opens. Without stable ids, holders match by position, so a page keeps its view and focus.
     */
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

    /**
     * Maps a live {@link TerminalSession} to the {@link TerminalView} currently displaying it.
     *
     * <p>Keyed by <b>session</b>, not by adapter position: closing a middle session shifts every
     * later page down without rebinding it, so a position-keyed map would have to be shifted by
     * hand on every structural change — and any slot the shift missed would resolve to a stale
     * view for a session that really is on screen.
     *
     * <p>{@link java.util.IdentityHashMap} is deliberate — session identity, not equality.
     */
    private final java.util.IdentityHashMap<TerminalSession, TerminalView> mSessionViews =
            new java.util.IdentityHashMap<>();

    /**
     * Fired from {@link #onBindViewHolder} — the one moment at which "this page now has a view" is
     * known for certain; the pager uses it to re-point the activity's active TerminalView when the
     * active page binds late (beyond {@code offscreenPageLimit}). Event-driven replacement for the
     * old child-attach + {@code post} + 300 ms recovery path, which never fired for an
     * already-attached view and left the activity routing input to a dead session.
     */
    public interface OnPageBoundListener {
        void onPageBound(@NonNull TerminalSession session, @NonNull TerminalView view);
    }

    private OnPageBoundListener mOnPageBoundListener;

    public void setOnPageBoundListener(@Nullable OnPageBoundListener listener) {
        mOnPageBoundListener = listener;
    }

    /** The movable "New tab" hint content inside the placeholder page, or null if the placeholder
     *  is not currently bound. Translated horizontally during a drag so the hint stays centered in
     *  the visible slice of the placeholder page. */
    private View mPlaceholderHintContent = null;
    /** The ViewHolder currently displaying the placeholder page (so we can clear state on recycle). */
    private TerminalPageViewHolder mPlaceholderHolder = null;

    /**
     * The placeholder overlay container currently bound, or null. Kept separately from
     * {@link #mPlaceholderHintContent} so {@link #fadeOutPlaceholderOverlay()} can still reach the
     * overlay after the slot has been rebound to a real session — the bind path has already
     * dropped the "this is the placeholder" bookkeeping by then.
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
     * Adapter position of the page that hosts the overlay currently fading out, or -1. Set for the
     * duration of the commit fade only: the page stops being "the placeholder" the instant the
     * session is committed, yet the overlay must keep tracking the pager's settle until the page
     * is fully revealed — otherwise the reveal freezes at the finger's last slice.
     */
    private int mFadingPage = -1;

    /**
     * Hint content of the overlay being faded out, captured at fade start: the re-arm a frame after
     * commit repoints {@link #mPlaceholderHintContent} at the NEW page, which would stop driving the
     * on-screen overlay. Held by the fade until it ends.
     */
    private View mFadingHintContent = null;

    /**
     * Placeholder overlay fade-out duration: short enough to read as the tail of the swipe, long
     * enough that the freshly attached terminal does not snap in behind it; fits inside the pager's
     * settle so the page is still by the time the fade lands.
     */
    private static final long PLACEHOLDER_FADE_OUT_MS = 150L;

    /**
     * User-configured terminal margins in dp (settings "terminal-margin-*"). Applied to the
     * TerminalView of EVERY page so the pager stays full-bleed while each terminal keeps its inset.
     */
    private int mMarginLeftDp = 0;
    private int mMarginTopDp = 0;
    private int mMarginRightDp = 0;
    private int mMarginBottomDp = 0;

    /**
     * User-configured terminal background transparency in percent (setting
     * "terminal-background-transparency"): 0 = opaque, 50 = maximum. Applied to every page so a
     * page created mid-swipe never appears opaque while the wallpaper is on.
     */
    private int mBackgroundTransparencyPercent = 0;

    public TerminalPagerAdapter(@NonNull TermuxActivity activity,
                                 @NonNull TermuxTerminalViewClient viewClient,
                                 @NonNull List<TermuxSession> sessions) {
        this.mActivity = activity;
        this.mViewClient = viewClient;
        this.mSessions = sessions;
        this.mDirectoryPicker = new DirectoryPickerController(activity);
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
     * Sync the adapter with the live service session list using incremental notifications
     * instead of {@link #notifyDataSetChanged()}: a full invalidation clears every pending
     * ViewHolder, and a GapWorker callback firing after it crashes with
     * {@code IndexOutOfBoundsException} ("Invalid item position" / "Inconsistency detected").
     */
    public void syncWithServiceList(@NonNull List<TermuxSession> serviceSessions) {
        // If a placeholder page was present, drop it first so the diff below operates on a clean
        // real-session list (getItemCount() must reflect only the real sessions during the diff).
        if (mPlaceholderActive) {
            mPlaceholderActive = false;
            // The placeholder has no session, so no mSessionViews entry — nothing to drop.
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
                    // mSessionViews is keyed by session: shifted pages keep their views, no
                    // key bookkeeping here.
                    notifyItemRangeRemoved(removeStart, removedCount);
                    return;
                }
            }
            // No mismatch found in the shared portion → last item(s) were removed.
            mSessions = new java.util.ArrayList<>(serviceSessions);
            // Entries for the removed sessions are dropped in onViewRecycled() when their
            // ViewHolders go back to the pool; nothing positional to clean up here.
            notifyItemRangeRemoved(oldSize - removedCount, removedCount);
        }
        // Same size — no structural change; tab titles etc. handled by updateTabs().
    }

    /**
     * Update the per-page terminal margins (settings "terminal-margin-*"). Applied to every page's
     * TerminalView — placeholder included, even one parked in RecyclerView's view cache (see
     * {@link #forEachPageTerminalView}) — so the pager container stays full-bleed and each
     * terminal screen keeps its own inset.
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
        forEachPageTerminalView(this::applyTerminalMargins);
    }

    /** Apply the configured margins to one page's TerminalView (safe when null/not yet bound). */
    private void applyTerminalMargins(@androidx.annotation.Nullable TerminalView terminalView) {
        if (terminalView == null) return;
        ViewUtils.setLayoutMarginsInDp(terminalView,
                mMarginLeftDp, mMarginTopDp, mMarginRightDp, mMarginBottomDp);
    }

    /**
     * Push the configured background transparency to every page, the placeholder included (it
     * paints its own fill at this alpha — see {@link #forEachPageTerminalView}). The value is
     * owned here so pages created later pick it up automatically.
     *
     * @param percent 0 (opaque, wallpaper disabled) .. 50 (maximum transparency).
     */
    public void setTerminalBackgroundTransparency(int percent) {
        mBackgroundTransparencyPercent = percent;
        forEachPageTerminalView(this::applyTerminalTransparency);
    }

    /** Apply the configured background transparency to one page (safe when null). */
    private void applyTerminalTransparency(@androidx.annotation.Nullable TerminalView terminalView) {
        if (terminalView != null)
            terminalView.setBackgroundTransparencyPercent(mBackgroundTransparencyPercent);
    }

    /** Push both per-page settings (margins + transparency) to one page's TerminalView. */
    private void applyPerPageConfig(@androidx.annotation.Nullable TerminalView terminalView) {
        applyTerminalMargins(terminalView);
        applyTerminalTransparency(terminalView);
    }

    /**
     * Run {@code action} on the TerminalView of every session page plus the placeholder.
     *
     * <p>Do not walk the pager's children: they are page <em>containers</em>, not TerminalViews, so
     * an {@code instanceof} walk silently degrades to the active page only (the same walk in
     * {@code TermuxTerminalSessionActivityClient} became dead code, commit dddc8121). A holder from
     * RecyclerView's view cache is also handed back without {@code onBindViewHolder()}
     * ({@code Recycler#tryGetViewHolderForPositionByDeadline}), so {@link #mSessionViews} plus
     * {@link #mPlaceholderHolder} is the complete set. Callers are settings changes, not per-frame;
     * actions must tolerate an unattached view (and, for the placeholder, one with no renderer).
     */
    public void forEachPageTerminalView(@NonNull Consumer<TerminalView> action) {
        for (TerminalView view : mSessionViews.values()) {
            if (view != null) action.accept(view);
        }
        final TerminalPageViewHolder holder = mPlaceholderHolder;
        if (holder != null && holder.mTerminalView != null) action.accept(holder.mTerminalView);
    }

    // NOTE: the placeholder page uses the SAME view type (and layout) as a normal terminal page —
    // committing it is an in-place rebind of the same ViewHolder (notifyItemChanged reuses it), so
    // there is no ViewHolder recreation / flash and the activity's active TerminalView pointer
    // stays valid.

    @NonNull
    @Override
    public TerminalPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View page = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_terminal_page, parent, false);
        TerminalPageViewHolder holder = new TerminalPageViewHolder(page);
        // Bind the shared client immediately — the placeholder returns early from
        // onBindViewHolder() before the per-session wiring, and mClient == null would crash any
        // IME/gesture access (e.g. onCreateInputConnection) with a NullPointerException.
        if (holder.mTerminalView != null) {
            holder.mTerminalView.setTerminalViewClient(mViewClient);
            // Margins and transparency too, so even the not-yet-bound placeholder page carries
            // the configured inset / alpha (a page created mid-swipe must not appear opaque).
            applyPerPageConfig(holder.mTerminalView);
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position) {
        boolean isPlaceholder = mPlaceholderActive && position == mSessions.size();

        // The placeholder layout carries a "New tab" hint overlay; show it only for the
        // placeholder page and hide it once this slot is rebound to a real session (commit).
        //
        // NOTE: no branch paints a background on the container, deliberately: the unbound
        // TerminalView already fills the whole page with the scheme background at the configured
        // transparency (onDraw's mEmulator == null branch). A second match_parent layer would
        // either be opaque (wallpaper hidden) or compose to 2A-A^2 — double the transparency.
        // The setBackgroundColor(TRANSPARENT) calls that used to stand here were no-ops that still
        // cost a ColorDrawable and an ancestor invalidation on the first bind of each holder.
        View hint = holder.mHintContainer;
        if (hint != null) {
            if (isPlaceholder) {
                // Tint the hint with the terminal foreground so an unbound (blank) terminal page
                // reads like a real one.
                applyPlaceholderHintColors(holder);
                hint.setVisibility(View.VISIBLE);
                // The whole overlay fades with the drag (see setPlaceholderScrollOffset). Start it
                // transparent: the ViewHolder can be created mid-drag, and fading in from 0 costs at
                // worst one invisible frame, whereas starting at 1 would flash the full overlay.
                hint.setAlpha(0f);
                mPlaceholderHintContainer = hint;
            } else if (mPlaceholderFadingOut) {
                // This slot was JUST rebound to the committed session and the overlay is still
                // fading over it. The animation owns the container's alpha/visibility; setting
                // INVISIBLE here would swallow the fade — the terminal must appear to fade in
                // from under the placeholder.
            } else {
                // INVISIBLE, never GONE — GONE makes setFlags() call requestLayout(), and this
                // runs on every bind of a non-placeholder page, including the re-arm a frame
                // after a commit (inside the swipe's settle). See the layout note.
                hint.setVisibility(View.INVISIBLE);
                hint.setAlpha(1f);
                // Drop the overlay handle ONLY when it refers to this holder's own overlay:
                // clearing it from an unrelated bind would orphan a cached placeholder page
                // (never rebound while off screen), and the commit would then find no container
                // to fade, leaving the reveal frozen at the finger's last slice.
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
            // Push the live per-page settings: this holder may come from the recycler pool with a
            // stale config, and no other push path reaches a pooled page (mPlaceholderHolder was
            // cleared on recycle, and there is no mSessionViews entry without a session).
            applyPerPageConfig(holder.mTerminalView);
            // Nothing to bind — the TerminalView is intentionally left unbound (no session). We
            // still keep a valid TerminalView in the holder so a commit can rebind it in place.
            return;
        }
        if (mPlaceholderFadingOut) {
            // This slot was just committed and the overlay is still leaving over the session that
            // took it. Keep the hint content referenced and the picker bound: the pager is still
            // settling and both are what the reveal tracking writes to. Dropping them here is what
            // used to freeze the overlay (content jumped sideways as the page finished sliding in).
            // The fade's end action does the teardown.
        } else if (mPlaceholderHolder == holder) {
            // Only this holder's own rebind may drop the placeholder bookkeeping — any other real
            // page being bound has nothing to do with a placeholder that may still be cached off
            // screen.
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
        // Re-apply the transparency too: the view may be a recycled holder whose renderer was
        // created (in setTextSize/setTypeface) after the last push.
        applyPerPageConfig(terminalView);

        // Attach the focus-change listener that drives the soft keyboard to THIS page's view.
        // Done here (not in TermuxTerminalViewClient.setSoftKeyboardState) because the shared
        // active view may be null during early lifecycle, while the page view is always non-null here.
        mViewClient.registerTerminalViewFocusListener(terminalView);

        // Register the terminal context menu on THIS page's TerminalView (not the activity root):
        // on the root, a long-press on the sibling text-input panel would bubble up and show the
        // terminal menu over the input field instead of letting the EditText select a word
        // (regression).
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

        // Remember this session->view mapping so the activity can resolve the active page's view
        // even when RecyclerView.findViewHolderForAdapterPosition() is still null mid-swipe. Keyed
        // by session, so a page that shifts position (a middle tab closed) needs no bookkeeping.
        holder.boundSession = session;
        mSessionViews.put(session, terminalView);
        // Event-driven re-point of the activity's active view: if this page is the active one, the
        // activity picks it up right now instead of waiting for some later (possibly never-coming)
        // event. No timers, no posts.
        if (mOnPageBoundListener != null) mOnPageBoundListener.onPageBound(session, terminalView);
    }

    /**
     * Payload-aware overload. RecyclerView never coalesces a call that carries a payload, so
     * {@link #PAYLOAD_REBIND} reliably re-runs the full bind — a plain notifyItemChanged() on an
     * already bound ViewHolder may be skipped, which is exactly what {@link #commitPlaceholder}
     * must avoid.
     */
    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        onBindViewHolder(holder, position);
    }

    /**
     * @return the {@link TerminalView} currently displaying {@code session}, or null when that
     *         session has no attached page (not bound yet, or beyond {@code offscreenPageLimit}).
     */
    @Nullable
    public TerminalView getViewForSession(@Nullable TerminalSession session) {
        if (session == null) return null;
        TerminalView view = mSessionViews.get(session);
        // A detached view is no longer on any page (recycled, or shifted out of the window): treat
        // it as absent so callers fall back to the live RecyclerView instead of routing input to a
        // page that is not on screen.
        return (view != null && view.isAttachedToWindow()) ? view : null;
    }

    /** @return the number of REAL session pages — the trailing placeholder is not counted. */
    public int getSessionCount() {
        return mSessions.size();
    }

    /** @return true when the adapter already backs exactly this sequence of sessions. */
    public boolean sameSessions(@NonNull List<TermuxSession> sessions) {
        if (mSessions.size() != sessions.size()) return false;
        for (int i = 0; i < sessions.size(); i++) {
            if (mSessions.get(i) != sessions.get(i)) return false;
        }
        return true;
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
        // Drop the session->view entry: the recycled holder's view is no longer displaying that
        // session. Keyed by session, so the removal is exact regardless of what shifted.
        final TerminalSession bound = holder.boundSession;
        if (bound != null && mSessionViews.get(bound) == holder.mTerminalView) {
            mSessionViews.remove(bound);
        } else if (holder.mTerminalView != null) {
            mSessionViews.values().remove(holder.mTerminalView);
        }
        holder.boundSession = null;
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
     * The adapter position hosting the placeholder overlay: the trailing placeholder while armed,
     * or — while the commit fade runs — the page it was just committed into (the overlay must keep
     * being driven until that page is fully revealed, else the reveal freezes at the finger's
     * slice).
     *
     * @return the adapter position, or -1 when no overlay is present.
     */
    public int getPlaceholderOverlayPage() {
        if (mFadingPage >= 0) return mFadingPage;
        return mPlaceholderActive ? mSessions.size() : -1;
    }

    /**
     * Show or hide the trailing placeholder page. Inserting it makes a real "next page" exist, so a
     * ViewPager2 right-swipe scrolls into it live instead of bouncing against a non-existent page.
     */
    public void setPlaceholderActive(boolean active) {
        if (active == mPlaceholderActive) return;
        mPlaceholderActive = active;
        if (active) {
            notifyItemInserted(mSessions.size());
        } else {
            // The placeholder never had a session, so there is no mSessionViews entry to drop.
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
        mPlaceholderActive = false;

        // Hand the new session to the ViewHolder that is ALREADY on screen, by binding it directly.
        //
        // notifyItemChanged() cannot do this job, and using it was the bug: RecyclerView resolves a
        // *changed* ViewHolder out of mChangedScrap, and the placeholder is not guaranteed to be
        // found there — so the on-screen page is recycled and a DIFFERENT holder is pulled from the
        // pool: the replacement's overlay is freshly inflated (cut in a single frame), and the
        // 150 ms fade-out runs on the recycled view, off screen. Binding directly keeps the same
        // ViewHolder, position and TerminalView — so the page keeps its focus and IME connection.
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
     * Re-apply the terminal palette to the placeholder page's overlay — the "+ new session" block
     * and the directory menu's rows.
     *
     * <p>The placeholder's ViewHolder is precisely the one a colour-scheme change does <em>not</em>
     * rebind: while the user sits on a real tab it stays in RecyclerView's view cache, so without
     * this hook the overlay kept the palette of whichever scheme was current when the slot was last
     * bound — catching up only on the next tab addition.
     *
     * <p>Idempotent and cheap (runs from the scheme-application path, never per frame). Nothing to
     * do when no placeholder is bound — the next bind reads the updated palette itself.
     */
    public void applyPlaceholderColors() {
        final TerminalPageViewHolder holder = mPlaceholderHolder;
        if (holder != null && holder.mHintContainer != null) {
            applyPlaceholderHintColors(holder);
        }
        // The rows carry their own colours; the controller also covers the surface a running commit
        // fade-out still owns, which is no longer the bound one.
        mDirectoryPicker.applyColors();
    }

    /**
     * Tint the "+ new session" block with the terminal foreground so an unbound (blank) terminal
     * page reads like a real one. Called on every placeholder bind and on a colour-scheme change
     * (see {@link #applyPlaceholderColors()}).
     *
     * <p>Guarded by the colour last applied to <em>this holder</em>: both writes allocate on the
     * framework side, and a placeholder bind inside the swipe's settle almost always sees the
     * colour already in force. The cache is per holder (not adapter-wide) because the placeholder
     * holder changes on every commit. Background is deliberately not painted here — one layer only,
     * see {@link #onBindViewHolder}.
     */
    private void applyPlaceholderHintColors(@NonNull TerminalPageViewHolder holder) {
        final int fg = getCurrentTerminalColor(TextStyle.COLOR_INDEX_FOREGROUND);
        if (holder.mHintFgValid && fg == holder.mHintFg) return;
        holder.mHintFgValid = true;
        holder.mHintFg = fg;
        if (holder.mHintPlus != null) holder.mHintPlus.setColorFilter(fg);
        if (holder.mHintText != null) holder.mHintText.setTextColor(fg);
    }

    /**
     * Translate the placeholder hint horizontally while the user drags from the last real tab, so
     * the hint stays centered in the <em>visible slice</em> of the placeholder page (between the
     * drag split point and the screen's right edge) instead of being pinned to the centre of the
     * partly off-screen page. The fade is applied to the whole overlay, so every layer arrives and
     * leaves together on one ramp (setAlpha is a composite-time op — no extra draw pass).
     *
     * @param pageOffset drag progress toward the placeholder, 0 = still on the last real tab,
     *                   1 = fully settled on the placeholder page.
     */
    public void setPlaceholderScrollOffset(float pageOffset) {
        // While the commit fade is running the overlay belongs to the fade, not to the placeholder
        // slot: the slot has already been re-armed onto a different page by then.
        final View hintContent = mPlaceholderFadingOut ? mFadingHintContent : mPlaceholderHintContent;
        if (hintContent == null) return;
        final View parent = (View) hintContent.getParent();
        if (parent == null) return;
        // Width is read live, deliberately not latched: TermuxActivity declares
        // configChanges="orientation|screenSize|…", so a rotation or split-screen resize does NOT
        // recreate the activity — the pager's width changes while this view survives, and a latched
        // width would misposition the overlay for the whole of the next gesture. It is two field
        // reads; not worth caching.
        final int width = parent.getWidth();
        if (width <= 0) return;

        // Offset 0 = hint at the screen's right edge, 1 = centred; lerp between them. Snapped to
        // whole pixels: a fractional translation rasterises soft edges and still re-records the
        // ancestors' display lists. Endpoints are unaffected (offset 1 is exactly 0, and at 0 the
        // overlay is fully transparent).
        hintContent.setTranslationX(Math.round(width * (pageOffset - 1f) / 2f));
        // The hint content is not faded on its own — the whole overlay fades (below).
        //
        // The overlay's opacity IS the pull: invisible at 0, fully opaque at 1, linear between —
        // not a ramp that saturates early, since committing needs the page pulled past halfway and
        // a premature full opacity would make the commit fade restart from opaque. Quantized to
        // the 8-bit alpha step so a callback that would not move the rendered alpha skips
        // re-recording ancestors' display lists. Not re-asserted while the commit fade runs (the
        // animation drives the same property); the horizontal translation above still applies.
        if (!mPlaceholderFadingOut) parent.setAlpha(quantizeAlpha(Math.min(1f, pageOffset)));
    }

    /** Round an alpha to the 8-bit step the framework's alpha channel can express. */
    private static float quantizeAlpha(float alpha) {
        return Math.round(alpha * 255f) / 255f;
    }

    /**
     * Fade the placeholder overlay out instead of hiding it outright, handing the page over to the
     * session committed underneath (cutting to GONE in that frame is what used to make it vanish
     * abruptly — see {@link #PLACEHOLDER_FADE_OUT_MS}).
     *
     * <p>The fade <em>continues</em> the opacity the pull reached (ViewPropertyAnimator starts
     * from the view's current alpha, which {@link #setPlaceholderScrollOffset} set to the pull
     * progress), so a short pull commits from a half-transparent placeholder. The hard reset is
     * deferred to the animation's end action. Falls through to an immediate hide when there is
     * nothing visible to fade.
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
                    // INVISIBLE, never GONE (see the layout note): this end action is the last frame
                    // of the commit fade, so it runs inside the settle animation — the one moment a
                    // layout traversal of the whole window is least welcome.
                    container.setVisibility(View.INVISIBLE);
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
        /** The session this ViewHolder's TerminalView is currently displaying; null when unbound.
         *  Lets onViewRecycled() drop the session->view entry without a scan. */
        public TerminalSession boundSession = null;
        /**
         * Foreground colour last applied to {@link #mHintPlus} / {@link #mHintText}, and whether it
         * has been applied at all. Guards the two framework-side allocations in
         * {@link #applyPlaceholderHintColors} — see there for why the cache is per holder.
         *
         * <p>A separate validity flag rather than a sentinel colour, because every {@code int} is a
         * legal ARGB value.
         */
        public int mHintFg;
        public boolean mHintFgValid = false;

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
