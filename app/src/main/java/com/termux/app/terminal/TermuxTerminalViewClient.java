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
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.KeyCombination;
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
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class TermuxTerminalViewClient extends TermuxTerminalViewClientBase {

    final TermuxActivity mActivity;

    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys. */
    boolean mVirtualControlKeyDown, mVirtualFnKeyDown;

    /**
     * {@link TermuxActivity#updateFloatingButtonMargin()}, posted from {@link #onEmulatorSet}.
     *
     * <p>A field rather than a fresh method reference per call: this runs on every page bind, and
     * the pager binds pages inside the settle of the swipe that opened the tab.
     */
    private final Runnable mUpdateFloatingButtonMargin;

    /**
     * Set when the soft keyboard was disabled for Termux by the time {@link #setSoftKeyboardState}
     * ran at startup: the next KEYBOARD toggle must (re)show the keyboard rather than just clear
     * the disable flags, because nothing requested a show while it was disabled (see #2112).
     */
    private boolean mShowSoftKeyboardOnTogglePending;

    /**
     * Cold-start "hide soft keyboard on startup" not yet applied: onResume() early-returns while
     * the pager has not bound page 0 ({@code getTerminalView()} null), which used to drop the
     * preference entirely (see #2112). Consumed by {@link #applyStartupSoftKeyboardState()} via
     * {@link TermuxActivity#consumePendingKeyboardRestoreIfReady()}, the single "page is live" hook.
     */
    private boolean mStartupSoftKeyboardPending;

    /**
     * Set on a bubble window's cold start: this window must not pull up the IME on its own.
     * A bubble is not a user launch, so a keyboard there would land on top of whatever app
     * the user switched to. Only half the fix: a fresh focused window with STATE_UNSPECIFIED
     * still gets the IME from the platform, so {@link #onResume()} also pre-empts with
     * {@code SOFT_INPUT_STATE_ALWAYS_HIDDEN | SOFT_INPUT_ADJUST_RESIZE} (measured: each
     * step alone left the keyboard up).
     *
     * <p>Raised in {@link #onResume()} on the cold start, dropped on the next resume or by
     * the first user interaction ({@link #endBubbleStartupImeSuppression()}). Deliberately
     * NOT dropped by {@link #applyStartupSoftKeyboardState()}: the page-live hook fires
     * while the pager settles and startup re-selects page 0 more than once while sessions
     * restore, and measured, dropping at the first hook let a later reconcile show the IME.
     */
    private boolean mBubbleStartupImeSuppressed;

    /**
     * Bounded re-assert of the cold-start hide: the per-session IME reconcile runs more than once
     * during startup and each pass re-reads the intent; a platform-driven show can also land in the
     * same window, so the hide is re-applied a few times and then stops. Cancelled by the first
     * real user interaction ({@link TermuxActivity#onUserInteraction()}), so it can never close a
     * keyboard the user just opened.
     */
    private static final long STARTUP_HIDE_REASSERT_DELAY_MS = 150;
    private static final int STARTUP_HIDE_REASSERT_MAX = 4;
    private final Runnable mStartupHideReassertRunnable = this::reassertStartupSoftKeyboardHide;
    private int mStartupHideReassertsLeft;

    private boolean mTerminalCursorBlinkerStateAlreadySet;

    private List<KeyboardShortcut> mSessionShortcuts;

    private static final String LOG_TAG = "TermuxTerminalViewClient";

    public TermuxTerminalViewClient(TermuxActivity activity, TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        this.mActivity = activity;
        this.mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;
        // Bound here, not in a field initializer: mActivity is a blank final, and a field
        // initializer runs before it is assigned (and may not reference it at all).
        this.mUpdateFloatingButtonMargin = activity::updateFloatingButtonMargin;
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
        // before that, getTerminalView() is still null РІР‚вЂќ the per-page font/screen-on settings are
        // applied per page in TerminalPagerAdapter.onBindViewHolder, so skipping here is safe.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) {
            terminalView.setTextSize(mActivity.getPreferences().getFontSize());
            terminalView.setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn());
        }
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // A bubble cold start must not pull up the IME РІР‚вЂќ see mBubbleStartupImeSuppressed. Raised
        // here because onResume() is the first point the window exists; cleared on a later resume
        // (returning to an existing bubble is a normal window).
        if (mActivity.isBubbleWindow()) {
            if (mActivity.isOnResumeAfterOnCreate() && !mActivity.isActivityRecreated()) {
                mBubbleStartupImeSuppressed = true;
                // Pre-empt the platform's own show: a fresh focused window with STATE_UNSPECIFIED
                // gets the IME even with all our shows suppressed (measured: up with
                // mShowExplicitlyRequested=false). Same pre-emption as the main window's startup
                // hide; ADJUST_RESIZE is OR'd in deliberately РІР‚вЂќ see the helper's javadoc.
                KeyboardUtils.setSoftKeyboardAlwaysHiddenAndAdjustResize(mActivity);
                Logger.logInfo(LOG_TAG, "Bubble cold start: suppressing automatic soft keyboard shows");
            } else {
                mBubbleStartupImeSuppressed = false;
            }
        }

        // Cold start + "hide soft keyboard on startup": defer the hide until the pager binds
        // page 0 (mStartupSoftKeyboardPending + consumePendingKeyboardRestoreIfReady), and
        // record the visible intent as hidden so the per-session IME reconcile that runs
        // AFTER the hide cannot re-show. A real user action (tap, toggle) overwrites it again.
        if (mActivity.isOnResumeAfterOnCreate() && !mActivity.isActivityRecreated()
                && !shouldSoftKeyboardBeDisabled()
                && mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
            mStartupSoftKeyboardPending = true;
            // Pre-set the window flag now (view-independent): stops the platform's IME auto-show on
            // window-focus-gain before page 0 binds, so there is no keyboard flash. The actual
            // hideSoftKeyboard() is applied once the TerminalView exists.
            KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);
            mActivity.getTextInputState().setSoftKeyboardVisibleIntent(false);
        }

        setSoftKeyboardState(true, mActivity.isActivityRecreated());

        mTerminalCursorBlinkerStateAlreadySet = false;

        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null && terminalView.mEmulator != null) {
            // Start cursor blinking if the emulator is already set; otherwise wait for
            // onEmulatorSet(). Needed because after resume via double-tap (not the power button)
            // onEmulatorSet() may not fire.
            setTerminalCursorBlinkerState(true);
            mTerminalCursorBlinkerStateAlreadySet = true;
        }
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
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
        setSoftKeyboardState(false, true);

        setTerminalCursorBlinkerState(true);
    }

    /**
     * Should be called when {@link com.termux.view.TerminalView#mEmulator} is set on
     * {@code terminalView}.
     *
     * <p><b>The source view matters.</b> This client is shared by every pager page
     * ({@code TerminalPagerAdapter} installs one instance on each), so this also fires for
     * background binds. Anything meaning "the emulator that just appeared" must apply to
     * {@code terminalView}, never to {@link TermuxActivity#getTerminalView()}: acting on the
     * latter let a neighbouring bind reset the active session's {@code autoScrollDisabled} РІР‚вЂќ
     * discarding "the user scrolled up" РІР‚вЂќ so the next output chunk took the
     * {@code mTopRow = 0} branch in {@code TerminalView.onScreenUpdated()} and snapped to bottom.
     *
     * <p>Preferences come from the activity's cached instance: building them resolves a package
     * context (IPC + fresh {@code Resources}) and this runs on every page bind, including the
     * placeholder re-arm inside the settle of the tab-committing swipe.
     */
    @Override
    public void onEmulatorSet(@NonNull TerminalView terminalView) {
        if (terminalView.mEmulator != null) {
            TermuxAppSharedPreferences prefs = mActivity.getPreferences();
            if (prefs == null) prefs = TermuxAppSharedPreferences.build(mActivity, true);
            if (prefs != null) {
                terminalView.mEmulator.setAutoScrollDisabled(!prefs.isScrollOnNewOutputEnabled());
            }
        }

        // The floating toggle button's right margin derives from the BOUND emulator's scrollback
        // (TermuxActivity.hasScrollbar), unknown until this callback: the pager binds a page before
        // it is measured, and mEmulator is only assigned by TerminalView.updateSize() once the size
        // is non-zero, so updateFloatingButtonMargin() refuses to run until then. Without this an
        // IDLE terminal never refreshed the margin after a recreate (its only other refresh source,
        // the OnScreenUpdateListener, fires only on output) and the button overlapped the scrollbar
        // thumb. Posted to stay out of the layout pass (updateSize() runs inside it); the settled
        // margin is a one-shot value so a frame's delay is fine, and the method early-returns while
        // the pager animates a page scroll. Still addressed to the ACTIVE view (unlike the write
        // above): the margin belongs to the page on screen; the field runnable allocates nothing.
        final TerminalView active = mActivity.getTerminalView();
        if (active != null) {
            active.post(mUpdateFloatingButtonMargin);
        }

        if (!mTerminalCursorBlinkerStateAlreadySet) {
            // Wait for the first session attach (onServiceConnected) and the last updateSize() that
            // sets mEmulator РІР‚вЂќ otherwise the blinker never restarts when TermuxActivity is reopened
            // after a double-back exit. See TerminalView.setTerminalCursorBlinkerState().
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
        // Resolve the view the IME must be shown for. The cached active view is tried first, but a
        // tap must not depend on it: after a jump of two+ tabs it is null until the destination page
        // attaches, and showSoftKeyboard(context, null) is a silent no-op РІР‚вЂќ the reported "tapping
        // the terminal does not open the keyboard". TerminalView.onSingleTapUp() focuses the tapped
        // view before this callback, so the focused view is the correct fallback.
        TerminalView terminalView = mActivity.getActiveTerminalView();
        if (terminalView == null && mActivity.getCurrentFocus() instanceof TerminalView) {
            terminalView = (TerminalView) mActivity.getCurrentFocus();
        }

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
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                // A tap is an explicit show request: clear a leftover SOFT_INPUT_STATE_ALWAYS_HIDDEN
                // (left by the cold-start hide / bubble suppression) first, or the window state works
                // against the show РІР‚вЂќ same precaution as the KEYBOARD toggle and per-session reconcile.
                // setSoftInputModeAdjustResize also keeps the window resizing (ALWAYS_HIDDEN alone
                // replaces ADJUST_RESIZE).
                KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
                KeyboardUtils.showSoftKeyboard(mActivity, terminalView);
            } else
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
        // Checked before the virtual keys and the built-in Ctrl+Alt shortcuts below: an explicitly
        // configured combination always wins. Keys the picker addresses by name (F1-F12, arrows,
        // ESC, TAB, ...) only arrive here РІР‚вЂќ by inputCodePoint() they are already escape sequences.
        // Extra-keys modifiers are peeked, never spent, so a non-binding key still reaches the
        // terminal with its one-shot modifier applied.
        if (handleSessionShortcut(keyTokenForEvent(keyCode, e),
                e.isCtrlPressed() || peekControlKey(), e.isAltPressed() || peekAltKey(),
                e.isShiftPressed() || peekShiftKey(), e.isFunctionPressed() || peekFnKey()))
            return true;

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
        return readExtraKeysSpecialButton(SpecialButton.CTRL, true) || mVirtualControlKeyDown;
    }

    @Override
    public boolean readAltKey() {
        return readExtraKeysSpecialButton(SpecialButton.ALT, true);
    }

    @Override
    public boolean readShiftKey() {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT, true);
    }

    @Override
    public boolean readFnKey() {
        return readExtraKeysSpecialButton(SpecialButton.FN, true);
    }

    public boolean readExtraKeysSpecialButton(SpecialButton specialButton, boolean consume) {
        if (mActivity.getExtraKeysView() == null) return false;
        Boolean state = mActivity.getExtraKeysView().readSpecialButton(specialButton, consume);
        if (state == null) {
            Logger.logError(LOG_TAG,"Failed to read an unregistered " + specialButton + " special button value from extra keys.");
            return false;
        }
        return state;
    }

    /**
     * Extra-keys modifier state without spending it, for the session-shortcut matcher.
     *
     * <p>The buttons are one-shot (reading clears) and the matcher runs before terminal key
     * handling: a consuming read would drop the modifier from the pressed key (Ctrl+C would
     * come out as plain {@code c}), and on the text path the modifier is already spent by
     * {@link TerminalView#inputCodePoint}.
     *
     * <p>Virtual volume-key Ctrl counts as held (released by its key-up); volume-key Fn does
     * not: it is the terminal's translation mode (letter to arrow/F-key), not the Fn button,
     * and {@link #readFnKey()} does not report it either, so a {@code FN} binding means the
     * extra-keys Fn button or a hardware Fn key.
     */
    private boolean peekControlKey() {
        return readExtraKeysSpecialButton(SpecialButton.CTRL, false) || mVirtualControlKeyDown;
    }

    private boolean peekAltKey() {
        return readExtraKeysSpecialButton(SpecialButton.ALT, false);
    }

    private boolean peekShiftKey() {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT, false);
    }

    private boolean peekFnKey() {
        return readExtraKeysSpecialButton(SpecialButton.FN, false);
    }

    /**
     * Spend the one-shot extra-keys modifiers a fired combination used.
     *
     * <p>The matcher only peeks at them, so a key that is not a binding still reaches the terminal
     * with its modifier intact. When the combination does fire, the key never gets that far and
     * nothing else will clear the button РІР‚вЂќ a tapped Ctrl or Alt would stay lit and silently apply
     * to the next key as well. Hardware modifiers and locked buttons have nothing to clear, so
     * these reads are no-ops for them.
     */
    private void consumeShortcutModifiers(boolean ctrl, boolean alt, boolean shift, boolean fn) {
        if (ctrl) readControlKey();
        if (alt) readAltKey();
        if (shift) readShiftKey();
        if (fn) readFnKey();
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onCodePoint(final int codePoint, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown,
                               TerminalSession session) {
        // A configured combination wins over everything below, so that what the user picked in the
        // settings is what actually happens РІР‚вЂќ including over the Ctrl+Alt virtual-key mapping and
        // over Ctrl+J's "remove the finished session" shortcut, which is only a fallback now.
        //
        // The modifiers are the ones TerminalView resolved for this code point and must not be read
        // again here: the extra-keys buttons are one-shot and TerminalView has already spent them
        // by the time we are called, so a fresh read would always come back false. That is what
        // used to make every Alt combination dead on this path РІР‚вЂќ the Alt was consumed by
        // inputCodePoint() before the matcher ever saw it.
        if (handleSessionShortcut(characterToken(codePoint), ctrlDown, altDown, shiftDown, fnDown))
            return true;

        if (mVirtualFnKeyDown) {
            int resultingKeyCode = -1;
            int resultingCodePoint = -1;
            // Whether the resulting code point is to be sent with an Alt (ESC) prefix, e.g. for the
            // readline Alt+B/Alt+F motions. Not the incoming modifier state of the same name.
            boolean altPrefix = false;
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
                    altPrefix = true;
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
                session.writeCodePoint(altPrefix, resultingCodePoint);
            }
            return true;
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session.isRunning()) {
                mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                return true;
            }
        }

        return false;
    }

    /**
     * Runs the session action bound to a pressed combination, if any.
     *
     * <p>Bindings are matched in reverse registration order, so when two of them share a
     * combination the one added last wins РІР‚вЂќ the same tie-break the settings screen shows last.
     *
     * <p>The caller passes the modifier set it resolved for the key; this method never reads the
     * extra-keys buttons itself, see {@link #peekControlKey()}. A fired combination spends the
     * one-shot modifiers it used, see {@link #consumeShortcutModifiers}.
     */
    private boolean handleSessionShortcut(@Nullable String key,
                                          boolean ctrl, boolean alt, boolean shift, boolean fn) {
        if (key == null) return false;

        List<KeyboardShortcut> shortcuts = mSessionShortcuts;
        if (shortcuts == null || shortcuts.isEmpty()) return false;

        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            KeyboardShortcut shortcut = shortcuts.get(i);
            if (!shortcut.matches(key, ctrl, alt, shift, fn)) continue;

            consumeShortcutModifiers(ctrl, alt, shift, fn);

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
        return false;
    }

    /**
     * The binding token for the key of a {@link KeyEvent}, or null when the key has no token.
     *
     * <p>Named keys are looked up first: the picker offers them by name, and a couple of them
     * (TAB, ENTER) would otherwise be resolved to their control character instead, so a binding
     * picked as {@code CTRL TAB} would never match. Anything else falls back to the unmodified
     * code point of the key, which is what a character binding stores.
     */
    @Nullable
    private static String keyTokenForEvent(int keyCode, @NonNull KeyEvent event) {
        String namedKey = KEY_CODES_FOR_TOKENS.get(keyCode);
        if (namedKey != null) return namedKey;

        int unicodeChar = event.getUnicodeChar(0);
        // 0 means the key produces nothing; negatives are combining-accent flags.
        if (unicodeChar <= 0 || !Character.isValidCodePoint(unicodeChar)) return null;
        return new String(Character.toChars(unicodeChar));
    }

    /** The binding token for a code point that arrived as text rather than as a key event. */
    @Nullable
    private static String characterToken(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) return null;
        return new String(Character.toChars(codePoint));
    }

    /** Reverse of {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS}. */
    private static final Map<Integer, String> KEY_CODES_FOR_TOKENS = new HashMap<>();
    static {
        for (Map.Entry<String, Integer> entry : ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.entrySet())
            KEY_CODES_FOR_TOKENS.put(entry.getValue(), entry.getKey());
    }

    private void setSessionShortcuts() {
        mSessionShortcuts = new ArrayList<>();

        // The {@link TermuxPropertyConstants#MAP_SESSION_SHORTCUTS} stores the session shortcut key and action pair
        for (Map.Entry<String, Integer> entry : TermuxPropertyConstants.MAP_SESSION_SHORTCUTS.entrySet()) {
            // The value is the parsed token list of the combination, empty when the shortcut is unset
            Object value = mActivity.getProperties().getInternalPropertyValue(entry.getKey(), true);
            if (!(value instanceof List)) continue;

            List<String> tokens = new ArrayList<>();
            for (Object token : (List<?>) value) {
                if (token != null) tokens.add(token.toString());
            }
            // A combination always carries at least one modifier and one key, see KeyCombination.
            if (KeyCombination.hasLeadingModifier(tokens) && KeyCombination.hasKey(tokens))
                mSessionShortcuts.add(new KeyboardShortcut(tokens, entry.getValue()));
        }
    }

    /**
     * Step the terminal font size up or down by one pinch step, then apply it everywhere. Reached
     * from the pinch gesture ({@link #onScale}) and the Ctrl+Alt+{@code +}/{@code -} shortcut
     * ({@link #onKeyDown}). Applied via the session client so every bound page gets the new size,
     * not just the active one: neighbours stay bound ({@code setOffscreenPageLimit(1)}) and are not
     * rebound when they return, so they would keep the old size until recycled. The Display settings
     * slider takes the same path РІР‚вЂќ see
     * {@code TermuxTerminalSessionActivityClient.applyTerminalFontSizeToAllViews()}.
     */
    public void changeFontSize(boolean increase) {
        mActivity.getPreferences().changeFontSize(increase);
        mTermuxTerminalSessionActivityClient.applyTerminalFontSizeToAllViews();
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
                // Show with a delay, otherwise the toggle won't show the keyboard after switching
                // back if it was previously disabled; also request focus, which setSoftKeyboardState
                // skipped at startup while disabled. #2112
                Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle");
                mActivity.getPreferences().setSoftKeyboardEnabled(true);
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                // Clear SOFT_INPUT_STATE_ALWAYS_HIDDEN (may be left by a resume-with-hidden-intent)
                // so the show below is not silently ignored.
                KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
                if (mShowSoftKeyboardOnTogglePending) {
                    mShowSoftKeyboardOnTogglePending = false;
                    // Just back from another app: the window may not be focused yet, so a single
                    // immediate show can be silently ignored. Ask again while the IME is still down
                    // instead of parking a blind 500 ms timer (see #2112).
                    terminalView.requestFocus();
                    com.termux.app.terminal.io.SoftKeyboardRestore.showWithRetry(terminalView,
                            mActivity::computeImeVisibility);
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
                // builds; a user toggle is an explicit intent РІР‚вЂќ restore the normal mode first.
                KeyboardUtils.setSoftInputModeAdjustResize(mActivity);
                KeyboardUtils.toggleSoftKeyboard(mActivity);
            }
        }
    }

    /** Whether the soft keyboard is disabled for Termux under the current preferences. */
    private boolean shouldSoftKeyboardBeDisabled() {
        return KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
                mActivity.getPreferences().isSoftKeyboardEnabled(),
                mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware());
    }

    public void setSoftKeyboardState(boolean isStartup, boolean isReloadTermuxProperties) {
        // The active view is owned by the ViewPager2 and may not be selected yet on a cold-start
        // onResume(). The per-page focus listener is attached in the adapter's onBindViewHolder
        // where the view is guaranteed non-null, so skip the view-bound work here РІР‚вЂќ it is applied
        // when the page is bound.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        boolean noShowKeyboard = false;

        // Focus is needed whether or not the keyboard will be shown: with a hardware keyboard,
        // typing without tapping the terminal first would add a colour tint as the focus highlight
        // (test with a light theme; defaultFocusHighlightEnabled is also false in TerminalView).
        // If soft keyboard is disabled by user for Termux (check function docs for Termux behaviour info)
        if (shouldSoftKeyboardBeDisabled()) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard");
            KeyboardUtils.disableSoftKeyboard(mActivity, terminalView);
            terminalView.requestFocus();
            noShowKeyboard = true;
            // Delay only needed when onCreate() runs (exit via double back), not when merely
            // switching back from another app and re-enabling via the toggle.
            if (isStartup && mActivity.isOnResumeAfterOnCreate())
                mShowSoftKeyboardOnTogglePending = true;
        } else {
            // Set flag to automatically push up TerminalView when keyboard is opened instead of showing over it
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity);

            // Clear any previous flags to disable soft keyboard in case setting updated.
            // Guarded: Window.clearFlags() dispatches window attributes even when the bit is
            // already clear, and this runs on every single resume.
            if (KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity))
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);

            // Hide-on-startup applies ONLY to a true cold start (first resume after onCreate, not a
            // recreate): on resume-from-background / recreate the keyboard-restore path
            // (runKeyboardRestore) is the single authority and honours the persisted intent. Forcing
            // SOFT_INPUT_STATE_ALWAYS_HIDDEN here on every resume would fight that restore (the
            // "hidden on startup vs showWithRetry" conflict).
            if (isStartup && mActivity.isOnResumeAfterOnCreate() && !mActivity.isActivityRecreated()
                && mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup");
                // Required to keep keyboard hidden at app startup while the window gains focus
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);

                KeyboardUtils.hideSoftKeyboard(mActivity, terminalView);
                terminalView.requestFocus();
                noShowKeyboard = true;
                // Nothing schedules IME shows now (see registerTerminalViewFocusListener) and the
                // ALWAYS_HIDDEN flag above stops the platform auto-showing one. Record the intent as
                // hidden so later startup reconciles agree with the hide (see onResume()).
                mActivity.getTextInputState().setSoftKeyboardVisibleIntent(false);
            }
        }

        // Do not force show soft keyboard if termux-reload-settings ran with a hardware keyboard,
        // or the soft keyboard is to be hidden/disabled.
        //
        // Bubble window: not "the user opened the app" but a second window appearing because the
        // user just left or tapped "Open in bubble"; popping the IME there would drop a keyboard
        // over whatever app they switched to (measured: hidden before HOME, shown after). Skipping
        // the show disables nothing: a terminal tap ({@link #onSingleTapUp}) and the KEYBOARD
        // toggle ({@link #onToggleSoftKeyboardRequest}) still request the IME explicitly.
        if (mActivity.isBubbleWindow()) {
            Logger.logInfo(LOG_TAG, "Not showing soft keyboard: bubble window");
        } else if (!isReloadTermuxProperties && !noShowKeyboard) {
            // On resume/recreate this requestFocus() may fire the per-page focus listener, but
            // onResume() raises mRestoringKeyboard BEFORE this method, so the listener early-returns.
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard");
            boolean restoreFromState = !mActivity.isOnResumeAfterOnCreate() || mActivity.isActivityRecreated();
            terminalView.requestFocus();
            // On resume-after-background / recreate the keyboard RESTORE path (runKeyboardRestore)
            // is the single authority РІР‚вЂќ no show is requested here. Cold start keeps the historical
            // always-show (also covers opening a URL via "Select URL" long press and returning:
            // #2111). Ask immediately and re-ask only while the IME is genuinely still down (the
            // window may not be focused yet on a cold start): no blind delay.
            if (!restoreFromState) {
                com.termux.app.terminal.io.SoftKeyboardRestore.showWithRetry(terminalView,
                        mActivity::computeImeVisibility);
            }
        }
    }

    /**
     * Applies the "hide soft keyboard on startup" preference for a true cold start, deferred until
     * the first pager page is bound (when {@link TermuxActivity#getTerminalView()} is non-null).
     *
     * <p>Called from {@link TermuxActivity#consumePendingKeyboardRestoreIfReady()}, the single
     * "page is live" hook, once per cold start (guarded by {@link #mStartupSoftKeyboardPending}).
     * The window flag is already pre-set in {@link #onResume()}, so this only does the actual
     * hide + focus + intent write once the view is real.
     */
    public void applyStartupSoftKeyboardState() {
        if (!mStartupSoftKeyboardPending) return;

        // Keep the flag up while the view is missing: the "page is live" hook fires again once the
        // destination page is actually attached (see SessionPagerManager.onTerminalPageSelected).
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;
        mStartupSoftKeyboardPending = false;

        // Keyboard fully disabled for Termux: nothing to hide (the disable path owns it).
        if (shouldSoftKeyboardBeDisabled())
            return;

        if (mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
            Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup (deferred to page live)");
            performStartupSoftKeyboardHide();

            // Re-assert a few times: the pager re-selects page 0 while sessions are still being
            // restored, and a platform-driven IME show can land inside that window too.
            mStartupHideReassertsLeft = STARTUP_HIDE_REASSERT_MAX;
            terminalView.removeCallbacks(mStartupHideReassertRunnable);
            terminalView.postDelayed(mStartupHideReassertRunnable,
                    STARTUP_HIDE_REASSERT_DELAY_MS);
        }
    }

    /**
     * One cold-start hide: window flag + {@code hideSoftKeyboard()} + focus, and the keyboard
     * intent recorded as hidden (see {@link #onResume()} for why the intent write is the part
     * that makes the preference stick across the repeated startup reconciles).
     */
    private void performStartupSoftKeyboardHide() {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        // Idempotent with the onResume() pre-set; re-assert in case a recreate/restore cleared it.
        KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);
        KeyboardUtils.hideSoftKeyboard(mActivity, terminalView);
        terminalView.requestFocus();
        // Nothing schedules IME shows any more (see registerTerminalViewFocusListener) and the
        // ALWAYS_HIDDEN window flag above stops the platform auto-showing one here.
        mActivity.getTextInputState().setSoftKeyboardVisibleIntent(false);
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null)
            mActivity.getTextInputState().setSoftKeyboardIntent(session, false);
    }

    /** Bounded re-assert of {@link #performStartupSoftKeyboardHide()}. */
    private void reassertStartupSoftKeyboardHide() {
        if (mStartupHideReassertsLeft <= 0) return;
        if (!mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) return;
        if (shouldSoftKeyboardBeDisabled()) return;

        performStartupSoftKeyboardHide();

        if (--mStartupHideReassertsLeft <= 0) return;
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null)
            terminalView.postDelayed(mStartupHideReassertRunnable,
                    STARTUP_HIDE_REASSERT_DELAY_MS);
    }

    /**
     * Abandon the cold-start hide re-assert, e.g. because the user just interacted with the app.
     * The keyboard state from that moment on is the user's, not the startup policy's.
     */
    public void cancelStartupSoftKeyboardReassert() {
        mStartupHideReassertsLeft = 0;
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null)
            terminalView.removeCallbacks(mStartupHideReassertRunnable);
    }

    /**
     * Whether a cold-start "hide soft keyboard on startup" is still pending/active.
     *
     * <p>Read by {@link TermuxActivity#applyTextInputVisibilityForSession} to stop the startup
     * per-session IME reconcile from popping the keyboard: a fresh session has no recorded intent,
     * so {@code isSoftKeyboardIntent()} falls back to the default {@code true} and the reconcile
     * (which runs AFTER the hide) would {@code SoftKeyboardRestore.showWithRetry}. Raised in
     * {@link #onResume()}, cleared by {@link #applyStartupSoftKeyboardState()} once the startup page
     * selection is done РІР‚вЂќ only the startup reconcile is affected; later user-driven shows work.
     *
     * <p>A bubble cold start reports {@code true} too ({@link #mBubbleStartupImeSuppressed}): the
     * bubble has no recorded intent either, and it stays true for the whole cold start rather than
     * only until the first "page is live", because the pager re-runs the reconcile while sessions
     * restore.
     */
    public boolean isStartupSoftKeyboardHidePending() {
        return mStartupSoftKeyboardPending || mBubbleStartupImeSuppressed;
    }

    /**
     * End the bubble's cold-start IME suppression because the user has interacted with the window.
     *
     * <p>Kept separate from {@link #cancelStartupSoftKeyboardReassert()} on purpose: that one also
     * runs from {@code onPause()} and the session-churn path, neither of which means "the user is
     * now driving this window". A pause is normal during a bubble's collapse/expand churn, and
     * clearing the suppression there would re-open the keyboard a moment later.
     */
    public void endBubbleStartupImeSuppression() {
        if (!mBubbleStartupImeSuppressed) return;
        mBubbleStartupImeSuppressed = false;
        Logger.logInfo(LOG_TAG, "Bubble cold start: IME suppression ended by user interaction");
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
                // During a page switch OR a keyboard restore, the shared IME and panel/focus
                // bookkeeping are owned by onTerminalPageSelected() / runKeyboardRestore(). Suppress
                // ALL churn here, BEFORE any side effect: the old page losing focus mid-switch, or
                // the restore's requestFocus(), must not pop the keyboard, close the panel, or
                // clobber the per-session focus flag РІР‚вЂќ kills the +500ms show leak and the
                // panel/focus clobber on resume. (Fixes #InputPanel6, keeps #InputPanel5 intact.)
                if (mActivity.isTerminalPageSwitchInProgress() || mActivity.isRestoringKeyboard()) {
                    Logger.logVerbose(LOG_TAG, "Suppressing soft keyboard churn: switch/restore in progress");
                    return;
                }

                // This listener deliberately does NOT schedule a keyboard show. It used to answer a
                // focus change with a blind 500 ms delayed show (removed) that any "keep hidden"
                // path could forget to cancel РІР‚вЂќ leaving the IME popping up after the user had
                // closed the panel. Every show is now requested explicitly by the path that owns the
                // intent (panel open, terminal tap, tab-switch reconcile, resume restore, KEYBOARD
                // toggle); a focus change only keeps focus/panel bookkeeping in sync and hides the
                // IME when neither the terminal nor the panel wants it.
                boolean textInputViewHasFocus = false;
                final EditText textInputView =  mActivity.findViewById(R.id.terminal_toolbar_text_input);
                if (textInputView != null) textInputViewHasFocus = textInputView.hasFocus();

                if (hasFocus || textInputViewHasFocus) {
                    // Terminal got focus (not the panel): remember input goes to terminal.
                    if (hasFocus && !textInputViewHasFocus) {
                        mActivity.setFocusOnInputForCurrentSession(false);
                        // Panel open + focus moves to the terminal (tap / long-press to select):
                        // switch to the extra keys panel. Driven by the user interacting with the
                        // terminal, independent of the "Action on send" preference (which only
                        // governs what happens when text is sent).
                        View container = mActivity.findViewById(
                                com.termux.R.id.terminal_toolbar_text_input_container);
                        if (container != null
                                && container.getVisibility() == View.VISIBLE) {
                            mActivity.setTextInputVisible(false);
                            mActivity.updateToggleTextInputButtonIcon();
                        }
                    }
                    Logger.logVerbose(LOG_TAG, "Focus moved to the terminal or the input panel");
                } else if (mActivity.isKbStateCreateInProgress()) {
                    // A tab is being created: rebuilding the adapter detaches the served IME target
                    // and makes the replaced page lose focus РІР‚вЂќ a spurious change that must NOT hide
                    // the keyboard (the create inherits the live keyboard state on purpose, see
                    // TermuxActivity.isKbStateCreateInProgress / createNewSession). This is the ONLY
                    // difference vs the right-swipe path: the swipe commits the placeholder IN PLACE
                    // (no pager move, no focus loss); the "+" button selects the new page via
                    // setCurrentSession(), which moves the pager and drops the old focus РІР‚вЂќ measured:
                    // `FOCUS HIDE kb ... switchInProg=false create=true` followed by re-asserts
                    // targeting the hidden panel EditText, so the keyboard stayed down on every
                    // "+"-created tab.
                    Logger.logVerbose(LOG_TAG, "Skipping soft keyboard hide: tab create in progress");
                } else if (terminalView != mActivity.getTerminalView()) {
                    // Fallback for the non-switching case (detached/recycled page losing focus
                    // outside a tracked switch): skip the hide when the losing view is no longer active.
                    Logger.logVerbose(LOG_TAG, "Skipping soft keyboard hide on focus change: page no longer active (switched)");
                } else {
                    Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change");
                    KeyboardUtils.hideSoftKeyboard(mActivity, terminalView);
                }
            }
        });
    }

    /**
     * Dismiss the soft keyboard after text was sent from the input field.
     *
     * <p>Independent of whether the input panel itself stays open: hiding is unconditional, and
     * nothing re-shows the IME behind our back (the focus listener does not schedule shows).
     */
    public void hideSoftKeyboardAfterSend() {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;
        KeyboardUtils.hideSoftKeyboard(mActivity, terminalView);
    }

    public void setTerminalCursorBlinkerState(boolean start) {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return; // page not selected yet; onEmulatorSet() will set it later
        if (start && mActivity.getProperties().getTerminalCursorBlinkEnabled()) {
            if (terminalView.setTerminalCursorBlinkerRate(mActivity.getProperties().getTerminalCursorBlinkRate()))
                terminalView.setTerminalCursorBlinkerState(true, true);
            else
                Logger.logError(LOG_TAG,"Failed to start cursor blinker");
        } else {
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
            ListView lv = dialog.getListView();
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
