package com.termux.app.terminal;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Tab title that fades the last visible characters instead of drawing an ellipsis.
 *
 * <p>The session overview card is a compact copy of a tab-strip tab. It reuses the strip's
 * wrapping and fade algorithm so a title is truncated the same way in both places. The strip's
 * single-line and two-line modes are both supported. A flight ghost can also borrow an already
 * rendered title from the view it stands in for, which keeps its line breaks identical even when
 * the ghost's own padding would have produced a different soft break.</p>
 */
public class SessionOverviewTabTitleView extends TextView {

    private String mFullTitle = "";
    /**
     * True while this view carries a title rendered by another {@link TextView}. Flight ghosts
     * use this to keep the exact line breaks of the strip or card they represent instead of
     * re-wrapping the full title for their own width.
     */
    private boolean mPreRendered;
    /** False until the super constructor and this view's own setup have finished. */
    private boolean mReady;

    public SessionOverviewTabTitleView(Context context) {
        this(context, null);
    }

    public SessionOverviewTabTitleView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public SessionOverviewTabTitleView(Context context, @Nullable AttributeSet attrs,
                                       int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TextFade.applyFadeContract(this);
        // The overview uses this title as a flying ghost above the terminal. Its Z offset keeps
        // it above the terminal snapshot, but the default background outline would make that
        // offset cast a shadow. A title never needs an outline.
        setOutlineProvider(null);
        mReady = true;
    }

    /** Sets the full title. The visible faded prefix is derived from the current width. */
    public void setFullTitle(@Nullable String title) {
        // A new full title always wins over a borrowed render, even when the text is unchanged.
        final boolean wasPreRendered = mPreRendered;
        mPreRendered = false;
        final String fullTitle = title == null ? "" : title;
        if (TextUtils.equals(mFullTitle, fullTitle) && !wasPreRendered) return;
        mFullTitle = fullTitle;
        renderTitle();
    }

    /**
     * Shows a title already rendered by another view, line breaks and fade spans included.
     *
     * <p>Flight ghosts call this for the strip or card view they stand in for. Re-rendering the
     * full title at the ghost's own padding would choose different soft breaks even when the
     * outer pill width and close-button position are identical.</p>
     */
    public void setPreRenderedTitle(@Nullable CharSequence title) {
        mPreRendered = true;
        TextFade.applyFadeContract(this);
        super.setText(title == null ? "" : title, BufferType.SPANNABLE);
    }

    /** Recomputes the faded prefix after the text size or padding changed. */
    public void refreshTitle() {
        if (mPreRendered) return;
        renderTitle();
    }

    @Override
    public void setMaxLines(int maxLines) {
        final boolean changed = getMaxLines() != maxLines;
        super.setMaxLines(maxLines);
        // A line-count change alters the wrapping and the fade point. The overview applies
        // maxLines before its first layout, so this is also what keeps the single/double-line
        // preference from leaving a one-line render in a two-line pill.
        if (mReady && changed && !mPreRendered) renderTitle();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && !mPreRendered) renderTitle();
    }

    private void renderTitle() {
        if (mPreRendered) return;
        // A theme, inflation attribute, or line-count change may have installed an ellipsis
        // since the last render; clear it before the TextView measures the faded prefix.
        TextFade.applyFadeContract(this);
        final int maxWidth = getWidth() - getPaddingStart() - getPaddingEnd();
        final TermuxSessionTabsController.TitleRender render =
                TermuxSessionTabsController.renderTitle(this, mFullTitle, maxWidth);
        // renderTitle() also strips a truncation marker added by the title producer and, once
        // the width is known, fades the last real code points. At width 0 it returns the marker
        // free title; onSizeChanged() replaces it with the faded prefix after layout.
        super.setText(render.text, BufferType.SPANNABLE);
    }
}
