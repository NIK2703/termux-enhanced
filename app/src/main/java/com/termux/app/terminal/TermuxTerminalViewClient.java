package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioManager;
import android.os.Environment;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.file.FileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.app.terminal.io.KeyboardShortcut;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.data.DataUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.data.TermuxUrlUtils;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class TermuxTerminalViewClient extends TermuxTerminalViewClientBase {

    final TermuxActivity mActivity;

    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys. */
    boolean mVirtualControlKeyDown, mVirtualFnKeyDown;

    private Runnable mShowSoftKeyboardRunnable;

    private boolean mShowSoftKeyboardIgnoreOnce;
    private boolean mShowSoftKeyboardWithDelayOnce;

    /**
     * Auto-clear runnable for {@link #mShowSoftKeyboardIgnoreOnce}. Bounds the latch lifetime
     * so that a stale latch (set, but never consumed because requestFocus() was a no-op on an
     * already-focused view) cannot swallow a later legitimate keyboard show.
     */
    private Runnable mIgnoreOnceAutoClearRunnable;

    /**
     * Set to ignore the next soft keyboard show request triggered by terminal view focus change.
     * Used when hiding the text input panel to prevent keyboard from reopening on terminal focus.
     *
     * The latch self-clears after 1 second: if the expected focus change never happens
     * (the view was already focused and requestFocus() did not fire onFocusChange), the latch
     * must not linger and eat a later legitimate show.
     */
    public void ignoreOnceSoftKeyboardOnFocus() {
        mShowSoftKeyboardIgnoreOnce = true;
        final TerminalView tv = mActivity.getTerminalView();
        if (tv != null) {
            if (mIgnoreOnceAutoClearRunnable != null)
                tv.removeCallbacks(mIgnoreOnceAutoClearRunnable);
            mIgnoreOnceAutoClearRunnable = () -> mShowSoftKeyboardIgnoreOnce = false;
            tv.postDelayed(mIgnoreOnceAutoClearRunnable, 1000);
        }
    }

    /** Clear the ignore-once latch and its auto-clear timer (used before an intended show). */
    public void clearIgnoreOnceSoftKeyboardOnFocus() {
        mShowSoftKeyboardIgnoreOnce = false;
        removeIgnoreOnceAutoClear();
    }

    private void removeIgnoreOnceAutoClear() {
        final TerminalView tv = mActivity.getTerminalView();
        if (tv != null && mIgnoreOnceAutoClearRunnable != null) {
            tv.removeCallbacks(mIgnoreOnceAutoClearRunnable);
        }
    }

    /**
     * Cancel any pending delayed keyboard-show runnable posted to the active terminal view.
     * Used on the "keep hidden" paths so a previously scheduled +500ms show cannot pop the
     * keyboard back after we explicitly hid it.
     */
    public void cancelPendingSoftKeyboardShow() {
        final TerminalView tv = mActivity.getTerminalView();
        if (tv != null) {
            tv.removeCallbacks(getShowSoftKeyboardRunnable());
        }
    }

    private boolean mTerminalCursorBlinkerStateAlreadySet;

    private List<KeyboardShortcut> mSessionShortcuts;

    private static final String LOG_TAG = "TermuxTerminalViewClient";

    public TermuxTerminalViewClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        this.mActivity = activity;
        this.mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
    }

    public TermuxActivity getActivity() {
        return mActivity;
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        onReloadProperties();

        // The active terminal view is owned by the ViewPager2 and is only bound once a page is
        // selected (after the session list is populated in onServiceConnected). When onCreate() runs
        // before that, getTerminalView() is still null — the per-page font/screen-on settings are
        // applied per page in TerminalPagerAdapter.onBindViewHolder, so skipping here is safe.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) {
            terminalView.setTextSize(mActivity.getPreferences().getFontSize());
            terminalView.setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn());
        }
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // Set {@link TerminalView#TERMINAL_VIEW_KEY_LOGGING_ENABLED} value
        // Also required if user changed the preference from {@link TermuxSettings} activity and returns
        boolean isTerminalViewKeyLoggingEnabled = mActivity.getPreferences().isTerminalViewKeyLoggingEnabled();
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null)
            terminalView.setIsTerminalViewKeyLoggingEnabled(isTerminalViewKeyLoggingEnabled);

        // Piggyback on the terminal view key logging toggle for now, should add a separate toggle in future
        mActivity.getTermuxActivityRootView().setIsRootViewLoggingEnabled(isTerminalViewKeyLoggingEnabled);
        ViewUtils.setIsViewUtilsLoggingEnabled(isTerminalViewKeyLoggingEnabled);
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Show the soft keyboard if required
        setSoftKeyboardState(true, mActivity.isActivityRecreated());

        mTerminalCursorBlinkerStateAlreadySet = false;

        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null && terminalView.mEmulator != null) {
            // Start terminal cursor blinking if enabled
            // If emulator is already set, then start blinker now, otherwise wait for onEmulatorSet()
            // event to start it. This is needed since onEmulatorSet() may not be called after
            // TermuxActivity is started after device display timeout with double tap and not power button.
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Stop terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(false);
    }

    /**
     * Should be called when mActivity.reloadProperties() is called
     */
    public void onReloadProperties() {
        setSessionShortcuts();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Show the soft keyboard if required
        setSoftKeyboardState(false, true);

        // Start terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(true);
    }

    /**
     * Should be called when {@link com.termux.view.TerminalView#mEmulator} is set
     */
    @Override
    public void onEmulatorSet() {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null && terminalView.mEmulator != null) {
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(mActivity, true);
            boolean disabled = !prefs.isScrollOnNewOutputEnabled();
            terminalView.mEmulator.setAutoScrollDisabled(disabled);
        }

        // The floating toggle button's right margin is derived from the BOUND emulator's scrollback
        // (see TermuxActivity.hasScrollbar), so it cannot be computed before this point: the pager
        // binds a page before it is measured, and mEmulator is only assigned by
        // TerminalView.updateSize() once the view has a non-zero size. TermuxActivity
        // .updateFloatingButtonMargin() therefore refuses to touch the margin while the scrollbar
        // state is unknown, and this callback is where that state becomes known.
        //
        // Without it the margin is never refreshed for an IDLE terminal: the button's other refresh
        // source is the TerminalView's OnScreenUpdateListener, which only fires on terminal output.
        // After an activity recreate (theme change) nothing is printing, so the button kept the
        // margin it had while no session was bound and ended up overlapping the scrollbar thumb.
        //
        // Posted, not called inline: updateSize() runs from onSizeChanged(), i.e. inside the layout
        // pass, and although the settled write is now a translation (no setLayoutParams, see
        // TermuxActivity.mFloatingButtonLayoutMarginEnd) posting still keeps it off the layout pass
        // and out of the re-entrancy question entirely. The settled margin is a one-shot value, so a
        // frame's delay is irrelevant, and updateFloatingButtonMargin() still early-returns while
        // the pager is animating a page scroll (that window belongs to the scroll interpolation).
        if (terminalView != null) {
            terminalView.post(mActivity::updateFloatingButtonMargin);
        }

        if (!mTerminalCursorBlinkerStateAlreadySet) {
            // Start terminal cursor blinking if enabled
            // We need to wait for the first session to be attached that's set in
            // TermuxActivity.onServiceConnected() and then the multiple calls to TerminalView.updateSize()
            // where the final one eventually sets the mEmulator when width/height is not 0. Otherwise
            // blinker will not start again if TermuxActivity is started again after exiting it with
            // double back press. Check TerminalView.setTerminalCursorBlinkerState().
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }



    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            boolean increase = scale > 1.f;
            changeFontSize(increase);
            return 1.0f;
        }
        return scale;
    }



    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession == null) return;
        TerminalEmulator term = currentSession.getEmulator();
        if (term == null) return;
        TerminalView terminalView = mActivity.getTerminalView();

        // Hide text input panel instantly when tapping on the terminal area,
        // instead of waiting for a keyboard focus change event.
        if (mActivity.isTextInputVisible()) {
            mActivity.setTextInputVisible(false);
            mActivity.updateToggleTextInputButtonIcon();
            return;
        }

        if (mActivity.getProperties().shouldOpenTerminalTranscriptURLOnClick()) {
            if (terminalView == null) return;
            int[] columnAndRow = terminalView.getColumnAndRow(e, true);
            String wordAtTap = term.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1]);
            LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(wordAtTap);

            if (!urlSet.isEmpty()) {
                String url = (String) urlSet.iterator().next();
                ShareUtils.openUrl(mActivity, url);
                return;
            }
        }

        if (!term.isMouseTrackingActive() && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity))
                KeyboardUtils.showSoftKeyboard(mActivity, terminalView);
            else
                Logger.logVerbose(LOG_TAG, "Not showing soft keyboard onSingleTapUp since its disabled");
        }
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return mActivity.getProperties().isBackKeyTheEscapeKey();
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return mActivity.getProperties().isEnforcingCharBasedInput();
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return mActivity.getProperties().isUsingCtrlSpaceWorkaround();
    }

    @Override
    public boolean isTerminalViewSelected() {
        if (mActivity.getTerminalToolbarContainer() == null) return true;
        TerminalView terminalView = mActivity.getTerminalView();
        return terminalView == null || terminalView.hasFocus();
    }



    @Override
    public void copyModeChanged(boolean copyMode) {
        // No drawer to lock anymore
    }



    @SuppressLint("RtlHardcoded")
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {
        if (handleVirtualKeys(keyCode, e, true)) return true;

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession);
            return true;
        } else if (!mActivity.getProperties().areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isAltPressed()) {
            // Get the unmodified code point:
            int unicodeChar = e.getUnicodeChar(0);

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'/* next */) {
                mTermuxTerminalSessionActivityClient.switchToSession(true);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p' /* previous */) {
                mTermuxTerminalSessionActivityClient.switchToSession(false);
            } else if (unicodeChar == 'k'/* keyboard */) {
                onToggleSoftKeyboardRequest();
            } else if (unicodeChar == 'm'/* menu */) {
                TerminalView tv = mActivity.getTerminalView();
                if (tv != null) tv.showContextMenu();
            } else if (unicodeChar == 'r'/* rename */) {
                mTermuxTerminalSessionActivityClient.renameSession(currentSession);
            } else if (unicodeChar == 'c'/* create */) {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
            } else if (unicodeChar == 'u' /* urls */) {
                showUrlSelection();
            } else if (unicodeChar == 'v') {
                doPaste();
            } else if (unicodeChar == '+' || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+') {
                // We also check for the shifted char here since shift may be required to produce '+',
                // see https://github.com/termux/termux-api/issues/2
                changeFontSize(true);
            } else if (unicodeChar == '-') {
                changeFontSize(false);
            } else if (unicodeChar >= '1' && unicodeChar <= '9') {
                int index = unicodeChar - '1';
                mTermuxTerminalSessionActivityClient.switchToSession(index);
            }
            return true;
        }

        return false;

    }



    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        // If emulator is not set, like if bootstrap installation failed and user dismissed the error
        // dialog, then just exit the activity, otherwise they will be stuck in a broken state.
        TerminalView terminalView = mActivity.getTerminalView();
        if (keyCode == KeyEvent.KEYCODE_BACK && (terminalView == null || terminalView.mEmulator == null)) {
            mActivity.finishActivityIfNotFinishing();
            return true;
        }

        return handleVirtualKeys(keyCode, e, false);
    }

    /** Handle dedicated volume buttons as virtual keys if applicable. */
    private boolean handleVirtualKeys(int keyCode, KeyEvent event, boolean down) {
        InputDevice inputDevice = event.getDevice();
        if (mActivity.getProperties().areVirtualVolumeKeysDisabled()) {
            return false;
        } else if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down;
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down;
            return true;
        }
        return false;
    }



    @Override
    public boolean readControlKey() {
        return readExtraKeysSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown;
    }

    @Override
    public boolean readAltKey() {
        return readExtraKeysSpecialButton(SpecialButton.ALT);
    }

    @Override
    public boolean readShiftKey() {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT);
    }

    @Override
    public boolean readFnKey() {
        return readExtraKeysSpecialButton(SpecialButton.FN);
    }

    public boolean readExtraKeysSpecialButton(SpecialButton specialButton) {
        if (mActivity.getExtraKeysView() == null) return false;
        Boolean state = mActivity.getExtraKeysView().readSpecialButton(specialButton, true);
        if (state == null) {
            Logger.logError(LOG_TAG,"Failed to read an unregistered " + specialButton + " special button value from extra keys.");
            return false;
        }
        return state;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }



    @Override
    public boolean onCodePoint(final int codePoint, boolean ctrlDown, TerminalSession session) {
        if (mVirtualFnKeyDown) {
            int resultingKeyCode = -1;
            int resultingCodePoint = -1;
            boolean altDown = false;
            int lowerCase = Character.toLowerCase(codePoint);
            switch (lowerCase) {
                // Arrow keys.
                case 'w':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP;
                    break;
                case 'a':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                    break;
                case 's':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                    break;
                case 'd':
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                    break;

                // Page up and down.
                case 'p':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP;
                    break;
                case 'n':
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN;
                    break;

                // Some special keys:
                case 't':
                    resultingKeyCode = KeyEvent.KEYCODE_TAB;
                    break;
                case 'i':
                    resultingKeyCode = KeyEvent.KEYCODE_INSERT;
                    break;
                case 'h':
                    resultingCodePoint = '~';
                    break;

                // Special characters to input.
                case 'u':
                    resultingCodePoint = '_';
                    break;
                case 'l':
                    resultingCodePoint = '|';
                    break;

                // Function keys.
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    resultingKeyCode = (codePoint - '1') + KeyEvent.KEYCODE_F1;
                    break;
                case '0':
                    resultingKeyCode = KeyEvent.KEYCODE_F10;
                    break;

                // Other special keys.
                case 'e':
                    resultingCodePoint = /*Escape*/ 27;
                    break;
                case '.':
                    resultingCodePoint = /*^.*/ 28;
                    break;

                case 'b': // alt+b, jumping backward in readline.
                case 'f': // alf+f, jumping forward in readline.
                case 'x': // alt+x, common in emacs.
                    resultingCodePoint = lowerCase;
                    altDown = true;
                    break;

                // Volume control.
                case 'v':
                    resultingCodePoint = -1;
                    AudioManager audio = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
                    audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                    break;

                // Writing mode:
                case 'q':
                case 'k':
                    mActivity.toggleTerminalToolbar();
                    mVirtualFnKeyDown=false; // force disable fn key down to restore keyboard input into terminal view, fixes termux/termux-app#1420
                    break;
            }

            if (resultingKeyCode != -1) {
                TerminalEmulator term = session.getEmulator();
                session.write(KeyHandler.getCode(resultingKeyCode, 0, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode()));
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint);
            }
            return true;
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session.isRunning()) {
                mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                return true;
            }

            List<KeyboardShortcut> shortcuts = mSessionShortcuts;
            if (shortcuts != null && !shortcuts.isEmpty()) {
                int codePointLowerCase = Character.toLowerCase(codePoint);
                for (int i = shortcuts.size() - 1; i >= 0; i--) {
                    KeyboardShortcut shortcut = shortcuts.get(i);
                    if (codePointLowerCase == shortcut.codePoint) {
                        switch (shortcut.shortcutAction) {
                            case TermuxPropertyConstants.ACTION_SHORTCUT_CREATE_SESSION:
                                mTermuxTerminalSessionActivityClient.addNewSession(false, null);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_NEXT_SESSION:
                                mTermuxTerminalSessionActivityClient.switchToSession(true);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_PREVIOUS_SESSION:
                                mTermuxTerminalSessionActivityClient.switchToSession(false);
                                return true;
                            case TermuxPropertyConstants.ACTION_SHORTCUT_RENAME_SESSION:
                                mTermuxTerminalSessionActivityClient.renameSession(mActivity.getCurrentSession());
                                return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Set the terminal sessions shortcuts.
     */
    private void setSessionShortcuts() {
        mSessionShortcuts = new ArrayList<>();

        // The {@link TermuxPropertyConstants#MAP_SESSION_SHORTCUTS} stores the session shortcut key and action pair
        for (Map.Entry<String, Integer> entry : TermuxPropertyConstants.MAP_SESSION_SHORTCUTS.entrySet()) {
            // The mMap stores the code points for the session shortcuts while loading properties
            Integer codePoint = (Integer) mActivity.getProperties().getInternalPropertyValue(entry.getKey(), true);
            // If codePoint is null, then session shortcut did not exist in properties or was invalid
            // as parsed by {@link #getCodePointForSessionShortcuts(String,String)}
            // If codePoint is not null, then get the action for the MAP_SESSION_SHORTCUTS key and
            // add the code point to sessionShortcuts
            if (codePoint != null)
                mSessionShortcuts.add(new KeyboardShortcut(codePoint, entry.getValue()));
        }
    }





    public void changeFontSize(boolean increase) {
        mActivity.getPreferences().changeFontSize(increase);
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null)
            terminalView.setTextSize(mActivity.getPreferences().getFontSize());
    }



    /**
     * Called when user requests the soft keyboard to be toggled via "KEYBOARD" toggle button in
     * drawer or extra keys, or with ctrl+alt+k hardware keyboard shortcut.
     */
    public void onToggleSoftKeyboardRequest() {
        // The active view is owned by the pager; may be null only in a narrow window before the
        // first page is selected. Toggling the keyboard before that is a no-op (nothing to toggle).
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        // If soft keyboard toggle behaviour is enable/disabled
        if (mActivity.getProperties().shouldEnableDisableSoftKeyboardOnToggle()) {
            // If soft keyboard is visible
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Disabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(false);
                KeyboardUtils.disableSoftKeyboard(mActivity, terminalView);
            } else {
                // Show with a delay, otherwise pressing keyboard toggle won't show the keyboard after
                // switching back from another app if keyboard was previously disabled by user.
                // Also request focus, since it wouldn't have been requested at startup by
                // setSoftKeyboardState if keyboard was disabled. #2112
                Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(true);
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                // Clear SOFT_INPUT_STATE_ALWAYS_HIDDEN (may be left by a resume-with-hidden-intent)
                // so the show below is not silently ignored.
                KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
                if(mShowSoftKeyboardWithDelayOnce) {
                    mShowSoftKeyboardWithDelayOnce = false;
                    terminalView.postDelayed(getShowSoftKeyboardRunnable(), 500);
                    terminalView.requestFocus();
                } else
                    KeyboardUtils.showSoftKeyboard(mActivity, terminalView);
            }
        }
        // If soft keyboard toggle behaviour is show/hide
        else {
            // If soft keyboard is disabled by user for Termux
            if (!mActivity.getPreferences().isSoftKeyboardEnabled()) {
                Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard on toggle");
                KeyboardUtils.disableSoftKeyboard(mActivity, terminalView);
            } else {
                Logger.logVerbose(LOG_TAG, "Showing/Hiding soft keyboard on toggle");
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                // toggleSoftInput(SHOW_FORCED) can be suppressed by ALWAYS_HIDDEN on some
                // builds; a user toggle is an explicit intent — restore the normal mode first.
                KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
                KeyboardUtils.toggleSoftKeyboard(mActivity);
            }
        }
    }

    public void setSoftKeyboardState(boolean isStartup, boolean isReloadTermuxProperties) {
        // The active terminal view is owned by the ViewPager2 and may not be selected yet when
        // onResume() runs on a cold start. The per-page focus listener is attached in the pager
        // adapter (onBindViewHolder) where the view is guaranteed non-null, so skip the view-bound
        // work here if it is not ready — it will be applied when the page is bound.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        boolean noShowKeyboard = false;

        // Requesting terminal view focus is necessary regardless of if soft keyboard is to be
        // disabled or hidden at startup, otherwise if hardware keyboard is attached and user
        // starts typing on hardware keyboard without tapping on the terminal first, then a colour
        // tint will be added to the terminal as highlight for the focussed view. Test with a light
        // theme. For android 8.+, the "defaultFocusHighlightEnabled" attribute is also set to false
        // in TerminalView layout to fix the issue.

        // If soft keyboard is disabled by user for Termux (check function docs for Termux behaviour info)
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
            mActivity.getPreferences().isSoftKeyboardEnabled(),
            mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware())) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard");
            KeyboardUtils.disableSoftKeyboard(mActivity, terminalView);
            terminalView.requestFocus();
            noShowKeyboard = true;
            // Delay is only required if onCreate() is called like when Termux app is exited with
            // double back press, not when Termux app is switched back from another app and keyboard
            // toggle is pressed to enable keyboard
            if (isStartup && mActivity.isOnResumeAfterOnCreate())
                mShowSoftKeyboardWithDelayOnce = true;
        } else {
            // Set flag to automatically push up TerminalView when keyboard is opened instead of showing over it
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity);

            // Clear any previous flags to disable soft keyboard in case setting updated.
            // Guarded: Window.clearFlags() dispatches the window attributes to the window manager
            // even when the bit is already clear, and this runs on every single resume.
            if (KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity))
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);

            // If soft keyboard is to be hidden on startup. Applies ONLY to a true cold start
            // (first resume after onCreate, not a recreate): on resume-from-background /
            // recreate the keyboard-restore path (runKeyboardRestore) is the single authority
            // and honours the persisted keyboard intent. Forcing SOFT_INPUT_STATE_ALWAYS_HIDDEN
            // here on every resume would fight that restore (the "hidden on startup vs
            // showWithRetry" conflict).
            if (isStartup && mActivity.isOnResumeAfterOnCreate() && !mActivity.isActivityRecreated()
                && mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup");
                // Required to keep keyboard hidden at app startup while the window gains focus
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);

                KeyboardUtils.hideSoftKeyboard(mActivity, terminalView);
                terminalView.requestFocus();
                noShowKeyboard = true;
                // Required to keep keyboard hidden on app startup. Use the self-clearing
                // variant so a no-op focus change cannot leave the latch stuck forever.
                ignoreOnceSoftKeyboardOnFocus();
            }
        }

        // Do not force show soft keyboard if termux-reload-settings command was run with hardware keyboard
        // or soft keyboard is to be hidden or is disabled
        if (!isReloadTermuxProperties && !noShowKeyboard) {
            // Request focus for TerminalView.
            // On a resume/recreate this requestFocus() may fire the per-page focus listener, but
            // onResume() raises mRestoringKeyboard BEFORE calling this method, so the listener
            // early-returns and no +500ms show is scheduled here.
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard");
            boolean restoreFromState = !mActivity.isOnResumeAfterOnCreate() || mActivity.isActivityRecreated();
            boolean kbIntent = mActivity.getTextInputState().isSoftKeyboardVisibleIntent();
            if (com.termux.app.terminal.io.KBTrace.ENABLED) com.termux.app.terminal.io.KBTrace.i("setSoftKeyboardState: requestFocus"
                    + " startup=" + isStartup + " reload=" + isReloadTermuxProperties
                    + " restoreFromState=" + restoreFromState + " kbIntent=" + kbIntent
                    + " restoringKb=" + mActivity.isRestoringKeyboard()
                    + " switchInProg=" + mActivity.isTerminalPageSwitchInProgress());
            terminalView.requestFocus();
            // On resume-after-background / recreate the keyboard RESTORE path (runKeyboardRestore)
            // is the single authority for the IME; do not schedule any show here. When the
            // persisted intent is hidden, also cancel a stale pending show so a stray
            // requestFocus-triggered runnable cannot pop the keyboard after the restore hides it.
            // Cold start (first resume after onCreate) keeps the historical always-show behaviour
            // (also covers opening a URL via the "Select URL" long press and returning: #2111).
            if (restoreFromState) {
                if (!kbIntent) {
                    terminalView.removeCallbacks(getShowSoftKeyboardRunnable());
                }
            } else {
                terminalView.postDelayed(getShowSoftKeyboardRunnable(), 300);
            }
        }
    }

    /**
     * Attach the focus-change listener that drives the soft keyboard to a specific {@link TerminalView}.
     * Called by the pager adapter for each page when it is (re)bound, since the shared active view
     * may be null during early lifecycle (onResume before the first page is selected).
     */
    public void registerTerminalViewFocusListener(@NonNull TerminalView terminalView) {
        terminalView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (com.termux.app.terminal.io.KBTrace.ENABLED) com.termux.app.terminal.io.KBTrace.i("focusChange " + (hasFocus ? "GAIN" : "LOSS")
                        + " view=" + (view == mActivity.getTerminalView() ? "activeTerm" : "page")
                        + " restoringKb=" + mActivity.isRestoringKeyboard()
                        + " switchInProg=" + mActivity.isTerminalPageSwitchInProgress()
                        + " ignoreOnce=" + mShowSoftKeyboardIgnoreOnce);
                // During a page switch OR a keyboard restore, the shared IME and the panel/focus
                // bookkeeping are owned by onTerminalPageSelected() / runKeyboardRestore().
                // Suppress ALL churn here and return BEFORE any side effect: the old page losing
                // focus mid-switch, or the restore's requestFocus(), must not pop the keyboard,
                // close the panel, or clobber the per-session focus flag. This kills both the
                // +500ms show leak and the panel/focus clobber on resume. (Fixes #InputPanel6,
                // keeps #InputPanel5 intact.)
                if (mActivity.isTerminalPageSwitchInProgress() || mActivity.isRestoringKeyboard()) {
                    Logger.logVerbose(LOG_TAG, "Suppressing soft keyboard churn: switch/restore in progress");
                    return;
                }

                // Force show soft keyboard if TerminalView or toolbar text input view has
                // focus and close it if they don't
                boolean textInputViewHasFocus = false;
                final EditText textInputView =  mActivity.findViewById(R.id.terminal_toolbar_text_input);
                if (textInputView != null) textInputViewHasFocus = textInputView.hasFocus();

                if (hasFocus || textInputViewHasFocus) {
                    if (mShowSoftKeyboardIgnoreOnce) {
                        mShowSoftKeyboardIgnoreOnce = false;
                        removeIgnoreOnceAutoClear();
                        return;
                    }
                    // Terminal got focus (not the panel): remember input goes to terminal.
                    if (hasFocus && !textInputViewHasFocus) {
                        mActivity.setFocusOnInputForCurrentSession(false);
                        // When the input panel is open and focus moves to the terminal
                        // (e.g. a tap or a long-press to start text selection), switch to
                        // the extra keys panel. This is driven by the user interacting with
                        // the terminal, independent of the "Action on send" preference
                        // (which only governs what happens right when text is sent).
                        View container = mActivity.findViewById(
                                com.termux.R.id.terminal_toolbar_text_input_container);
                        if (container != null
                                && container.getVisibility() == View.VISIBLE) {
                            mActivity.setTextInputVisible(false);
                            mActivity.updateToggleTextInputButtonIcon();
                        }
                    }
                    Logger.logVerbose(LOG_TAG, "Showing soft keyboard on focus change");
                } else {
                    Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change");
                }

                boolean showKeyboard = hasFocus || textInputViewHasFocus;
                if (!showKeyboard && terminalView != mActivity.getTerminalView()) {
                    // Fallback guard for the non-switching case (e.g. a detached/recycled page
                    // losing focus outside a tracked switch): skip the hide when the losing view
                    // is no longer the activity's active page.
                    Logger.logVerbose(LOG_TAG, "Skipping soft keyboard hide on focus change: page no longer active (switched)");
                } else {
                    if (com.termux.app.terminal.io.KBTrace.ENABLED) com.termux.app.terminal.io.KBTrace.i("focusChange -> setSoftKeyboardVisibility show=" + showKeyboard);
                    KeyboardUtils.setSoftKeyboardVisibility(getShowSoftKeyboardRunnable(), mActivity, terminalView, showKeyboard);
                }
            }
        });
    }

    private Runnable getShowSoftKeyboardRunnable() {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = () -> {
                if (com.termux.app.terminal.io.KBTrace.ENABLED) com.termux.app.terminal.io.KBTrace.i("showSoftKeyboardRunnable FIRES");
                TerminalView tv = mActivity.getTerminalView();
                if (tv != null) KeyboardUtils.showSoftKeyboard(mActivity, tv);
            };
        }
        return mShowSoftKeyboardRunnable;
    }

    /**
     * Dismiss the soft keyboard after text was sent from the input field.
     *
     * This is independent of whether the input panel itself stays open. When the
     * "Hide input panel after send" option is also enabled, focus hands off to the
     * terminal which (via the focus-change listener) schedules a delayed keyboard
     * show (~500ms). We cancel that pending show so our hide actually sticks, then
     * hide immediately. If the panel stays open (focus remains in the input field)
     * no show was scheduled, so we just hide right away.
     */
    public void hideSoftKeyboardAfterSend() {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;
        terminalView.removeCallbacks(getShowSoftKeyboardRunnable());
        KeyboardUtils.hideSoftKeyboard(mActivity, terminalView);
    }



    public void setTerminalCursorBlinkerState(boolean start) {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return; // page not selected yet; onEmulatorSet() will set it later
        if (start && mActivity.getProperties().getTerminalCursorBlinkEnabled()) {
            // If set/update the cursor blinking rate is successful, then enable cursor blinker
            if (terminalView.setTerminalCursorBlinkerRate(mActivity.getProperties().getTerminalCursorBlinkRate()))
                terminalView.setTerminalCursorBlinkerState(true, true);
            else
                Logger.logError(LOG_TAG,"Failed to start cursor blinker");
        } else {
            // Disable cursor blinker
            terminalView.setTerminalCursorBlinkerState(false, true);
        }
    }



    public void shareSessionTranscript() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        String transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        if (transcriptText == null) return;

        // See https://github.com/termux/termux-app/issues/1166.
        transcriptText = DataUtils.getTruncatedCommandOutput(transcriptText, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, false, true, false).trim();
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_transcript),
            transcriptText, mActivity.getString(R.string.title_share_transcript_with));
    }

    public void shareSelectedText() {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;
        String selectedText = terminalView.getStoredSelectedText();
        if (DataUtils.isNullOrEmpty(selectedText)) return;
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_selected_text),
            selectedText, mActivity.getString(R.string.title_share_selected_text_with));
    }

    public void showUrlSelection() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        String text = ShellUtils.getTerminalSessionTranscriptText(session, true, true);

        LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(text);
        if (urlSet.isEmpty()) {
            AlertDialog noneDialog = new AlertDialog.Builder(mActivity).setMessage(R.string.title_select_url_none_found).create();
            noneDialog.show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(mActivity).setItems(urls, (di, which) -> {
            String url = (String) urls[which];
            ShareUtils.copyTextToClipboard(mActivity, url, mActivity.getString(R.string.msg_select_url_copied_to_clipboard));
        }).setTitle(R.string.title_select_url_dialog).create();

        // Long press to open URL:
        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
            if (lv != null) {
                lv.setOnItemLongClickListener((parent, view, position, id) -> {
                    dialog.dismiss();
                    String url = (String) urls[position];
                    ShareUtils.openUrl(mActivity, url);
                    return true;
                });
            }
        });

        dialog.show();
    }

    public void reportIssueFromTranscript() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;

        final String transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
        if (transcriptText == null) return;

        MessageDialogUtils.showMessage(mActivity, mActivity.getString(R.string.report_issue_title),
            mActivity.getString(R.string.msg_add_termux_debug_info),
            mActivity.getString(com.termux.shared.R.string.action_yes), (dialog, which) -> reportIssueFromTranscript(transcriptText, true),
            mActivity.getString(com.termux.shared.R.string.action_no), (dialog, which) -> reportIssueFromTranscript(transcriptText, false),
            null);
    }

    private void reportIssueFromTranscript(String transcriptText, boolean addTermuxDebugInfo) {
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_generating_report), true);

        new Thread() {
            @Override
            public void run() {
                StringBuilder reportString = new StringBuilder();

                String title = mActivity.getString(R.string.report_issue_title);

                reportString.append(mActivity.getString(R.string.report_issue_transcript_header));
                reportString.append("\n").append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true));
                reportString.append(mActivity.getString(R.string.report_issue_transcript_footer));

                if (addTermuxDebugInfo) {
                    reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                } else {
                    reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_PACKAGE));
                }

                reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(mActivity, true));

                if (TermuxBootstrap.isAppPackageManagerAPT()) {
                    String termuxAptInfo = TermuxUtils.geAPTInfoMarkdownString(mActivity);
                    if (termuxAptInfo != null)
                        reportString.append("\n\n").append(termuxAptInfo);
                }

                if (addTermuxDebugInfo) {
                    String termuxDebugInfo = TermuxUtils.getTermuxDebugMarkdownString(mActivity);
                    if (termuxDebugInfo != null)
                        reportString.append("\n\n").append(termuxDebugInfo);
                }

                String userActionName = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName();

                ReportInfo reportInfo = new ReportInfo(userActionName,
                    TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, title);
                reportInfo.setReportString(reportString.toString());
                reportInfo.setReportStringSuffix("\n\n" + TermuxUtils.getReportIssueMarkdownString(mActivity));
                reportInfo.setReportSaveFileLabelAndPath(userActionName,
                    Environment.getExternalStorageDirectory() + "/" +
                        FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                ReportActivity.startReportActivity(mActivity, reportInfo);
            }
        }.start();
    }

    public void doPaste() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (!session.isRunning()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        if (text != null)
            session.getEmulator().paste(text);
    }

}
