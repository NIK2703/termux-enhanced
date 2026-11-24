package com.termux.shared;

/** Short string values shared by diagnostics, logs and report formatting. */
public final class CommonValues {

    /** Text used instead of a null object in null-safe {@code toString()} helpers. */
    public static final String NULL_STRING = "null";

    /** Placeholder passed to the log/report entry helpers when a value is absent. */
    public static final String EMPTY_VALUE_PLACEHOLDER = "-";

    private CommonValues() {}
}
