package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;

import java.util.HashMap;

/** Special buttons (CTRL, ALT, SHIFT, FN) for {@link ExtraKeysView}. */
public class SpecialButton {

    private static final HashMap<String, SpecialButton> map = new HashMap<>();

    public static final SpecialButton CTRL = new SpecialButton("CTRL");
    public static final SpecialButton ALT = new SpecialButton("ALT");
    public static final SpecialButton SHIFT = new SpecialButton("SHIFT");
    public static final SpecialButton FN = new SpecialButton("FN");

    private final String key;

    /**
     * Registers {@code key} in {@link #map} so the button can be retrieved via
     * {@link #valueOf(String)}.
     */
    public SpecialButton(@NonNull final String key) {
        this.key = key;
        map.put(key, this);
    }

    /** Get {@link #key} for this {@link SpecialButton}. */
    public String getKey() {
        return key;
    }

    /** Get the {@link SpecialButton} registered for {@code key}, or {@code null} if none. */
    public static SpecialButton valueOf(String key) {
        return map.get(key);
    }

    @NonNull
    @Override
    public String toString() {
        return key;
    }

}
