package com.termux.app;

import android.content.pm.ActivityInfo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ScreenOrientationResolverTest {

    private static final String SENSOR = "sensor";
    private static final String PORTRAIT_FOLLOW_SENSOR = "portrait_follow_sensor";
    private static final String LANDSCAPE_FOLLOW_SENSOR = "landscape_follow_sensor";
    private static final String PORTRAIT = "portrait";
    private static final String LANDSCAPE = "landscape";

    @Test
    public void systemRotationLockOverridesEveryMode() {
        final String[] modes = {
                SENSOR, PORTRAIT_FOLLOW_SENSOR, LANDSCAPE_FOLLOW_SENSOR, PORTRAIT, LANDSCAPE,
                "unknown", null
        };

        for (String mode : modes) {
            assertEquals("phone mode " + mode,
                    ActivityInfo.SCREEN_ORIENTATION_USER,
                    TermuxActivityUtils.resolveScreenOrientation(mode, false, false));
            assertEquals("tablet mode " + mode,
                    ActivityInfo.SCREEN_ORIENTATION_USER,
                    TermuxActivityUtils.resolveScreenOrientation(mode, true, false));
        }
    }

    @Test
    public void followSensorModesUseTheUserFamily() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,
                TermuxActivityUtils.resolveScreenOrientation(SENSOR, false, true));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                TermuxActivityUtils.resolveScreenOrientation(PORTRAIT_FOLLOW_SENSOR, false, true));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
                TermuxActivityUtils.resolveScreenOrientation(LANDSCAPE_FOLLOW_SENSOR, false, true));
    }

    @Test
    public void followSensorModesNeverRequestSensorDirectly() {
        final int[] resolved = {
                TermuxActivityUtils.resolveScreenOrientation(SENSOR, false, true),
                TermuxActivityUtils.resolveScreenOrientation(PORTRAIT_FOLLOW_SENSOR, false, true),
                TermuxActivityUtils.resolveScreenOrientation(LANDSCAPE_FOLLOW_SENSOR, false, true)
        };
        final int[] ignoredByRotationLock = {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        };

        for (int orientation : resolved) {
            for (int sensorOrientation : ignoredByRotationLock) {
                assertNotEquals("rotation lock would be ignored",
                        sensorOrientation, orientation);
            }
        }
    }

    @Test
    public void explicitModesApplyWhileRotationIsFree() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                TermuxActivityUtils.resolveScreenOrientation(PORTRAIT, false, true));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                TermuxActivityUtils.resolveScreenOrientation(LANDSCAPE, false, true));
    }

    @Test
    public void defaultModeDependsOnScreenSize() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                TermuxActivityUtils.resolveScreenOrientation(null, false, true));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,
                TermuxActivityUtils.resolveScreenOrientation(null, true, true));
    }

    @Test
    public void unknownValueFallsBackToUnspecifiedWhenRotationIsFree() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                TermuxActivityUtils.resolveScreenOrientation("unknown", false, true));
    }
}
