package com.termux.app.terminal;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxActivityUtils;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.FontUtils;
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
     * Ask the user to confirm wiping the message history. In per-directory mode the
     * dialog has three buttons: OK (current directory only), All (all directories),
     * Cancel. In global mode it stays as OK + Cancel.
     *
     * @param perDirectoryEnabled whether per-directory history mode is active.
     * @param onConfirmCurrent run when the user confirms clearing the current context.
     * @param onConfirmAll run when the user confirms clearing all directories (per-directory mode only).
     */
    public void showConfirmClearAllHistory(boolean perDirectoryEnabled,
                                            @NonNull Runnable onConfirmCurrent,
                                            @NonNull Runnable onConfirmAll) {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mActivity)
                .setTitle(mActivity.getString(R.string.input_history_clear_question))
                .setNegativeButton(android.R.string.cancel, null);

        if (perDirectoryEnabled) {
            builder.setMessage(mActivity.getString(R.string.input_history_clear_current_only_question))
                    .setPositiveButton(mActivity.getString(R.string.input_history_clear_ok), (d, w) -> onConfirmCurrent.run())
                    .setNeutralButton(mActivity.getString(R.string.input_history_clear_all_button), (d, w) -> onConfirmAll.run());
        } else {
            builder.setMessage(mActivity.getString(R.string.input_history_clear_all_question))
                    .setPositiveButton(android.R.string.ok, (d, w) -> onConfirmCurrent.run());
        }

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Convenience overload for the simple (single-confirm) case.
     */
    public void showConfirmClearAllHistory(@NonNull Runnable onConfirm) {
        showConfirmClearAllHistory(false, onConfirm, onConfirm);
    }

    /**
     * "Clear message history..." item: ask for confirmation, then wipe all history.
     *
     * @param perDirectoryEnabled whether per-directory history mode is active.
     * @param onConfirm run when the user confirms clearing the history.
     */
    public void showConfirmClearHistory(boolean perDirectoryEnabled, @NonNull Runnable onConfirm) {
        final MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(mActivity);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setTitle(mActivity.getString(R.string.input_history_clear_dialog_title));
        String msg = perDirectoryEnabled
                ? mActivity.getString(R.string.input_history_clear_confirm_current)
                : mActivity.getString(R.string.input_history_clear_confirm_all);
        b.setMessage(msg);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            onConfirm.run();
        });
        b.setNegativeButton(android.R.string.no, null);
        androidx.appcompat.app.AlertDialog dialog = b.create();
        dialog.show();
    }

    /**
     * Convenience overload for {@link #showConfirmClearHistory(boolean, Runnable)}.
     */
    public void showConfirmClearHistory(@NonNull Runnable onConfirm) {
        showConfirmClearHistory(false, onConfirm);
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

    /**
     * Show the color-scheme picker for the currently active theme, applying the choice
     * live to that theme.
     */
    public void showStylingDialog() {
        final boolean isNight = TermuxActivity.isNightModeActive();
        ColorSchemeUtils.showColorSchemeDialog(mActivity, isNight, mActivity.getString(R.string.color_scheme_dialog_title),
                mActivity.getString(R.string.error_styling_not_installed),
                () -> TermuxActivityUtils.updateTermuxActivityStyling(mActivity, false));
    }

    /**
     * Show the Termux:Style font picker dialog. Lists every font shipped by the installed
     * Termux:Style plugin and applies the selected one live without an activity restart.
     */
    public void showFontPicker() {
        FontUtils.showFontDialog(mActivity, mActivity.getString(R.string.error_styling_not_installed),
                () -> {
                    TermuxActivityUtils.updateTermuxActivityStyling(mActivity, false);
                    mActivity.showToast(mActivity.getResources().getString(R.string.msg_terminal_font_applied), true);
                });
    }

    /**
     * Show a toast and dismiss the last one if still visible. Delegates to the activity so
     * the single {@code mLastToast} instance there is reused.
     *
     * @param text the message to show.
     * @param longDuration whether to use {@link Toast#LENGTH_LONG}.
     */
    public void showToast(String text, boolean longDuration) {
        mActivity.showToast(text, longDuration);
    }
}
