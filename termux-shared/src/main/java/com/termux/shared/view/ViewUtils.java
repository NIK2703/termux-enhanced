package com.termux.shared.view;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

public class ViewUtils {

    /** Convert value in device independent pixels (dp) to pixels (px) units. */
    public static float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    public static void setLayoutMarginsInDp(@NonNull View view, int left, int top, int right, int bottom) {
        Context context = view.getContext();
        setLayoutMarginsInPixels(view, (int) dpToPx(context, left), (int) dpToPx(context, top),
            (int) dpToPx(context, right), (int) dpToPx(context, bottom));
    }

    public static void setLayoutMarginsInPixels(@NonNull View view, int left, int top, int right, int bottom) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            // Identity early-out: setLayoutParams() ends in requestLayout(), i.e. a measure/layout
            // traversal of the whole window for a set of margins that are already in force. The pager
            // re-applies these on every page bind — including the trailing placeholder's re-arm, which
            // lands inside the settle of the swipe that opened a tab — where the values are almost
            // always the ones already set. Comparing four ints is free; the traversal is not.
            if (params.leftMargin == left && params.topMargin == top
                    && params.rightMargin == right && params.bottomMargin == bottom) {
                return;
            }
            params.setMargins(left, top, right, bottom);
            view.setLayoutParams(params);
        }
    }

}
