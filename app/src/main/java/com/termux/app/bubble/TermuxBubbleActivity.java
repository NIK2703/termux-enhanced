package com.termux.app.bubble;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;

/**
 * The floating terminal window a bubble hosts. A separate class only because no one but SystemUI
 * may host a bubble, so this window must not be one the app can open itself; otherwise it is a
 * plain {@link TermuxActivity}, session routing included.
 */
public class TermuxBubbleActivity extends TermuxActivity {

    /** This window is the floating one, which the base class uses to tell the two apart. */
    @Override
    public boolean isBubbleWindow() {
        return true;
    }
}
