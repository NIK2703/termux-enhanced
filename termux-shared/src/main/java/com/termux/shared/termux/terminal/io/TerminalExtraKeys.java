package com.termux.shared.termux.terminal.io;

import android.os.Build;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;


import java.util.List;
import androidx.annotation.Nullable;

import static com.termux.shared.termux.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS;


public class TerminalExtraKeys implements ExtraKeysView.IExtraKeysView {

    private TerminalView mTerminalView;
    private MacroRunner mMacroRunner;
    private boolean mMacroCtrlDown;
    private boolean mMacroAltDown;
    private boolean mMacroShiftDown;
    private boolean mMacroFnDown;

    public TerminalExtraKeys(@NonNull TerminalView terminalView) {
        mTerminalView = terminalView;
        mMacroRunner = new MacroRunner(token -> handleMacroToken(token));
    }

    /**
     * Point the extra keys at the currently active {@link TerminalView}. Used when the terminal is
     * shown through a horizontal pager where each page is its own view: the active page changes as
     * the user swipes, so key input must follow the selected page rather than a fixed view.
     */
    public void setTerminalView(@Nullable TerminalView terminalView) {
        mTerminalView = terminalView;
    }

    /**
     * Resolve the {@link TerminalView} that extra-key input should be routed to. Defaults to the
     * cached {@link #mTerminalView} (kept in sync with the active pager page), but subclasses may
     * override this to resolve the live active page when the cached pointer is still {@code null}
     * (e.g. during early lifecycle or a fast tab switch) so a key press is never silently dropped
     * by the {@code mTerminalView == null} guard.
     */
    @Nullable
    protected TerminalView getTerminalViewForInput() {
        return mTerminalView;
    }

    @Override
    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (buttonInfo.hasDelay()) {
            // Reset modifier state for new macro
            mMacroCtrlDown = false;
            mMacroAltDown = false;
            mMacroShiftDown = false;
            mMacroFnDown = false;
            // Use asynchronous macro runner for bindings with delays
            List<String> tokens = buttonInfo.getParsedTokens();
            mMacroRunner.start(tokens);
        } else if (buttonInfo.isMacro()) {
            boolean ctrlDown = false, altDown = false, shiftDown = false, fnDown = false;
            // Use the tokens parsed by ExtraKeyButton: a literal token may itself contain spaces
            // (a quoted custom binding such as "ls -la"), so the joined key must not be re-split.
            for (String key : buttonInfo.getParsedTokens()) {
                if (SpecialButton.CTRL.getKey().equals(key)) {
                    ctrlDown = true;
                } else if (SpecialButton.ALT.getKey().equals(key)) {
                    altDown = true;
                } else if (SpecialButton.SHIFT.getKey().equals(key)) {
                    shiftDown = true;
                } else if (SpecialButton.FN.getKey().equals(key)) {
                    fnDown = true;
                } else {
                    onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
                    ctrlDown = false;
                    altDown = false;
                    shiftDown = false;
                    fnDown = false;
                }
            }
        } else {
            String k = buttonInfo.getKey();
            // Guard: simple-key modifiers (e.g. {key: "CTRL"}) must not be sent
            // as literal text — they are handled by ExtraKeysView's sticky/hold toggle.
            if (SpecialButton.CTRL.getKey().equals(k) || SpecialButton.ALT.getKey().equals(k)
                    || SpecialButton.SHIFT.getKey().equals(k) || SpecialButton.FN.getKey().equals(k))
                return;
            onTerminalExtraKeyButtonClick(view, k, false, false, false, false);
        }
    }

    private void handleMacroToken(String token) {
        if (SpecialButton.CTRL.getKey().equals(token)) {
            mMacroCtrlDown = true;
        } else if (SpecialButton.ALT.getKey().equals(token)) {
            mMacroAltDown = true;
        } else if (SpecialButton.SHIFT.getKey().equals(token)) {
            mMacroShiftDown = true;
        } else if (SpecialButton.FN.getKey().equals(token)) {
            mMacroFnDown = true;
        } else {
            onTerminalExtraKeyButtonClick(null, token,
                mMacroCtrlDown, mMacroAltDown, mMacroShiftDown, mMacroFnDown);
            mMacroCtrlDown = false;
            mMacroAltDown = false;
            mMacroShiftDown = false;
            mMacroFnDown = false;
        }
    }

    protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        // Resolve the target view dynamically (subclass may fall back to the live active page)
        // instead of a bare `if (mTerminalView == null) return;` so a press is never silently
        // dropped when the cached pointer has not been refreshed yet.
        TerminalView terminalView = getTerminalViewForInput();
        if (terminalView == null) return;
        if (PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
            Integer keyCode = PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
            if (keyCode == null) return;
            int metaState = 0;
            if (ctrlDown) metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            if (altDown) metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            if (shiftDown) metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            if (fnDown) metaState |= KeyEvent.META_FUNCTION_ON;

            KeyEvent keyEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState);
            terminalView.onKeyDown(keyCode, keyEvent);
        } else {
            // not a control char
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                key.codePoints().forEach(codePoint -> {
                    terminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, codePoint, ctrlDown, altDown);
                });
            } else {
                TerminalSession session = terminalView.getCurrentSession();
                if (session != null && key.length() > 0)
                    session.write(key);
            }
        }
    }

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        return false;
    }

    // ---- Gesture press/release lifecycle ----

    @Override
    public void onExtraKeyButtonGesturePress(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        // Gesture press is identical to a tap — delegate to onClick
        onExtraKeyButtonClick(view, buttonInfo, button);
    }

    @Override
    public void onExtraKeyButtonGestureRelease(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        // Macros with DELAY tokens execute asynchronously and must not be cancelled
        // by finger release — the user should not have to hold the screen for the
        // entire macro sequence. A new macro press will cancel any running one via
        // MacroRunner.start() → cancel().
    }

}
