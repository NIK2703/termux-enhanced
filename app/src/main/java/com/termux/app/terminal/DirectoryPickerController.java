package com.termux.app.terminal;

import android.animation.ValueAnimator;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.terminal.io.autocomplete.DirectoryHistoryController;

import java.util.ArrayList;

/**
 * Owns the state and the behaviour of the directory picker shown on the trailing placeholder page.
 *
 * <p>One instance lives as long as the pager (created by {@link TerminalPagerAdapter}). It is
 * <em>bound</em> to the placeholder page's views on every bind and <em>unbound</em> on recycle, but
 * the geometry it computes survives the binding: the swipe can resolve a directory even if the page
 * view was recycled mid-gesture, because {@link #resolvePick(float)} works off the stored
 * {@link DirectoryPickerLayout.Result} rather than off the views.
 *
 * <h2>Gesture contract</h2>
 * <ol>
 *   <li>{@link #show(float, float)} — called once per gesture, on the first frame that reveals the
 *       placeholder. Locks the anchor and the layout; they never move again for this gesture.</li>
 *   <li>{@link #updateFinger(float)} — on every move, to move the highlight.</li>
 *   <li>{@link #resolvePick(float)} — on release: the row under the finger, or {@code null} for
 *       "everything else", which the caller turns into the default working directory.</li>
 *   <li>{@link #hide()} — when the gesture ends.</li>
 * </ol>
 */
public final class DirectoryPickerController {

    /**
     * How many history entries the menu offers. Deliberately a hard cap independent of the
     * {@code directory_history_max} preference (which bounds how much history is <em>kept</em>):
     * the menu has to stay legible and reachable with one thumb.
     */
    public static final int MAX_ITEMS = 10;

    private static final int PAD_DP = 8;
    private static final int GAP_DP = 12;
    private static final int HINT_PAD_DP = 8;
    /** Fixed row height (48 dp = touch target for 15 sp label); tight space yields fewer rows. */
    private static final int ROW_HEIGHT_DP = 48;
    private static final int ROW_PADDING_H_DP = 14;
    private static final int CORNER_RADIUS_DP = 6;
    private static final int TEXT_SIZE_SP = 15;
    /** Fallback hint height (48dp icon + 8dp gap + one text line) used before the first measure. */
    private static final int HINT_FALLBACK_DP = 76;
    /**
     * How long the un-picked rows take to leave once the finger is lifted.
     *
     * <p>Deliberately far shorter than the pager's settle and than the commit's own
     * {@code PLACEHOLDER_FADE_OUT_MS} (150 ms): the point is that the choice is readable
     * <em>during</em> the settle, not that the list disappears gracefully. By the time the page
     * lands, only the picked row is left, and the commit fade then takes that one away.
     */
    private static final long ROW_FADE_OUT_MS = 50L;

    private final TermuxActivity mActivity;
    private final ArrayList<String> mItems = new ArrayList<>();

    @Nullable
    private DirectoryPickerView mView;
    @Nullable
    private View mHintContent;

    /**
     * The surface the commit fade-out keeps driving after {@link #mView} has moved on.
     * The trailing placeholder is re-armed ~one frame after commit and rebinds {@link #mView}
     * to the new page while the committed page's overlay is still fading; without this second
     * handle its rows would drift sideways for the rest of the settle instead of completing.
     */
    @Nullable
    private DirectoryPickerView mFadingView;

    @Nullable
    private DirectoryPickerLayout.Result mLayout;
    private float mPageHeight;
    private int mHighlight = -1;
    /** How much of the page is on screen, 0…1; kept so a rebind can re-apply it. */
    private float mRevealedFraction = 1f;

    /** Row the release fade leaves alone; {@code -1} = nothing was selected, so every row fades. */
    private int mRowFadeKeepRow = -1;
    /** True while a row fade is in effect, so {@link #clearRowFade()} can bail out for free. */
    private boolean mRowFadeActive;
    /**
     * Drives the release fade. Built once and restarted per gesture rather than allocated on the
     * finger lift — the release is the settle's critical frame.
     */
    private final ValueAnimator mRowFadeAnimator;

    public DirectoryPickerController(@NonNull TermuxActivity activity) {
        mActivity = activity;
        mRowFadeAnimator = ValueAnimator.ofFloat(1f, 0f);
        mRowFadeAnimator.setDuration(ROW_FADE_OUT_MS);
        mRowFadeAnimator.setInterpolator(new LinearInterpolator());
        // Driven off the fraction rather than the animated value: it is a primitive float, so the
        // update callback allocates nothing on the frames it runs.
        mRowFadeAnimator.addUpdateListener(
                animator -> applyRowFade(1f - animator.getAnimatedFraction()));
    }

