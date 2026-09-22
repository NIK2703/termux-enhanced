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
     * Set on a true cold start (first {@link TermuxActivity#onResume()} after {@code onCreate}) when
     * the active {@link TerminalView} is not ready yet. Consumed by
     * {@link #applyStartupSoftKeyboardState()} once the first pager page is bound and
     * {@link TermuxActivity#getTerminalView()} is non-null.
     *
     * <p>Why this exists: {@link #onResume()} calls {@link #setSoftKeyboardState(boolean, boolean)},
     * which early-returns while {@code getTerminalView()} is still null (the ViewPager2 has not bound
     * and selected page 0 yet on a cold start). That dropped the "hide soft keyboard on startup"
     * preference entirely, so the keyboard popped up on launch even though the user enabled it.
     * {@link TermuxActivity#consumePendingKeyboardRestoreIfReady()} is the single "page is live" hook
     * (first fired after page 0 is bound), so it re-runs the hide with a real view.
     */
    private boolean mStartupSoftKeyboardPending;

    /**
     * Set on a bubble window's cold start: this window must not pull up the IME on its own.
     *
     * <p>A bubble is not a user launch of the app — it appears either because the user just left
     * (the automatic option) or because they tapped "Open in bubble" in the notification. A keyboard
     * that pops up there lands on top of whatever app the user switched to, which is the regression
     * this prevents. The bubble is a cold start of the very same activity class, so without this it
     * runs the historical cold-start behaviour ("show the keyboard") even though the keyboard was
     * hidden when the user left.
     *
     * <p>This is only half the fix. Suppressing the shows <em>we</em> request is not enough on its
     * own: a fresh window that gains focus with a focused editor and a {@code STATE_UNSPECIFIED}
     * soft-input mode gets the IME from the <em>platform</em>, with {@code mShowExplicitlyRequested}
     * false. {@link #onResume()} therefore also pre-empts that with
     * {@code SOFT_INPUT_STATE_ALWAYS_HIDDEN | SOFT_INPUT_ADJUST_RESIZE}. Both bits are needed there —
     * see {@link KeyboardUtils#setSoftKeyboardAlwaysHiddenAndAdjustResize} — and neither step is
     * redundant: measured, each one alone left the keyboard up.
     *
     * <p>Lifetime: raised in {@link #onResume()} when this resume <em>is</em> the cold start, and
     * dropped on the next resume (the user coming back to an already-created bubble is a normal
     * window) or earlier by the first user interaction ({@link #endBubbleStartupImeSuppression()},
     * which is deliberately not {@link #cancelStartupSoftKeyboardReassert()} — that one is also
     * called from {@code onPause()} and from the session-churn path). Deliberately NOT dropped by
     * {@link #applyStartupSoftKeyboardState()} — the "first page is live" hook fires while the pager
     * is still settling, and the startup page selection runs the per-session reconcile <em>more than
     * once</em> (the pager re-selects page 0 every time the session list is re-notified while
     * sessions are being restored). Measured: dropping it at the first page-live let the second
     * reconcile open the keyboard anyway
     * ({@code showSoftInput requested by TermuxActivity.applyTextInputVisibilityForSession:4621}).
     */
    private boolean mBubbleStartupImeSuppressed;

    /**
     * Bounded re-assert of the cold-start hide. The startup sequence fires the per-session IME
     * reconcile more than once (the pager re-selects page 0 whenever the session list is
     * re-notified while sessions are still being restored), and each pass re-reads the keyboard
     * intent. Recording the intent as hidden fixes that, but a platform-driven IME show can still
     * land in the same window, so the hide is re-applied a few times and then stops.
     *
     * <p>Cancelled by the first real user interaction ({@link TermuxActivity#onUserInteraction()}),
     * so it can never close a keyboard the user just opened.
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
        // before that, getTerminalView() is still null — the per-page font/screen-on settings are
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
        // A bubble window's cold start must not pull up the IME at all — see
        // mBubbleStartupImeSuppressed. Raised here because onResume() is the first point at which the
        // window exists, and before setSoftKeyboardState()/the startup reconcile below could show it.
        // Cleared on a later resume: that one is the user coming back to an already-created bubble,
        // which is a normal window from then on.
        if (mActivity.isBubbleWindow()) {
            if (mActivity.isOnResumeAfterOnCreate() && !mActivity.isActivityRecreated()) {
                mBubbleStartupImeSuppressed = true;
                // Suppressing our own shows is not enough: the bubble is a fresh window that gains
                // focus with a focused TerminalView, and while the window's soft-input state is
                // STATE_UNSPECIFIED the PLATFORM shows the IME on its own. Measured: with every show
                // of ours suppressed the IME was still up with mShowExplicitlyRequested=false, i.e.
                // nothing of ours had asked for it. This is the same pre-emption the main window's
                // "hide keyboard on startup" path and runKeyboardRestore()'s hide case already do.
                // ADJUST_RESIZE is OR'd in deliberately — see the helper's javadoc.
                KeyboardUtils.setSoftKeyboardAlwaysHiddenAndAdjustResize(mActivity);
                Logger.logInfo(LOG_TAG, "Bubble cold start: suppressing automatic soft keyboard shows");
            } else {
                mBubbleStartupImeSuppressed = false;
            }
        }

        // On a true cold start with the "hide soft keyboard on startup" preference in effect, keep
        // the keyboard hidden for the whole startup window. Two things must be neutralised, both of
        // which otherwise pop the IME on launch:
        //   1. setSoftKeyboardState() below early-returns while the ViewPager2 has not bound and
        //      selected page 0 yet (getTerminalView() == null), so the hide is never applied.
        //   2. The startup per-session IME reconcile (applyTextInputVisibilityForSession) shows the
        //      keyboard via SoftKeyboardRestore.showWithRetry because a fresh session's default
        //      keyboard intent is "visible" (SessionUiStateStore) — and it runs AFTER the hide.
        // mStartupSoftKeyboardPending gates (2); consumePendingKeyboardRestoreIfReady() (the single
        // "page is live" hook) applies the hide for (1) and clears the gate.
        if (mActivity.isOnResumeAfterOnCreate() && !mActivity.isActivityRecreated()
                && !KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
                        mActivity.getPreferences().isSoftKeyboardEnabled(),
                        mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware())
                && mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
            mStartupSoftKeyboardPending = true;
            // Pre-set the window flag now (view-independent): it stops the platform's IME auto-show
            // on window-focus-gain (~30ms after resume) before page 0 is even bound, so there is no
            // keyboard flash. The actual hideSoftKeyboard() is applied once the TerminalView exists.
            KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);
            // Record the INTENT as hidden — this is the part that actually makes the preference
            // stick. The startup page selection runs the per-session IME reconcile more than once
            // (the pager re-selects page 0 every time the session list is re-notified while
            // sessions are still being restored), and each pass reads the keyboard intent fresh.
            // A restored session has no per-session record, so it falls back to this global one,
            // which defaults to "visible" — meaning every reconcile AFTER the hide re-showed the
            // keyboard (SoftKeyboardRestore.showWithRetry) and the preference appeared broken.
            // Writing "hidden" here makes every later pass agree with the hide instead of fighting
            // it. A real user action (tap, keyboard toggle) overwrites it again on the spot.
            mActivity.getTextInputState().setSoftKeyboardVisibleIntent(false);
        }

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
     * Should be called when {@link com.termux.view.TerminalView#mEmulator} is set on
     * {@code terminalView}.
     *
     * <p><b>The source view matters.</b> This client is shared by every page of the session pager
     * ({@code TerminalPagerAdapter} installs the same instance on each page), so this callback fires
     * for a background page bind just as it does for the active page. Anything that means "the
     * emulator that just appeared" must therefore be applied to {@code terminalView} and never to
     * {@link TermuxActivity#getTerminalView()}: acting on the latter made a bind of a
     * <em>neighbouring</em> page reset the active session's {@code autoScrollDisabled} flag, which
     * discards "the user scrolled up" — and the very next chunk of output then took the
     * {@code mTopRow = 0} branch in {@code TerminalView.onScreenUpdated()} and snapped the viewport
     * back to the bottom.
     *
     * <p>The preferences are read from the activity's cached instance rather than built here:
     * {@code TermuxAppSharedPreferences.build(...)} resolves a package context
     * ({@code createPackageContext} — an IPC plus a fresh {@code Resources}), and this method runs on
     * every page bind, including the placeholder re-arm that lands inside the settle of the swipe
     * that just committed a tab.
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
        // Still addressed to the ACTIVE view, unlike the auto-scroll write above: the margin belongs
        // to the one page the user is looking at, and this callback is merely the moment that page's
        // scrollbar state becomes knowable. The runnable is a field, so a bind allocates nothing.
        final TerminalView active = mActivity.getTerminalView();
        if (active != null) {
            active.post(mUpdateFloatingButtonMargin);
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
        // Resolve the view the IME must be shown for. The activity's cached active view is tried
        // first (it is what the rest of the app routes to), but a tap must never depend on it being
        // non-null: after a jump of two or more tabs it is null until the destination page is
        // attached, and KeyboardUtils.showSoftKeyboard(context, null) is a silent no-op — which is
        // the reported "tapping the terminal does not open the keyboard". TerminalView.onSingleTapUp()
        // requests focus for the view that was tapped before calling this, so the focused view is
        // the correct fallback.
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
                // A tap is an explicit show request, so clear a leftover SOFT_INPUT_STATE_ALWAYS_HIDDEN
                // first: it is left behind by the cold-start "hide keyboard on startup" policy and by
                // the bubble's cold-start suppression, and while it is in place the window state works
                // against the show. Same precaution the KEYBOARD toggle and the per-session reconcile
                // already take before showing. setSoftInputModeAdjustResize also keeps the window
                // resizing for the keyboard (the ALWAYS_HIDDEN bit alone replaces ADJUST_RESIZE).
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
        // Checked before the virtual keys and before the built-in Ctrl+Alt shortcuts below: an
        // explicitly configured combination always wins. Keys that the picker can address by name
        // (F1-F12, arrows, ESC, TAB, ...) only ever arrive here — by the time they reach
        // inputCodePoint() they would already have been turned into an escape sequence.
        // The extra-keys modifiers are only peeked at, never spent, so that a key which is not a
        // binding still reaches the terminal with its one-shot modifier applied.
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
     * The extra-keys modifier state without spending it, for the session-shortcut matcher.
     *
     * <p>The Ctrl/Alt/Shift/Fn buttons are one-shot: reading one clears it, which is exactly what
     * the terminal key handling below wants — the modifier applies to the key being pressed and is
     * then gone. The matcher runs <em>before</em> that handling, so it must only look: a consuming
     * read here would leave the key to be typed without the modifier it was pressed with (Ctrl+C
     * would come out as a plain {@code c}), and on the text path it would see the modifier already
     * spent by {@link TerminalView#inputCodePoint}.
     *
     * <p>The virtual volume-key Ctrl counts as a held modifier, just as it does in
     * {@link #readControlKey()}; it is released by its key-up event, not by a read. The virtual
     * volume-key Fn is deliberately <em>not</em> one: it is a translation mode of the terminal
     * (letter → arrow/F-key), not the Fn button, and {@link #readFnKey()} does not report it
     * either — a {@code FN} binding means the extra-keys Fn button or a hardware Fn key.
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
     * nothing else will clear the button — a tapped Ctrl or Alt would stay lit and silently apply
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
        // settings is what actually happens — including over the Ctrl+Alt virtual-key mapping and
        // over Ctrl+J's "remove the finished session" shortcut, which is only a fallback now.
        //
        // The modifiers are the ones TerminalView resolved for this code point and must not be read
        // again here: the extra-keys buttons are one-shot and TerminalView has already spent them
        // by the time we are called, so a fresh read would always come back false. That is what
        // used to make every Alt combination dead on this path — the Alt was consumed by
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
     * combination the one added last wins — the same tie-break the settings screen shows last.
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

    /**
     * Set the terminal sessions shortcuts.
     */
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
     * Step the terminal font size up or down by one pinch step, then apply it everywhere.
     *
     * <p>Reached from the pinch gesture ({@link #onScale}) and from the Ctrl+Alt+{@code +}/{@code -}
     * hardware keyboard shortcut ({@link #onKeyDown}). The application goes through the session
     * client so that <em>every</em> page the pager keeps bound gets the new size, not just the
     * active one: the neighbours are already bound and are not rebound when they come back on
     * screen ({@code setOffscreenPageLimit(1)}), so they would otherwise keep the old size until
     * recycled. The Display settings slider writes the same preference and takes the same path —
     * see {@code TermuxTerminalSessionActivityClient.applyTerminalFontSizeToAllViews()}.
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
                if (mShowSoftKeyboardOnTogglePending) {
                    mShowSoftKeyboardOnTogglePending = false;
                    // We may have just come back from another app and the window is not focused
                    // yet, so a single immediate show can be silently ignored. Ask again while the
                    // IME is still down instead of parking a blind 500 ms timer (see #2112).
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
                mShowSoftKeyboardOnTogglePending = true;
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
                // No focus-triggered show to swallow any more: the focus listener does not schedule
                // shows (see registerTerminalViewFocusListener), and the view-independent
                // ALWAYS_HIDDEN flag above keeps the platform from auto-showing the IME here.
                // Record the intent as hidden so any later startup reconcile agrees with the
                // hide instead of restoring the default "visible" intent (see onResume()).
                mActivity.getTextInputState().setSoftKeyboardVisibleIntent(false);
            }
        }

        // Do not force show soft keyboard if termux-reload-settings command was run with hardware keyboard
        // or soft keyboard is to be hidden or is disabled.
        //
        // The bubble window is a third case, and it is not "the user opened the app": it is a second
        // window that shows up either because the user just left the app (the automatic option) or
        // because they tapped "Open in bubble" in the notification. Popping the IME there would drop
        // a keyboard on top of whatever app the user switched to. The bubble is a cold start of this
        // very activity class, so without this guard it runs the historical cold-start behaviour
        // ("always show") even though the keyboard was hidden when the user left — that was the
        // measured regression (mInputShown false before HOME, true after).
        //
        // Skipping the show disables nothing: a tap on the terminal ({@link #onSingleTapUp}) and the
        // KEYBOARD toggle ({@link #onToggleSoftKeyboardRequest}) both request the IME explicitly and
        // do not go through this method.
        if (mActivity.isBubbleWindow()) {
            Logger.logInfo(LOG_TAG, "Not showing soft keyboard: bubble window");
        } else if (!isReloadTermuxProperties && !noShowKeyboard) {
            // Request focus for TerminalView. On a resume/recreate this requestFocus() may fire the
            // per-page focus listener, but onResume() raises mRestoringKeyboard BEFORE calling this
            // method, so the listener early-returns and nothing happens here.
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard");
            boolean restoreFromState = !mActivity.isOnResumeAfterOnCreate() || mActivity.isActivityRecreated();
            terminalView.requestFocus();
            // On resume-after-background / recreate the keyboard RESTORE path (runKeyboardRestore)
            // is the single authority for the IME — no show is requested here.
            // Cold start (first resume after onCreate) keeps the historical always-show behaviour
            // (also covers opening a URL via the "Select URL" long press and returning: #2111).
            // The show is asked for immediately and re-asked only while the IME is genuinely still
            // down (the window may not be focused yet on a cold start): no blind delay.
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
     * <p>See {@link #onResume()} for why this is needed: {@link #setSoftKeyboardState(boolean, boolean)}
     * early-returns when the active {@link TerminalView} is not ready yet, which dropped the hide on
     * launch. Called from {@link TermuxActivity#consumePendingKeyboardRestoreIfReady()} — the single
     * "page is live" hook — exactly once per cold start (guarded by {@link #mStartupSoftKeyboardPending}).
     * The window flag {@code SOFT_INPUT_STATE_ALWAYS_HIDDEN} is already pre-set in {@link #onResume()}
     * to suppress the platform's IME auto-show before the view exists, so this only needs to perform
     * the actual {@code hideSoftKeyboard()} + focus + ignore-once once the view is real.
     */
    public void applyStartupSoftKeyboardState() {
        if (!mStartupSoftKeyboardPending) return;

        // Keep the flag up while the view is missing: the "page is live" hook fires again once the
        // destination page is actually attached (see SessionPagerManager.onTerminalPageSelected).
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;
        mStartupSoftKeyboardPending = false;

        // Keyboard fully disabled for Termux: nothing to hide (the disable path owns it).
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
                mActivity.getPreferences().isSoftKeyboardEnabled(),
                mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware()))
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
        // No focus-triggered show to swallow and no queued show to drop: nothing schedules IME
        // shows any more (see registerTerminalViewFocusListener), and the ALWAYS_HIDDEN window
        // flag above keeps the platform from auto-showing one here.

        mActivity.getTextInputState().setSoftKeyboardVisibleIntent(false);
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null)
            mActivity.getTextInputState().setSoftKeyboardIntent(session, false);
    }

    /** Bounded re-assert of {@link #performStartupSoftKeyboardHide()}. */
    private void reassertStartupSoftKeyboardHide() {
        if (mStartupHideReassertsLeft <= 0) return;
        if (!mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) return;
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
                mActivity.getPreferences().isSoftKeyboardEnabled(),
                mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware())) return;

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
     * per-session IME reconcile from popping the keyboard: a freshly created session has no recorded
     * keyboard intent, so {@code isSoftKeyboardIntent()} falls back to the global default of
     * {@code true} and the reconcile would call {@code SoftKeyboardRestore.showWithRetry} — which
     * runs AFTER the startup hide and wins. The flag is raised in {@link #onResume()} and cleared by
     * {@link #applyStartupSoftKeyboardState()} once the startup page selection is done, so only the
     * startup reconcile is affected and later user-driven shows keep working.
     *
     * <p>A bubble cold start reports {@code true} here too ({@link #mBubbleStartupImeSuppressed}):
     * the bubble has no recorded keyboard intent of its own either, so the very same reconcile would
     * pop the IME into the freshly opened bubble. It stays true for the whole cold start rather than
     * only until the first "page is live", because the pager runs the reconcile more than once while
     * the sessions are being restored.
     */
    public boolean isStartupSoftKeyboardHidePending() {
        return mStartupSoftKeyboardPending || mBubbleStartupImeSuppressed;
    }

    /**
     * End the bubble's cold-start IME suppression because the user has interacted with the window.
     *
     * <p>Kept separate from {@link #cancelStartupSoftKeyboardReassert()} on purpose, even though both
     * are triggered by the same user gesture: that one is also called from {@code onPause()} and from
     * the session-churn path, and neither of those means "the user is now driving this window". A
     * pause in particular is normal during a bubble's own collapse/expand churn, and clearing the
     * suppression there would re-open the keyboard a moment later.
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

                // This listener deliberately does NOT schedule a keyboard show. It used to answer a
                // focus change with a show delayed by 500 ms (KeyboardUtils.setSoftKeyboardVisibility,
                // now removed), i.e. a blind timer that could only be cancelled, never reasoned
                // about — and any "keep hidden" path that forgot the cancel left the keyboard
                // popping up after the user had already closed the input panel.
                // Every show in this app is now requested explicitly by the path that owns the
                // intent (panel open, tap on the terminal, tab-switch reconcile, resume restore,
                // KEYBOARD toggle), so a focus change only keeps the focus/panel bookkeeping in
                // sync and hides the IME when neither the terminal nor the input panel wants it.
                boolean textInputViewHasFocus = false;
                final EditText textInputView =  mActivity.findViewById(R.id.terminal_toolbar_text_input);
                if (textInputView != null) textInputViewHasFocus = textInputView.hasFocus();

                if (hasFocus || textInputViewHasFocus) {
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
                    Logger.logVerbose(LOG_TAG, "Focus moved to the terminal or the input panel");
                } else if (mActivity.isKbStateCreateInProgress()) {
                    // A tab is being created. Adding the session rebuilds the adapter, which detaches
                    // the served IME target and makes the page being replaced lose focus — a spurious
                    // focus change that must NOT hide the keyboard. The create inherits the live
                    // keyboard state on purpose (see TermuxActivity.isKbStateCreateInProgress and
                    // TermuxTerminalSessionActivityClient.createNewSession), so "the keyboard state
                    // must not change" means this hide has to be suppressed.
                    //
                    // This is the ONLY difference between the "+"-button path and the right-swipe
                    // path: the swipe commits the placeholder IN PLACE (no pager move), so no page
                    // loses focus and the hide never fired there; the button selects the new page via
                    // setCurrentSession(), which moves the pager and drops the old page's focus —
                    // measured: `FOCUS HIDE kb (active view lost focus) switchInProg=false create=true`
                    // followed by re-asserts whose target was the hidden panel EditText, so the
                    // keyboard stayed down on every "+"-created tab.
                    Logger.logVerbose(LOG_TAG, "Skipping soft keyboard hide: tab create in progress");
                } else if (terminalView != mActivity.getTerminalView()) {
                    // Fallback guard for the non-switching case (e.g. a detached/recycled page
                    // losing focus outside a tracked switch): skip the hide when the losing view
                    // is no longer the activity's active page.
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
