package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.view.TerminalView;
import com.termux.shared.termux.terminal.io.BellHandler;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Properties;

/** The {@link TerminalSessionClient} implementation that may require an {@link Activity} for its interface methods. */
public class TermuxTerminalSessionActivityClient extends TermuxTerminalSessionClientBase {

    private final TermuxActivity mActivity;

    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    static final int MAX_SESSIONS = 8;

    /**
     * The session that was just created by a right-swipe-to-add gesture. The tab-strip's
     * right-end scroll must be deferred until this session's label is actually set (the shell
     * emits an OSC window title), so the scroll reveals the (+) button only once the new tab
     * shows its real title — never while it still reads the default placeholder. Cleared once
     * the end-scroll fires (via onTitleChanged or the fallback timer).
     */
    private TerminalSession mPendingEndScrollSession = null;
    /** Fallback runnable that scrolls to the end even if the shell never sets a title. */
    private java.lang.Runnable mEndScrollFallback;

    /**
     * One-shot coalescing flag for cosmetic tab-strip refreshes driven by OSC title changes.
     * A CLI animating its title (spinner/progress) fires OSC 0/2 at 10-30 Hz; the flag folds
     * every title event of a frame into a single posted refresh, so at most one refresh runs
     * per frame. The refresh reads the LATEST titles straight from the sessions, so the final
     * state is always applied and several sessions changing inside one frame are all covered.
     */
    private boolean mTitleRefreshPending = false;
    private final java.lang.Runnable mTitleRefreshRunnable = new java.lang.Runnable() {
        @Override public void run() { runTitleRefresh(); }
    };

    /** Last resolved session name handed to the extra-keys controller (hot-path guard). */
    private String mLastExtraKeysSessionName;

    /**
     * Key describing everything {@link #applyTerminalColorScheme} actually depends on:
     * night mode, the resolved colour-scheme file and the font file. When the key is unchanged the
     * (expensive) scheme application is skipped — see {@link #checkForFontAndColors()}.
     */
    private String mAppliedSchemeKey = null;

    /**
     * Key of the scheme currently reflected by the global {@link TerminalColors#COLOR_SCHEME}.
     * Tracked separately from {@link #mAppliedSchemeKey} because the per-page
     * {@link #checkForFontAndColorsForView} also needs the palette loaded, but must not be
     * mistaken for a full activity/panel application.
     */
    private String mLoadedColorSchemeKey = null;

    /** Cached terminal typeface — parsing the font file is expensive and it rarely changes. */
    private static Typeface sCachedTypeface = null;
    private static String sCachedTypefaceKey = null;

    /**
     * Armed in {@link #onStart()} and consumed by the FIRST
     * {@link #onSessionPageSelected(TerminalSession)} afterwards. Two one-shot effects ride on it:
     * a forced full scheme application after an activity recreate, and the single disk re-read of
     * the extra-keys session map that recovers profiles written while the activity was stopped.
     */
    private boolean mPendingPostStartRecovery = false;

