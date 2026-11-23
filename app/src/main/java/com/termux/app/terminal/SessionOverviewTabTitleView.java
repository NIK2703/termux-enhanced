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
 * wrapping and fade algorithm so a title is truncated the same way in both places, including
 * during the opening flight when the view width changes on every frame. The strip's single-line
 * and two-line modes are both supported.</p>
 */
public class SessionOverviewTabTitleView extends TextView {

    private String mFullTitle = "";

    public SessionOverviewTabTitleView(Context context) {
        this(context, null);
    }

    public SessionOverviewTabTitleView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public SessionOverviewTabTitleView(Context context, @Nullable AttributeSet attrs,
                                       int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // Truncation is an alpha fade; a platform ellipsis would draw on top of the faded
        // prefix and is exactly the "..." the overview must never show.
        setEllipsize(null);
        setHorizontallyScrolling(false);
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) renderTitle();
    }

    private void renderTitle() {
        final String title = mFullTitle;
        final int maxWidth = getWidth() - getPaddingStart() - getPaddingEnd();
        if (title.isEmpty() || maxWidth <= 0) {
            super.setText(title);
            return;
        }

        final TermuxSessionTabsController.TitleRender render =
                TermuxSessionTabsController.renderTitle(this, title, maxWidth);
        super.setText(render.text, BufferType.SPANNABLE);
    }
}
