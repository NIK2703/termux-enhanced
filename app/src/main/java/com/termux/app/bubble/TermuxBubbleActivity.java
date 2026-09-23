package com.termux.app.bubble;

import com.termux.app.TermuxActivity;

/**
 * The app's own window, hosted inside a system bubble. Deliberately adds nothing: it exists only
 * so the bubble can get its own manifest entry, because a bubble needs two things
 * {@link TermuxActivity} itself cannot have:
 *
 * <ul>
 *   <li>{@code android:allowEmbedded="true"} — SystemUI embeds the bubble content via an
 *       {@code ActivityView}; setting that on {@code TermuxActivity} would let any process embed
 *       the main activity.</li>
 *   <li>A launch mode permitting a second instance. {@code TermuxActivity} is
 *       {@code singleTask}, so the system would bring the full-screen instance forward instead
 *       and the bubble would come up empty.</li>
 * </ul>
 *
 * <p>Everything visible (toolbar, tabs, keys, drawer, dialogs, colour schemes) is inherited
 * from {@link TermuxActivity} unchanged — an earlier cut-down version looked like a different
 * app rather than a floating copy of this one.
 *
 * <p>Two consequences: the bubble and full-screen windows are two independent instances of the
 * same UI (sessions are shared, pagers are not, so switching tabs in one does not move the
 * other; the pty size follows whichever window was laid out last, so the terminal can rewrap
 * once when focus moves). And {@link #isBubbleWindow()} tells the inherited lifecycle code which
 * instance it is running in — without it, the bubble's own resume would read as "user came
 * back" (closing the bubble just tapped) and its backgrounding as "user left" (opening a
 * second bubble).
 */
public class TermuxBubbleActivity extends TermuxActivity {

    @Override
    public boolean isBubbleWindow() {
        return true;
    }
}
