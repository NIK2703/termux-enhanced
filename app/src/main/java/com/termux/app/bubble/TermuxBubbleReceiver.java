package com.termux.app.bubble;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.shared.termux.TermuxConstants;

/**
 * Handles the two bubble-related broadcasts.
 *
 * <p><b>Dismissal</b> ({@link TermuxBubbleManager#ACTION_BUBBLE_DISMISSED}) is fired by SystemUI when
 * the user throws the bubble away. The notification is not cancelled when the bubble activity is
 * destroyed, because destroying the activity is also what happens when the bubble is merely
 * <em>collapsed</em> — cancelling there would make the bubble icon vanish on every collapse.
 * Dismissal is the only event that means the user is done with it.
 *
 * <p><b>Opening</b> ({@link TermuxBubbleManager#ACTION_OPEN_BUBBLE}) comes from the "bubble" button on
 * the app's own notification. It is a broadcast rather than an activity intent because the button is
 * tapped from the shade, where the app is normally in the background: launching an activity from
 * there would drag the whole UI to the foreground just to post a notification, and would also fight
 * the automatic background/foreground behaviour. The label is carried in the intent because a
 * receiver has no session to ask.
 */
public class TermuxBubbleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        final String action = intent.getAction();
        if (action == null) return;

        if (TermuxBubbleManager.ACTION_BUBBLE_DISMISSED.equals(action)) {
            // The state changed outside this app, and the notification record may already be gone by
            // the time this arrives, so the "was it posted?" test inside cancel() cannot be trusted to
            // notice — say so explicitly, or the button would stay hidden on the service notification
            // after the user throws the bubble away.
            TermuxBubbleManager.cancel(context, true);
        } else if (TermuxBubbleManager.ACTION_OPEN_BUBBLE.equals(action)) {
            CharSequence title = intent.getCharSequenceExtra(TermuxBubbleManager.EXTRA_BUBBLE_TITLE);
            if (title == null || title.length() == 0) title = TermuxConstants.TERMUX_APP_NAME;
            TermuxBubbleManager.showBubble(context, title);
        }
    }
}
