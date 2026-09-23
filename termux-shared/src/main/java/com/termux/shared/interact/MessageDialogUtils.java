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
     * @param context dialog context; must be an {@link Activity} context or exceptions are thrown.
     * @param titleText dialog title.
     * @param messageText dialog message.
     * @param onDismiss listener run when the dialog is dismissed.
     */
    public static void showMessage(Context context, String titleText, String messageText, final DialogInterface.OnDismissListener onDismiss) {
        showMessage(context, titleText, messageText, null, null, null, null, onDismiss);
    }

    /**
     * Show a message in a dialog
     *
     * @param context dialog context; must be an {@link Activity} context or exceptions are thrown.
     * @param titleText dialog title.
     * @param messageText dialog message.
     * @param positiveText positive button text.
     * @param onPositiveButton listener run when the positive button is pressed.
     * @param negativeText negative button text; {@code null} hides the negative button.
     * @param onNegativeButton listener run when the negative button is pressed.
     * @param onDismiss listener run when the dialog is dismissed.
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
