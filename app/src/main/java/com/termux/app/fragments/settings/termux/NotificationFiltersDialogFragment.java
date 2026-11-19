package com.termux.app.fragments.settings.termux;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.notification.NotificationPrefixFilter;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the prefix rules that suppress terminal notifications and edits one rule. Adding,
 * changing or deleting a rule saves it immediately.
 */
@Keep
public class NotificationFiltersDialogFragment extends DialogFragment {

    public static final String TAG = "notification_filters";

    private final List<NotificationPrefixFilter> mFilters = new ArrayList<>();
    private TermuxAppSharedPreferences mPrefs;
    private BaseAdapter mAdapter;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        mPrefs = TermuxAppSharedPreferences.build(context);
        if (mPrefs != null)
            mFilters.addAll(NotificationPrefixFilter.parse(
                mPrefs.getNotificationPrefixFilters(false)));

        mAdapter = new BaseAdapter() {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public int getCount() {
                // One row for the empty state.
                return mFilters.isEmpty() ? 1 : mFilters.size();
            }

            @Override
            public Object getItem(int position) {
                return mFilters.isEmpty() ? null : mFilters.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean isEnabled(int position) {
                return !mFilters.isEmpty();
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_notification_filter, parent, false);
                TextView modeText = row.findViewById(R.id.notification_filter_mode);
                TextView prefixText = row.findViewById(R.id.notification_filter_prefix);
                ImageButton delete = row.findViewById(R.id.notification_filter_delete);

                if (mFilters.isEmpty()) {
                    modeText.setVisibility(View.GONE);
                    prefixText.setText(R.string.termux_notification_filters_empty);
                    prefixText.setGravity(Gravity.CENTER);
                    delete.setVisibility(View.GONE);
                    delete.setOnClickListener(null);
                    return row;
                }

                NotificationPrefixFilter filter = mFilters.get(position);
                int modeRes = filter.getMode() == NotificationPrefixFilter.Mode.TITLE
                    ? R.string.termux_notification_filter_mode_title
                    : R.string.termux_notification_filter_mode_body;
                modeText.setVisibility(View.VISIBLE);
                modeText.setText(modeRes);
                prefixText.setText(filter.getPrefix());
                prefixText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                delete.setVisibility(View.VISIBLE);
                delete.setOnClickListener(v -> deleteFilter(filter));
                return row;
            }
        };

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context,
            R.style.ThemeOverlay_TermuxActivity_Dialog)
            .setTitle(R.string.termux_notification_filters_dialog_title)
            .setAdapter(mAdapter, null)
            .setPositiveButton(R.string.termux_notification_filter_add, null)
            .setNegativeButton(R.string.termux_notification_filters_close, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            // The add button opens another dialog, so it must not dismiss this one first.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> openFilterDialog(-1));
            // AlertDialog would dismiss this list before the editor opens; keep it visible.
            dialog.getListView().setOnItemClickListener(
                (parent, row, position, id) -> openFilterDialog(position));
            // Rows carry their own spacing; the dialog's default separators would double it.
            dialog.getListView().setDivider(null);
            dialog.getListView().setDividerHeight(0);
        });
        return dialog;
    }

    private void saveFilters() {
        if (mPrefs != null)
            mPrefs.setNotificationPrefixFilters(NotificationPrefixFilter.serialize(mFilters));
    }

    private void deleteFilter(@NonNull NotificationPrefixFilter filter) {
        mFilters.remove(filter);
        saveFilters();
        mAdapter.notifyDataSetChanged();
    }

    /** {@code position < 0} adds a rule; otherwise edits the one at that position. */
    private void openFilterDialog(int position) {
        boolean editing = position >= 0 && position < mFilters.size();
        NotificationPrefixFilter current = editing ? mFilters.get(position) : null;

        Context context = requireContext();
        View view = getLayoutInflater().inflate(R.layout.dialog_notification_filter, null);
        RadioButton titleMode = view.findViewById(R.id.notification_filter_mode_title);
        RadioButton bodyMode = view.findViewById(R.id.notification_filter_mode_body);
        EditText prefixInput = view.findViewById(R.id.notification_filter_prefix);

        if (current != null) {
            if (current.getMode() == NotificationPrefixFilter.Mode.BODY) bodyMode.setChecked(true);
            else titleMode.setChecked(true);
            prefixInput.setText(current.getPrefix());
            prefixInput.setSelection(prefixInput.length());
        } else {
            titleMode.setChecked(true);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(context,
            R.style.ThemeOverlay_TermuxActivity_Dialog)
            .setTitle(R.string.termux_notification_filter_dialog_title)
            .setView(view)
            .setPositiveButton(editing ? R.string.termux_notification_filter_save
                : R.string.termux_notification_filter_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String prefix = prefixInput.getText().toString().trim();
                if (prefix.isEmpty()) {
                    prefixInput.setError(getString(R.string.termux_notification_filter_prefix_required));
                    return;
                }

                NotificationPrefixFilter filter = new NotificationPrefixFilter(
                    bodyMode.isChecked() ? NotificationPrefixFilter.Mode.BODY
                        : NotificationPrefixFilter.Mode.TITLE,
                    prefix);
                int existing = mFilters.indexOf(filter);
                if (existing >= 0 && existing != position) {
                    prefixInput.setError(getString(R.string.termux_notification_filter_duplicate));
                    return;
                }

                if (editing) mFilters.set(position, filter);
                else mFilters.add(filter);
                saveFilters();
                mAdapter.notifyDataSetChanged();
                dialog.dismiss();
            });

            prefixInput.requestFocus();
            dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(prefixInput, InputMethodManager.SHOW_IMPLICIT);
        });

        dialog.show();
    }
}
