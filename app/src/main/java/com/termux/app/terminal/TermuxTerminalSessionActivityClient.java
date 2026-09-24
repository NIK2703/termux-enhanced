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
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
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
import com.termux.shared.termux.monet.MonetOptions;
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

    /**
     * Memoized result of {@link #resolveSchemeKey} (key + night flag + build time).
     * {@link #checkForFontAndColorsForView} needs the key on <em>every</em> pager page bind, and
     * building it stats scheme/font files, reads preferences and loads {@link MonetOptions}. The
     * cache is dropped by {@link #invalidateAppliedScheme()} (recreate / settings reload) and
     * expires after {@link #SCHEME_KEY_MAX_AGE_MS} so an external file edit is still caught;
     * in-app changes never wait for the throttle.
     */
    private String mCachedSchemeKey = null;
    private boolean mCachedSchemeKeyIsNight;
    private long mCachedSchemeKeyBuiltAtMs;

    /** How long {@link #mCachedSchemeKey} may be reused before it is rebuilt from the filesystem. */
    private static final long SCHEME_KEY_MAX_AGE_MS = 1000L;

    /** Cached terminal typeface — parsing the font file is expensive and it rarely changes. */
    private static Typeface sCachedTypeface = null;
    private static String sCachedTypefaceKey = null;
    /** When {@link #sCachedTypefaceKey} was last verified against the font file — see {@link #resolveTerminalTypeface()}. */
    private static long sCachedTypefaceCheckedAtMs;
    /** How long the cached typeface key may be trusted before the font file is stat'ed again. */
    private static final long TYPEFACE_KEY_MAX_AGE_MS = 1000L;

    /**
     * Armed in {@link #onStart()} and consumed by the FIRST
     * {@link #onSessionPageSelected(TerminalSession)} afterwards. Two one-shot effects ride on it:
     * a forced full scheme application after an activity recreate, and the single disk re-read of
     * the extra-keys session map that recovers profiles written while the activity was stopped.
     */
    private boolean mPendingPostStartRecovery = false;

    /**
     * Set only for the duration of the synchronous {@link #setCurrentSession} in
     * {@link #onStart()}. On foreground return the stored session is usually the current page, so
     * the call takes the same-index shortcut and runs {@link #onSessionPageSelected} inline; its
     * focus/IME reconcile would run on pre-resume insets and then fight the authoritative
     * {@code runKeyboardRestore()} a few milliseconds later. Scoped to that one call only: a cold
     * start selects its page later (after the async service connect), long after the flag is
     * cleared, and must keep applying focus — the only restore a cold start gets.
     */
    private boolean mDeferFocusApplyToResume = false;

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";

    /**
     * How long the tab-create rebuild window (see {@code TermuxActivity.beginSessionUiChurn})
     * suppresses keyboard-intent recording. Must cover the adapter rebuild, the page switch, the
     * per-session reconcile and the bounded post-switch re-assert (300 ms) that may follow it.
     */
    private static final long SESSION_UI_CHURN_MS = 900L;

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
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
        withTerminalView(TerminalView::onScreenUpdated);
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Warm up the SoundPool so the first bell press is not silent
        // (https://stackoverflow.com/questions/35435625). Posted one message out of onResume():
        // SoundPool needs a Looper thread, and posting keeps the cost out of the resume
        // transaction while still finishing within a frame. onBell() also loads lazily, so
        // nothing depends on this having run.
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
        // Re-apply the terminal font size to every bound page. Deliberately before the scheme
        // apply below: a size change re-lays-out the grid, and this way that grid is repainted by
        // the scheme's own pass instead of by a second one. Free when the size did not change —
        // TerminalView.setTextSize() early-returns on an unchanged size.
        applyTerminalFontSizeToAllViews();
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
                withTerminalView(TerminalView::onScreenUpdated);
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

        // Animated titles fire OSC 0/2 at 10-30 Hz — coalesce, never rebuild per event
        // (see mTitleRefreshPending).
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

        withTerminalView(terminalView -> terminalView.setTerminalCursorBlinkerState(enabled, false));
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
        withTerminalView(terminalView -> terminalView.setTerminalCursorBlinkerState(true, true));
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }

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

    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }

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
            // Same-index switch: fix the active index too. Nothing else will, because the pager
            // fires no callback for it — and the active index is what "which session is current"
            // resolves from everywhere else.
            SessionPagerManager pagerManager = mActivity.getSessionPagerManager();
            if (pagerManager != null) pagerManager.setActiveIndex(index);
            onSessionPageSelected(session);
            mActivity.setTerminalPageSwitchInProgress(false);
            return;
        }

        if (pager != null) {
            // Mark a page switch in progress BEFORE the switch so the per-page focus
            // listener does not pop the IME while the old page loses focus mid-switch
            // (scenario #InputPanel6: tab click -> setCurrentItem). onTerminalPageSelected()
            // clears this flag and becomes the single authority that re-asserts the keyboard.
            mActivity.setTerminalPageSwitchInProgress(true);
            // animate semantics: see @param animate above. onPageSelected() always fires and
            // runs onSessionPageSelected() (text-input restore, highlight, colours, cwd).
            pager.setCurrentItem(index, animate);
            // Tab click (animate == false): no intermediate onPageScrolled events, so centre the
            // selected tab ourselves from the strip's current scroll position. Scroll-only —
            // highlight stays solely with onTerminalPageSelected(). Dropped while an end-scroll
            // owns the strip; no-op when already centred.
            if (!animate) {
                TermuxSessionTabsController tabs = mActivity.getTermuxSessionTabsController();
                if (tabs != null) tabs.scrollToTabIndex(index);
            }
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

        // NOTE: the session-change toast path (notifyOfSessionChange()) was removed — the user
        // requested no popups when switching tabs. The pager itself already did the smooth scroll
        // to land here; the tab highlight is refreshed separately in onTerminalPageSelected() via
        // TermuxSessionTabsController.setCurrentSession().
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

        setCurrentSessionByIndex(service, index);
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        setCurrentSessionByIndex(service, index);
    }

    /** Make the session at {@code index} current when it exists (shared by both switch overloads). */
    private void setCurrentSessionByIndex(@NonNull TermuxService service, int index) {
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    /** Index of {@code session} in the live service list, or -1 when there is no service/session. */
    private int indexOfSession(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return -1;
        return service.getIndexOfSession(session);
    }

    /** Run {@code action} on the active terminal view when one exists. */
    private void withTerminalView(@NonNull TerminalViewAction action) {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) action.apply(terminalView);
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

    /** Convenience overload of {@link #applySessionExtraKeys(TerminalSession, boolean)} with {@code reloadMap = true}. */
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
                // Full pager sync now: the layout pass binds the new page via attachSession()
                // → updateSize(), which only resizes because the emulator is already initialized
                // (no fork on the UI thread). onTerminalPageSelected() completes once the page
                // exists — all without blocking the UI.
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

        // ── Capture the state the new tab must inherit, BEFORE anything is rebuilt ─────────────
        // createTermuxSession() notifies the session list synchronously, so the pager runs the
        // new page's keyboard reconcile INSIDE the call below — before anything is seeded. That
        // reconcile falls back to the GLOBAL keyboard intent; if it disagrees with the real IME
        // state it hides/shows the keyboard, and the second reconcile (from setCurrentSession(),
        // reading the seeded record) undoes it — visible keyboard flicker on every new tab.
        // Seeding the real state here makes both reconciles no-ops.
        final boolean inheritedKeyboard = mActivity.computeImeVisibility();
        final boolean inheritedPanel = mActivity.isTextInputVisible();
        final TerminalSession outgoingSession = mActivity.getCurrentSession();
        final boolean inheritedFocusOnInput = mActivity.isFocusOnInputForSession(outgoingSession);
        mActivity.getTextInputState().setSoftKeyboardVisibleIntent(inheritedKeyboard);
        if (outgoingSession != null)
            mActivity.getTextInputState().setSoftKeyboardIntent(outgoingSession, inheritedKeyboard);

        // Open the rebuild window before the session is added: adding it rebuilds the adapter and
        // can detach the served IME target, and the system's resulting IME HIDE must not be
        // recorded as a keyboard intent (see TermuxActivity.beginSessionUiChurn).
        mActivity.beginSessionUiChurn(SESSION_UI_CHURN_MS);
        // Mark the create itself as in flight. The reconcile that runs INSIDE the call below sees
        // the new session as current while it still has no per-session record, and both the panel
        // fallback and the "keyboard state follows tab switch = OFF" correction would misread that
        // as "this tab has no open panel / no open keyboard" (see
        // TermuxActivity.isKbStateCreateInProgress). The window closes with the churn runnable.
        mActivity.beginKbStateCreate();

        TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
        if (newTermuxSession == null) {
            mActivity.endKbStateCreate();
            return null;
        }
        TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
        // Seed the new tab with the captured state, so the reconcile authority
        // (applyTextInputVisibilityForSession) has a per-session record that matches what the user
        // was looking at instead of the "current panel" fallback, which races the switch.
        mActivity.getTextInputState().setSoftKeyboardIntent(newTerminalSession, inheritedKeyboard);
        mActivity.getTextInputState().setVisible(newTerminalSession.mHandle, inheritedPanel);
        mActivity.getTextInputState().setFocusOnInput(newTerminalSession,
                inheritedPanel && inheritedFocusOnInput);
        // The new tab mirrors the live state by construction, so exempt it from the
        // "keyboard state follows tab switch = OFF" correction while the create rebuild settles:
        // that correction reads the live IME, which reports "hidden" while the pager has the
        // served view detached, and closed the inherited open keyboard on every new tab.
        mActivity.setKbStateInheritedFromCreate(newTerminalSession);
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
     * <p>
     * The tab starts in the <em>configured default working directory</em>, not in a copy of the
     * directory the currently-visible session happens to sit in: the "+" button means "give me a
     * plain new tab", and silently inheriting an arbitrary {@code cd} made the result depend on
     * whatever the user had been doing. Picking a specific directory is the job of the
     * directory-history popup ({@link #addNewSessionInDirectory}) and of the right-swipe picker
     * ({@link #createSessionForPlaceholder}), which both pass an explicit path.
     */
    public void addNewSession(boolean isFailSafe, String sessionName) {
        createNewSession(isFailSafe, sessionName,
                mActivity.getProperties().getDefaultWorkingDirectory(),
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
        return createSessionForPlaceholder(isFailSafe, sessionName, null);
    }

    /**
     * Same as {@link #createSessionForPlaceholder(boolean, String)}, but starting in an explicit
     * directory. Used by the right-swipe picker, whose whole purpose is to choose the directory:
     * the release position selects a visited directory or falls back to the configured default
     * working directory.
     *
     * @param directory working directory; null falls back to the current session's cwd or the
     *                  default working directory (see {@link #createNewSession}).
     */
    public TermuxSession createSessionForPlaceholder(boolean isFailSafe, String sessionName,
            @Nullable String directory) {
        return createNewSession(isFailSafe, sessionName, directory,
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

    /**
     * Persist the current session handle so a later {@link #onStart()} can restore it.
     * <p/>
     * Only writes when a session is bound: a background recreate (theme change, day/night switch)
     * runs {@code onStop()} before {@code onServiceConnected()}, so {@code getCurrentSession()}
     * is null — writing null then deleted the stored handle and every theme change silently
     * jumped to the rightmost tab. Keeping the old handle is safe: the restore path validates it
     * against the live list and falls back when the session no longer exists.
     */
    public void setCurrentStoredSession() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
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
        // Same reasoning for the bounded intent-recording window: the page-switch flag above is
        // cleared one frame after the reconcile, which can be BEFORE the system delivers the IME
        // HIDE caused by detaching the closed page's view.
        mActivity.beginSessionUiChurn(SESSION_UI_CHURN_MS);

        // Decide the heir BEFORE the removal, while both neighbours are still in the list (the
        // returned index would be meaningless after the shift). Closing the active tab lands on
        // its right neighbour (pickHeir); a background close keeps the session the user is on —
        // passing the heir there dragged the user to a neighbour of a tab they were not viewing.
        final TerminalSession active = mActivity.getCurrentSession();
        final boolean closingIsActive = (active == finishedSession);
        final TerminalSession target = closingIsActive ? pickHeir(service, finishedSession) : active;

        // Step the pager off the page that is about to disappear BEFORE the list changes — but only
        // when the page going away is the one on screen. Removing the page the pager is anchored on
        // is what leaves the dead ViewHolder on screen and the parked index stale; see
        // SessionPagerManager.parkOnSessionBeforeRemoval() for the measured evidence. With the
        // right-neighbour policy the heir sits at removedIndex+1 before the removal and at
        // removedIndex after it, so the park lands one page PAST the final target; the sync below
        // then steps back one page (a real move, not the no-op it is when the heir is the left
        // neighbour). The park's job is only to get off the doomed page — where it lands exactly is
        // the sync's business.
        SessionPagerManager pagerManager = mActivity.getSessionPagerManager();
        if (closingIsActive && pagerManager != null) {
            pagerManager.parkOnSessionBeforeRemoval(target);
        }

        service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            // Sync pager and tabs with `target`. For a background close it is the session the user is
            // on: its page may shift index (the closed tab was to its left), so the sync still has
            // to re-anchor the pager — but on the SAME session, which is why the user sees no switch.
            termuxSessionListNotifyUpdated(target);
        }

        // The deferred page-switch bookkeeping (onTerminalPageSelected ->
        // applyTextInputVisibilityForSession -> reconcile) runs on a posted runnable, AFTER
        // this method returns. Clear the close-suppression flag in a posted runnable as well
        // so it stays raised for the whole close+reconcile window.
        mActivity.getWindow().getDecorView().post(() ->
                mActivity.setTerminalPageSwitchInProgress(false));
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated();
    }

    public void termuxSessionListNotifyUpdated(@androidx.annotation.Nullable TerminalSession target) {
        mActivity.termuxSessionListNotifyUpdated(target);
    }

    /**
     * Which session takes over when {@code closingSession} is the <b>active</b> tab: the
     * <b>RIGHT</b> neighbour (slides into the freed slot; closing 3 of 1-2-3-4 lands on 4 — the
     * old left-neighbour behaviour was never a deliberate policy, just the pager clamping a
     * stale old-list index), or the LEFT one when the closed tab was the last. The single place
     * the "which tab after close" policy lives.
     *
     * <p>Call <b>before</b> removal (neighbours must still be in place); returns a session, never
     * an index — the pager resolves its position in the new list. Only answers the ACTIVE-tab
     * case: a background close keeps the current session (see {@link #removeFinishedSession}).
     *
     * <p>Cost note: post-removal the heir sits one index earlier, so the pre-removal park lands
     * one page past the final target and the sync steps back — the park's only job is getting
     * off the doomed page (see {@link SessionPagerManager#parkOnSessionBeforeRemoval}).
     */
    @androidx.annotation.Nullable
    private static TerminalSession pickHeir(@androidx.annotation.NonNull TermuxService service,
                                            @androidx.annotation.NonNull TerminalSession closingSession) {
        final int removedIndex = service.getIndexOfSession(closingSession);
        if (removedIndex < 0) return null;
        if (removedIndex + 1 < service.getTermuxSessionsSize()) {
            TermuxSession right = service.getTermuxSession(removedIndex + 1);
            if (right != null) return right.getTerminalSession();
        }
        if (removedIndex - 1 >= 0) {
            TermuxSession left = service.getTermuxSession(removedIndex - 1);
            if (left != null) return left.getTerminalSession();
        }
        return null;
    }

    /**
     * Identity of everything {@link #applyTerminalColorScheme} consumes. Cheap to build
     * (a few stat() calls) compared to the application itself (file read + parse, TTF parse,
     * drawable allocation, activity restyle, full terminal repaint).
     */
    private String buildSchemeKey(boolean isNight) {
        File colorsFile = ColorSchemeUtils.getColorSchemeFileForTheme(isNight);
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        // WHICH scheme is selected lives in the SharedPreferences, not in a scheme file: switching
        // Default <-> Monet <-> Monet-<variant> touches neither colors.*.properties
        // nor the Monet palette, so without this the key would be identical before and
        // after the switch and the new selection would silently never be applied.
        // (It used to be derived from the mtime of ~/.termux/termux.properties, which no longer
        // holds the selection.)
        String selectedScheme = ColorSchemeUtils.getSelectedSchemeName(mActivity, isNight);
        // Same for the monet-* tunables: they feed the generated palette but have no file of
        // their own, so their signature is folded in explicitly.
        int monetOptions = MonetOptions.load().revision();
        // Monet has no file on disk, so mtime/size cannot detect a wallpaper change. Its
        // token is a plain volatile read, which is why it is safe to include here even though this
        // runs on every tab switch.
        long monetToken = ColorSchemeUtils.monetToken(isNight);
        // The preferences the derived floating-control colours are computed from, excluding the
        // element-alpha sliders (those flow through the same path but are not part of the scheme
        // identity). The background-transparency and contrast-background switches both change those
        // colours without touching any scheme file or the palette, so without them here the
        // "scheme already applied" gate would swallow the change and the settings screen would need
        // a recreate to show it.
        TermuxAppSharedPreferences prefs = mActivity.getPreferences();
        return (isNight ? "n1" : "n0")
                + "|" + (colorsFile == null ? "-" : colorsFile.lastModified() + ":" + colorsFile.length())
                + "|" + (fontFile == null ? "-" : fontFile.lastModified() + ":" + fontFile.length())
                + "|s" + selectedScheme
                + "|o" + monetOptions
                + "|monet" + monetToken
                + "|t" + prefs.getTerminalBackgroundTransparency()
                + "|c" + prefs.isContrastFloatingElementBackgroundEnabled();
    }

    /**
     * {@link #buildSchemeKey(boolean)} with the Monet scheme generated first, so the token
     * in the key is the final one and does not change again right after the scheme was applied.
     *
     * <p>Memoized — see {@link #mCachedSchemeKey} for why this matters on the pager's bind path.
     */
    private String resolveSchemeKey(boolean isNight) {
        final long now = SystemClock.uptimeMillis();
        final String cached = mCachedSchemeKey;
        if (cached != null && mCachedSchemeKeyIsNight == isNight
                && now - mCachedSchemeKeyBuiltAtMs < SCHEME_KEY_MAX_AGE_MS) {
            return cached;
        }
        // Generate the Monet palette first so the token folded into the key is the final one and
        // does not change again right after the scheme was applied.
        ColorSchemeUtils.warmUpMonet(mActivity, isNight);
        final String key = buildSchemeKey(isNight);
        mCachedSchemeKey = key;
        mCachedSchemeKeyIsNight = isNight;
        mCachedSchemeKeyBuiltAtMs = now;
        return key;
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
        // And the memoized key itself, or the re-read below would be handed the value the gate was
        // just cleared for and the whole reload would be a no-op.
        mCachedSchemeKey = null;
        sCachedTypefaceCheckedAtMs = 0L;
    }

    /**
     * Resolve the terminal typeface, parsing the font file only when it actually changed
     * (mtime + size). {@code Typeface.createFromFile()} is a full TTF/OTF parse and used to run on
     * every page bind.
     *
     * <p>Like {@link #resolveSchemeKey}, the <em>check</em> is memoized as well as the result: it is
     * three filesystem calls ({@code exists}, {@code lastModified}, {@code length}) and it runs on
     * every page bind, i.e. inside the settle of the swipe that opened a tab. Re-verifying at most
     * once per {@link #TYPEFACE_KEY_MAX_AGE_MS} still catches a font dropped in by another app,
     * while an in-app change goes through {@link #invalidateAppliedScheme()}.
     */
    private static Typeface resolveTerminalTypeface() {
        final long now = SystemClock.uptimeMillis();
        if (sCachedTypeface != null && now - sCachedTypefaceCheckedAtMs < TYPEFACE_KEY_MAX_AGE_MS) {
            return sCachedTypeface;
        }
        final File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        final String key = (fontFile == null) ? "-"
                : fontFile.lastModified() + ":" + fontFile.length();
        if (sCachedTypeface != null && key.equals(sCachedTypefaceKey)) {
            sCachedTypefaceCheckedAtMs = now;
            return sCachedTypeface;
        }
        Typeface tf = (fontFile != null && fontFile.exists() && fontFile.length() > 0)
                ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
        sCachedTypeface = tf;
        sCachedTypefaceKey = key;
        sCachedTypefaceCheckedAtMs = now;
        return tf;
    }

    /**
     * Make sure the global {@link TerminalColors#COLOR_SCHEME} reflects {@code key}, reading and
     * parsing the scheme file only when it does not. Both the activity-level
     * {@link #applyTerminalColorScheme} and the per-page {@link #checkForFontAndColorsForView}
     * funnel through here, so binding a page no longer re-reads {@code colors.properties} from
     * disk — and vice versa.
     *
     * @return true when the palette was actually reloaded, i.e. {@code key} differed from the one
     *         currently reflected by the global scheme. Callers use this to decide whether the
     *         things <em>derived</em> from the palette — the emulators' cached colours, the
     *         repaint — have to be redone; when it is false they are provably still current, and
     *         redoing them costs a full page repaint and (worse) wipes any dynamic palette a
     *         program set with OSC 4/11/12.
     */
    private boolean ensureColorSchemeLoaded(boolean isNight, String key) {
        if (key.equals(mLoadedColorSchemeKey)) return false;
        // One shared resolution chain: Termux:Style file -> Monet, but ONLY when this theme
        // actually selected one of the Monet entries -> the built-in light/dark scheme.
        // Routing through ColorSchemeUtils is what stops "Default" from being silently replaced by
        // the Monet scheme, and it keeps every surface (activity, per-page bind, extra-keys
        // editor) in agreement.
        ColorSchemeUtils.applyColorSchemeForTheme(mActivity, isNight,
                isNight ? null : getLightTerminalColorScheme());
        mLoadedColorSchemeKey = key;
        return true;
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
     * Apply the current terminal font and colour scheme to a specific {@link TerminalView} (a
     * pager page) without touching the shared bottom panel. Used to theme pages as they are
     * (re)bound in the horizontal pager.
     *
     * <p>Everything below the key is <em>conditional</em> on {@code ensureColorSchemeLoaded}
     * having reloaded the palette — this runs on every page bind, and both writes it used to do
     * unconditionally were harmful: {@code mColors.reset()} overwrote live OSC 4/11/12 colours
     * (breaking the placeholder match), and {@code onScreenUpdated()} snapped a scrolled view
     * to the bottom. With an unchanged key neither is needed: a fresh page paints itself, and a
     * re-bound one was already repainted by {@link #applyTerminalColorScheme}.
     */
    public void checkForFontAndColorsForView(@NonNull TerminalView terminalView) {
        final boolean isNight = TermuxActivity.isNightModeActive();
        try {
            // Only pay for the disk read when the global palette is not already current — and this
            // is also the signal for whether anything below has to run at all.
            final boolean schemeReloaded =
                    ensureColorSchemeLoaded(isNight, resolveSchemeKey(isNight));

            if (schemeReloaded) {
                TerminalEmulator emulator = terminalView.mEmulator;
                if (emulator == null) {
                    TerminalSession session = terminalView.getCurrentSession();
                    if (session != null) emulator = session.getEmulator();
                }
                if (emulator != null) {
                    emulator.mColors.reset();
                }
                terminalView.invalidate();
                terminalView.onScreenUpdated();
            }

            // Always attempted, but cheap: setTypeface() early-returns on an unchanged typeface,
            // and this call is what keeps a page bound AFTER a scheme change in sync. Cached: a page
            // bind no longer stats + parses the font file either.
            terminalView.setTypeface(resolveTerminalTypeface());

            // Same reasoning as the typeface above: the scrollbar belongs to THIS view, so a page
            // bound here would otherwise keep the hardcoded fallback tint, which — unlike the
            // input-panel toggle button — ignores the element-opacity sliders.
            applyScrollbarColorsTo(terminalView);
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

            // The placeholder page is not a terminal page: its overlay (the "+ new session" block and
            // the directory menu) takes its colours at bind time and is NOT rebound by a scheme
            // change, so it has to be restyled explicitly. Runs after resetAllEmulatorColors() above,
            // which is what getCurrentTerminalColor() reads the new palette from.
            mActivity.applyPlaceholderColors();
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
     * @param resyncScreen whether to also run {@link TerminalView#onScreenUpdated()} on the
     *                     <em>active</em> page. Must be {@code false} when the repaint is not caused
     *                     by screen content (e.g. an OSC 4/11 palette change): onScreenUpdated() snaps
     *                     a scrolled view back to the bottom ({@code mTopRow = 0}), which would
     *                     silently throw away the user's scroll position.
     */
    private void invalidateAllTerminalViews(@Nullable Typeface typeface, boolean resyncScreen) {
        final TerminalView active = mActivity.getTerminalView();
        forEachBoundTerminalView(terminalView -> {
            terminalView.invalidate();
            // Resync only the page in view — see @param resyncScreen: running it on background
            // pages would snap their scroll to the bottom; invalidate() alone is a full repaint.
            if (resyncScreen && terminalView == active) terminalView.onScreenUpdated();
            if (typeface != null) terminalView.setTypeface(typeface);
        });
    }

    /**
     * Re-apply the configured terminal font size to every page the pager keeps bound.
     *
     * <p><b>Not just the active page.</b> The pager keeps the current page's neighbours bound
     * ({@code setOffscreenPageLimit(1)}, {@code SessionPagerManager:331}) and a neighbour that comes
     * back on screen is <em>not</em> rebound — {@code onBindViewHolder} only runs for a page that
     * enters the offscreen window — so a size applied to the active page alone stays stale on its
     * neighbours until they are recycled out and back in. Both entry points for the size (the pinch
     * gesture and the Display settings slider) therefore go through here.
     *
     * <p>Cheap by construction: {@code TerminalView.setTextSize()} early-returns on an unchanged
     * size, so pages that are already correct cost one int comparison each. That is what lets this
     * be the single funnel for every path that can set the size — the pinch, the Ctrl+Alt
     * shortcut, a styling reload and the Display settings slider alike.
     */
    public void applyTerminalFontSizeToAllViews() {
        final int fontSize = mActivity.getPreferences().getFontSize();
        forEachBoundTerminalView(terminalView -> terminalView.setTextSize(fontSize));
    }

    /**
     * Run {@code action} for the active terminal view and for every other page the adapter owns a
     * view for — the one walk both {@link #invalidateAllTerminalViews} and
     * {@link #applyTerminalFontSizeToAllViews} need. The set comes from the adapter (pages plus
     * trailing placeholder) via {@link TerminalPagerAdapter#forEachPageTerminalView}, not a walk
     * over the pager's children: RecyclerView children are page <em>containers</em>, so the old
     * {@code instanceof TerminalView} loop was dead code and styling changes reached only the
     * active page until its slot happened to be rebound.
     */
    private void forEachBoundTerminalView(@NonNull TerminalViewAction action) {
        final TerminalView active = mActivity.getTerminalView();
        if (active != null) action.apply(active);

        androidx.viewpager2.widget.ViewPager2 pager = mActivity.getTerminalPager();
        if (pager == null) return;
        RecyclerView.Adapter<?> adapter = pager.getAdapter();
        if (!(adapter instanceof TerminalPagerAdapter)) return;
        ((TerminalPagerAdapter) adapter).forEachPageTerminalView(view -> {
            if (view == active) return; // already handled above
            action.apply(view);
        });
    }

    /** The per-page action {@link #forEachBoundTerminalView} applies. */
    private interface TerminalViewAction {
        void apply(@NonNull TerminalView terminalView);
    }

    /**
     * Apply the bottom-panel styling derived from the active terminal color scheme.
     * - The panel (toolbar container + extra-keys view) background is made transparent so only the
     *   buttons themselves show a background.
     * - Each button background becomes a translucent overlay whose tone is picked from the scheme
     *   lightness: dark translucent for light schemes, light translucent for dark schemes.
     * - The alpha (transparency) of the button backgrounds is read from user preferences so the
     *   value is applied ONCE at change time, not recomputed on every frame.
     * - The input-panel toggle button and the terminal scrollbar thumb, drawn on the terminal
     *   itself, instead carry the terminal background inside their translucent colour.
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

        // The controls drawn on the terminal itself; also re-run on its own when the background
        // changes without the scheme doing so.
        applyFloatingControlColors();

        // Plain tab button (new session): no stroke — fill only, with an active
        // (pressed/swiped) background so the press/swipe gesture gives visible feedback.
        // Use the same scheme-derived colours as the extra-keys buttons so the contrast
        // between idle and active states is clearly visible.
        ImageButton newSessionBtn = mActivity.findViewById(R.id.new_session_tab_button);
        if (newSessionBtn != null) {
            // Active background: a clearly visible highlight. The scheme-derived
            // buttonActiveBg is only a faint (default ~12% alpha) tint, so compose a
            // stronger overlay of the active colour onto the terminal background and
            // present it at high opacity so the idle -> active change is unmistakable.
            int activeFill = withAlpha(
                    TermuxColorSchemeManager.compositeColors(
                            mActivity.getColorSchemeManager().getSchemeBackground(),
                            ColorSchemeUtils.getButtonActiveBackground(isSchemeLight, 55)),
                    230);
            applyCircleButtonStyle(newSessionBtn,
                    createOvalStateListDrawable(buttonBg, activeFill), buttonText);
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

        withTerminalView(tv -> {
            // Terminal text-selection drag handles follow the scheme foreground, just like the
            // input-panel selection handles.
            tv.setTextSelectionHandleColor(buttonText);
            // The ActionMode (text-selection CAB) bar + title also follow the scheme.
            tv.setTextSelectionActionModeColors(buttonActiveBg, buttonText);
        });

        // NOTE: the status-bar styling is deliberately NOT applied here any more.
        // applyPanelColors() has exactly one caller — applyTerminalColorScheme() — which invokes
        // TermuxActivity.applySchemeColors() immediately afterwards, and that already calls
        // applySystemBarColors() with the same cached scheme background and lightness. Doing it
        // here too painted the identical values twice per application; the authoritative
        // (live-emulator) background is applied at the very end of applyTerminalColorScheme()
        // via updateBackgroundColor().
    }

    /**
     * Style the two controls drawn on the terminal itself: the input-panel toggle button and the
     * scrollbar thumb. They share one pair of cached colours, since they are the same kind of
     * element and sit side by side — see {@link TermuxColorSchemeManager#getFloatingButtonFill()}.
     *
     * <p>Split out of {@link #applyPanelColors(boolean)} because their colour follows the
     * <em>live</em> terminal background, so {@link #updateBackgroundColor()} re-runs just this.
     */
    private void applyFloatingControlColors() {
        final int fill = mActivity.getFloatingButtonFill();
        final int stroke = mActivity.getFloatingButtonStroke();
        final int strokePx = Math.round(mActivity.getResources().getDimension(R.dimen.terminal_text_input_stroke));

        ImageButton toggleBtn = mActivity.findViewById(R.id.toggle_text_input_button);
        if (toggleBtn != null) {
            // Pressed and focused both take the active colour for fill and stroke.
            final android.graphics.drawable.GradientDrawable active =
                    createOvalDrawable(stroke, strokePx, stroke);
            android.graphics.drawable.StateListDrawable states =
                    new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{ android.R.attr.state_pressed }, active);
            states.addState(new int[]{ android.R.attr.state_focused }, active);
            states.addState(new int[]{}, createOvalDrawable(fill, strokePx, stroke));
            applyCircleButtonStyle(toggleBtn, states, mActivity.getButtonText());
        }

        // Every bound page, not just the active one: a neighbour bound while it was off screen
        // would otherwise keep whatever colours it last saw.
        forEachBoundTerminalView(this::applyScrollbarColorsTo);
    }

    /** Push the current floating-control colours onto one page's scrollbar thumb. */
    private void applyScrollbarColorsTo(@NonNull TerminalView terminalView) {
        terminalView.setScrollbarColors(mActivity.getFloatingButtonFill(),
                mActivity.getFloatingButtonStroke());
    }

    /** A plain oval fill (no stroke). */
    static android.graphics.drawable.GradientDrawable createOvalDrawable(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** An oval fill with a stroke of {@code strokePx} in {@code strokeColor}. */
    static android.graphics.drawable.GradientDrawable createOvalDrawable(int color, int strokePx,
                                                                        int strokeColor) {
        android.graphics.drawable.GradientDrawable d = createOvalDrawable(color);
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    /** Oval {@code activeFill} on pressed/selected, {@code idleColor} otherwise. */
    static android.graphics.drawable.StateListDrawable createOvalStateListDrawable(int idleColor,
                                                                                  int activeFill) {
        android.graphics.drawable.GradientDrawable idle = createOvalDrawable(idleColor);
        android.graphics.drawable.GradientDrawable active = createOvalDrawable(activeFill);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        // Pressed (tap / swipe in progress) shows the active background.
        states.addState(new int[]{android.R.attr.state_pressed}, active);
        states.addState(new int[]{android.R.attr.state_selected}, active);
        states.addState(new int[]{}, idle);
        return states;
    }

    /** Install {@code states} on a circle button and tint its icon with the scheme foreground. */
    private static void applyCircleButtonStyle(ImageButton button,
                                               android.graphics.drawable.StateListDrawable states,
                                               int textColor) {
        button.setBackground(states);
        button.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_ATOP);
        button.setForeground(null);
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
            // A background that changed without the scheme changing (OSC 4/11, or a tab whose
            // session repainted its own palette) leaves the floating controls mixed with a colour
            // the terminal no longer shows.
            if (mActivity.getColorSchemeManager().refreshFloatingColorsForBackground(bg)) {
                applyFloatingControlColors();
            }
            TermuxActivity.applySystemBarColors(mActivity.getWindow(), bg, mActivity.isCachedSchemeLight(),
                mActivity.getEffectiveBackgroundTransparency());
        }
    }

    /**
     * Returns a {@link Properties} describing a light terminal color scheme (white background,
     * black foreground, readable 16-color palette) used when the app is in light mode and the
     * user has not defined a custom {@code ~/.termux/colors.properties}.
     *
     * <p>Delegates to {@link ColorSchemeUtils#getBuiltinLightSchemeProperties}, which owns the
     * values, so the terminal and the color-scheme picker's "Default" preview cannot disagree.
     */
    private Properties getLightTerminalColorScheme() {
        // The cursor color is auto-picked based on background brightness by TerminalColorScheme.
        return ColorSchemeUtils.getBuiltinLightSchemeProperties(mActivity);
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
