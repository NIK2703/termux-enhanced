package com.termux.shared.interact;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.view.ContextThemeWrapper;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.shared.R;



public class MessageDialogUtils {

    /**
     * Show a message in a dialog
     *
     * @param context The {@link Context} to use to start the dialog. An {@link Activity} {@link Context}
     *                must be passed, otherwise exceptions will be thrown.
     * @param titleText The title text of the dialog.
     * @param messageText The message text of the dialog.
     * @param onDismiss The {@link DialogInterface.OnDismissListener} to run when dialog is dismissed.
     */
    public static void showMessage(Context context, String titleText, String messageText, final DialogInterface.OnDismissListener onDismiss) {
        showMessage(context, titleText, messageText, null, null, null, null, onDismiss);
    }

    /**
     * Show a message in a dialog
     *
     * @param context The {@link Context} to use to start the dialog. An {@link Activity} {@link Context}
     *                must be passed, otherwise exceptions will be thrown.
     * @param titleText The title text of the dialog.
     * @param messageText The message text of the dialog.
     * @param positiveText The positive button text of the dialog.
     * @param onPositiveButton The {@link DialogInterface.OnClickListener} to run when positive button
     *                         is pressed.
     * @param negativeText The negative button text of the dialog. If this is {@code null}, then
     *                         negative button will not be shown.
     * @param onNegativeButton The {@link DialogInterface.OnClickListener} to run when negative button
     *                         is pressed.
     * @param onDismiss The {@link DialogInterface.OnDismissListener} to run when dialog is dismissed.
     */
    public static void showMessage(Context context, String titleText, String messageText,
                                    String positiveText,
                                    final DialogInterface.OnClickListener onPositiveButton,
                                    String negativeText,
                                    final DialogInterface.OnClickListener onNegativeButton,
                                    final DialogInterface.OnDismissListener onDismiss) {

        // Theme the dialog with ThemeOverlay.BaseDialog.DayNight instead of the raw context: the
        // scheme-wrapped activity theme's on-surface colour is a runtime scheme placeholder that
        // can resolve to white for a dialog title/buttons in light mode (white-on-white, which is
        // exactly what the "Report issue" confirmation dialog used to do). The overlay pins the
        // surface + title/message colours per light/dark mode, matching every other dialog in the
        // app (see TextInputDialogUtils for the same fix).
        Context dialogContext = new ContextThemeWrapper(context, R.style.ThemeOverlay_BaseDialog_DayNight);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(dialogContext);
        builder.setTitle(titleText);
        builder.setMessage(messageText);

        if (positiveText == null)
            positiveText = context.getString(android.R.string.ok);
        builder.setPositiveButton(positiveText, onPositiveButton);

        if (negativeText != null)
            builder.setNegativeButton(negativeText, onNegativeButton);

        if (onDismiss != null)
            builder.setOnDismissListener(onDismiss);

        builder.show();
    }

    public static void exitAppWithErrorMessage(Context context, String titleText, String messageText) {
        showMessage(context, titleText, messageText, dialog -> System.exit(0));
    }

}
