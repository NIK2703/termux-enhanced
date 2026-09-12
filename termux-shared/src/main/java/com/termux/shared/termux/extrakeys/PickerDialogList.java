package com.termux.shared.termux.extrakeys;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import androidx.annotation.NonNull;

/**
 * Height bounding for the {@link ListView} of a picker dialog.
 *
 * <p>{@code AlertController} hands the dialog's list the whole remaining height of the dialog and
 * asks it to measure itself with an {@code AT_MOST} spec of that size. {@link ListView#onMeasure}
 * answers with the height of the rows that fit <b>plus the row that crossed the limit</b> — unlike
 * the framework's own containers, it does not clamp its own measurement to the spec it was given.
 * The overshooting row is therefore laid out past the bottom of the dialog. The list is already
 * showing every row it has, so there is nothing left to scroll and the row stays half drawn: the
 * gesture that should reveal it only stretches the list.
 *
 * <p>Bounding the list to the height the window can actually show removes the overshoot, which
 * turns that same gesture into a scroll that reaches the last row. The bound is applied before the
 * frame is drawn, so the dialog never appears at the wrong height, and it is a no-op when the list
 * already fits — a short list must stay short.
 */
final class PickerDialogList {

    private PickerDialogList() {}

    /**
     * Clamp {@code list} to the height its window can show, if it currently overflows it.
     *
     * <p>Must be called once the dialog is shown, so that the list is attached and its root view is
     * the dialog's decor.
     */
    static void boundHeightToWindow(@NonNull final ListView list) {
        list.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                final View decor = list.getRootView();
                final ViewGroup.LayoutParams params = list.getLayoutParams();

                // The area the window may occupy: the display minus the screen decorations above and
                // below it. A dialog is measured against this, so it is also the most the dialog can
                // ever show.
                final Rect visible = new Rect();
                decor.getWindowVisibleDisplayFrame(visible);

                // Everything the dialog draws besides the list — title, panel paddings, button row.
                // Taken from the laid-out dialog, so it is exact and stays valid as the list resizes.
                final int chrome = decor.getHeight() - list.getHeight();
                final int max = visible.height() - chrome;

                if (params == null || max <= 0 || list.getHeight() <= max) {
                    list.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }

                params.height = max;
                list.setLayoutParams(params);

                // Skip this frame: the dialog is re-laid out at the bounded height and only then
                // drawn, so the overflowing height is never seen.
                return false;
            }
        });
    }
}
