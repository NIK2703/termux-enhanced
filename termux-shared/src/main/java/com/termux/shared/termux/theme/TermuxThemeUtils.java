package com.termux.shared.termux.theme;

import androidx.annotation.Nullable;

import com.termux.shared.theme.NightMode;

public class TermuxThemeUtils {

    /** Set name as app wide night mode value. */
    public static void setAppNightMode(@Nullable String name) {
        NightMode.setAppNightMode(name);
    }

}
