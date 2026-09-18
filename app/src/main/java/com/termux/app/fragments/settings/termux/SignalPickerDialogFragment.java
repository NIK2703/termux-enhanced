package com.termux.app.fragments.settings.termux;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.view.inputmethod.InputMethodManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.termux.shared.termux.extrakeys.BindingTokenizer;
import com.termux.shared.termux.extrakeys.KeyCombination;

@Keep
public class SignalPickerDialogFragment extends DialogFragment {

    public static final String REQUEST_KEY = "signal_picker";

    /** Picker for one binding of an extra-keys button. */
    public static final String MODE_EXTRA_KEY = "extra_key";
    /** Picker for a session shortcut combination; the delay entry and the action-only buttons
     *  (KEYBOARD/PASTE/SCROLL/AUTOFILL_*) are hidden, since there is no key to press for any of
     *  them — a session shortcut has to be a modifier+key combination. */
    public static final String MODE_SESSION_SHORTCUT = "session_shortcut";

    private static final String ARG_ROW = "row";
    private static final String ARG_COL = "col";
    private static final String ARG_TARGET = "target";
    private static final String ARG_SIGNALS = "signals";
    private static final String ARG_MODE = "mode";
    private static final String ARG_TITLE = "title";
    private static final String ARG_REQUEST_ID = "request_id";

    public static final String RESULT_ROW = "row";
    public static final String RESULT_COL = "col";
    public static final String RESULT_TARGET = "target";
    public static final String RESULT_SIGNALS = "signals";
    /** Echoes back {@link #ARG_REQUEST_ID} so a caller editing several bindings can tell them apart. */
    public static final String RESULT_REQUEST_ID = "request_id";

    private static final Set<String> SESSION_SHORTCUT_HIDDEN_VALUES = new HashSet<>(Arrays.asList(
        "__DELAY_PICKER__", "KEYBOARD", "PASTE", "SCROLL",
        "AUTOFILL_USERNAME", "AUTOFILL_PASSWORD"));

    public enum BindTarget { TAP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT }

    private static final Set<String> MODIFIERS = new HashSet<>(Arrays.asList("CTRL", "ALT", "SHIFT", "FN"));

    private ArrayList<String> mSelected;
    private ViewGroup mChipsContainer;
    private AlertDialog mDialog;
    private int mEditingDelayIndex = -1;
    /** Index of a custom-text token being edited, or -1 when adding a new one. */
    private int mEditingCustomIndex = -1;
    /** Known grid signal values; tokens not in this set are user custom text (editable). */
    private Set<String> mSignalValues;

    /** Whether this picker is in session-shortcut mode (drives the phased enabled state below). */
    private boolean mSessionShortcutMode;
    /** Adapter backing the grid; kept so the enabled/dim state can be refreshed after each change. */
    private ArrayAdapter<String> mAdapter;

    public static SignalPickerDialogFragment newInstance(int row, int col, BindTarget target, ArrayList<String> currentSignals) {
        Bundle args = new Bundle();
        args.putInt(ARG_ROW, row);
        args.putInt(ARG_COL, col);
        args.putString(ARG_TARGET, target.name());
        args.putString(ARG_MODE, MODE_EXTRA_KEY);
        args.putStringArrayList(ARG_SIGNALS, currentSignals != null ? currentSignals : new ArrayList<>());
        SignalPickerDialogFragment f = new SignalPickerDialogFragment();
        f.setArguments(args);
        return f;
    }

