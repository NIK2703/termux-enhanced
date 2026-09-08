package com.termux.shared.termux.interact;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Selection;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.ViewGroup.LayoutParams;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.shared.R;

public final class TextInputDialogUtils {

    public interface TextSetListener {
        void onTextSet(String text);
    }

    public static void textInput(Activity activity, int titleText, String initialText,
                                 int positiveButtonText, final TextSetListener onPositive,
                                 int neutralButtonText, final TextSetListener onNeutral,
                                 int negativeButtonText, final TextSetListener onNegative,
                                 final DialogInterface.OnDismissListener onDismiss) {
        // Theme the dialog with ThemeOverlay.BaseDialog.DayNight instead of the raw
        // (scheme-wrapped) activity context: the activity theme's on-surface colour is a
        // runtime scheme placeholder that can resolve to white for a framework AlertDialog
        // title in light mode (white-on-white title). The overlay pins the title colours per
        // light/dark mode (BaseDialog.Title.Text.Light/Dark), matching every other dialog
        // in the app.
        Context dialogContext = new ContextThemeWrapper(activity, R.style.ThemeOverlay_BaseDialog_DayNight);

        final EditText input = new EditText(dialogContext);
        input.setSingleLine();
        if (initialText != null) {
            input.setText(initialText);
            Selection.setSelection(input.getText(), initialText.length());
        }

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        input.setImeActionLabel(activity.getResources().getString(positiveButtonText), KeyEvent.KEYCODE_ENTER);
        input.setOnEditorActionListener((v, actionId, event) -> {
            onPositive.onTextSet(input.getText().toString());
            dialogHolder[0].dismiss();
            return true;
        });

        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, activity.getResources().getDisplayMetrics());
        // https://www.google.com/design/spec/components/dialogs.html#dialogs-specs
        int paddingTopAndSides = Math.round(16 * dipInPixels);
        int paddingBottom = Math.round(24 * dipInPixels);

        LinearLayout layout = new LinearLayout(dialogContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        layout.setPadding(paddingTopAndSides, paddingTopAndSides, paddingTopAndSides, paddingBottom);
        layout.addView(input);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(dialogContext)
            .setTitle(titleText).setView(layout)
            .setPositiveButton(positiveButtonText, (d, whichButton) -> onPositive.onTextSet(input.getText().toString()));

        if (onNeutral != null) {
            builder.setNeutralButton(neutralButtonText, (dialog, which) -> onNeutral.onTextSet(input.getText().toString()));
        }

        if (onNegative == null) {
            builder.setNegativeButton(android.R.string.cancel, null);
        } else {
            builder.setNegativeButton(negativeButtonText, (dialog, which) -> onNegative.onTextSet(input.getText().toString()));
        }

        if (onDismiss != null)
            builder.setOnDismissListener(onDismiss);

        dialogHolder[0] = builder.create();
        dialogHolder[0].setCanceledOnTouchOutside(false);
        dialogHolder[0].show();
    }

}
