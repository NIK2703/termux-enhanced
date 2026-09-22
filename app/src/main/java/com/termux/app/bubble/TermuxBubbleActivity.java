package com.termux.app.bubble;

import com.termux.app.TermuxActivity;

/**
 * The app's own window, hosted inside a system bubble.
 *
 * <p>This class deliberately adds <em>nothing</em>. It exists only so the bubble can be given its
 * own manifest entry, because a bubble needs two things {@link TermuxActivity} itself cannot have:
 *
 * <ul>
 *   <li>{@code android:allowEmbedded="true"} — SystemUI does not run the bubble's content in a
 *       window of its own, it embeds it into the bubble window through an {@code ActivityView}.
 *       Setting that on {@code TermuxActivity} would let any other process embed the app's main
 *       activity.</li>
 *   <li>A launch mode that permits a second instance. {@code TermuxActivity} is
 *       {@code launchMode="singleTask"}, so it can never be instantiated a second time inside a
 *       bubble task: the system would bring the existing full-screen instance forward instead, and
 *       the bubble would come up empty.</li>
 * </ul>
 *
 * <p>Everything the user sees — toolbar, session tabs, extra-keys panel, text input panel, the
 * session list drawer, every dialog, the colour schemes — is inherited from {@link TermuxActivity}
 * unchanged. That is the point: an earlier version of this feature drew its own cut-down terminal
 * plus two rows of keys, which looked like a different app rather than a floating copy of this one.
 *
 * <p>Two consequences worth knowing:
 *
 * <ul>
 *   <li>The bubble window and the full-screen window are two independent instances of the same UI.
 *       They share the sessions (one {@link com.termux.app.TermuxService}, one shell per session)
 *       but each has its own pager, so switching tabs in one does not move the other. An activity
 *       cannot be displayed in two windows at once, so "the same window" can only ever mean "a
 *       second instance of the same window".</li>
 *   <li>Because both instances hold a view of the same session, the pty window size follows whichever
 *       window was laid out last. That is bounded — it reflows once when focus moves between the
 *       windows — rather than a continuous fight, but it does mean the terminal can rewrap.</li>
 * </ul>
 *
 * <p>{@link #isBubbleWindow()} is the one thing this class does contribute: it tells the inherited
 * lifecycle code which of the two instances it is running in. Without it, the automatic
 * background/foreground behaviour would read the bubble's own resume as "the user came back to the
 * app" (and close the bubble the user just tapped) and its own backgrounding as "the user left the
 * app" (and open a second bubble).
 */
public class TermuxBubbleActivity extends TermuxActivity {

    @Override
    public boolean isBubbleWindow() {
        return true;
    }
}
