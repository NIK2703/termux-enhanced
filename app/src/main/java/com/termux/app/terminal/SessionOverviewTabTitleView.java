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
 * single-line and two-line modes are both supported.</p>
 */
public class SessionOverviewTabTitleView extends TextView {

    private String mFullTitle = "";
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
        final String fullTitle = title == null ? "" : title;
        if (TextUtils.equals(mFullTitle, fullTitle)) return;
        mFullTitle = fullTitle;
        renderTitle();
    }

    /** Recomputes the faded prefix after the text size or padding changed. */
    public void refreshTitle() {
        renderTitle();
    }

    @Override
    public void setMaxLines(int maxLines) {
        final boolean changed = getMaxLines() != maxLines;
        super.setMaxLines(maxLines);
        // A line-count change alters the wrapping and the fade point. The overview applies
        // maxLines before its first layout, so this is also what keeps the single/double-line
        // preference from leaving a one-line render in a two-line pill.
        if (mReady && changed) renderTitle();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) renderTitle();
    }

    private void renderTitle() {
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
