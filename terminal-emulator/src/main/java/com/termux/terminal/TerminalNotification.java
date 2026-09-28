package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A desktop notification requested by a program running in the terminal, as defined by the kitty
 * OSC 99 desktop notification protocol.
 *
 * <p>Produced by {@link Osc99NotificationParser} once a notification is complete. One with no title
 * and no body is never produced: the protocol says to ignore it.
 *
 * @see <a href="https://sw.kovidgoyal.net/kitty/desktop-notifications/">kitty desktop notifications</a>
 */
public final class TerminalNotification {

    /** {@code o} occasion: show only while the window has keyboard focus. */
    public static final int OCCASION_FOCUSED = 1;
    /** {@code o} occasion: show only while the window does <em>not</em> have keyboard focus. */
    public static final int OCCASION_UNFOCUSED = 2;
    /** {@code o} occasion: show only while the window is unfocused <em>and</em> not visible. */
    public static final int OCCASION_INVISIBLE = 4;
    /** {@code o} occasion: always show. This is the protocol default. */
    public static final int OCCASION_ALWAYS = 8;

    /** Identifier the protocol mandates when the program did not supply an {@code i} key. */
    public static final String DEFAULT_ID = "0";

    private final String mId;
    private final CharSequence mTitle;
    private final CharSequence mBody;
    private final int mOccasions;
    private final boolean mSilent;
    private final boolean mReportOnActivate;
    private final boolean mFocusOnActivate;

    TerminalNotification(@NonNull String id, @Nullable CharSequence title, @Nullable CharSequence body,
                         int occasions, boolean silent, boolean reportOnActivate, boolean focusOnActivate) {
        mId = id;
        mTitle = title;
        mBody = body;
        mOccasions = occasions;
        mSilent = silent;
        mReportOnActivate = reportOnActivate;
        mFocusOnActivate = focusOnActivate;
    }

    /**
     * The {@code i} key, tying the chunks of one notification together. Only meaningful within the
     * terminal that produced it.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * The escape code to write back when the user activates a notification that asked for the
     * {@code report} action. The protocol fixes the form to {@code OSC 99 ; i=id ; ST}.
     *
     * @param id the {@code i} value the program supplied.
     */
    @NonNull
    public static String buildActivationReport(@NonNull String id) {
        return "\033]99;i=" + id + ";\033\\";
    }

    /** The {@code p=title} chunks, or {@code null} if the program sent none. */
    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * The {@code p=body} chunks, or {@code null} if the program sent none. Deliberately not filled in
     * from the title: the protocol mandates only the other direction, and copying it would make
     * "title only" indistinguishable from "both", printing one line twice.
     */
    @Nullable
    public CharSequence getBody() {
        return mBody;
    }

    /** Bitmask of the {@code OCCASION_*} values requested via the {@code o} key. */
    public int getOccasions() {
        return mOccasions;
    }

    /** {@code true} when the {@code s} key asked for {@code silent}, i.e. no sound. */
    public boolean isSilent() {
        return mSilent;
    }

    /** {@code a=report}: send an escape code back to the program when the user activates it. */
    public boolean isReportOnActivate() {
        return mReportOnActivate;
    }

    /** {@code a=focus} (the protocol default): bring the window to the front on activation. */
    public boolean isFocusOnActivate() {
        return mFocusOnActivate;
    }
}