    /**
     * Set only for the duration of the synchronous {@link #setCurrentSession} call made from
     * {@link #onStart()} when returning from the background. In that situation the stored session
     * is virtually always the page the pager is already on, so {@code setCurrentSession()} takes its
     * "same index" shortcut and runs {@link #onSessionPageSelected} INLINE — which would perform a
     * complete focus + IME reconcile (focus request, {@code computeImeVisibility()}, possible
     * show/hide) that {@code TermuxActivity.onResume()} then repeats authoritatively a few
     * milliseconds later via {@code runKeyboardRestore()}.
     * <p/>
     * Skipping the focus half here removes one full IME reconcile per foreground return (2
     * window-attribute dispatches + an InputMethodManager IPC + a 6-attempt layout wait) and, more
     * importantly, stops the early reconcile from acting on pre-resume insets — its
     * {@code computeImeVisibility()} runs before the window has settled, so it can show or hide the
     * keyboard on a stale reading and then fight the resume restore.
     * <p/>
     * The flag is deliberately scoped to that one call (not "first page selection after start"):
     * a cold start connects the service asynchronously, so the startup page selection happens
     * LATER, long after the flag is cleared, and must keep applying focus — that is the only
     * restore a cold start gets.
     */
    private boolean mDeferFocusApplyToResume = false;

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
        // Fallback runnable that scrolls to the end even if the shell never sets a title.
        this.mEndScrollFallback = () -> {
            if (mPendingEndScrollSession != null) {
                TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
                if (tabs != null) tabs.scrollStripToEnd();
                mPendingEndScrollSession = null;
            }
        };
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            // Scope the suppression to this call only — see mDeferFocusApplyToResume. Any page
            // selection triggered later (service connect, adapter fill) keeps applying focus.
            mDeferFocusApplyToResume = true;
            try {
                setCurrentSession(getCurrentStoredSessionOrLast());
            } finally {
                mDeferFocusApplyToResume = false;
            }
            termuxSessionListNotifyUpdated();
        }

        // Re-apply the terminal color scheme now that the session is (re)attached, but ONLY when
        // the activity was recreated (e.g. System Light<->Dark switch). On a recreate, the
        // mColors.reset() inside applyTerminalColorScheme() is skipped during onCreate() because
        // the session is still null, and the session is only (re)attached here -- so without this
        // the persisted terminal keeps its stale palette and only the panel/status-bar repaint.
        // We deliberately guard with isActivityRecreated() so a normal foreground-from-background
        // does NOT reset mColors, which would otherwise wipe shell-set OSC dynamic colors.
        if (mActivity.isActivityRecreated()) {
            // Force a full re-application after a recreate (the palette may be stale) and arm the
            // one-shot recovery for the first page selection below.
            mAppliedSchemeKey = null;
            checkForFontAndColors();
        }
        // Consumed by the first onSessionPageSelected(): one full scheme apply after a recreate
        // and one re-read of the extra-keys session map after returning to the foreground.
        mPendingPostStartRecovery = true;

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        TerminalView tv = mActivity.getTerminalView();
        if (tv != null) tv.onScreenUpdated();
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        //
        // Deferred by one message on purpose. SoundPool MUST be constructed on a Looper thread,
        // so it cannot move off the main thread, but it does not belong inside onResume(): it is
        // a warm-up whose only purpose is that the sound is ready before the user presses bell,
        // and it is rebuilt on every single foreground return. Posting it keeps the cost out of
        // the resume transaction while still finishing within a frame. The bell path itself still
        // loads lazily (see onBell), so nothing depends on this having run.
        mMainHandler.removeCallbacks(mLoadBellRunnable);
        mMainHandler.post(mLoadBellRunnable);
    }

    /** Warm-up task posted out of onResume(); see {@link #onResume()}. */
    private final Runnable mLoadBellRunnable = this::loadBellSoundPool;

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        // Drop any pending coalesced title refresh so it does not run while stopped.
        mMainHandler.removeCallbacks(mTitleRefreshRunnable);
        mTitleRefreshPending = false;

        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession();

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // An explicit styling reload must always really re-apply, even if the scheme files are
        // untouched (e.g. only a preference changed).
        invalidateAppliedScheme();
        // Set terminal fonts and colors
        checkForFontAndColors();
    }



    private void runIfVisible(java.lang.Runnable action) {
        if (!mActivity.isVisible()) return;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            // TerminalSession callbacks (onTextChanged / onTitleChanged) are delivered on the
            // terminal's output thread, not the UI thread. Running them there manipulated the
            // View hierarchy from a background thread (onTitleChanged → termuxSessionListNotifyUpdated
            // → updateTabs → addView → requestLayout), which is not thread-safe. Always marshal
            // onto the main looper.
            mMainHandler.post(action);
        }
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        runIfVisible(() -> {
            if (mActivity.getCurrentSession() == changedSession) {
                TerminalView tv = mActivity.getTerminalView();
                if (tv != null) tv.onScreenUpdated();
            }
        });
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        runIfVisible(() -> {
        // Toast suppressed — the user requested no popups on session events.

        // If this is the session we just added by a right-swipe, its label is now real — scroll
        // the tab strip to the right end (revealing the (+) button) ONLY now, after the label is
        // actually set. This fires exactly once per added tab — keep it fully synchronous so the
        // markPendingEndScrollSession/scrollStripToEnd contract and the 250 ms fallback behave
        // exactly as before.
        if (mPendingEndScrollSession == updatedSession) {
            mPendingEndScrollSession = null;
            mMainHandler.removeCallbacks(mEndScrollFallback);
            TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
            if (tabs != null) tabs.scrollStripToEnd();
            // Still refresh the tab list so the new title paints.
            termuxSessionListNotifyUpdated();
            return;
        }

        // Hot path (animated titles): never rebuild the tabs per event. Coalesce into a single
        // throttled COSMETIC refresh. The refresh reads the LATEST titles straight from the
        // sessions, so intermediate frames may be dropped but the final state never is, and
        // several sessions changing inside one window are all covered.
        scheduleTitleRefresh();
        });
    }

    /**
     * Coalesce the cosmetic title refresh: at most one posted execution per frame, with the
     * final change always scheduled (trailing edge).
     */
    private void scheduleTitleRefresh() {
        if (mTitleRefreshPending) return;
        mTitleRefreshPending = true;
        mMainHandler.post(mTitleRefreshRunnable);
    }

    /**
     * The coalesced title refresh. Cosmetic only: refreshes tab labels via a pure diff and
     * clamps the active tab into view. Deliberately does NOT run the structural
     * {@link #termuxSessionListNotifyUpdated()} — a title change cannot alter the session
     * list, so no pager resync, no session snapshot, no end-scroll bookkeeping. Skipping the
     * pager resync is also what kills the per-frame ViewPager2 rebuild that used to happen
     * while the trailing placeholder page was active (user on the last tab).
     */
    private void runTitleRefresh() {
        mTitleRefreshPending = false;
        if (mActivity.isFinishing()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) tabs.refreshTabAppearance(service.getTermuxSessions());
        // For unnamed sessions the (animated) title doubles as the session name for the
        // extra-keys profile match; the reloadMap=false variant never touches disk and is a
        // cheap no-op while the resolved name stays the same.
        TerminalSession current = mActivity.getCurrentSession();
        if (current != null) applySessionExtraKeys(current, false);
    }

    /**
     * Arm the right-end scroll for the session just created by a right-swipe-to-add gesture.
     * The actual scroll fires from {@link #onTitleChanged} once the shell sets the tab's real
     * title (so we never scroll to a default/"Terminal" label), with a short fallback timer in
     * case the shell never emits a title.
     */
    public void markPendingEndScrollSession(@NonNull TerminalSession session) {
        mPendingEndScrollSession = session;
        mMainHandler.removeCallbacks(mEndScrollFallback);
        mMainHandler.postDelayed(mEndScrollFallback, 250);
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        TermuxService service = mActivity.getTermuxService();

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing();
            return;
        }

        int index = service.getIndexOfSession(finishedSession);

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        boolean isPluginExecutionCommandWithPendingResult = false;
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult();
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.");
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            // Also auto-close if killed by signal (negative exit status like -9 for SIGKILL)
            int exitStatus = finishedSession.getExitStatus();
            if (exitStatus == 0 || exitStatus == 130 || exitStatus < 0 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        runIfVisible(() -> ShareUtils.copyTextToClipboard(mActivity, text));
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        runIfVisible(() -> {
            String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
            if (text != null) {
                TerminalView tv = mActivity.getTerminalView();
                if (tv != null && tv.mEmulator != null) tv.mEmulator.paste(text);
            }
        });
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        runIfVisible(() -> {
        switch (mActivity.getProperties().getBellBehaviour()) {
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE:
                BellHandler.getInstance(mActivity).doBell();
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP:
                loadBellSoundPool();
                if (mBellSoundPool != null)
                    mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE:
                // Ignore the bell character.
                break;
        }
        });
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        if (mActivity.getCurrentSession() == changedSession) {
            updateBackgroundColor();
            // The palette changed: every cell may resolve to a different color even though no cell
            // content changed, which dirty-row tracking cannot see. Force a full repaint — on every
            // bound page, not just the active one: a background session can emit OSC 4/11 as well,
            // and its page would otherwise keep the old palette (and the old alpha blend) until
            // some unrelated repaint happens to cover it. resyncScreen = false, see above.
            invalidateAllTerminalViews(null, false);
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible");
            return;
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(enabled, false);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }


    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    public void onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(true, true);
    }



    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }



    /** Load mBellSoundPool */
    private synchronized void loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();

            try {
                mBellSoundId = mBellSoundPool.load(mActivity, com.termux.shared.R.raw.bell, 1);
            } catch (Exception e){
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e);
            }
        }
    }

    /** Release mBellSoundPool resources */
    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }



    /** Try switching to session. */
    public void setCurrentSession(TerminalSession session) {
        setCurrentSession(session, true);
    }

    /**
     * Try switching to session, smoothly scrolling the pager to the target page. Used by the
     * keyboard shortcuts, the sessions list and service-driven switches.
     */
    public void setCurrentSession(TerminalSession session, boolean showToast) {
        setCurrentSession(session, showToast, true);
    }

    /**
     * Switch to the given session. In the horizontal-pager model each session has its own
     * TerminalView (bound by {@link TerminalPagerAdapter}), so switching means scrolling the
     * pager to that page. The {@code onPageSelected} callback then re-points the activity's active
     * {@code mTerminalView} and runs the per-session bookkeeping via {@link #onSessionPageSelected}.
     *
     * @param animate if true the pager smoothly scrolls through the intermediate pages (keyboard
     *                shortcuts, sessions list); if false it jumps straight to the target page with
     *                no animation and no intermediate {@code onPageScrolled} events (tab click), so
     *                no in-between tab gets highlighted; the tab strip then scrolls on its own to
     *                centre the selected tab from its current scroll position.
     */
    public void setCurrentSession(TerminalSession session, boolean showToast, boolean animate) {
        if (session == null) return;

        // Switching sessions invalidates any in-flight shell-completion context
        // (cwd, cwd-bound candidates, running bash subprocess). Dismiss the
        // autocomplete popup so stale candidates from the old session don't linger.
        mActivity.dismissAutoCompleteSuggestions();

        // If the pager has not been populated yet (e.g. onStart restored the stored session before
        // onServiceConnected filled the adapter), remember it and bail; syncTerminalPagerToService()
        // will select it once sessions exist.
        androidx.viewpager2.widget.ViewPager2 pager = mActivity.getTerminalPager();
        if (pager == null || pager.getAdapter() == null || pager.getAdapter().getItemCount() == 0) {
            mActivity.setPendingInitialSession(session);
            if (showToast) notifyOfSessionChange();
            return;
        }

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        int index = service.getIndexOfSession(session);
        if (index < 0) return;

        // If the target page is already the current page, pager.setCurrentItem() is a no-op
        // (the pager silently skips the scroll and fires NO callbacks).  This leaves
        // switchInProgress stuck at true and onSessionPageSelected() uninvoked, which means
        // tab highlight, text-input restore and directory recording are skipped for what
        // should be a perfectly valid session-switch request (e.g. clicking the already-visible
        // tab). Handle it inline so bookkeeping runs and the flag is cleared.
        if (index == pager.getCurrentItem()) {
            onSessionPageSelected(session);
            mActivity.setTerminalPageSwitchInProgress(false);
            if (showToast) notifyOfSessionChange();
            return;
        }

        if (pager != null) {
            // Mark a page switch in progress BEFORE the switch so the per-page focus
            // listener does not pop the IME while the old page loses focus mid-switch
            // (scenario #InputPanel6: tab click -> setCurrentItem). onTerminalPageSelected()
            // clears this flag and becomes the single authority that re-asserts the keyboard.
            mActivity.setTerminalPageSwitchInProgress(true);
            // Move the pager to the target page. When animate is true (keyboard shortcut / sessions
            // list) the pager smoothly scrolls through the intermediate pages; when animate is false
            // (tab click) it jumps instantly, so NO intermediate onPageScrolled events are emitted
            // and no in-between tab gets highlighted along the way. In both cases onPageSelected()
            // fires and runs onSessionPageSelected(), which performs the text-input restore, tab
            // highlight, background colour and directory-history update for the newly-visible session.
            pager.setCurrentItem(index, animate);
            // Tab click (animate == false): the pager emits no intermediate onPageScrolled events,
            // so the tab strip would otherwise stay put. Scroll the strip to CENTRE the selected tab
            // starting from its CURRENT scroll position (scrollToTabIndex -> requestScroll(CENTRE) ->
            // runCentreScroll animates from getScrollX() to the centred target). The scroll only
            // moves scrollX — it never touches the selection state, so the in-between tabs are
            // neither scrolled-through-highlighted nor activated; the single highlight is applied
            // once by onTerminalPageSelected() -> TermuxSessionTabsController.setCurrentSession().
            // The request is dropped while an end-scroll (freshly added tab) owns the strip, and is
            // a no-op when the target is already centred.
            if (!animate) {
                TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
                if (tabs != null) tabs.scrollToTabIndex(index);
            }
        }

        if (showToast) {
            notifyOfSessionChange();
        }
    }

    /**
     * Per-session bookkeeping for the page the user just landed on (swipe, tab click or keyboard
     * switch). Kept separate from {@link #setCurrentSession} so a swipe does not re-trigger a pager
     * scroll / toast loop. The page's TerminalView is already bound to the session by the adapter.
     */
    public void onSessionPageSelected(TerminalSession session) {
        if (session == null) return;

        // Re-apply the terminal font/color scheme after the session is attached. This is required
        // after a hot theme swap (recreate / system day-night change): the previously-attached
        // session is re-bound to a brand-new TerminalView here, and a checkForFontAndColors() that
        // ran earlier (e.g. from onServiceConnected) may have executed before the emulator was
        // bound to the new view, so its repaint would have been silently dropped. Re-applying now
        // guarantees the terminal matches the current night mode.
        // On the first page selection after (re)start force a real application (recreate case);
        // every other switch hits the gate in checkForFontAndColors() and is a cheap no-op.
        if (mPendingPostStartRecovery) invalidateAppliedScheme();
        checkForFontAndColors();

        // NOTE: we deliberately do NOT call checkAndScrollToSession() here. That helper ends in
        // termuxSessionListNotifyUpdated(), which calls notifyDataSetChanged() + setCurrentItem(...,
        // false). On a plain swipe the session COUNT has not changed, so rebuilding the adapter
        // mid-animation destroys the page ViewHolder and the setCurrentItem(false) snaps without
        // the smooth settle — that is exactly the "abrupt page switch" bug. The pager itself
        // already did the smooth scroll to land here; the tab highlight is refreshed separately
        // in onTerminalPageSelected() via TermuxSessionTabsController.setCurrentSession().
        updateBackgroundColor();

        // Tab titles are already populated when the session was created (via
        // termuxSessionListNotifyUpdated / onTitleChanged).  We deliberately skip
        // updateTabs() here because calling populateTabView() for ALL tabs triggers
        // setText() → requestLayout() on every tab, causing a full re-layout pass.
        // This layout pass runs DURING the pager smooth scroll (onPageSelected fires
        // at scroll START for setCurrentItem(true), before the animation completes),
        // shifting tab positions mid-animation and producing the visible "jump" on
        // non-adjacent tab switches.  The selection highlight is already handled by
        // setCurrentSession() in onTerminalPageSelected() — see its caller in
        // SessionPagerManager.onTerminalPageSelected().

        // Apply the per-session text input panel visibility state for the new session.
        // This also restores the saved text input content (single restore site — avoids
        // double restore on tab switch, which previously widened a race with the deferred
        // auto-complete recompute).
        // Slot visibility + text are ALWAYS applied. Focus and the IME reconcile are skipped while
        // returning from the background, because TermuxActivity.onResume() -> runKeyboardRestore()
        // is the single authority for them and would otherwise repeat this work (P1-5).
        if (mDeferFocusApplyToResume) {
            mActivity.applyTextInputVisibilityForSession(session, false);
        } else {
            mActivity.applyTextInputVisibilityForSession(session);
        }

        // Record the newly-current session's working directory into the
        // recent-directories history (for the "new tab" button popup) and swap the
        // per-directory message history. The cwd is a /proc/<pid>/cwd readlink, so it is
        // resolved OFF the UI thread (P3-2) and the result posted back to the main thread for
        // both consumers; a tab switch never blocks on the disk read.
        mActivity.getCurrentSessionCwdAsync(cwd -> {
            mActivity.recordCurrentDirectory(cwd);
            mActivity.onHistoryDirectoryChanged(cwd);
        });

        // Session-name based extra-keys profile switching. The property map is re-read from disk
        // once per (re)start (that is what recovers profiles saved while the activity was stopped);
        // every subsequent switch uses the cheap in-memory match, which is a no-op while the
        // resolved session name stays the same.
        final boolean reloadExtraKeysMap = mPendingPostStartRecovery;
        mPendingPostStartRecovery = false;
        applySessionExtraKeys(session, reloadExtraKeysMap);
    }

    void notifyOfSessionChange() {
        // Suppressed — the user requested no popups when switching tabs.
    }

    public void switchToSession(boolean forward) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession currentTerminalSession = mActivity.getCurrentSession();
        int index = service.getIndexOfSession(currentTerminalSession);
        int size = service.getTermuxSessionsSize();
        if (forward) {
            if (++index >= size) index = 0;
        } else {
            if (--index < 0) index = size - 1;
        }

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    @SuppressLint("InflateParams")
    public void renameSession(final TerminalSession sessionToRename) {
        if (sessionToRename == null) return;

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, text -> {
            renameSession(sessionToRename, text);
            termuxSessionListNotifyUpdated();
        }, -1, null, -1, null, null);
    }

    private void renameSession(TerminalSession sessionToRename, String text) {
        if (sessionToRename == null) return;
        sessionToRename.mSessionName = text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
        // Session-name based extra-keys: re-evaluate if the renamed session is the active one.
        if (mActivity.getCurrentSession() == sessionToRename) {
            applySessionExtraKeys(sessionToRename);
        }
    }

    /**
     * Notify the extra-keys controller of the active session's name so a session-name based
     * layout profile (property "extra-keys-session") can be applied. Priority vs the
     * process-based context is handled inside the controller. Re-reads the property map from
     * disk (navigation events: tab switch, rename).
     */
    private void applySessionExtraKeys(@NonNull TerminalSession session) {
        applySessionExtraKeys(session, true);
    }

    /**
     * Notify the extra-keys controller of the active session's name so a session-name based
     * layout profile (property "extra-keys-session") can be applied. Priority vs the
     * process-based context is handled inside the controller.
     *
     * @param reloadMap when true (navigation events: tab switch, rename) the property is
     *                  re-read from disk first — recovering profiles saved while this
     *                  activity was stopped and missed the reload broadcast. When false
     *                  (high-frequency title path) only the in-memory match runs, and the
     *                  whole call is skipped while the resolved name is unchanged.
     */
    private void applySessionExtraKeys(@NonNull TerminalSession session, boolean reloadMap) {
        // mSessionName is null for unnamed sessions, so prefer it, then the emulator
        // title (what the tab shows). Both null -> no profile match -> default layout.
        String sessionName = session.mSessionName;
        if (TextUtils.isEmpty(sessionName)) sessionName = session.getTitle();
        if (TextUtils.isEmpty(sessionName)) sessionName = null;
        if (!reloadMap && TextUtils.equals(sessionName, mLastExtraKeysSessionName)) return;
        mLastExtraKeysSessionName = sessionName;
        TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
        if (extraKeys == null) return;
        if (reloadMap) extraKeys.reloadSessionMap();
        extraKeys.onSessionNameChanged(sessionName);
    }

    /**
     * How the freshly-created session should be presented to the user.
     * <ul>
     *   <li>{@link #SELECT_AND_SCROLL} — select the new page via the pager
     *       ({@link #setCurrentSession}) and scroll the tab strip to its right end so the new
     *       tab and the trailing (+) button are fully revealed. Used by the "+" button, the
     *       directory-history popup and keyboard shortcuts.</li>
     *   <li>{@link #CALLER_MANAGED} — do NOT select or scroll; the caller already has the pager
     *       parked on the placeholder slot (right-swipe-to-add) and manages selection/bookkeeping
     *       itself so the transition reads as a normal tab-to-tab swipe with no jump.</li>
     * </ul>
     */
    public enum NewSessionSelectMode {
        SELECT_AND_SCROLL,
        CALLER_MANAGED
    }

    /**
     * Return the working directory of the current terminal session, or {@code null} if there is
     * no activity or no current session. Used to resolve the default working directory for a
     * freshly-created session from the session the user is currently looking at.
     */
    @Nullable
    public static String getCurrentSessionCwd(@Nullable TermuxActivity activity) {
        if (activity == null) return null;
        TerminalSession session = activity.getCurrentSession();
        return (session != null) ? session.getCwd() : null;
    }

    /**
     * Initialize a freshly-created session's emulator on a background thread so the JNI fork
     * (PTY setup) does not block the UI thread during the first pager layout pass, then sync the
     * pager to the service on the UI thread once the subprocess is alive. Used for the cold-start
     * path when the pager is still empty.
     */
    public static void initSessionEmulatorOnBackgroundThread(@NonNull TermuxActivity activity,
            @NonNull TerminalSession session) {
        activity.setColdStartSessionPending(true);
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            // Initialize the emulator with reasonable default dimensions.
            // JNI.createSubprocess() (fork + PTY setup) runs here, NOT on the UI thread.
            session.updateSize(80, 24, 10, 10);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                activity.setColdStartSessionPending(false);
                // Run the full pager sync now.  syncWithServiceList will notify
                // RecyclerView that items are available, triggering a layout pass
                // that creates the ViewHolder and binds it to the session via
                // attachSession() → updateSize().  Since the emulator is already
                // initialized, that updateSize() will find mEmulator != null and
                // only resize — no fork on the UI thread.
                // onTerminalPageSelected will run through its deferred path
                // (pageView == null initially) and complete once the layout
                // pass creates the page — all without blocking the UI.
                activity.syncTerminalPagerToService();
            });
        }).start();
    }

    /**
     * Single entry point for creating a new terminal-session tab, shared by every add-tab path:
     * the "+" button, the directory-history popup, keyboard shortcuts and the right-swipe-to-add
     * gesture. Centralises the MAX_SESSIONS limit, working-directory resolution, session creation
     * and (for selectable tabs) the end-scroll reveal — removing the duplicated logic that used to
     * live in {@code addNewSession} / {@code addNewSessionInDirectory} / {@code createSessionForPlaceholder}.
     *
     * @param isFailSafe     start a failsafe session.
     * @param sessionName    session name (may be null).
     * @param directory      working directory; null resolves to the current session's cwd or the
     *                       default working directory.
     * @param selectMode     {@link NewSessionSelectMode#SELECT_AND_SCROLL} for UI add-tab paths,
     *                       {@link NewSessionSelectMode#CALLER_MANAGED} for the right-swipe gesture.
     * @param showLimitDialog show the "max terminals reached" dialog when the limit is hit (true for
     *                        UI paths; the swipe passes false and silently gets null).
     * @param allowColdStart permit the cold-start background init when the pager is still empty.
     *                       Only the regular "+" button needs this, so the directory popup and the
     *                       swipe pass false to preserve their existing behaviour exactly.
     * @return the created {@link TermuxSession}, or null on error / limit reached / cold-start init.
     */
    public TermuxSession createNewSession(boolean isFailSafe, @Nullable String sessionName,
            @Nullable String directory, @NonNull NewSessionSelectMode selectMode,
            boolean showLimitDialog, boolean allowColdStart) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            if (showLimitDialog) {
                AlertDialog maxDialog = new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                    .setPositiveButton(android.R.string.ok, null).create();
                maxDialog.show();
            }
            return null;
        }

        String workingDirectory = directory;
        if (workingDirectory == null) {
            String cwd = getCurrentSessionCwd(mActivity);
            workingDirectory = (cwd != null) ? cwd : mActivity.getProperties().getDefaultWorkingDirectory();
        }
        if (TermuxConstants.TERMUX_HOME_DIR_PATH.equals(workingDirectory)) {
            workingDirectory = mActivity.getFilesDir().getAbsolutePath() + "/home";
        }
        Logger.logInfo("CHDIR_DEBUG", "createNewSession: workingDirectory='" + workingDirectory
            + "' mActivity.getFilesDir()='" + mActivity.getFilesDir().getAbsolutePath()
            + "' getDefaultWD='" + mActivity.getProperties().getDefaultWorkingDirectory() + "'");

        TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
        if (newTermuxSession == null) return null;
        TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
        // A new tab inherits the keyboard state of the moment it is created (user expectation:
        // creating a tab must not make the keyboard jump). Record it BEFORE the page switch so
        // the per-session reconcile in applyTextInputVisibilityForSession works with the
        // inherited value instead of the global fallback (which races the switch churn).
        mActivity.getTextInputState().setSoftKeyboardIntent(newTerminalSession,
                mActivity.computeImeVisibility());
        // CALLER_MANAGED (right-swipe gesture): the caller handles selection / pager bookkeeping /
        // its own end-scroll, so just hand back the session.
        if (selectMode == NewSessionSelectMode.CALLER_MANAGED) {
            return newTermuxSession;
        }

        // Reveal the newly-added tab AND the trailing (+) button by scrolling the tab strip to its
        // right end — identical to the right-swipe-to-add gesture. Reserve the end-scroll now (so
        // competing CENTRE scrolls are suppressed) and arm the label-triggered scroll;
        // scrollStripToEnd() fires from onTitleChanged once the shell sets the tab's real title,
        // with a 250ms fallback.
        armEndScrollForNewSession(newTerminalSession);

        // Cold-start detection: the pager has never been populated (adapter has 0 items)
        // and we just created the first session.  The emulator subprocess
        // (JNI.createSubprocess → fork) takes long enough to cause a visible UI stutter
        // if it runs inside the pager layout pass on the UI thread.  Instead, initialize
        // the emulator with default dimensions on a background thread, then attach the
        // session to the pager once the subprocess is alive.
        androidx.viewpager2.widget.ViewPager2 pager = mActivity.getTerminalPager();
        boolean isColdStart = allowColdStart && (pager == null || pager.getAdapter() == null
                || pager.getAdapter().getItemCount() == 0)
                && service.getTermuxSessionsSize() == 1
                && newTerminalSession.getEmulator() == null;
        if (isColdStart) {
            initSessionEmulatorOnBackgroundThread(mActivity, newTerminalSession);
            return newTermuxSession;
        }

        setCurrentSession(newTerminalSession);
        return newTermuxSession;
    }

    /**
     * Reserve the tab-strip end-scroll for a freshly-added session and arm its label-triggered
     * fire. The actual right-end scroll ({@link TermuxSessionTabsController#scrollStripToEnd})
     * fires from {@link #onTitleChanged} once the shell sets the tab's real title, with a 250ms
     * fallback in case the shell never emits one.
     */
    private void armEndScrollForNewSession(@NonNull TerminalSession session) {
        TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
        if (tabs != null) tabs.setEndScrollReserved(true);
        markPendingEndScrollSession(session);
    }

    /**
     * Create a new terminal session tab. Thin wrapper over {@link #createNewSession} for the
     * "+" button, keyboard shortcuts and the notification/service-launch paths: the new tab is
     * selected and the strip scrolls to reveal the trailing (+) button.
     */
    public void addNewSession(boolean isFailSafe, String sessionName) {
        createNewSession(isFailSafe, sessionName, null,
                NewSessionSelectMode.SELECT_AND_SCROLL, true, true);
    }

    /**
     * Create a new terminal session to replace a placeholder "new tab" page, and return it.
     * Thin wrapper over {@link #createNewSession} for the right-swipe-to-add gesture: the caller
     * (the placeholder-commit path in {@code SessionPagerManager}) already has the pager parked on
     * the placeholder slot and handles selection/bookkeeping itself, so no selection/scroll happens
     * here. The session limit is enforced silently (no dialog) — the swipe simply edge-bounces.
     *
     * @return the created {@link TermuxSession}, or null if the service is missing or the session
     *         limit was reached.
     */
    public TermuxSession createSessionForPlaceholder(boolean isFailSafe, String sessionName) {
        return createNewSession(isFailSafe, sessionName, null,
                NewSessionSelectMode.CALLER_MANAGED, false, false);
    }

    /**
     * Create a new terminal session starting in the given directory. Thin wrapper over
     * {@link #createNewSession} for the "new tab" button's directory-history popup: picking a
     * directory opens a fresh session there instead of inheriting the current session's cwd. The
     * new tab is selected and the strip scrolls to reveal the trailing (+) button.
     */
    public void addNewSessionInDirectory(@NonNull String directory) {
        createNewSession(false, null, directory,
                NewSessionSelectMode.SELECT_AND_SCROLL, true, false);
    }

    public void setCurrentStoredSession() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getCurrentStoredSessionOrLast() {
        TerminalSession stored = getCurrentStoredSession();

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored;
        } else {
            // Else return the last session currently running
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return null;

            TermuxSession termuxSession = service.getLastTermuxSession();
            if (termuxSession != null)
                return termuxSession.getTerminalSession();
            else
                return null;
        }
    }

    private TerminalSession getCurrentStoredSession() {
        String sessionHandle = mActivity.getPreferences().getCurrentSession();

        // If no session is stored in shared preferences
        if (sessionHandle == null)
            return null;

        // Check if the session handle found matches one of the currently running sessions
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        return service.getTerminalSessionForHandle(sessionHandle);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        // Drop the per-session saved text input for the removed session.
        mActivity.clearTextInputForSession(finishedSession);

        // Suppress IME/focus churn for the whole close sequence. The pager rebuild detaches
        // the closed page's view (the served IME target), which fires a focus LOSS and an
        // imeChange HIDE on the main looper. Without the guard those run with
        // mTerminalPageSwitchInProgress already false, so the focus listener hides the IME
        // and — worse — the HIDE is treated as an honest user action and OVERWRITES the
        // LANDED session's keyboard intent to "hidden" before its reconcile runs (the
        // "keyboard state not restored after closing a tab" bug). The landed session's own
        // reconcile then restores its remembered state untouched.
        mActivity.setTerminalPageSwitchInProgress(true);

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            if (index >= size) {
                index = size - 1;
            }
            // Sync pager and tabs. The pager sync inside termuxSessionListNotifyUpdated() already
            // handles session restoration via its restoreIndex logic:
            //   - If the removed session was the current one: falls back to the pager's old current
            //     item index (clamped to the new list bounds), which selects the session that shifted
            //     into that position or the last session.
            //   - If the removed session was NOT the current one: finds the current session's new
            //     index in the updated list and stays on it.
            // Then onTerminalPageSelected() re-points mTerminalView and runs per-session bookkeeping.
            //
            // IMPORTANT: Do NOT add a setCurrentSession() call here using the stale <index> returned
            // by removeTermuxSession().  That index was the removed session's position in the OLD
            // list and is meaningless in the new list.  Using it to look up a "fallback" session
            // causes two regressions:
            //   (a) Scenario #8 (close tab + new tab race): list [A,B,C,D] → [A,C,D], index=1 (B's
            //       old slot), getTermuxSession(1)=C → switches from D to C.
            //   (b) Non-current session finishes: list [A,B,C] → [A,C], index=1 (B's old slot),
            //       getTermuxSession(1)=C → switches from A to C.
            // In both cases the pager sync already restored the correct session; the stale-index
            // lookup overrides it.
            termuxSessionListNotifyUpdated(index);
        }

        // The deferred page-switch bookkeeping (onTerminalPageSelected ->
        // applyTextInputVisibilityForSession -> reconcile) runs on a posted runnable, AFTER
        // this method returns. Clear the close-suppression flag in a posted runnable as well
        // so it stays raised for the whole close+reconcile window.
        mActivity.getWindow().getDecorView().post(() ->
                mActivity.setTerminalPageSwitchInProgress(false));
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated(-1);
    }

    public void termuxSessionListNotifyUpdated(int removedIndex) {
        mActivity.termuxSessionListNotifyUpdated(removedIndex);
    }

    public void checkAndScrollToSession(TerminalSession session) {
        runIfVisible(() -> {
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return;

            final int indexOfSession = service.getIndexOfSession(session);
            if (indexOfSession < 0) return;

            // Update session tabs
            termuxSessionListNotifyUpdated();
        });
    }


    String toToastTitle(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return null;
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }


    /**
     * Apply terminal fonts and colors for the current theme.
     * Determines the effective night mode from Termux's own {@link NightMode#getAppNightMode()}
     * (the source of truth). For {@link NightMode#SYSTEM} the actual system night mode is resolved
     * from the device's system resources via {@link ThemeUtils#isSystemNightModeEnabled()}, NOT the
     * application context and NOT the activity context.
     *
     * <p>Why the <i>system</i> resources and not the application/activity context:
     * <ul>
     *   <li>The Application context's {@code uiMode} is updated independently of the activity
     *       lifecycle and can hold a stale configuration right after a {@code recreate()} — especially
     *       on OEM ROMs (e.g. MIUI/HyperOS) — so it can still report the OLD night state while the
     *       recreated activity already switched. That is the original bug: the toolbar/status bar
     *       (re-themed from the activity config) update, but the terminal (read from the app context)
     *       keeps the previous scheme.</li>
     *   <li>The activity context is better than the application context, but AppCompat applies night
     *       mode to the activity via {@code applyOverrideConfiguration}. When the app uses
     *       {@code MODE_NIGHT_FOLLOW_SYSTEM} it should mirror the <i>actual device</i> day/night
     *       setting, which is exactly what {@link Resources#getSystem()} exposes — the one true
     *       source of the device {@code uiMode}, unaffected by any per-activity override.</li>
     * </ul>
     * Reading the system resources guarantees the terminal color scheme tracks the same device
     * night state that drives the activity/toolbar theme, keeping them in sync after any
     * recreate or direct system {@code uiMode} change.
     */
    /**
     * Identity of everything {@link #applyTerminalColorScheme} consumes. Cheap to build
     * (a few stat() calls) compared to the application itself (file read + parse, TTF parse,
     * drawable allocation, activity restyle, full terminal repaint).
     */
    private String buildSchemeKey(boolean isNight) {
        File colorsFile = ColorSchemeUtils.getColorSchemeFileForTheme(isNight);
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        // WHICH scheme is selected lives in termux.properties, not in a scheme file: switching
        // Default <-> Monet <-> Monet-<variant> touches neither colors.*.properties
        // nor the Monet palette, so without this the key would be identical before and
        // after the switch and the new selection would silently never be applied.
        File propsFile = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
        // Monet has no file on disk, so mtime/size cannot detect a wallpaper change. Its
        // token is a plain volatile read, which is why it is safe to include here even though this
        // runs on every tab switch.
        long monetToken = ColorSchemeUtils.monetToken(isNight);
        return (isNight ? "n1" : "n0")
                + "|" + (colorsFile == null ? "-" : colorsFile.lastModified() + ":" + colorsFile.length())
                + "|" + (fontFile == null ? "-" : fontFile.lastModified() + ":" + fontFile.length())
                + "|p" + (propsFile == null ? "-" : propsFile.lastModified() + ":" + propsFile.length())
                + "|monet" + monetToken;
    }

    /**
     * {@link #buildSchemeKey(boolean)} with the Monet scheme generated first, so the token
     * in the key is the final one and does not change again right after the scheme was applied.
     */
    private String resolveSchemeKey(boolean isNight) {
        ColorSchemeUtils.warmUpMonet(mActivity, isNight);
        return buildSchemeKey(isNight);
    }

    /**
     * Drop the "scheme already applied" gate so the next {@link #checkForFontAndColors()} really
     * re-applies (used after a recreate and on an explicit styling reload).
     */
    public void invalidateAppliedScheme() {
        mAppliedSchemeKey = null;
        // The "palette already loaded" gate has to go as well. It survived reloads before, so an
        // explicit re-apply repainted the panel and the terminals but never re-read the newly
        // selected scheme — picking a different Monet variant (or going back to Default)
        // looked like a no-op even though the selection had been persisted correctly.
        mLoadedColorSchemeKey = null;
    }

    /**
     * Resolve the terminal typeface, parsing the font file only when it actually changed
     * (mtime + size). {@code Typeface.createFromFile()} is a full TTF/OTF parse and used to run on
     * every page bind.
     */
    private static Typeface resolveTerminalTypeface() {
        final File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        final String key = (fontFile == null) ? "-"
                : fontFile.lastModified() + ":" + fontFile.length();
        if (sCachedTypeface != null && key.equals(sCachedTypefaceKey)) return sCachedTypeface;
        Typeface tf = (fontFile != null && fontFile.exists() && fontFile.length() > 0)
                ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
        sCachedTypeface = tf;
        sCachedTypefaceKey = key;
        return tf;
    }

    /**
     * Make sure the global {@link TerminalColors#COLOR_SCHEME} reflects {@code key}, reading and
     * parsing the scheme file only when it does not. Both the activity-level
     * {@link #applyTerminalColorScheme} and the per-page {@link #checkForFontAndColorsForView}
     * funnel through here, so binding a page no longer re-reads {@code colors.properties} from
     * disk — and vice versa.
     */
    private void ensureColorSchemeLoaded(boolean isNight, String key) {
        if (key.equals(mLoadedColorSchemeKey)) return;
        // One shared resolution chain: Termux:Style file -> Monet, but ONLY when this theme
        // actually selected one of the Monet entries -> the built-in light/dark scheme.
        // Routing through ColorSchemeUtils is what stops "Default" from being silently replaced by
        // the Monet scheme, and it keeps every surface (activity, per-page bind, extra-keys
        // editor) in agreement.
        ColorSchemeUtils.applyColorSchemeForTheme(mActivity, isNight,
                isNight ? null : getLightTerminalColorScheme());
        mLoadedColorSchemeKey = key;
    }

    /**
     * Apply the terminal font and colour scheme — but only when something it depends on actually
     * changed.
     *
     * <p>This method is invoked from {@link #onSessionPageSelected}, i.e. on EVERY tab switch, and
     * the work it triggers (reading {@code colors.properties} and the font file from disk,
     * reallocating the panel drawables, resetting the emulator palette, restyling the activity and
     * repainting the terminal) does not depend on WHICH session is selected — repeating it per
     * switch was pure waste. The key covers night mode plus both files' size/mtime, so a genuine
     * scheme/theme/font change is still picked up immediately.
     */
    public void checkForFontAndColors() {
        final boolean isNight = TermuxActivity.isNightModeActive();
        final String key = resolveSchemeKey(isNight);
        if (key.equals(mAppliedSchemeKey)) return;
        mAppliedSchemeKey = key;
        // Hand the very same key down: recomputing it inside would produce a different string
        // once the Monet palette exists, which would look like "changed again" on the next
        // call and cost a second full restyle.
        applyTerminalColorScheme(isNight, key);
    }

    /**
     * Apply the current terminal font and color scheme to a specific {@link TerminalView} (a pager
     * page) without touching the shared bottom panel (which is updated once via the active view).
     * Used to theme pages as they are (re)bound to their session in the horizontal pager.
     */
    public void checkForFontAndColorsForView(@NonNull TerminalView terminalView) {
        final boolean isNight = TermuxActivity.isNightModeActive();
        try {
            // Only pay for the disk read when the global palette is not already current.
            ensureColorSchemeLoaded(isNight, resolveSchemeKey(isNight));

            TerminalEmulator emulator = terminalView.mEmulator;
            if (emulator == null) {
                TerminalSession session = terminalView.getCurrentSession();
                if (session != null) emulator = session.getEmulator();
            }
            if (emulator != null) {
                emulator.mColors.reset();
            }
            if (terminalView != null) {
                terminalView.invalidate();
                terminalView.onScreenUpdated();
            }

            // Cached: a page bind no longer stats + parses the font file.
            terminalView.setTypeface(resolveTerminalTypeface());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColorsForView()", e);
        }
    }

    /**
     * Apply terminal fonts and the color scheme (light or dark) for the given night mode.
     *
     * @param key The scheme identity computed by the caller — see {@link #checkForFontAndColors()}.
     */
    private void applyTerminalColorScheme(boolean isNight, String key) {
        try {
            // Load a user color scheme if one is defined (and only if the global palette is not
            // already current — see ensureColorSchemeLoaded).
            ensureColorSchemeLoaded(isNight, key);

            // Cache all derived colours from the now-applied COLOR_SCHEME before styling the
            // panel, so applyPanelColors() reads fresh values via the activity's getters.
            mActivity.recomputeUIColors();

            // Drive the bottom panel + status bar from the now-applied scheme (works for both
            // custom schemes and the theme-derived light/dark fallback).
            applyPanelColors(ColorSchemeUtils.isTerminalSchemeLight());

            // Restyle the rest of the activity (window, status bar, open popups) from the scheme.
            mActivity.applySchemeColors();

            // Reset the cached palette on EVERY live terminal emulator — not just the one currently
            // on screen. Each emulator keeps its colours in mColors, so leaving the others stale is
            // exactly what made already-open terminals keep showing the previous scheme while only
            // newly created terminals picked up the new one (their view is bound after the change
            // and runs checkForFontAndColorsForView(), which re-syncs mColors).
            resetAllEmulatorColors();

            updateBackgroundColor();

            // A full forced redraw is NOT required: TerminalRenderer.render() now clears the entire
            // canvas to the current background color and then repaints every visible row on each
            // onDraw() call, so a plain invalidate()/onScreenUpdated() is a COMPLETE repaint of both
            // the glyphs AND the pane background with the new scheme. We repaint the active view AND
            // every offscreen page the ViewPager2 keeps bound so the change is visible everywhere at
            // once instead of only after a tab switch. onScreenUpdated() early-returns when mEmulator
            // is null, which is why the emulator reset above runs first.
            final Typeface newTypeface = resolveTerminalTypeface();
            invalidateAllTerminalViews(newTypeface);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in applyTerminalColorScheme()", e);
        }
    }

    /**
     * Re-sync the cached colour array of every live terminal emulator with the global
     * {@link TerminalColors#COLOR_SCHEME} so a scheme change is reflected by all open terminals,
     * not only the active one.
     *
     * <p>Emulators are owned by their {@link TerminalSession} and hold their palette in
     * {@code mColors}; the active view's emulator is one of these, so covering all sessions also
     * covers the active terminal. Called from {@link #applyTerminalColorScheme} which only runs
     * when the scheme actually changed (it is gated by {@link #checkForFontAndColors}), so the
     * per-session cost here is paid rarely.
     */
    private void resetAllEmulatorColors() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        final int n = service.getTermuxSessionsSize();
        for (int i = 0; i < n; i++) {
            TermuxSession ts = service.getTermuxSession(i);
            if (ts == null) continue;
            TerminalSession session = ts.getTerminalSession();
            if (session == null) continue;
            TerminalEmulator emulator = session.getEmulator();
            if (emulator != null) emulator.mColors.reset();
        }
    }

    /**
     * Force a complete repaint of the active terminal view and every offscreen page the
     * ViewPager2 keeps bound, so a freshly applied colour scheme shows up everywhere immediately.
     *
     * @param typeface The (cached) terminal typeface to apply to each view.
     */
    private void invalidateAllTerminalViews(@Nullable Typeface typeface) {
        invalidateAllTerminalViews(typeface, true);
    }

    /**
     * @param resyncScreen whether to also run {@link TerminalView#onScreenUpdated()} on each page.
     *                     Must be {@code false} when the repaint is not caused by screen content
     *                     (e.g. an OSC 4/11 palette change): onScreenUpdated() snaps a scrolled
     *                     view back to the bottom ({@code mTopRow = 0}), which would silently
     *                     throw away the user's scroll position in a background tab.
     */
    private void invalidateAllTerminalViews(@Nullable Typeface typeface, boolean resyncScreen) {
        final TerminalView active = mActivity.getTerminalView();
        if (active != null) {
            active.invalidate();
            if (resyncScreen) active.onScreenUpdated();
            if (typeface != null) active.setTypeface(typeface);
        }

        androidx.viewpager2.widget.ViewPager2 pager = mActivity.getTerminalPager();
        if (pager == null) return;
        final int pages = pager.getChildCount();
        for (int i = 0; i < pages; i++) {
            View child = pager.getChildAt(i);
            if (!(child instanceof androidx.recyclerview.widget.RecyclerView)) continue;
            androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) child;
            final int count = rv.getChildCount();
            for (int j = 0; j < count; j++) {
                View v = rv.getChildAt(j);
                if (!(v instanceof TerminalView)) continue;
                TerminalView tv = (TerminalView) v;
                if (tv == active) continue; // already handled above
                tv.invalidate();
                if (resyncScreen) tv.onScreenUpdated();
                if (typeface != null) tv.setTypeface(typeface);
            }
        }
    }

    /**
     * Apply the bottom-panel styling derived from the active terminal color scheme.
     * - The panel (toolbar container + extra-keys view) background is made transparent so only the
     *   buttons themselves show a background.
     * - Each button background becomes a translucent overlay whose tone is picked from the scheme
     *   lightness: dark translucent for light schemes, light translucent for dark schemes.
     * - The alpha (transparency) of the button backgrounds is read from user preferences so the
     *   value is applied ONCE at change time, not recomputed on every frame.
     * - The interactive scrollbar thumb receives the same pre-computed colours.
     * - The status bar icons/theme follow the scheme lightness (light icons on dark schemes,
     *   dark icons on light schemes).
     *
     * @param isSchemeLight Whether the applied terminal color scheme is light.
     */
    public void applyPanelColors(boolean isSchemeLight) {
        // All derived colours are pre-cached by TermuxActivity.recomputeUIColors().
        final int buttonBg = mActivity.getButtonBg();
        final int buttonActiveBg = mActivity.getButtonActiveBg();
        final int buttonText = mActivity.getButtonText();
        final int selectionHighlight = mActivity.getTextSelectionHighlightColor();

        // Panel containers stay transparent: the decor view is painted with the scheme
        // background at the terminal's transparency alpha (see
        // TermuxActivity.applySystemBarColors()), so panels inherit exactly the same
        // "scheme bg at alpha A over the wallpaper" as the terminal itself. Painting opaque
        // panel bars on top would punch hard holes into the translucent look.
        View toolbar = mActivity.findViewById(R.id.terminal_toolbar_container);
        if (toolbar != null) toolbar.setBackgroundColor(Color.TRANSPARENT);
        ExtraKeysView extraKeys = mActivity.getExtraKeysView();
        if (extraKeys != null) {
            extraKeys.setBackgroundColor(Color.TRANSPARENT);
            extraKeys.setButtonColors(buttonText, deriveActiveTextColor(buttonText), buttonBg, buttonActiveBg);
        }

        // Session tabs panel buttons (new session / toggle text input): the fill is the
        // terminal background overlaid with the inactive-element overlay (buttonBg) and the
        // stroke is the terminal background overlaid with the active-element overlay
        // (buttonActiveBg) — both composed by TermuxColorSchemeManager using the alpha
        // percentages from settings. On top of those opaque colours the button itself is
        // drawn at a fixed 50% opacity so it reads as a translucent control over the terminal.
        int buttonStrokePx = Math.round(mActivity.getResources().getDimension(R.dimen.terminal_text_input_stroke));
        // Compose an OPAQUE colour = terminal background overlaid with the inactive/active
        // element tint at the SETTINGS overlay alpha (so moving the transparency sliders changes
        // the element tint strength), then apply a flat 50% opacity on top of that opaque colour.
        // NOTE: getButtonBg()/getButtonActiveBg() already carry the SETTINGS alpha baked in as
        // transparency and are NOT composited onto the terminal background, so they cannot be
        // re-alpha'd here. Instead rebuild the overlay at the settings alpha and composite it.
        int buttonFillAlpha = 128; // 50%
        TermuxAppSharedPreferences prefs = mActivity.getPreferences();
        // Double the settings overlay alpha so that, after the flat 50% opacity is applied on
        // top below, the resulting visible strength matches the original (no-50%-overlay) look.
        int overlayInactive = ColorSchemeUtils.getButtonBackground(isSchemeLight, Math.min(100, prefs.getButtonBgInactiveAlpha() * 2));
        int overlayActive = ColorSchemeUtils.getButtonActiveBackground(isSchemeLight, Math.min(100, prefs.getButtonBgActiveAlpha() * 2));
        int toggleButtonBg = withAlpha(
                TermuxColorSchemeManager.compositeColors(mActivity.getColorSchemeManager().getSchemeBackground(), overlayInactive),
                buttonFillAlpha);
        int toggleButtonStroke = withAlpha(
                TermuxColorSchemeManager.compositeColors(mActivity.getColorSchemeManager().getSchemeBackground(), overlayActive),
                buttonFillAlpha);
        // Plain tab button (new session): no stroke — fill only, with an active
        // (pressed/swiped) background so the press/swipe gesture gives visible feedback.
        // Use the same scheme-derived colours as the extra-keys buttons so the contrast
        // between idle and active states is clearly visible.
        ImageButton newSessionBtn = mActivity.findViewById(R.id.new_session_tab_button);
        if (newSessionBtn != null) {
            android.graphics.drawable.GradientDrawable normalState =
                    new android.graphics.drawable.GradientDrawable();
            normalState.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            normalState.setColor(buttonBg);

            // Active background: a clearly visible highlight. The scheme-derived
            // buttonActiveBg is only a faint (default ~12% alpha) tint, so compose a
            // stronger overlay of the active colour onto the terminal background and
            // present it at high opacity so the idle -> active change is unmistakable.
            int activeFill = withAlpha(
                    TermuxColorSchemeManager.compositeColors(
                            mActivity.getColorSchemeManager().getSchemeBackground(),
                            ColorSchemeUtils.getButtonActiveBackground(isSchemeLight, 55)),
                    230);
            android.graphics.drawable.GradientDrawable activeState =
                    new android.graphics.drawable.GradientDrawable();
            activeState.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            activeState.setColor(activeFill);

            android.graphics.drawable.StateListDrawable states =
                    new android.graphics.drawable.StateListDrawable();
            // Pressed (tap / swipe in progress) shows the active background.
            states.addState(new int[]{android.R.attr.state_pressed}, activeState);
            states.addState(new int[]{android.R.attr.state_selected}, activeState);
            states.addState(new int[]{}, normalState);

            newSessionBtn.setBackground(states);
            newSessionBtn.setColorFilter(buttonText, android.graphics.PorterDuff.Mode.SRC_ATOP);
            newSessionBtn.setForeground(null);
        }

        // Toggle text-input button: on press, fill + stroke both become the stroke colour.
        ImageButton toggleBtn = mActivity.findViewById(R.id.toggle_text_input_button);
        if (toggleBtn != null) {
            android.graphics.drawable.GradientDrawable normalState =
                    new android.graphics.drawable.GradientDrawable();
            normalState.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            normalState.setColor(toggleButtonBg);
            normalState.setStroke(buttonStrokePx, toggleButtonStroke);

            android.graphics.drawable.GradientDrawable pressedState =
                    new android.graphics.drawable.GradientDrawable();
            pressedState.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            pressedState.setColor(toggleButtonStroke);
            pressedState.setStroke(buttonStrokePx, toggleButtonStroke);

            android.graphics.drawable.StateListDrawable states =
                    new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{ android.R.attr.state_pressed }, pressedState);
            states.addState(new int[]{ android.R.attr.state_focused }, pressedState);
            states.addState(new int[]{}, normalState);

            toggleBtn.setBackground(states);
            toggleBtn.setColorFilter(buttonText, android.graphics.PorterDuff.Mode.SRC_ATOP);
            toggleBtn.setForeground(null);
        }

        // Session tabs themselves: translucent background (selected = active tone) + scheme fg.
        TermuxSessionTabsController tabsController = mActivity.getTermuxSessionTabsController();
        if (tabsController != null) {
            tabsController.applySchemeColorsToTabs(buttonText, buttonBg, buttonActiveBg);
        }

        // Text input field: foreground from the scheme, translucent container background.
        EditText textInput = mActivity.findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null) {
            textInput.setTextColor(buttonText);
            textInput.setHintTextColor((buttonText & 0x00FFFFFF) | 0x80000000);
            // Selection highlight uses the cached colour (recomputedUIColors).
            textInput.setHighlightColor(selectionHighlight);
            // Drag handles for text selection also follow the scheme foreground colour.
            tintSelectionHandles(textInput, buttonText);
        }
        View textInputContainer = mActivity.findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer != null && textInputContainer.getBackground() instanceof android.graphics.drawable.GradientDrawable) {
            // Background = inactive control color; stroke = active control color (matching the
            // bottom-panel buttons), so the input panel reads as part of the same control family.
            android.graphics.drawable.GradientDrawable d =
                (android.graphics.drawable.GradientDrawable) textInputContainer.getBackground().mutate();
            d.setColor(buttonBg);
            d.setStroke(Math.round(mActivity.getResources().getDimension(R.dimen.terminal_text_input_stroke)), buttonActiveBg);
        }

        // Push pre-computed scrollbar thumb colours to TerminalView (alpha baked in ONCE here).
        TerminalView tv = mActivity.getTerminalView();
        if (tv != null) {
            tv.setScrollbarColors(toggleButtonBg, toggleButtonStroke);
            // Terminal text-selection drag handles follow the scheme foreground, just like the
            // input-panel selection handles.
            tv.setTextSelectionHandleColor(buttonText);
            // The ActionMode (text-selection CAB) bar + title also follow the scheme.
            tv.setTextSelectionActionModeColors(buttonActiveBg, buttonText);
        }

        // NOTE: the status-bar styling is deliberately NOT applied here any more.
        // applyPanelColors() has exactly one caller — applyTerminalColorScheme() — which invokes
        // TermuxActivity.applySchemeColors() immediately afterwards, and that already calls
        // applySystemBarColors() with the same cached scheme background and lightness. Doing it
        // here too painted the identical values twice per application; the authoritative
        // (live-emulator) background is applied at the very end of applyTerminalColorScheme()
        // via updateBackgroundColor().
    }

    public void updateBackgroundColor() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            // NOTE: Do NOT reset mColors here. This method is also called from shell-driven OSC
            // dynamic color changes and must only *reflect* the live color array onto the activity
            // background, never overwrite it with COLOR_SCHEME (that would discard OSC 4/11 colors).
            // The live emulator color array is re-synced to COLOR_SCHEME by
            // applyTerminalColorScheme() (invoked from checkForFontAndColors(), which runs again in
            // onServiceConnected() once the session is attached after a recreate()).
            int bg = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
            TermuxActivity.applySystemBarColors(mActivity.getWindow(), bg, mActivity.isCachedSchemeLight(),
                mActivity.getEffectiveBackgroundTransparency());
        }
    }

    /**
     * Returns a {@link Properties} describing a light terminal color scheme (white background,
     * black foreground, readable 16-color palette) used when the app is in light mode and the
     * user has not defined a custom {@code ~/.termux/colors.properties}.
     *
     * Reads color values from {@code R.array.light_terminal_color_scheme_keys} and
     * {@code R.array.light_terminal_color_scheme_values} arrays.
     */
    private Properties getLightTerminalColorScheme() {
        Properties props = new Properties();
        String[] keys = mActivity.getResources().getStringArray(R.array.light_terminal_color_scheme_keys);
        String[] values = mActivity.getResources().getStringArray(R.array.light_terminal_color_scheme_values);
        int len = Math.min(keys.length, values.length);
        for (int i = 0; i < len; i++) {
            props.setProperty(keys[i], values[i]);
        }
        // cursor color is auto-picked based on background brightness by TerminalColorScheme.
        return props;
    }

    /**
     * Tint the three text-selection drag handles (left, right, paste/cursor) of the text input field
     * to the given {@code color}, so they match the terminal colour scheme instead of the theme's
     * static accent colour.
     * <p>
     * Uses reflection to call the public {@code getTextSelectHandle*()} / {@code setTextSelectHandle*()}
     * methods (public API since API 29).  The fields themselves are avoided because on modern Android
     * the hidden-API blocklist prevents accessing {@code mSelectHandleLeft} etc. via reflection.
     * <p>
     * On API < 29 this is a silent no-op (no public API, and fields are blocked on API 28+).
     * <p>
     * Silent best-effort: does nothing on API levels or ROM variants that lack these methods.
     */
    @SuppressLint("DiscouragedPrivateApi,PrivateApi")
    private static void tintSelectionHandles(@NonNull EditText editText, int tintColor) {
        try {
            Class<?> tvClass = TextView.class;
            // Public API methods available since API 29 → accessible via reflection even when
            // compileSdk < 29.  Not blocked by hidden API restrictions because they are public.
            String[][] apiDefs = {
                {"getTextSelectHandleLeft",   "setTextSelectHandleLeft"},
                {"getTextSelectHandleRight",  "setTextSelectHandleRight"},
                {"getTextSelectHandle",       "setTextSelectHandle"},
            };
            for (String[] def : apiDefs) {
                String getterName = def[0];
                String setterName = def[1];
                Method getter;
                try {
                    getter = tvClass.getMethod(getterName);
                } catch (NoSuchMethodException e) {
                    continue; // API level below 29 → skip
                }
                Object obj = getter.invoke(editText);
                if (obj instanceof Drawable) {
                    Drawable d = ((Drawable) obj).mutate();
                    d.setTint(tintColor);
                    try {
                        Method setter = tvClass.getMethod(setterName, Drawable.class);
                        setter.invoke(editText, d);
                    } catch (NoSuchMethodException ignored) {
                        // Setter not available on this ROM
                    }
                }
            }
            editText.invalidate();
        } catch (Exception e) {
            // Best-effort — silently ignore.
        }
    }

    /** Apply {@code alpha} (0–255) to the RGB of {@code color}. */
    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int deriveActiveTextColor(int foreground) {
        float[] hsv = new float[3];
        Color.colorToHSV(foreground, hsv);
        if (hsv[1] < 0.1f) {
            hsv[0] = 180f;
            hsv[1] = 0.6f;
            hsv[2] = Math.min(1f, hsv[2] * 1.3f);
        } else {
            hsv[0] = (hsv[0] + 30f) % 360f;
            hsv[2] = Math.min(1f, hsv[2] * 1.2f);
        }
        return Color.HSVToColor(hsv);
    }

}
