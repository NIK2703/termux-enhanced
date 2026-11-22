package com.termux.app.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FloatingShadowTest {

    @Test
    public void darkShadowOnLightSchemeAndLightShadowOnDarkScheme() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final int lightScheme = FloatingShadow.color(activity.getResources(), true,
                R.fraction.floating_shadow_alpha);
        final int darkScheme = FloatingShadow.color(activity.getResources(), false,
                R.fraction.floating_shadow_alpha);

        Assert.assertEquals(0x99, Color.alpha(lightScheme));
        Assert.assertEquals(0, Color.red(lightScheme));
        Assert.assertEquals(0, Color.green(lightScheme));
        Assert.assertEquals(0, Color.blue(lightScheme));

        Assert.assertEquals(0x99, Color.alpha(darkScheme));
        Assert.assertEquals(255, Color.red(darkScheme));
        Assert.assertEquals(255, Color.green(darkScheme));
        Assert.assertEquals(255, Color.blue(darkScheme));
    }

    @Test
    public void applyUsesTheSharedElevation() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final View view = new View(activity);
        final int elevation = FloatingShadow.elevationPx(activity.getResources());

        FloatingShadow.apply(view, elevation, 0x99123456);

        // Robolectric does not expose the outline shadow colours, so only the shared elevation
        // is asserted here; the colour pair is covered above.
        Assert.assertEquals(elevation, view.getElevation(), 0f);
    }
}
