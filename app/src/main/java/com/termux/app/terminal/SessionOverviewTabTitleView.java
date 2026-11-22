package com.termux.app.terminal;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Single-line tab title that fades the last visible characters instead of drawing an ellipsis.
 *
 * <p>The session overview card is a compact copy of a tab-strip tab. It reuses the strip's fade
 * algorithm so a title is truncated the same way in both places, including during the opening
 * flight, when the view width changes on every frame.</p>
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
    }

    /** Sets the full title. The visible faded prefix is derived from the current width. */
    public void setFullTitle(@Nullable String title) {
        final String singleLine = title == null ? ""
                : title.replace('\n', ' ').replace('\r', ' ');
        if (TextUtils.equals(mFullTitle, singleLine)) return;
        mFullTitle = singleLine;
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
        if (getPaint().measureText(title) <= maxWidth) {
            super.setText(title);
            return;
        }

        final int visibleEnd = getPaint().breakText(title, true, maxWidth, null);
        if (visibleEnd >= title.length()) {
            super.setText(title);
        } else {
            super.setText(TermuxSessionTabsController.buildFadedTitle(title, visibleEnd).text,
                    BufferType.SPANNABLE);
        }
    }
}