    /** Attach the placeholder page's views. Called from {@code onBindViewHolder}. */
    public void bind(@Nullable DirectoryPickerView view, @Nullable View hintContent) {
        mView = view;
        mHintContent = hintContent;
        if (view != null) {
            view.setLabelTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP,
                    view.getResources().getDisplayMetrics()));
            view.setMetrics(TermuxActivityUtils.dpToPx(view.getContext(), ROW_PADDING_H_DP),
                    TermuxActivityUtils.dpToPx(view.getContext(), CORNER_RADIUS_DP));
            applyColors(view);
            // A freshly bound surface never carries content: the view may be a recycled one that
            // still holds the previous gesture's rows — notably when a commit fade-out was cut short
            // by the page being recycled, in which case hide() never got to reach the view. The next
            // show() repopulates it.
            view.setItems(java.util.Collections.emptyList(), null);
            // INVISIBLE, never GONE: View.setVisibility(GONE) makes setFlags() call requestLayout()
            // (the GONE bit is what triggers it), costing a whole-window layout on the gesture's
            // critical frame. "Nothing to draw" is the empty item set above — the first thing
            // DirectoryPickerView.onDraw checks.
            view.setVisibility(View.INVISIBLE);
            // Measure candidate labels while the page is at rest: show() runs on the gesture's
            // critical frame, where shaping up to ten paths would land entirely on that frame.
            // setItems() reuses these widths (show installs the first `rows` entries of this list)
            // and only re-measures if history changed in between.
            buildItems();
            view.premeasure(mItems);
            applyReveal(view);
        }
    }

    /**
     * Re-read history and pre-measure labels without touching views.
     *
     * <p>Called from the gesture's ACTION_DOWN so a history change does not force
     * re-shaping of up to {@link #MAX_ITEMS} paths on the critical frame that reveals
     * the menu; no-op cost when history did not change (premeasure early-outs on a
     * matching prefix). {@link #show} installs the rows itself.
     */
    public void refreshItems() {
        buildItems();
        if (mView != null) mView.premeasure(mItems);
    }

    /** Drop the view references (page recycled / slot rebound to a real session). */
    public void unbind() {
        // The surfaces are going away, so a running fade can no longer be pushed to them — and the
        // animator would otherwise keep writing to a view we no longer own until it ends. Stopping
        // it here also drops the flag, so the next bind starts from "no fade".
        stopRowFade();
        mView = null;
        mHintContent = null;
        mFadingView = null;
    }

    /**
     * Hand the currently bound surface to the commit fade-out: from now on the reveal keeps being
     * pushed to it even once {@link #bind} has repointed the controller at the re-armed placeholder
     * page. Paired with {@link #endFadeOut()}.
     */
    public void beginFadeOut() {
        mFadingView = mView;
    }

    /**
     * Release the fade's surface (the fade finished or was cut short). Also where a row fade that
     * outlived its commit is dropped: the adapter's end action only tears the picker down while
     * the container it faded is still the current placeholder binding — not the case after a
     * commit (the page is re-armed one frame later and the picker rebound). Without this the fade
     * would stay armed and the next menu would open with its rows dimmed.
     */
    public void endFadeOut() {
        if (mRowFadeActive) resetRowFade();
        mFadingView = null;
    }

    /**
     * How much of the placeholder page is currently on screen.
     *
     * <p>During the drag the page slides in from the right, so only its left {@code fraction} is
     * visible; the rows are revealed together with the page instead of being laid out at full length
     * the moment the menu appears. The menu fades in on the same ramp the "+ new session" hint
     * already uses, so the two layers arrive together.
     *
     * @param fraction 0 = not visible yet, 1 = fully settled.
     */
    public void setRevealedFraction(float fraction) {
        final float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped == mRevealedFraction) return;
        mRevealedFraction = clamped;
        if (mView != null) applyReveal(mView);
        // The overlay being faded out is no longer the bound one, but it is still on screen and its
        // rows must keep growing with the page until the fade ends.
        if (mFadingView != null && mFadingView != mView) applyReveal(mFadingView);
    }

    /**
     * Lay the menu out for a gesture whose finger sits at {@code anchorY}.
     *
     * @param anchorY    page-local Y of the finger at the moment the placeholder appeared.
     * @param pageHeight height of the placeholder page.
     */
    public void show(float anchorY, float pageHeight) {
        if (pageHeight <= 0f) return;
        mPageHeight = pageHeight;
        // A new gesture: the previous release fade (if any) must not carry over, or the list would
        // open with rows already dimmed.
        resetRowFade();
        buildItems();

        final float hintHeight = measureHintHeight();
        mLayout = DirectoryPickerLayout.compute(pageHeight, dp(PAD_DP), dp(GAP_DP), dp(HINT_PAD_DP),
                anchorY, mItems.size(), hintHeight, dp(ROW_HEIGHT_DP));
        mHighlight = -1;

        if (mView != null) {
            if (mLayout.hasList()) {
                // The layout decides how many entries fit; the rest (the oldest, since the history is
                // newest-first) are simply not offered this time round.
                mView.setItems(mItems.subList(0, mLayout.rows), mLayout);
                mView.setVisibility(View.VISIBLE);
                applyReveal(mView);
            } else {
                mView.setItems(java.util.Collections.emptyList(), null);
                // INVISIBLE for the same reason as in bind() — see the comment there.
                mView.setVisibility(View.INVISIBLE);
            }
        }
        positionHint();
    }

    /** Move the highlight to whatever row the finger is over (no-op when it did not change). */
    public void updateFinger(float pageY) {
        final int index = indexAt(pageY);
        if (index == mHighlight) return;
        mHighlight = index;
        if (mView != null) mView.setHighlight(index);
    }

    /**
     * The directory the release selects.
     *
     * <p>Goes through {@link DirectoryPickerLayout.Result#itemIndexAt(int)} rather than reading the
     * row index straight into the list, so the pick always matches the entry actually painted on
     * that row — which is reversed when the list opened above the finger.
     *
     * @return the path of the row under the finger, or {@code null} for every position outside the
     *         list rows — which the caller resolves to the default working directory.
     */
    @Nullable
    public String resolvePick(float pageY) {
        final DirectoryPickerLayout.Result layout = mLayout;
        if (layout == null) return null;
        final int itemIndex = layout.itemIndexAt(layout.indexAt(pageY));
        return (itemIndex >= 0) ? mItems.get(itemIndex) : null;
    }

    /**
     * Fade out unselected rows in {@link #ROW_FADE_OUT_MS}. Called from the commit path,
     * not the finger lift (a swipe that creates nothing must leave the list intact).
     * Runs on its own clock (fade must be shorter than the pager settle). Reads the
     * current highlight only — never moves it; no highlight means every row fades.
     */
    public void beginRowFadeOut() {
        mRowFadeKeepRow = mHighlight;
        mRowFadeActive = true;
        // Restart rather than resume: a second lift in the same gesture (or a stale run) must not
        // pick up mid-ramp, where the rows would be half gone already.
        mRowFadeAnimator.cancel();
        mRowFadeAnimator.start();
    }

    /** Push the current fade onto every surface the controller owns. */
    private void applyRowFade(float alpha) {
        if (mView != null) mView.setRowFade(mRowFadeKeepRow, alpha);
        // The commit fade-out hands the overlay of the committed page over to mFadingView; its rows
        // must keep fading on the same ramp as the page it is still drawn on.
        if (mFadingView != null && mFadingView != mView) mFadingView.setRowFade(mRowFadeKeepRow, alpha);
    }

    /**
     * Undo the release fade: stop the animator, then hand every surface the neutral value.
     *
     * <p>The neutral value is what makes this safe to call from anywhere: {@code setRowFade} stores
     * it and the next draw pass derives the alphas from it, so a surface that missed the fade's
     * last frames is put back to "no fade" rather than to whatever it happened to hold.
     */
    private void resetRowFade() {
        stopRowFade();
        applyRowFade(1f);
    }

    /** Stop the fade's animator and forget it, without writing to any surface. */
    private void stopRowFade() {
        mRowFadeAnimator.cancel();
        mRowFadeKeepRow = -1;
        mRowFadeActive = false;
    }

    /**
     * Drop the row fade if one is still in effect. Called whenever the placeholder page is fully
     * off screen — the one moment guaranteed to precede any gesture that can reopen the menu, so a
     * fade can never survive into the next pull-out. Returns immediately when idle (one comparison).
     */
    public void clearRowFade() {
        if (!mRowFadeActive) return;
        resetRowFade();
    }

    /** Clear the overlay: called when the gesture ends, whichever way it ended. */
    public void hide() {
        resetRowFade();
        mHighlight = -1;
        mLayout = null;
        if (mView != null) {
            mView.setItems(java.util.Collections.emptyList(), null);
            // INVISIBLE for the same reason as in bind() — see the comment there. This write used
            // to land on the settle's IDLE: a layout traversal at the end of every placeholder gesture.
            mView.setVisibility(View.INVISIBLE);
        }
        if (mHintContent != null) mHintContent.setTranslationY(0f);
    }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    /** Index of the row containing {@code pageY}, or -1 when the position is not on the list. */
    private int indexAt(float pageY) {
        final DirectoryPickerLayout.Result layout = mLayout;
        return (layout != null) ? layout.indexAt(pageY) : -1;
    }

    /** The 10 newest visited directories, newest first. */
    private void buildItems() {
        mItems.clear();
        DirectoryHistoryController history = mActivity.getDirectoryHistoryController();
        if (history == null) return;
        final ArrayList<String> all = history.getHistoryList();
        final int count = Math.min(MAX_ITEMS, all.size());
        for (int i = 0; i < count; i++) {
            final String path = all.get(i);
            if (path != null && !path.isEmpty()) mItems.add(path);
        }
    }

    /**
     * Height of the "+ new session" block. The page is already laid out by the time a swipe starts,
     * so the view's own height is normally available; the manual measure covers the cold case (a
     * placeholder page bound but not yet measured) and the constant covers "no view at all".
     */
    private float measureHintHeight() {
        final View hint = mHintContent;
        if (hint == null) return dp(HINT_FALLBACK_DP);
        if (hint.getHeight() > 0) return hint.getHeight();
        final View parent = (hint.getParent() instanceof View) ? (View) hint.getParent() : null;
        final int width = (parent != null && parent.getWidth() > 0)
                ? parent.getWidth()
                : mActivity.getResources().getDisplayMetrics().widthPixels;
        hint.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int measured = hint.getMeasuredHeight();
        return measured > 0 ? measured : dp(HINT_FALLBACK_DP);
    }

    /**
     * Place the hint block vertically. The block is centred by its own {@code layout_gravity}, and
     * that centring is load-bearing: {@code TerminalPagerAdapter#setPlaceholderScrollOffset} derives
     * the horizontal translation from the block being centred in the page. So the vertical position
     * is expressed as a translation away from the centre rather than by re-laying it out.
     */
    private void positionHint() {
        final View hint = mHintContent;
        if (hint == null) return;
        final DirectoryPickerLayout.Result layout = mLayout;
        if (layout == null || layout.mode == DirectoryPickerLayout.Mode.NONE) {
            hint.setTranslationY(0f);
            return;
        }
        final View parent = (hint.getParent() instanceof View) ? (View) hint.getParent() : null;
        final float containerHeight = (parent != null && parent.getHeight() > 0)
                ? parent.getHeight()
                : mPageHeight;
        final float centreY = layout.hintTop + layout.hintHeight / 2f;
        hint.setTranslationY(centreY - containerHeight / 2f);
    }

    /**
     * Re-apply scheme colours to the rows on every surface this controller owns. The rows take
     * their colours in {@link #bind}, and the placeholder ViewHolder is exactly the one a
     * colour-scheme change does not rebind (while the user sits on a real tab it stays in
     * RecyclerView's cache), so without this the menu kept the old scheme until the next tab add.
     * Also restyles {@link #mFadingView} so a running commit fade finishes in the new palette.
     */
    public void applyColors() {
        if (mView != null) applyColors(mView);
        // The surface a running commit fade-out still drives is no longer the bound one, but it is
        // still on screen — restyle it too or it would finish the fade in the old palette.
        if (mFadingView != null && mFadingView != mView) applyColors(mFadingView);
    }

    private void applyColors(@NonNull DirectoryPickerView view) {
        TermuxColorSchemeManager colors = mActivity.getTermuxColorSchemeManager();
        if (colors == null) return;
        view.setColors(colors.getHistoryTextColor(), colors.getHistoryHighlightFill(),
                colors.getHistoryPopupSepColor());
    }

    /**
     * Push the current reveal onto the view: how much of it is on screen. Applied as a translation
     * of the whole row block rather than a row length — see
     * {@link DirectoryPickerView#setRevealedWidth(float)} — so a scroll frame re-applies one
     * render-node property instead of re-recording the rows. Only the reveal: the fade is applied
     * by {@code TerminalPagerAdapter#setPlaceholderScrollOffset} to the whole placeholder overlay,
     * so every layer arrives on the same ramp and a second alpha here would multiply into it.
     */
    private void applyReveal(@NonNull DirectoryPickerView view) {
        final int width = (view.getWidth() > 0)
                ? view.getWidth()
                : mActivity.getResources().getDisplayMetrics().widthPixels;
        view.setRevealedWidth(mRevealedFraction * width);
    }

    private float dp(int value) {
        return TermuxActivityUtils.dpToPx(mActivity, value);
    }
}
