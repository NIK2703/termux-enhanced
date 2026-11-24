package com.termux.app.terminal;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.View;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** The card and transition image rule: fill width, anchor top, clip the bottom. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TopCropImageViewTest {

    @Test
    public void fillsTheViewWidthWithoutChangingTheImageAspect() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TopCropImageView view = new TopCropImageView(activity);
        view.setImageBitmap(Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888));
        layout(view, 100, 60);

        Matrix matrix = view.getImageMatrix();
        float[] values = new float[9];
        matrix.getValues(values);
        Assert.assertEquals(2.5f, values[Matrix.MSCALE_X], 0.001f);
        Assert.assertEquals(2.5f, values[Matrix.MSCALE_Y], 0.001f);
        Assert.assertEquals(0f, values[Matrix.MTRANS_X], 0.001f);
        Assert.assertEquals(0f, values[Matrix.MTRANS_Y], 0.001f);
    }

    @Test
    public void aShorterImageStaysAtTheTopInsteadOfBeingCentred() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TopCropImageView view = new TopCropImageView(activity);
        view.setImageBitmap(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888));
        layout(view, 100, 200);

        Matrix matrix = view.getImageMatrix();
        float[] values = new float[9];
        matrix.getValues(values);
        Assert.assertEquals(2.5f, values[Matrix.MSCALE_X], 0.001f);
        Assert.assertEquals(2.5f, values[Matrix.MSCALE_Y], 0.001f);
        Assert.assertEquals(0f, values[Matrix.MTRANS_Y], 0.001f);
    }

    @Test
    public void aTallerImageIsClippedInsteadOfScaledDown() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TopCropImageView view = new TopCropImageView(activity);
        view.setImageBitmap(Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888));
        layout(view, 100, 50);

        Matrix matrix = view.getImageMatrix();
        float[] values = new float[9];
        matrix.getValues(values);
        // Width-fill gives a 200px image inside a 50px view; the matrix must not shrink it to
        // 50px just to fit. The extra height is clipped by the view's own bounds.
        Assert.assertEquals(2.5f, values[Matrix.MSCALE_Y], 0.001f);
        Assert.assertEquals(0f, values[Matrix.MTRANS_Y], 0.001f);
        Assert.assertEquals(new android.graphics.Rect(0, 0, 100, 50), view.getClipBounds());
    }

    @Test
    public void sizeChangesRecomputeTheScale() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TopCropImageView view = new TopCropImageView(activity);
        view.setImageBitmap(Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888));
        layout(view, 100, 50);
        layout(view, 20, 50);

        float[] values = new float[9];
        view.getImageMatrix().getValues(values);
        Assert.assertEquals(0.5f, values[Matrix.MSCALE_X], 0.001f);
        Assert.assertEquals(0.5f, values[Matrix.MSCALE_Y], 0.001f);
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
}
