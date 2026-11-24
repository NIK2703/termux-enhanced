package com.termux.app.terminal.io.autocomplete;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxAppConstants;
import com.termux.app.TermuxActivityUtils;

/**
 * Wraps one auto-complete suggestion row in the same red delete underlay the
 * input-history popup uses. The underlay and the dustbin icon stay hidden until a
 * left swipe starts; the row is opaque while pressed, so at rest no red is visible.
 *
 * <p>The holder only owns the decoration. The swipe gesture lives in
 * {@link AutoCompleteController} because it needs the candidate's source entry to
 * remove the suggestion from history.
 */
final class SuggestionRowHolder extends FrameLayout {

    @NonNull private final TextView mRow;
    @NonNull private final ImageView mDeleteIcon;
    /** True from the moment a swipe delete is accepted until the row is removed. */
    private boolean mDeleting;

    SuggestionRowHolder(@NonNull Context context, @NonNull TextView row) {
        super(context);
        mRow = row;
        setBackgroundColor(Color.TRANSPARENT);

        mDeleteIcon = new ImageView(context);
        mDeleteIcon.setImageResource(R.drawable.ic_delete);
        mDeleteIcon.setColorFilter(Color.WHITE);
        mDeleteIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mDeleteIcon.setVisibility(View.GONE);
        int iconSize = TermuxActivityUtils.dpToPx(context, 24);
        LayoutParams iconParams = new LayoutParams(
                iconSize, iconSize, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        iconParams.rightMargin = TermuxActivityUtils.dpToPx(context, 20);
        addView(mDeleteIcon, iconParams);

        // The row is added last so it paints over the icon until it slides left.
        addView(row, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @NonNull TextView row() {
        return mRow;
    }

    /** Reveal the red underlay and the dustbin icon for the duration of a swipe. */
    void showDeleteUnderlay() {
        setBackgroundColor(TermuxAppConstants.SwipeDelete.BACKGROUND);
        mDeleteIcon.setVisibility(View.VISIBLE);
    }

    /** Return to the idle look: no red and no icon behind the row. */
    void hideDeleteUnderlay() {
        setBackgroundColor(Color.TRANSPARENT);
        mDeleteIcon.setVisibility(View.GONE);
    }

    /** Mark the row as being removed, so a content rebuild does not reuse it. */
    void markDeleting() {
        mDeleting = true;
    }

    boolean isDeleting() {
        return mDeleting;
    }
}
