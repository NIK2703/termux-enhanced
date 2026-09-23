package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

/**
 * Centralised home for every user-facing dialog and toast previously living in
 * {@link TermuxActivity}. Moving them here keeps the activity lean and makes the
 * dialog logic reusable and unit-testable.
 */
public class TermuxDialogs {

    private final TermuxActivity mActivity;

    public TermuxDialogs(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /**
     * Ask the user to confirm killing the running process of a session.
     *
     * @param session the session whose process should be killed.
     * @param onKill run after the session process has been asked to finish.
     */
    public void showKillSessionDialog(TerminalSession session, @NonNull Runnable onKill) {
        if (session == null) return;

        final MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(mActivity);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
            onKill.run();
        });
        b.setNegativeButton(android.R.string.no, null);
        androidx.appcompat.app.AlertDialog dialog = b.create();
        dialog.show();
    }
}