    /**
     * Picker for a session shortcut. {@code requestId} is the settings key being edited, so the
     * caller knows which row to update, and {@code title} is that row's title — a combination is
     * not tied to a swipe direction, so the extra-keys titles would not describe it.
     */
    public static SignalPickerDialogFragment newInstanceForSessionShortcut(@NonNull String requestId,
                                                                          @NonNull String title,
                                                                          @Nullable List<String> currentTokens) {
        Bundle args = new Bundle();
        args.putInt(ARG_ROW, -1);
        args.putInt(ARG_COL, -1);
        args.putString(ARG_TARGET, BindTarget.TAP.name());
        args.putString(ARG_MODE, MODE_SESSION_SHORTCUT);
        args.putString(ARG_TITLE, title);
        args.putString(ARG_REQUEST_ID, requestId);
        args.putStringArrayList(ARG_SIGNALS,
            currentTokens != null ? new ArrayList<>(currentTokens) : new ArrayList<>());
        SignalPickerDialogFragment f = new SignalPickerDialogFragment();
        f.setArguments(args);
        return f;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        int row = args.getInt(ARG_ROW);
        int col = args.getInt(ARG_COL);
        String targetStr = args.getString(ARG_TARGET, BindTarget.TAP.name());
        BindTarget target = BindTarget.valueOf(targetStr);
        boolean sessionShortcutMode = MODE_SESSION_SHORTCUT.equals(args.getString(ARG_MODE, MODE_EXTRA_KEY));
        mSessionShortcutMode = sessionShortcutMode;

        mSelected = new ArrayList<>(args.getStringArrayList(ARG_SIGNALS));
        mSelected.removeIf(String::isEmpty);

        Context context = requireContext();
        String[] allEntries = getResources().getStringArray(R.array.extra_keys_editor_signal_entries);
        String[] allValues = getResources().getStringArray(R.array.extra_keys_editor_signal_values);
        mSignalValues = new HashSet<>(Arrays.asList(allValues));
        final String[] entries;
        final String[] values;
        if (sessionShortcutMode) {
            List<String> keptEntries = new ArrayList<>();
            List<String> keptValues = new ArrayList<>();
            for (int i = 0; i < allValues.length && i < allEntries.length; i++) {
                if (SESSION_SHORTCUT_HIDDEN_VALUES.contains(allValues[i])) continue;
                keptValues.add(allValues[i]);
                keptEntries.add(allEntries[i]);
            }
            entries = keptEntries.toArray(new String[0]);
            values = keptValues.toArray(new String[0]);
        } else {
            entries = allEntries;
            values = allValues;
        }

        View view = getLayoutInflater().inflate(R.layout.extra_keys_signal_grid, null);
        mChipsContainer = view.findViewById(R.id.chips_container);
        GridView grid = view.findViewById(R.id.signal_grid);

        String title;
        if (sessionShortcutMode) {
            title = args.getString(ARG_TITLE, getString(R.string.shortcuts_category_title));
        } else switch (target) {
            case TAP:
                title = getString(R.string.extra_keys_editor_signal_dialog_title) + getString(R.string.extra_keys_editor_tap_suffix);
                break;
            case SWIPE_UP:
                title = getString(R.string.extra_keys_editor_signal_dialog_title) + getString(R.string.extra_keys_editor_swipe_up_suffix);
                break;
            case SWIPE_DOWN:
                title = getString(R.string.extra_keys_editor_signal_dialog_title) + getString(R.string.extra_keys_editor_swipe_down_suffix);
                break;
            case SWIPE_LEFT:
                title = getString(R.string.extra_keys_editor_signal_dialog_title) + getString(R.string.extra_keys_editor_swipe_left_suffix);
                break;
            case SWIPE_RIGHT:
                title = getString(R.string.extra_keys_editor_signal_dialog_title) + getString(R.string.extra_keys_editor_swipe_right_suffix);
                break;
            default:
                title = getString(R.string.extra_keys_editor_signal_dialog_title);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_TermuxActivity_Dialog);
        builder.setTitle(title);
        builder.setView(view);
        // The click handler is attached in onShow below: a session shortcut must be able to refuse
        // the confirmation and keep the dialog open, which a positive-button listener cannot do.
        builder.setPositiveButton(getString(R.string.extra_keys_editor_done), null);
        builder.setNegativeButton(android.R.string.cancel, null);

        mDialog = builder.create();
        mDialog.setCanceledOnTouchOutside(false);

        mDialog.setOnShowListener(dialog -> mDialog.getButton(DialogInterface.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                if (sessionShortcutMode && !validateSessionShortcut()) return;
                Bundle result = new Bundle();
                result.putInt(RESULT_ROW, row);
                result.putInt(RESULT_COL, col);
                result.putString(RESULT_TARGET, target.name());
                result.putString(RESULT_REQUEST_ID, args.getString(ARG_REQUEST_ID, ""));
                result.putStringArrayList(RESULT_SIGNALS, mSelected);
                getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
                dismiss();
            }));

        mAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, entries) {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                if (position < 0 || position >= values.length) return false;
                return isSignalSelectable(values[position]);
            }

            @Override
            @NonNull
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                boolean enabled = isEnabled(position);
                row.setEnabled(enabled);
                row.setAlpha(enabled ? 1.0f : 0.38f);
                return row;
            }
        };
        grid.setAdapter(mAdapter);
        grid.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= values.length) return;
            String value = values[position];

            // In session-shortcut mode the grid is phased: a disabled signal must be ignored even
            // if the framework lets the click through (belt-and-suspenders with isEnabled() above).
            if (mSessionShortcutMode && !isSignalSelectable(value)) return;

            if ("__CUSTOM__".equals(value)) {
                openCustomTextDialog();
            } else if ("__DELAY_PICKER__".equals(value)) {
                mEditingDelayIndex = -1;
                openDelayInputDialog("100");
            } else {
                if (mSelected.size() >= 8) {
                    Toast.makeText(context, getString(R.string.extra_keys_editor_max_signals), Toast.LENGTH_SHORT).show();
                    return;
                }
                mSelected.add(value);
                rebuildChipsWithGrouping();
                updateDoneButton();
            }
        });

        rebuildChipsWithGrouping();
        updateDoneButton();

        return mDialog;
    }

    private void rebuildChipsWithGrouping() {
        mChipsContainer.removeAllViews();
        if (mSelected.isEmpty()) {
            mChipsContainer.setVisibility(View.GONE);
            return;
        }
        mChipsContainer.setVisibility(View.VISIBLE);

        int i = 0;
        while (i < mSelected.size()) {
            int groupStart = i;
            // Collect consecutive modifiers
            while (i < mSelected.size() && MODIFIERS.contains(mSelected.get(i))) {
                i++;
            }
            boolean hasModifiers = (i > groupStart);
            // Check if there's a non-modifier following the modifiers (but NOT a delay token)
            boolean hasTarget = hasModifiers && i < mSelected.size()
                && !BindingTokenizer.isDelay(mSelected.get(i))
                && !BindingTokenizer.hasDelayPrefix(mSelected.get(i));

            if (hasTarget) {
                i++; // include the first non-modifier after modifiers in the group
                addGroupedChips(groupStart, i);
            } else if (hasModifiers) {
                // Lone modifier(s) with no following non-modifier — no underlay
                addStandaloneChip(groupStart);
                i = groupStart + 1;
            } else {
                // No modifiers — standalone chip, no underlay
                addStandaloneChip(groupStart);
                i = groupStart + 1;
            }
        }
    }

    private void addGroupedChips(int start, int end) {
        Context context = mChipsContainer.getContext();
        int dp4 = dpToPx(context, 4);
        int dp2 = dpToPx(context, 2);
        int dp1 = dpToPx(context, 1);

        LinearLayout groupLayout = new LinearLayout(context);
        groupLayout.setOrientation(LinearLayout.HORIZONTAL);
        groupLayout.setGravity(Gravity.CENTER_VERTICAL);

        groupLayout.setBackgroundResource(R.drawable.bg_signal_group_underlay);

        groupLayout.setPadding(dp4, dp4, dp4, dp4);

        LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        groupLp.setMargins(dp4, 0, dp4, 0);
        groupLayout.setLayoutParams(groupLp);

        for (int j = start; j < end; j++) {
            final int index = j;
            Chip chip = createStyledChip(getDisplayForToken(mSelected.get(j)), v -> {
                mSelected.remove(index);
                rebuildChipsWithGrouping();
                updateDoneButton();
            });

            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            chipLp.setMargins(dp1, 0, dp1, 0);
            chip.setLayoutParams(chipLp);

            // The non-modifier target token (last in the group) is editable when it is
            // custom text: tapping it reopens the custom-text dialog pre-filled for editing.
            if (j == end - 1 && isEditableCustomText(mSelected.get(index))) {
                chip.setOnClickListener(v -> openCustomTextDialog(index));
            }

            groupLayout.addView(chip);
        }

        mChipsContainer.addView(groupLayout);
    }

    private void addStandaloneChip(int index) {
        Context context = mChipsContainer.getContext();
        int dp4 = dpToPx(context, 4);

        Chip chip = createStyledChip(getDisplayForToken(mSelected.get(index)), v -> {
            mSelected.remove(index);
            rebuildChipsWithGrouping();
            updateDoneButton();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(dp4, 0, dp4, 0);
        chip.setLayoutParams(lp);

        // Tap on delay chip to edit its value
        if (BindingTokenizer.isDelay(mSelected.get(index))) {
            chip.setOnClickListener(v -> {
                String currentToken = mSelected.get(index);
                int currentMs = BindingTokenizer.parseDelayMs(currentToken);
                mEditingDelayIndex = index;
                openDelayInputDialog(String.valueOf(currentMs));
            });
        } else if (isEditableCustomText(mSelected.get(index))) {
            // Tap on a custom-text chip to edit its text in place
            chip.setOnClickListener(v -> openCustomTextDialog(index));
        }

        mChipsContainer.addView(chip);
    }

    /**
     * The combination is built in three phases keyed to how many signals are already chosen:
     *   - 0 chosen  → only modifiers (CTRL/ALT/SHIFT/FN) are selectable;
     *   - 1 chosen  → only the key is selectable (everything except modifiers);
     *   - 2+ chosen → nothing is selectable, the combination is complete.
     * In extra-key mode every signal stays selectable.
     */
    private boolean isSignalSelectable(String value) {
        if (!mSessionShortcutMode) return true;
        if (mSelected.isEmpty()) {
            return MODIFIERS.contains(value);
        } else if (mSelected.size() == 1) {
            return !MODIFIERS.contains(value);
        }
        return false;
    }

    private void updateDoneButton() {
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    /**
     * A session shortcut must be a combination, so it has to start with a modifier and have a key
     * for that modifier to apply to. An empty selection is allowed through: that is how a binding
     * is cleared, and an empty value has always meant "disabled".
     */
    private boolean validateSessionShortcut() {
        if (mSelected.isEmpty()) return true;

        if (!KeyCombination.hasLeadingModifier(mSelected)) {
            Toast.makeText(requireContext(), R.string.session_shortcut_needs_modifier, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!KeyCombination.hasKey(mSelected)) {
            Toast.makeText(requireContext(), R.string.session_shortcut_needs_key, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }
    private static int dpToPx(Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    private String getDisplayForToken(String token) {
        if (BindingTokenizer.isDelay(token)) {
            return getString(R.string.extra_keys_editor_delay_format, BindingTokenizer.parseDelayMs(token));
        }
        return token;
    }

    private Chip createStyledChip(String signal, View.OnClickListener removeListener) {
        Context context = mChipsContainer.getContext();
        Chip chip = new Chip(context);
        chip.setText(signal);
        chip.setCheckable(false);
        chip.setCheckedIconVisible(false);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipBackgroundColor(ColorStateList.valueOf(
            context.getColor(R.color.signal_picker_underlay_fill)
        ));
        try {
            java.lang.reflect.Method m = chip.getChipDrawable().getClass().getDeclaredMethod("setChipSurfaceColor", ColorStateList.class);
            m.setAccessible(true);
            m.invoke(chip.getChipDrawable(), ColorStateList.valueOf(0));
        } catch (Exception ignored) {}

        chip.setCloseIconVisible(true);
        chip.setCloseIconResource(R.drawable.ic_close);
        chip.setCloseIconSize(dpToPx(context, 16));
        chip.setCloseIconTint(ColorStateList.valueOf(
            chip.getTextColors().getDefaultColor()
        ));
        chip.setCloseIconStartPadding(dpToPx(context, 2));
        chip.setCloseIconEndPadding(dpToPx(context, 2));
        chip.setCloseIconContentDescription(getString(R.string.extra_keys_editor_remove_signal, signal));
        chip.setOnCloseIconClickListener(removeListener);

        float cornerRadius = dpToPx(context, 6);
        chip.setShapeAppearanceModel(
            chip.getShapeAppearanceModel().toBuilder()
                .setAllCornerSizes(cornerRadius)
                .build()
        );

        return chip;
    }

    private void openCustomTextDialog() {
        openCustomTextDialog(-1);
    }

    /**
     * Open the custom-text dialog. With {@code editIndex < 0} it appends a new custom signal;
     * with a valid index it pre-fills the field with the existing token at that position so the
     * user can edit it, replacing it in place on confirm.
     */
    private void openCustomTextDialog(int editIndex) {
        Context context = requireContext();
        mEditingCustomIndex = editIndex;

        EditText editText = new EditText(context);
        editText.setMaxLines(1);
        editText.setEms(1);
        if (editIndex >= 0 && editIndex < mSelected.size()) {
            editText.setText(mSelected.get(editIndex));
            editText.selectAll();
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_TermuxActivity_Dialog)
            .setTitle(R.string.extra_keys_editor_custom_text_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, (d, which) -> hideKeyboard(editText));

        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        dialog.setOnShowListener(d -> {
            WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(dialog.getWindow(), editText);
            if (controller != null) controller.show(WindowInsetsCompat.Type.ime());

            editText.requestFocus();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);

            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = editText.getText().toString().trim();
                if (!text.isEmpty() && addOrSetCustomSignal(text)) {
                    hideKeyboard(editText);
                    dialog.dismiss();
                }
            });
        });

        dialog.setOnCancelListener(d -> hideKeyboard(editText));
        dialog.setOnDismissListener(d -> {
            mEditingCustomIndex = -1;
            hideKeyboard(editText);
        });
        dialog.show();
    }

    private void openDelayInputDialog(String initialValue) {
        Context context = requireContext();
        EditText editText = new EditText(context);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editText.setMaxLines(1);
        editText.setText(initialValue);
        editText.selectAll();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_TermuxActivity_Dialog)
            .setTitle(R.string.extra_keys_editor_delay_dialog_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, (d, which) -> hideKeyboard(editText));

        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        dialog.setOnShowListener(d -> {
            WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(dialog.getWindow(), editText);
            if (controller != null) controller.show(WindowInsetsCompat.Type.ime());

            editText.requestFocus();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);

            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = editText.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(context, getString(R.string.extra_keys_editor_delay_hint), Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    int ms = Integer.parseInt(text);
                    if (ms < 1 || ms > 1000) {
                        Toast.makeText(context, getString(R.string.extra_keys_editor_delay_hint), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (mEditingDelayIndex < 0 && mSelected.size() >= 8) {
                        Toast.makeText(context, getString(R.string.extra_keys_editor_max_signals), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String delayToken = BindingTokenizer.delayToken(ms);
                    if (mEditingDelayIndex >= 0 && mEditingDelayIndex < mSelected.size()) {
                        mSelected.set(mEditingDelayIndex, delayToken);
                        mEditingDelayIndex = -1;
                    } else {
                        mSelected.add(delayToken);
                    }
                    hideKeyboard(editText);
                    dialog.dismiss();
                    rebuildChipsWithGrouping();
                    updateDoneButton();
                } catch (NumberFormatException e) {
                    Toast.makeText(context, getString(R.string.extra_keys_editor_delay_hint), Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.setOnCancelListener(d -> hideKeyboard(editText));
        dialog.setOnDismissListener(d -> hideKeyboard(editText));
        dialog.show();
    }

    private void hideKeyboard(EditText editText) {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            android.os.IBinder token = editText.getWindowToken();
            if (token != null) imm.hideSoftInputFromWindow(token, 0);
        }
    }

    /** True if the token is user-entered custom text that can be edited in place
     *  (i.e. not a known grid signal, not a modifier, not a delay token). */
    private boolean isEditableCustomText(String token) {
        if (token == null || token.isEmpty()) return false;
        if (mSignalValues.contains(token)) return false;
        if (BindingTokenizer.isDelay(token) || BindingTokenizer.hasDelayPrefix(token)) return false;
        return true;
    }

    /**
     * Add a new custom signal, or replace an existing one when {@link #mEditingCustomIndex}
     * points at a valid position. Editing does not count against the 8-signal cap because it
     * only replaces an existing token.
     */
    private boolean addOrSetCustomSignal(String text) {
        if (mEditingCustomIndex >= 0) {
            int idx = mEditingCustomIndex;
            mEditingCustomIndex = -1;
            if (idx >= 0 && idx < mSelected.size()) {
                mSelected.set(idx, text);
                rebuildChipsWithGrouping();
                updateDoneButton();
            }
            return true;
        }
        if (mSelected.size() >= 8) {
            Toast.makeText(requireContext(), getString(R.string.extra_keys_editor_max_signals), Toast.LENGTH_SHORT).show();
            return false;
        }
        // Spaces are allowed: a custom binding such as "ls -la" is stored as a single
        // literal key, so the spaces are preserved and the text is sent verbatim.
        mSelected.add(text);
        rebuildChipsWithGrouping();
        updateDoneButton();
        return true;
    }
}
