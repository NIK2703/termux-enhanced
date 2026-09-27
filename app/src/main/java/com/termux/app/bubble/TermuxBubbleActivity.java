package com.termux.app.bubble;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;

/**
 * The floating terminal window a bubble hosts.
 *
 * <p>Exists as a distinct class for one reason: a bubble can only ever be hosted by SystemUI, so this
 * window must not be something the app opens on its own. It is a {@link TermuxActivity} in every other
 * respect, and shares its session routing with it — the handle a notification names is read and acted
 * on by the base class, so a bubble and a tap on the notification land on the same session by the same
 * code. See {@code TermuxActivity#applyRequestedSession()}.
 */
public class TermuxBubbleActivity extends TermuxActivity {

    /** This window is the floating one, which the base class uses to tell the two apart. */
    @Override
    public boolean isBubbleWindow() {
        return true;
    }
}
