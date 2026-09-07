package com.termux.app.terminal.io;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.terminal.TerminalSession;

/**
 * Controller for the terminal "text input" panel and its toggle button.
 * Wired into TermuxActivity to hold view references and save/restore text state.
 */
public final class TextInputPanelController {

    public interface Host {
        @Nullable TerminalSession getCurrentSession();
        void onToggleTextInput(boolean nowVisible);
    }

    @NonNull private final Context mContext;
    @NonNull private final Host mHost;
    @NonNull private final SessionUiStateStore mTextInputState;

    @Nullable private EditText mEditText;
    @Nullable private View mTextInputContainer;
    @Nullable private ImageButton mToggleTextInputButton;

    private static final String PREF_TEXT_INPUT_VISIBLE = "text_input_visible";
    private static final String PREF_TEXT_INPUT_ENABLED = "text_input_enabled";

    public TextInputPanelController(@NonNull Context context,
                                    @NonNull Host host,
                                    @NonNull SessionUiStateStore textInputState) {
        mContext = context;
        mHost = host;
        mTextInputState = textInputState;
    }

    /** Cache view references. */
    public void setup(@Nullable Bundle savedInstanceState, @NonNull View rootView) {
        mEditText = rootView.findViewById(R.id.terminal_toolbar_text_input);
        mTextInputContainer = rootView.findViewById(R.id.terminal_toolbar_text_input_container);
        mToggleTextInputButton = rootView.findViewById(R.id.toggle_text_input_button);
    }

    /** Record the current input state for the current session. */
    public void saveTextInputForCurrentSession() {
        TerminalSession session = mHost.getCurrentSession();
        if (session == null || mEditText == null) return;
        String handle = session.mHandle;
        String text = mEditText.getText() != null ? mEditText.getText().toString() : "";
        mTextInputState.saveInput(handle, text);
        mTextInputState.setCaret(handle, mEditText.getSelectionStart());
        mTextInputState.setVisible(handle, mTextInputContainer != null
                && mTextInputContainer.getVisibility() == View.VISIBLE);
        mTextInputState.setFocusOnInput(handle, mEditText.hasFocus());
    }

    /** Restore text + caret for session handle into cached EditText. */
    public void restoreTextInputForSession(@Nullable String sessionHandle) {
        if (mEditText == null) return;
        if (sessionHandle == null) {
            mEditText.setText("");
            return;
        }
        String text = mTextInputState.getInputText(sessionHandle);
        mEditText.setText(text != null ? text : "");
        int caret = mTextInputState.getCaret(sessionHandle);
        if (caret >= 0) {
            mEditText.setSelection(Math.min(caret, mEditText.length()));
        }
    }

    /** Remove saved state for a closed session. */
    public void clearTextInputForSession(@NonNull String sessionHandle) {
        mTextInputState.clear(sessionHandle);
    }

    /** Mark whether the text input panel has focus for the current session. */
    public void setFocusOnInputForCurrentSession(boolean focusOnInput) {
        TerminalSession session = mHost.getCurrentSession();
        if (session == null) return;
        mTextInputState.setFocusOnInput(session, focusOnInput);
        if (focusOnInput && mEditText != null) mEditText.requestFocus();
        if (!focusOnInput && mEditText != null && mEditText.hasFocus()) mEditText.clearFocus();
    }

    /** Update the toggle button icon to reflect the current visible state. */
    public void updateToggleTextInputButtonIcon() {
        if (mToggleTextInputButton == null) return;
        boolean isVisible = mTextInputContainer != null
                && mTextInputContainer.getVisibility() == View.VISIBLE;
        mToggleTextInputButton.setImageResource(isVisible
                ? com.termux.R.drawable.ic_keyboard_hide
                : com.termux.R.drawable.ic_keyboard_show);
    }

    @Nullable public EditText getEditText() { return mEditText; }
    @Nullable public View getTextInputContainer() { return mTextInputContainer; }
    @Nullable public ImageButton getToggleTextInputButton() { return mToggleTextInputButton; }
}
