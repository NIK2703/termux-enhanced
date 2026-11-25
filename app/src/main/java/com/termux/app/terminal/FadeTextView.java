package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * TextView that replaces the platform ellipsis with the shared alpha fade.
 *
 * <p>{@link Truncation#END} uses the tab title renderer: the text wraps to at most
 * {@code maxLines} lines and the last visible code points fade out. {@link Truncation#MIDDLE}
 * keeps a prefix and a suffix on two lines and fades both cut edges, so a long path still
 * shows its root and its final segment.</p>
 */
public class FadeTextView extends TextView {

    public enum Truncation { END, MIDDLE }

    @NonNull private String mFullText = "";
    @NonNull private Truncation mTruncation = Truncation.END;
    private boolean mReady;
    private boolean mRendering;

    public FadeTextView(Context context) {
        this(context, null);
    }

    public FadeTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public FadeTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TextFade.applyFadeContract(this);
        mReady = true;
    }

    /** Choose where the text is cut. END is the default. */
    public void setTruncation(@NonNull Truncation truncation) {
        if (mTruncation == truncation) return;
        mTruncation = truncation;
        render();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (!mReady || mRendering) {
            super.setText(text, type);
            return;
        }
        mFullText = text == null ? "" : text.toString();
        render();
    }

    @Override
    public void setMaxLines(int maxLines) {
        final boolean changed = getMaxLines() != maxLines;
        super.setMaxLines(maxLines);
        if (mReady && changed) render();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mReady && w != oldw) render();
    }

    private void render() {
        if (!mReady || mRendering) return;
        final int width = getWidth() - getPaddingStart() - getPaddingEnd();
        final int maxLines = getMaxLines();
        if (width <= 0 || maxLines <= 0 || mFullText.isEmpty()) {
            setRenderedText(mFullText);
            return;
        }
        final TermuxSessionTabsController.TitleRender end =
                TermuxSessionTabsController.renderFadedText(this, mFullText, width, maxLines);
        if (mTruncation == Truncation.END || !end.truncated || maxLines < 2
                || mFullText.indexOf('\n') >= 0 || mFullText.indexOf('\r') >= 0) {
            setRenderedText(end.text);
            return;
        }
        setRenderedText(middleTruncatedText(mFullText, width, maxLines));
    }

    @NonNull
    private SpannableString middleTruncatedText(@NonNull String text, int width, int maxLines) {
        final TextPaint paint = getPaint();
        int prefixEnd = paint.breakText(text, 0, text.length(), true, width, null);
        if (prefixEnd <= 0 || prefixEnd >= text.length()) return endFallback(text, width, maxLines);
        // Never split a surrogate pair.
        if (Character.isHighSurrogate(text.charAt(prefixEnd - 1))
                && Character.isLowSurrogate(text.charAt(prefixEnd))) {
            prefixEnd--;
        }
        if (prefixEnd <= 0) return endFallback(text, width, maxLines);

        int suffixStart = suffixStart(paint, text, width);
        // Never split a surrogate pair.
        if (suffixStart > 0 && suffixStart < text.length()
                && Character.isHighSurrogate(text.charAt(suffixStart - 1))
                && Character.isLowSurrogate(text.charAt(suffixStart))) {
            suffixStart++;
        }
        if (suffixStart >= text.length()) return endFallback(text, width, maxLines);

        if (prefixEnd >= suffixStart) {
            // The two edges overlap: all text fits when split at the first line's edge.
            if (paint.measureText(text, prefixEnd, text.length()) > width) {
                return endFallback(text, width, maxLines);
            }
            return new SpannableString(text.substring(0, prefixEnd) + "\n"
                    + text.substring(prefixEnd));
        }

        final String prefix = text.substring(0, prefixEnd);
        final String suffix = text.substring(suffixStart);
        final SpannableString visible = new SpannableString(prefix + "\n" + suffix);
        TextFade.fadeTail(visible, prefix.length());
        TextFade.fadeHead(visible, prefix.length() + 1, visible.length());
        return visible;
    }

    /** Smallest index whose suffix fits one line of {@code width}. */
    private static int suffixStart(@NonNull Paint paint, @NonNull String text, int width) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (paint.measureText(text, mid, text.length()) <= width) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    @NonNull
    private SpannableString endFallback(@NonNull String text, int width, int maxLines) {
        final TermuxSessionTabsController.TitleRender render =
                TermuxSessionTabsController.renderFadedText(this, text, width, maxLines);
        return new SpannableString(render.text);
    }

    private void setRenderedText(@NonNull CharSequence text) {
        mRendering = true;
        try {
            super.setText(text, BufferType.SPANNABLE);
        } finally {
            mRendering = false;
        }
    }
}
