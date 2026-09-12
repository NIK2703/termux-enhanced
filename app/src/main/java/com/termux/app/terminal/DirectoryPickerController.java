package com.termux.app.terminal;

import android.util.TypedValue;
import android.view.View;

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
    /**
     * Height of one entry, fixed. Rows are never stretched to fill the space and never compressed to
     * make more of them fit — when the space is tight the list simply offers fewer entries, down to
     * none at all. 48 dp is the comfortable touch-target height for the 15 sp label, matching the
     * history popup's rows.
     */
    private static final int ROW_HEIGHT_DP = 48;
    private static final int ROW_PADDING_H_DP = 14;
    private static final int CORNER_RADIUS_DP = 6;
    private static final int TEXT_SIZE_SP = 15;
    /** Fallback hint height (48dp icon + 8dp gap + one text line) used before the first measure. */
    private static final int HINT_FALLBACK_DP = 76;

    private final TermuxActivity mActivity;
    private final ArrayList<String> mItems = new ArrayList<>();

    @Nullable
    private DirectoryPickerView mView;
    @Nullable
    private View mHintContent;

    /**
     * The surface the commit fade-out keeps driving after {@link #mView} has moved on.
     *
     * <p>The trailing placeholder is re-armed roughly one frame after the commit, and that rebind
     * points {@link #mView} at the new page's surface — while the overlay of the page just committed
     * is still on screen, still fading. Without this second handle its rows would stop growing with
     * the page and drift sideways for the rest of the settle instead of completing the reveal.
     */
    @Nullable
    private DirectoryPickerView mFadingView;

    @Nullable
    private DirectoryPickerLayout.Result mLayout;
    private float mPageHeight;
    private int mHighlight = -1;
    /** How much of the page is on screen, 0…1; kept so a rebind can re-apply it. */
    private float mRevealedFraction = 1f;

    public DirectoryPickerController(@NonNull TermuxActivity activity) {
        mActivity = activity;
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
            // INVISIBLE, never GONE. View.setVisibility(GONE) makes setFlags() call requestLayout()
            // — the GONE bit is what triggers it, not the visibility as such — so hiding the picker
            // that way costs a layout traversal of the whole window. It is toggled on the first frame
            // that reveals the placeholder and again when the gesture ends, i.e. exactly on the
            // gesture's critical frame. INVISIBLE keeps the view out of the draw pass with no layout
            // at all, and nothing reads this view's visibility: "nothing to draw" is expressed by the
            // empty item set above, which is the first thing DirectoryPickerView.onDraw checks.
            view.setVisibility(View.INVISIBLE);
            // Measure the candidate labels now, while the page is at rest. show() runs on the first
            // frame that reveals the placeholder — the gesture's critical frame, where the picker also
            // goes from GONE to VISIBLE and has its display list recorded from scratch — and shaping
            // up to ten paths there would land entirely on that frame. setItems() then reuses these
            // widths because show() installs the first `rows` entries of this very list, and falls
            // back to measuring only when the history changed in between.
            buildItems();
            view.premeasure(mItems);
            applyReveal(view);
        }
    }

    /** Drop the view references (page recycled / slot rebound to a real session). */
    public void unbind() {
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

    /** Release the fade's surface (the fade has finished, or was cut short). */
    public void endFadeOut() {
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

    /** Clear the overlay: called when the gesture ends, whichever way it ended. */
    public void hide() {
        mHighlight = -1;
        mLayout = null;
        if (mView != null) {
            mView.setItems(java.util.Collections.emptyList(), null);
            // INVISIBLE for the same reason as in bind() — see the comment there. This is the write
            // that used to land on the settle's IDLE, i.e. a layout traversal at the end of every
            // placeholder gesture.
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
     * Re-apply the scheme colours to the rows, on every surface this controller currently owns.
     *
     * <p>The rows take their colours in {@link #bind}, and the placeholder page's ViewHolder is
     * exactly the one a colour-scheme change does not rebind (while the user sits on a real tab it
     * stays in RecyclerView's view cache), so without this the menu kept the previous scheme's
     * colours until the slot was rebound — i.e. until the next tab was added. Called from the
     * scheme-application path, so it costs nothing per frame.
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
     * Push the current reveal onto the view: how much of it is on screen.
     *
     * <p>Applied as a translation of the whole row block rather than as a row length — see
     * {@link DirectoryPickerView#setRevealedWidth(float)} — so a scroll frame re-applies one
     * render-node property instead of re-recording the rows.
     *
     * <p>Only the reveal — the fade is applied by {@code TerminalPagerAdapter#setPlaceholderScrollOffset}
     * to the whole placeholder overlay, so every layer of the page arrives on the same ramp and a
     * second alpha here would multiply into it.
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
