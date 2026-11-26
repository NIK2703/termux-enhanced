package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.IdentityHashMap;

/**
 * Direct copy of the tab strip for an overview flight.
 *
 * <p>The live strip is transparent while the overview is open, so a flight needs a second set
 * of tab views above the overlay. This container holds those views at their real strip
 * positions and clips them to the horizontal scroll viewport. Unlike a bitmap, a tab can be
 * removed when its own flying ghost takes over without re-drawing the whole strip.</p>
 */
final class SessionOverviewTabStripGhostView extends FrameLayout {

    private final Rect mClip = new Rect();
    private final IdentityHashMap<View, View> mTabs = new IdentityHashMap<>();

    SessionOverviewTabStripGhostView(@NonNull Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClipChildren(false);
    }

    /** Clip in this container's coordinates; an empty rect disables clipping. */
    void setClipRect(@Nullable Rect clip) {
        if (clip == null) {
            mClip.setEmpty();
        } else {
            mClip.set(clip);
        }
        invalidate();
    }

    /** Remember a static tab so a later flight can remove exactly that copy. */
    void addTab(@NonNull View sourceTab, @NonNull View ghost) {
        mTabs.put(sourceTab, ghost);
        addView(ghost);
    }

    /** Remove the static copy whose own flying ghost is about to be installed. */
    void removeTab(@NonNull View sourceTab) {
        final View ghost = mTabs.remove(sourceTab);
        if (ghost != null) removeView(ghost);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mClip.isEmpty()) {
            super.draw(canvas);
            return;
        }
        final int save = canvas.save();
        canvas.clipRect(mClip);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }
}
