package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.Window;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxServiceConnectionManager;
import com.termux.app.terminal.TermuxSessionSnapshotManager;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.SessionPagerManager;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxSessionTabsController;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.app.terminal.TermuxSchemeTheme;
import com.termux.app.terminal.TermuxActivityViewHelper;
import com.termux.app.terminal.TermuxActivityBroadcastManager;
import com.termux.app.terminal.TermuxDialogs;
import com.termux.app.terminal.TermuxActivityPopupController;
import com.termux.app.terminal.io.TextInputPanelController;
import com.termux.app.terminal.io.autocomplete.AutoCompleteController;
import com.termux.app.terminal.io.autocomplete.DirectoryHistoryController;
import com.termux.app.terminal.io.autocomplete.DirectoryHistoryPopupController;
import com.termux.app.terminal.io.SessionUiStateStore;
import com.termux.app.terminal.io.autocomplete.MessageHistoryController;
import com.termux.app.terminal.io.FullScreenWorkAround;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.shared.view.ImeVisibilityDetector;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;


/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements TextInputPanelController.Host, TermuxActivityPopupController.Host {

    /**
     * Owns the {@link TermuxService} binding for this activity. Created in {@link #onCreate(Bundle)}
     * and used to start/bind/unbind the service and to access the bound {@link TermuxService}.
     */
    TermuxServiceConnectionManager mServiceConnectionManager;

    /**
     * The terminal view shown in  {@link TermuxActivity} that displays the terminal.
     * <p/>
     * With the horizontal session pager (ViewPager2), this field is a <em>dynamic</em> pointer to
     * the {@link TerminalView} of the <b>currently selected</b> page. It is updated in
     * {@link #onTerminalPageSelected(int)} whenever the user swipes to a different session, so the
     * rest of the codebase that calls {@link #getTerminalView()} keeps working unchanged.
     */
    TerminalView mTerminalView;

    /**
     * Manager that owns all ViewPager2 horizontal session-pager logic (page selection, adapter
     * sync, IME-churn guards). Created in {@link #setTermuxTerminalViewAndClients()} once the pager
     * view is resolved.
     */
    SessionPagerManager mSessionPagerManager;

    public void setPendingInitialSession(@Nullable TerminalSession session) {
        if (mSessionPagerManager != null) mSessionPagerManager.setPendingInitialSession(session);
    }

    public void setColdStartSessionPending(boolean pending) {
        if (mSessionPagerManager != null) mSessionPagerManager.setColdStartSessionPending(pending);
    }

    public boolean isColdStartSessionPending() {
        return mSessionPagerManager != null && mSessionPagerManager.isColdStartSessionPending();
    }

    /**
     * Populate the pager with the live session list and select the initial page. Called from
     * {@link #onServiceConnected} once sessions exist. Honours a pending session requested earlier
     * by {@code setCurrentSession}, otherwise restores the stored/last session.
     */
    public void syncTerminalPagerToService() {
        if (mSessionPagerManager != null) mSessionPagerManager.syncTerminalPagerToService();
    }

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * Persists/restores the open terminal tabs (working directory, name, failsafe
     * flag) plus the active tab index across app restarts.
     */
    TermuxSessionSnapshotManager mSessionSnapshotManager;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The termux session tabs controller.
     */
    TermuxSessionTabsController mTermuxSessionTabsController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private TermuxActivityBroadcastManager mBroadcastManager = null;
    private TermuxActivityViewHelper mViewHelper = null;

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * True once onPause() has run (app going to background / screen turning off).
     * Used to suppress the IME-hidden handler that would otherwise close the text
     * input panel when the soft keyboard is dismissed by the system on pause.
     */
    private boolean mIsPaused = false;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /** Static handle of the live activity, used only by debug-build automation hooks (src/debug). */
    private static volatile TermuxActivity sInstance;

    /** True while the onResume focus/panel-visibility restore path is executing. */
    private boolean mResumeFocusRestore = false;

    /** Whether the onResume focus/panel-visibility restore path is executing (debug hook). */
    public boolean isResumeFocusRestore() {
        return mResumeFocusRestore;
    }

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;
    // Last known IME (soft keyboard) height in px, taken from WindowInsetsCompat.Type.ime().
    // This is an INDEPENDENT signal of the real keyboard height, not derived from
    // getWindowVisibleDisplayFrame(), so it is reliable even when the visible-frame reading
    // is garbage (e.g. during an IME-height change in multi-window). TermuxActivityRootView
    // uses it to reject implausible bottom-margin measurements. Zero when the IME is hidden
    // or insets are unavailable (API < 30 without ADJUST_RESIZE).
    private int mLastImeBottomPx = 0;
    // Tracks the last known IME (soft keyboard) visibility so we can react to it
    // being hidden while the text input panel is open.
    private boolean mSoftKeyboardVisible = false;

    /** IME visibility as reported by {@link WindowInsetsCompat.Type#ime()} (insets method).
     *  Combined with {@link ImeVisibilityDetector} (visible-frame method) via OR logic:
     *  IME is considered visible if EITHER method reports visible. This handles three
     *  fallback situations:
     *  1) API < 30 without ADJUST_RESIZE → insets always returns 0, frame works
     *  2) Floating/undocked keyboard → frame may miss it, insets catches it (API 30+)
     *  3) Startup/recreate race → whichever fires first sets state, second confirms */
    private boolean mImeVisibleFromInsets = false;

    /** True while the terminal toolbar is temporarily shown just for the text input panel. */
    private boolean mToolbarTemporarilyShownForTextInput = false;

    /** Non-null while the user's finger is on the toggle-text-input button. */
    private boolean mButtonTouchInProgress = false;

    private TextInputPanelController mTextInputPanel;
    private AutoCompleteController mAutoCompleteCtrl;
    private TermuxActivityPopupController mPopupCtrl;
    private FullScreenWorkAround mFullScreenWorkAround;
    private ImeVisibilityDetector mImeDetector;

    /**
     * True for a short window right after onStart(), i.e. just after the app
     * returned from the background / was recreated. Used to suppress the
     * IME-hidden auto-close of the text input panel: on return the system may
     * emit transient "IME visible -> hidden" insets frames (especially if the
     * keyboard was active before backgrounding, or on a config change). Without
     * this guard the panel would be closed by that spurious transition. The flag
     * is cleared shortly after by a delayed handler, so normal in-foreground
     * keyboard dismissal still closes the panel as expected.
     */
    private boolean mJustResumed = false;

    /**
     * True while a pager page switch is in progress (an instant tab click -> setCurrentItem, or the
     * smooth settle of a swipe / programmatic animated switch). While set, the per-page focus listener
     * ({@code TermuxTerminalViewClient.registerTerminalViewFocusListener}) suppresses ALL IME
     * show/hide churn so the old page losing focus mid-switch cannot pop the keyboard.
     * {@link #onTerminalPageSelected(int)} is the single authority that re-asserts the keyboard
     * for the landed page and clears this flag. See scenario #InputPanel6.
     */
    private boolean mTerminalPageSwitchInProgress = false;

    /** Returns the live activity for debug-build automation hooks, or null when none exists. */
    public static TermuxActivity getInstance() {
        return sInstance;
    }

    public void setTerminalPageSwitchInProgress(boolean inProgress) {
        mTerminalPageSwitchInProgress = inProgress;
    }

    public boolean isTerminalPageSwitchInProgress() {
        return mTerminalPageSwitchInProgress;
    }

    /**
     * Whether the ViewPager2 is currently animating a page scroll — i.e. its scroll state is
     * DRAGGING (finger down) or SETTLING (the settle animation), as opposed to IDLE.
     *
     * Derived live from the pager instead of tracked in a private boolean: a hand-rolled flag has
     * to be cleared from exactly the right callback, and getting that wrong either leaks it (the
     * button margin freezes forever) or drops it too early — which is precisely the bug this
     * replaced. The framework's own scroll state is already exact and self-healing, and
     * {@code ScrollEventAdapter} assigns {@code mScrollState} BEFORE dispatching
     * onPageScrollStateChanged, so it reads correctly from inside the callbacks too.
     *
     * While this is true, {@code onPageScrolled()} is the SOLE owner of the floating toggle
     * button's marginEnd and the settled-state writers must keep their hands off it. Deliberately
     * separate from {@link #mTerminalPageSwitchInProgress}: that one is an IME-suppression flag
     * whose lifetime is tied to focus delivery, not to the scroll animation, so reusing it here
     * left the button unguarded for most of the settle.
     */
    public boolean isPagerScrollInProgress() {
        androidx.viewpager2.widget.ViewPager2 pager = getTerminalPager();
        return pager != null
            && pager.getScrollState() != androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE;
    }

    /** Single store of per-session + global UI state (supersedes TextInputSessionStateManager). */
    private final SessionUiStateStore mTextInputState = new SessionUiStateStore();

    private float mTerminalToolbarDefaultHeight;

    // ---- Sent-message history (shown as a context menu on pencil-button swipe) ----

    /** Message history controller — owns command history list and per-directory store. */
    private MessageHistoryController mMessageHistoryCtrl = null;
    private boolean mPerDirectoryMessageHistory = false;



    /** Cached color scheme manager — computes and vends all scheme-derived colours. */
    private final TermuxColorSchemeManager mColorSchemeManager = new TermuxColorSchemeManager();



    /** Default max number of remembered messages (overridable in Settings). */
    private static final int MESSAGE_HISTORY_MAX_DEFAULT = 20;




    /** Directory history controller — owns visited-CWD list. */
    private DirectoryHistoryController mDirectoryHistoryCtrl = null;

    /** Directory-history popup controller — owns the directory popup UI + gesture state. */
    private DirectoryHistoryPopupController mDirectoryHistoryPopupCtrl = null;

    /** Default max number of remembered directories. */
    private static final int DIRECTORY_HISTORY_MAX_DEFAULT = 20;

    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_FONT_ID = 12;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_TEXT_INPUT_PER_SESSION = "text_input_per_session";
    private static final String ARG_TEXT_INPUT_CARET_PER_SESSION = "text_input_caret_per_session";
    private static final String ARG_TEXT_INPUT_VISIBLE_PER_SESSION = "text_input_visible_per_session";
    private static final String ARG_FOCUS_ON_INPUT_PER_SESSION = "focus_on_input_per_session";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";
    private static final String PREF_TEXT_INPUT_VISIBLE = "text_input_visible";
    private static final String PREF_MESSAGE_HISTORY = "message_history";

    /** Pref key (in termux_prefs) holding the L3 (process-death) UI state JSON. */
    private static final String PREF_UI_STATE_JSON = "ui_state_json";

    /** Keyboard-restore intent captured in onResume (before any insets churn). */
    private boolean mKeyboardRestoreIntent = true;
    /** True while a deferred keyboard/focus restore is waiting for window focus or a bound page. */
    private boolean mPendingKeyboardRestore = false;
    /** Latch: raised while the post-resume keyboard restore is in flight. */
    private boolean mRestoringKeyboard = false;
    /** One-shot flag: keeps reassertPanelLayout()'s GONE→VISIBLE kick from firing more than once. */
    private boolean mPanelRelayoutKickDone = false;

    public boolean isRestoringKeyboard() {
        return mRestoringKeyboard;
    }

    /** True while a keyboard restore waits for window focus / a bound page (diagnostics/tests). */
    public boolean isPendingKeyboardRestore() {
        return mPendingKeyboardRestore;
    }

    /** Combined IME visibility (insets OR visible-frame) — diagnostics/tests. */
    public boolean isSoftKeyboardVisible() {
        return mSoftKeyboardVisible;
    }

    /** Current session-pager page index — diagnostics/tests. */
    public int getPagerCurrentItem() {
        androidx.viewpager2.widget.ViewPager2 pager = getTerminalPager();
        return pager != null ? pager.getCurrentItem() : -1;
    }

    /** Pref key (in termux_prefs) persisting the per-directory message history map (JSON object). */
    private static final String PREF_MESSAGE_HISTORY_PER_DIR = "message_history_per_dir";

    /** Listens for message-history settings changes from the Settings activity. */
    private final SharedPreferences.OnSharedPreferenceChangeListener mPerDirPrefListener =
            (prefs, key) -> {
                if ("per_directory_message_history".equals(key)) {
                    boolean newValue = prefs.getBoolean("per_directory_message_history", false);
                    if (newValue == mMessageHistoryCtrl.isPerDirectoryEnabled()) return;

                    // Persist history under the current (old) mode before switching.
                    mMessageHistoryCtrl.save();
                    mMessageHistoryCtrl.setPerDirectoryEnabled(newValue);
                    loadMessageHistory();
                } else if ("save_cleared_to_history".equals(key)) {
                    mMessageHistoryCtrl.setSaveClearedToHistory(
                            prefs.getBoolean("save_cleared_to_history", true));
                }
            };

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        // Inflate the whole terminal activity (toolbar, extra-keys, session tabs, and the
        // framework long-press context menu popup) in the active Termux:Style scheme from the
        // first frame — no post-layout repaint.
        super.attachBaseContext(com.termux.shared.interact.SchemeDialogTheme.wrapActivityTheme(base));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");

        sInstance = this;
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);

        // Restore per-session text input state saved before recreation.
        if (savedInstanceState != null) {
            mTextInputState.restoreFromBundle(savedInstanceState);
        }

        // Delete ReportInfo serialized object files from cache older than N days
        int cleanupDays = getResources().getInteger(R.integer.report_cleanup_days);
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, cleanupDays, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        // Initialise the history controllers (they own the in-memory lists + persistence).
        mMessageHistoryCtrl = new MessageHistoryController(getSharedPreferences("termux_prefs", MODE_PRIVATE));
        mDirectoryHistoryCtrl = new DirectoryHistoryController(getSharedPreferences("termux_prefs", MODE_PRIVATE));

        // Directory-history popup controller — owns its own popup window + gesture state
        // (fully decoupled from the message-history popup, which keeps its own).
        mSessionSnapshotManager = new TermuxSessionSnapshotManager(this);
        mDirectoryHistoryPopupCtrl = new DirectoryHistoryPopupController(this, mDirectoryHistoryCtrl,
                mColorSchemeManager, new DirectoryHistoryPopupController.Callback() {
                    @Nullable
                    @Override
                    public String recordCurrentDirectory() {
                        return TermuxActivity.this.recordCurrentDirectory();
                    }

                    @Override
                    public void onDirectoryPicked(@NonNull String directory) {
                        mTermuxTerminalSessionActivityClient.addNewSessionInDirectory(directory);
                    }

                    @Override
                    public void onClearAllDirectories() {
                        mDirectoryHistoryCtrl.clear();
                        showToast(getString(R.string.directory_history_cleared), true);
                    }
                }, (int) (getResources().getDimension(R.dimen.message_history_popup_gap) / getResources().getDisplayMetrics().density),
                        (int) (getResources().getDimension(R.dimen.message_history_popup_max_height) / getResources().getDisplayMetrics().density));

        applyTermuxTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

if (!TermuxInstaller.isBootstrapInstalled(this)) {
                    TermuxInstaller.cleanupInterruptedInstall();
            Intent selectorIntent = new Intent(this, com.termux.installer.BootstrapSelectorActivity.class);
            startActivityForResult(selectorIntent, REQUEST_BOOTSTRAP_SETUP);
            mIsInvalidState = true;
            return;
        }

        // Must set ADJUST_RESIZE so WindowInsetsCompat.Type.ime() works on API < 30.
        // When ADJUST_RESIZE is later overwritten (e.g. by setSoftKeyboardAlwaysHiddenFlags),
        // the ImeVisibilityDetector (visible-frame method) still functions as fallback.
        KeyboardUtils.setSoftInputModeAdjustResize(this);

        // Apply the user's screen-orientation choice (Settings -> Screen orientation).
        TermuxActivityUtils.applyScreenOrientation(this);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTextInputPanel = new TextInputPanelController(this, this, mTextInputState);
        mPopupCtrl = new TermuxActivityPopupController(this, this, mColorSchemeManager);
        mPopupCtrl.setMessageHistoryController(mMessageHistoryCtrl);

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTextInputPanel.setup(savedInstanceState, mTermuxActivityRootView);
        mViewHelper = new TermuxActivityViewHelper(this, getLayoutInflater());
        mViewHelper.setDirectoryHistoryPopupController(mDirectoryHistoryPopupCtrl);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        // ── Dual IME detection: insets method + visible-frame method ──
        // Both methods run simultaneously and complement each other via OR logic:
        //   IME_visible = insets_visible || frame_visible
        // This handles three fallback situations:
        //   1) API < 30 without ADJUST_RESIZE → insets always 0, frame works
        //   2) Floating/undocked keyboard → frame may miss it, insets catches
        //   3) Startup/recreate race → whichever fires first sets the state
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            WindowInsetsCompat _compat = WindowInsetsCompat.toWindowInsetsCompat(insets);
            // Independent signal of the real keyboard height, used by TermuxActivityRootView to
            // reject bottom-margin measurements that are physically impossible. It is not derived
            // from getWindowVisibleDisplayFrame(), so it stays correct while that reading is
            // garbage (IME height change in multi-window).
            mLastImeBottomPx = _compat.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            onImeInsetsChanged(_compat.isVisible(WindowInsetsCompat.Type.ime())
                || mLastImeBottomPx > 0);
            return v.onApplyWindowInsets(insets);
        });

        mImeDetector = new ImeVisibilityDetector(this, (visible, heightPx) -> {
            reevaluateImeVisibility();
        });
        mImeDetector.attach();

        setFullScreenFlags();


        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        mAutoCompleteCtrl = new AutoCompleteController(this,
                findViewById(R.id.terminal_toolbar_text_input),
                mMessageHistoryCtrl, mColorSchemeManager);

        // Feed the controller the working directory of the active session so shell
        // completion (file/path candidates) resolves in the right place.
        mAutoCompleteCtrl.setCwdProvider(() -> {
            TerminalSession session = getCurrentSession();
            String cwd = session != null ? session.getCwd() : null;
            return cwd != null ? cwd : ".";
        });

        setNewSessionButtonView();

        setToggleTextInputButtonView();

        setToggleKeyboardView();

        // NOTE: the terminal context menu is registered per-page on each TerminalView inside
        // TerminalPagerAdapter (onBindViewHolder), NOT on the activity root view. Registering it on
        // the root made a long-press on the sibling text-input panel bubble up to the root's context
        // menu and show the terminal menu over the input field (regression: long-press on the input
        // panel opened the terminal context menu instead of selecting a word).

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        // Register broadcast receiver in onCreate so it works even when activity is in background
        registerTermuxActivityBroadcastReceiver();

        // Create the service connection manager that owns the TermuxService binding lifecycle.
        mServiceConnectionManager = new TermuxServiceConnectionManager(this);

        // Start the {@link TermuxService} and bind to it. On failure mark the activity invalid
        // and stop — a toast explaining the failure is shown by the manager.
        if (!mServiceConnectionManager.startAndBindService()) {
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        // Reset both IME-visibility latches on (re)start. They may be left stale as
        // true from before the app was backgrounded, when the soft keyboard was
        // dismissed by the system. Without this reset, the first insets after
        // resume (with IME still hidden) would falsely read as "IME just hidden
        // while panel open" and close the text input panel.
        mSoftKeyboardVisible = false;
        mImeVisibleFromInsets = false;
        if (mImeDetector != null) mImeDetector.refresh();

        // Open the "just resumed" window: suppress the IME-hidden auto-close of
        // the panel until the transient post-return insets frames have settled.
        // Cleared after a short delay so ordinary keyboard dismissal still works.
        mJustResumed = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> mJustResumed = false, 400);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();
        if (mPreferences.isTerminalMarginAdjustmentEnabled()) {
            final TermuxActivityRootView rootView = getTermuxActivityRootView();
            if (rootView != null)
                rootView.forceRelayout();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // A keyboard restore deferred in onResume (window not yet focused, e.g.
        // returning from Settings) is executed here — showSoftInput only works
        // once the window has focus.
        if (hasFocus && mPendingKeyboardRestore) {
            runKeyboardRestore();
        }

        // When Termux regains focus (e.g. after returning from Settings), apply
        // the screen-orientation choice immediately so the change is visible
        // without restarting the app.
        // Skip if the activity is in an invalid state (e.g. no bootstrap installed)
        // to avoid triggering setRequestedOrientation() on MIUI/HyperOS, which can
        // cause infinite recursion through AppCompatDelegateImpl.onConfigurationChanged().
        if (hasFocus && !mIsInvalidState) {
            TermuxActivityUtils.applyScreenOrientation(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        // Snapshot the remembered focus target BEFORE the terminal view client runs.
        // mTermuxTerminalViewClient.onResume() -> setSoftKeyboardState() calls
        // terminalView.requestFocus(), which fires the terminal's onFocusChange
        // listener and overwrites mFocusOnInputPerSession to "terminal" (false) —
        // clobbering the "focus was on panel" state we saved in onStop(). We
        // re-assert this snapshot before restoring, so the panel focus + caret is
        // honoured on return from the background.
        final boolean resumeFocusWasOnInput = isFocusOnInputForSession(getCurrentSession());

        // Whether this resume runs the keyboard/focus restore (return from background or a
        // recreate). A fresh cold start (first resume after onCreate) keeps the historical
        // "show keyboard on launch" behaviour and skips the restore.
        final boolean willRestoreKeyboard = !mIsOnResumeAfterOnCreate || mIsActivityRecreated;

        // Raise the restore latch BEFORE the view client runs setSoftKeyboardState(), so the
        // per-page focus listener suppresses ALL IME / panel churn triggered by its
        // requestFocus(). runKeyboardRestore() owns the final state and clears the latch.
        // Without this, setSoftKeyboardState()'s requestFocus() schedules a +500ms show that
        // outlives the restore's hide (the "keyboard pops back on resume" bug) and the focus
        // listener clobbers the saved per-session panel/focus state before the restore reads it.
        if (willRestoreKeyboard) {
            mRestoringKeyboard = true;
        }

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a crash happened and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        // On return from the background (not a fresh create — that path restores
        // with applyFocus=false at startup), re-apply the current session's panel
        // visibility together with its remembered focus target and caret, exactly
        // as a tab switch does. Restores keyboard-on-panel or focus-on-terminal.
        // Also run after recreate (e.g. settings changed extra keys, which calls
        // recreate() while SettingsActivity is on top). mIsActivityRecreated
        // distinguishes cold start (false) from recreate (true), so the first
        // onResume after recreate applies the focus/IME state restored from the
        // saved-instance Bundle even though mIsOnResumeAfterOnCreate is true.
        TerminalSession currentSession = getCurrentSession();
        mResumeFocusRestore = true;
        try {
            if (willRestoreKeyboard) {
                // Restore the pre-background focus target clobbered by the client above.
                setFocusOnInputForCurrentSession(resumeFocusWasOnInput);

                // Capture the keyboard intent NOW — before any insets churn of the
                // resume frames can overwrite it (onImeVisibilityChanged is guarded
                // by mJustResumed/mRestoringKeyboard, but keep the read here too).
                final boolean kbIntent = mTextInputState.isSoftKeyboardVisibleIntent();
                // Slot + text ONLY, no focus/IME: focus and keyboard are driven
                // exclusively by runKeyboardRestore(), which waits for a clean
                // toolbar re-layout (fixes the cached zero-height measure).
                applyTextInputVisibilityForSession(currentSession, false, kbIntent);
                reassertPanelLayout();
                scheduleKeyboardRestoreForResumedSession(kbIntent);
            }
        } finally {
            mResumeFocusRestore = false;
        }

        mIsOnResumeAfterOnCreate = false;
        mIsPaused = false;
        if (mPreferences.isTerminalMarginAdjustmentEnabled()) {
            final TermuxActivityRootView rootView = getTermuxActivityRootView();
            if (rootView != null)
                new Handler(Looper.getMainLooper()).postDelayed(rootView::forceRelayout, 300);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Mark paused so the IME-hidden handler (WindowInsetsListener) does not
        // close the text input panel when the system dismisses the soft keyboard
        // on pause. The panel must stay open and reappear on resume.
        mIsPaused = true;

        // Snapshot the current session's full UI state (focus target, input text,
        // caret, terminal scroll position, keyboard intent) into the store while
        // the views are still live — this is the last reliable moment before the
        // system may hide the IME / kill the window.
        captureCurrentSessionUiState();
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        // Dismiss any history popup still showing, to avoid a leaked window when
        // the activity goes to the background.
        dismissMessageHistoryPopup();
        if (mDirectoryHistoryPopupCtrl != null) mDirectoryHistoryPopupCtrl.dismiss();

        // Remember, for the current session, whether focus was on the input panel
        // or the terminal, and persist the caret position — exactly as a tab
        // switch does before leaving a tab. This lets onResume() restore the same
        // focus target and caret when the app returns from the background.
        final EditText toolbarTextInput = findViewById(R.id.terminal_toolbar_text_input);
        setFocusOnInputForCurrentSession(toolbarTextInput != null && toolbarTextInput.hasFocus());
        saveTextInputForCurrentSession();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        // Snapshot open tabs (cwd/name) so a later cold start can reopen them.
        saveSessionSnapshot();

        // L3 persist of the per-session UI state (text, caret, panel visibility,
        // focus, scroll, keyboard intent) keyed by session index for the
        // process-death restore path.
        persistUiState();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        sInstance = null;

        if (mIsInvalidState) return;

        getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(mPerDirPrefListener);

        unregisterTermuxActivityBroadcastReceiver();

        // Unbind the TermuxService, releasing the session client so the service no longer holds
        // a reference to this activity.
        mServiceConnectionManager.unbindService();

        // Release the auto-complete controller's background executor + debounce
        // handler so the single-thread shell-fetch worker doesn't leak across
        // activity destruction.
        if (mAutoCompleteCtrl != null) mAutoCompleteCtrl.destroy();

        // Remove the fullscreen workaround global layout listener to prevent
        // leaking the activity via ViewTreeObserver.
        if (mFullScreenWorkAround != null) {
            mFullScreenWorkAround.unregister();
            mFullScreenWorkAround = null;
        }

        // Detach the IME detector to prevent leaking the activity via
        // ViewTreeObserver.OnGlobalLayoutListener.
        if (mImeDetector != null) {
            mImeDetector.detach();
            mImeDetector = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);

        // Persist per-session text input state across activity recreation
        mTextInputState.saveToBundle(savedInstanceState);
    }

    /**
     * Snapshot the CURRENT session's full UI state into the store: focus target,
     * input text + caret, terminal scroll position, and the keyboard intent.
     * Called from onPause (last reliable moment before the system may hide the
     * IME / kill the window) so the restore in onResume reads a consistent picture.
     */
    public void captureCurrentSessionUiState() {
        TerminalSession session = getCurrentSession();
        if (session == null) return;

        final EditText input = findViewById(R.id.terminal_toolbar_text_input);
        if (input != null) {
            mTextInputState.setFocusOnInput(session, input.hasFocus());
        }
        saveTextInputForCurrentSession();   // text + caret

        TerminalView view = getTerminalView();
        if (view != null) {
            mTextInputState.setScrollState(session, view.getTopRow(), view.getScrollTranscriptRows());
        }
        mTextInputState.setSoftKeyboardVisibleIntent(mSoftKeyboardVisible);
    }

    /** Ordered live TerminalSession list (service order == snapshot order). */
    @NonNull
    private java.util.ArrayList<TerminalSession> orderedTerminalSessions() {
        java.util.ArrayList<TerminalSession> out = new java.util.ArrayList<>();
        TermuxService service = getTermuxService();
        if (service != null) {
            for (int i = 0; i < service.getTermuxSessionsSize(); i++) {
                com.termux.shared.termux.shell.command.runner.terminal.TermuxSession ts =
                        service.getTermuxSession(i);
                if (ts != null && ts.getTerminalSession() != null) out.add(ts.getTerminalSession());
            }
        }
        return out;
    }

    /** L3 persist: index-keyed JSON, written once per onStop. */
    public void persistUiState() {
        TermuxService service = getTermuxService();
        if (service == null) return;
        java.util.ArrayList<TerminalSession> ordered = orderedTerminalSessions();
        if (ordered.isEmpty()) return;
        TerminalSession current = getCurrentSession();
        int activeIndex = (current != null) ? service.getIndexOfSession(current) : -1;
        mTextInputState.setActiveSessionIndex(activeIndex);
        String json = mTextInputState.exportToJson(ordered, activeIndex);
        if (json != null) {
            getSharedPreferences("termux_prefs", MODE_PRIVATE).edit()
                    .putString(PREF_UI_STATE_JSON, json).apply();
        }
    }

    /**
     * L3 restore for the process-death path: re-key the persisted (index-based)
     * state onto the sessions just rebuilt from the snapshot. Call this in
     * onServiceConnected RIGHT AFTER restoreSessionSnapshot() and BEFORE
     * syncTerminalPagerToService().
     */
    public void restorePersistedUiState() {
        if (getTermuxService() == null) return;
        String json = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString(PREF_UI_STATE_JSON, null);
        if (json == null) return;
        mTextInputState.importFromJson(json, orderedTerminalSessions());
    }

    /**
     * Schedule the post-resume keyboard/focus restore. Defers itself when:
     *  - the window has no focus yet (returning from another activity — consumed
     *    in onWindowFocusChanged(true));
     *  - no active page is bound yet (cold start — consumed at the end of
     *    SessionPagerManager.onTerminalPageSelected via consumePendingKeyboardRestoreIfReady()).
     *
     * Note: mRestoringKeyboard is already raised in onResume() before the view client runs,
     * so the focus listener stays suppressed for the whole deferral window as well.
     */
    private void scheduleKeyboardRestoreForResumedSession(boolean kbIntent) {
        mKeyboardRestoreIntent = kbIntent;
        if (getActiveTerminalView() == null && !isTextInputVisible()) {
            mPendingKeyboardRestore = true;
            return;
        }
        runKeyboardRestore();
    }

    private void runKeyboardRestore() {
        if (isFinishing()) return;
        if (!hasWindowFocus()) {
            mPendingKeyboardRestore = true;   // consumed by onWindowFocusChanged(true)
            return;
        }
        final TerminalSession session = getCurrentSession();
        final boolean panelVisible = isTextInputVisible();
        final boolean focusOnInput = panelVisible && mTextInputState.isFocusOnInput(session);
        final boolean kbIntent = mKeyboardRestoreIntent;
        final View target = focusOnInput
                ? findViewById(R.id.terminal_toolbar_text_input)
                : getActiveTerminalView();
        if (target == null) {
            mPendingKeyboardRestore = true;   // page not bound yet
            return;
        }
        mPendingKeyboardRestore = false;
        mRestoringKeyboard = true;

        // (1) First force a clean toolbar measure so the EditText is not 0-sized
        // (a zero-sized view is never a "served" IME target — see findings).
        if (focusOnInput) reassertPanelLayout();

        // (2) Wait for the target to have real sizes, THEN focus + keyboard.
        whenViewLaidOut(target, () -> {
            // Drop a stale ignore-once latch so it cannot swallow the show we are about to
            // request (and cannot survive to eat a later legitimate show).
            if (mTermuxTerminalViewClient != null) {
                mTermuxTerminalViewClient.clearIgnoreOnceSoftKeyboardOnFocus();
            }

            // Respect a user-disabled soft keyboard: never attempt to show it.
            final boolean kbDisabled = KeyboardUtils.shouldSoftKeyboardBeDisabled(this,
                    mPreferences.isSoftKeyboardEnabled(),
                    mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware());

            if (kbIntent && !kbDisabled) {
                target.requestFocus();
                com.termux.app.terminal.io.SoftKeyboardRestore.showWithRetry(target, () ->
                        mImeVisibleFromInsets
                                || (mImeDetector != null && mImeDetector.isImeVisible()));
                // showWithRetry is up to 4x120ms; keep the latch until everything settles.
                target.postDelayed(() -> mRestoringKeyboard = false, 800);
            } else {
                // The user hid the keyboard before backgrounding (or it is disabled): keep it
                // hidden. Swallow the focus-triggered show AND cancel any stray pending show
                // so the hide sticks even against a previously scheduled +500ms show runnable.
                if (mTermuxTerminalViewClient != null) {
                    mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                    mTermuxTerminalViewClient.cancelPendingSoftKeyboardShow();
                }
                target.requestFocus();
                KeyboardUtils.hideSoftKeyboard(this, target);
                target.postDelayed(() -> mRestoringKeyboard = false, 300);
            }
        }, 6);
    }

    /** Called by SessionPagerManager.onTerminalPageSelected once a page is live. */
    public void consumePendingKeyboardRestoreIfReady() {
        if (mPendingKeyboardRestore && getActiveTerminalView() != null) {
            runKeyboardRestore();
        }
    }

    /**
     * Forces a clean measure/layout pass of the toolbar subtree.
     *
     * Root cause being worked around: with ADJUST_RESIZE the system hides the IME while
     * we are in the background; the window resizes, the RelativeLayout re-measures its
     * children across transient frames, and the wrap_content toolbar (and the pager that
     * depends on it via layout_above) can end up with a cached degenerate 0 size. The
     * resume path re-asserts visibilities that are ALREADY in place (VISIBLE→VISIBLE),
     * so nobody calls requestLayout() and the cached "0" lives forever. A real visibility
     * change (the manual GONE→VISIBLE cycle) fixes it because it sets PFLAG_FORCE_LAYOUT.
     * Here we do the same without flicker: first a requestLayout() over the whole subtree,
     * then check the height on the next pre-draw and, if it is still 0, apply the
     * guaranteed kick exactly once.
     */
    private void reassertPanelLayout() {
        final LinearLayout toolbar = getTerminalToolbarContainer();
        if (toolbar == null) return;
        mPanelRelayoutKickDone = false;

        kickLayoutTree(toolbar);
        // Nudge the RelativeLayout too so the pager dependency (layout_above) is
        // resolved against the re-measured toolbar.
        ViewParent rp = toolbar.getParent();
        if (rp instanceof View) ((View) rp).requestLayout();

        // After the next measure/layout pass, verify the result. One-shot pre-draw.
        androidx.core.view.OneShotPreDrawListener.add(toolbar, () -> {
            if (toolbar.getVisibility() == View.VISIBLE
                    && toolbar.getHeight() <= 0
                    && !mPanelRelayoutKickDone) {
                forceSlotRelayoutByToggle();   // guaranteed recovery, exactly once
            }
        });
    }

    /** Recursive requestLayout()/invalidate() over a view subtree. */
    private static void kickLayoutTree(@Nullable View v) {
        if (v == null) return;
        v.requestLayout();
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) kickLayoutTree(vg.getChildAt(i));
        }
    }

    /** The manual fix: a real visibility change sets PFLAG_FORCE_LAYOUT and makes the
     *  system re-measure the slot from scratch. Honours whichever child currently owns
     *  the shared slot (text input panel vs extra keys) so the kick can never force the
     *  input panel open while the extra keys panel is the active one. */
    private void forceSlotRelayoutByToggle() {
        mPanelRelayoutKickDone = true;
        final View container = findViewById(R.id.terminal_toolbar_text_input_container);
        final ExtraKeysView ekv = getExtraKeysView();
        if (isTextInputVisible()) {
            if (container == null) return;
            // Text input owns the slot: a real GONE→VISIBLE cycle on its container.
            container.setVisibility(View.GONE);
            container.setVisibility(View.VISIBLE);
            if (ekv != null) ekv.setVisibility(View.GONE);
        } else {
            // Extra keys own the slot: cycle the extra keys instead, keep the
            // text input panel hidden — otherwise it would reappear below them.
            if (ekv == null) return;
            ekv.setVisibility(View.GONE);
            ekv.setVisibility(shouldShowExtraKeys() ? View.VISIBLE : View.GONE);
            if (container != null) container.setVisibility(View.GONE);
        }
        ViewParent slot = container != null ? container.getParent() : null;
        if (slot instanceof View) ((View) slot).requestLayout();
        ViewParent toolbar = slot != null ? slot.getParent() : null;
        if (toolbar instanceof View) ((View) toolbar).requestLayout();
        updateTextInputToggleButtonAnchor();
    }

    /**
     * Runs {@code action} once the view is attached and has non-zero sizes.
     * If already laid out — runs immediately; otherwise waits for pre-draw, with a
     * bounded number of attempts so the restore can never hang forever.
     */
    private static void whenViewLaidOut(@NonNull final View view,
                                        @NonNull final Runnable action,
                                        final int attemptsLeft) {
        if (view.isAttachedToWindow() && view.getWidth() > 0 && view.getHeight() > 0) {
            action.run();
            return;
        }
        if (attemptsLeft <= 0) {
            action.run();   // cannot wait any longer — run as-is
            return;
        }
        androidx.core.view.OneShotPreDrawListener.add(view, () -> {
            if (view.isAttachedToWindow() && view.getWidth() > 0 && view.getHeight() > 0) {
                action.run();
            } else {
                view.post(() -> whenViewLaidOut(view, action, attemptsLeft - 1));
            }
        });
    }











    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    public void applyTermuxTheme() {
        if (mViewHelper != null) mViewHelper.applyTheme();
    }

    /**
     * Apply the user-selected screen orientation to the given activity.
     * Reads "screen_orientation" from the "termux_prefs" file (written by the
     * Settings screen-orientation list). Valid values: "sensor", "portrait",
     * "landscape". Default is "sensor" on tablets (smallestScreenWidthDp >= 600)
     * and "portrait" on phones.
     */
    public static void applyScreenOrientation(@NonNull Activity activity) {
        TermuxActivityUtils.applyScreenOrientation(activity);
    }

    /** Convert dp to pixels using the current display density. */
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void setMargins() {
        // The margins are applied to each terminal page INSIDE the pager (via
        // SessionPagerManager -> TerminalPagerAdapter), NOT to the pager container:
        // with the margins on the container, the pager itself is inset from the screen
        // edges and a horizontal swipe clips the neighbouring page at the container
        // boundary instead of revealing it edge-to-edge. When the pager does not exist
        // yet (first call from onCreate, before setTermuxTerminalViewAndClients()),
        // SessionPagerManager.setup() applies the margins from the properties itself.
        if (mSessionPagerManager == null) return;
        mSessionPagerManager.setTerminalMargins(mProperties.getTerminalMarginLeft(),
                mProperties.getTerminalMarginTop(),
                mProperties.getTerminalMarginRight(),
                mProperties.getTerminalMarginBottom());

        // The floating toggle-text-input button used to live inside the (margin-inset)
        // container, so it followed the terminal's right margin automatically. Now that
        // the margins live on the terminal pages, push the button's own right margin by
        // the terminal's right inset so it keeps clearing the terminal edge (and its
        // scrollbar) by the same gap as before.
        updateFloatingButtonMargin();
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set up the horizontal session pager (ViewPager2). The pager owns the TerminalViews now;
        // mTerminalView is (re)assigned to the active page in SessionPagerManager.onTerminalPageSelected().
        androidx.viewpager2.widget.ViewPager2 pager = findViewById(R.id.terminal_view_pager);
        mSessionPagerManager = new SessionPagerManager(this, pager);
        mSessionPagerManager.setup();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    public void setTermuxSessionsListView() {
        // Initialize session tabs controller
        mTermuxSessionTabsController = new TermuxSessionTabsController(this);
        
        // Set up new session tab button
        ImageButton newSessionTabButton = findViewById(R.id.new_session_tab_button);
        if (newSessionTabButton != null) {
            // Tap opens a new tab (default cwd); long-press creates a named session.
            newSessionTabButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
            newSessionTabButton.setOnLongClickListener(v -> {
                TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                    R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                    R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                    -1, null, null);
                return true;
            });

            // A swipe-up (drag off the button) opens the directory-history popup,
            // mirroring the pencil button's gesture. We return false from the
            // touch listener until a swipe is actually detected, so a plain tap or
            // long-press still reaches the click / long-click listeners above.
            final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            final float[] downXY = new float[2];
            final boolean[] gestureActive = { false };
            final boolean[] swipeConsumed = { false };

            newSessionTabButton.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downXY[0] = event.getRawX();
                        downXY[1] = event.getRawY();
                        gestureActive[0] = true;
                        swipeConsumed[0] = false;
                        // Show the active background immediately on touch-down and keep it
                        // lit for the whole gesture (tap or swipe). state_pressed is not
                        // reliable here because the button lives in a HorizontalScrollView
                        // that can send ACTION_CANCEL, so we drive state_selected ourselves.
                        v.setSelected(true);
                        // Stop the parent HorizontalScrollView from intercepting the touch
                        // stream (which would send ACTION_CANCEL and clear the active state
                        // / dismiss the popup). Must target the scrolling ancestor, not the
                        // immediate LinearLayout parent, since the disallow flag only affects
                        // the ancestor that actually intercepts.
                        disallowTabScrollIntercept(v, true);
                        return false;   // let click / long-press proceed
                    case MotionEvent.ACTION_MOVE: {
                        if (!gestureActive[0]) return false;
                        // Popup already open: track the finger to highlight items.
                        if (mDirectoryHistoryPopupCtrl.isShowing()) {
                            mDirectoryHistoryPopupCtrl.updateHighlight(event.getRawX(), event.getRawY());
                            return true;
                        }
                        float dy = event.getRawY() - downXY[1];
                        float dx = event.getRawX() - downXY[0];
                        boolean swipeUp = dy < -touchSlop;
                        boolean swipeDown = dy > touchSlop;
                        // Re-read the preference every move so a settings change
                        // takes effect without restarting the activity.
                        boolean triggered = !isTabPanelAtBottom() ? swipeDown : swipeUp;
                        if (triggered && Math.abs(dy) > Math.abs(dx)
                                && mDirectoryHistoryPopupCtrl.shouldShow()) {
                            v.setSelected(true);   // keep the active background lit
                            v.cancelLongPress();   // cancel pending long-press before it fires
                            swipeConsumed[0] = true;
                            mDirectoryHistoryPopupCtrl.show(v);
                            return true;   // consume: cancels pending click/long-press
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        boolean wasPopup = mDirectoryHistoryPopupCtrl.isShowing();
                        if (wasPopup) {
                            int selected = mDirectoryHistoryPopupCtrl.getHighlightIndex();
                            mDirectoryHistoryPopupCtrl.dismiss();
                            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                if (selected == DirectoryHistoryPopupController.CLEAR_ALL_TAG) {
                                    mDirectoryHistoryPopupCtrl.confirmClear();
                                } else if (selected >= 0) {
                                    mDirectoryHistoryPopupCtrl.pick(selected);
                                }
                            }
                        }
                        // Always clear the active visual state and release the scroll lock
                        // on finger release (tap and swipe alike).
                        gestureActive[0] = false;
                        v.setSelected(false);
                        v.setPressed(false);
                        disallowTabScrollIntercept(v, false);
                        return wasPopup;   // swipe -> consume; plain tap -> allow onClick
                    }
                }
                return false;
            });
        }
    }

    /**
     * Allow / disallow the session-tabs {@link HorizontalScrollView} from intercepting the touch
     * stream of a descendant view (the new-session button). The disallow flag only affects the
     * ancestor that actually performs interception, so we walk up the parent chain until we reach
     * the {@link HorizontalScrollView} rather than calling it on the button's immediate parent
     * (a {@link LinearLayout} that never intercepts).
     */
    private void disallowTabScrollIntercept(View child, boolean disallow) {
        android.view.ViewParent p = child.getParent();
        while (p != null && !(p instanceof android.widget.HorizontalScrollView)) {
            p = p.getParent();
        }
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }


    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, getTerminalView(),
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final LinearLayout terminalToolbarContainer = getTerminalToolbarContainer();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarContainer.setVisibility(View.VISIBLE);

        // Set default height for toolbar items (37.5dp as in original layout)
        mTerminalToolbarDefaultHeight = (int) (37.5f * getResources().getDisplayMetrics().density);

        // Setup ExtraKeysView
        ExtraKeysView extraKeysView = findViewById(R.id.terminal_toolbar_extra_keys);
        extraKeysView.setExtraKeysViewClient(mTermuxTerminalExtraKeys);
        extraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
        extraKeysView.setDynamicFontSize(getPreferences().isExtraKeysDynamicFontSizeEnabled(this));
        extraKeysView.setRuntimeEdgeIndicatorsEnabled(getPreferences().isExtraKeysEdgeIndicatorsEnabled());
        setExtraKeysView(extraKeysView);

        // apply extra keys fix if enabled in prefs
        if (mProperties.isUsingFullScreen() && mProperties.isUsingFullScreenWorkAround()) {
            mFullScreenWorkAround = FullScreenWorkAround.apply(this);
        }

        setTerminalToolbarHeight();

        // Ensure the toggle button anchor is correct even when toolbar is initially GONE.
        updateTextInputToggleButtonAnchor();

        // Load extra keys buttons - needed after activity recreate (theme change)
        if (mTermuxTerminalExtraKeys.getExtraKeysInfo() != null) {
            extraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
        }

        // Push cached scheme colors to the freshly created extra-keys view so the panel is
        // themed on the first frame, before the service connects and applyPanelColors() runs.
        // On cold start the cache may be empty (service not connected yet), so seed it from
        // preferences first to avoid a black/transparent flash of the extra-keys panel.
        TermuxColorSchemeManager csm = getColorSchemeManager();
        if (csm.getButtonBg() == 0) {
            csm.recompute(getPreferences());
        }
        extraKeysView.setButtonColors(csm.getButtonText(), deriveActiveTextColor(csm.getButtonText()), csm.getButtonBg(), csm.getButtonActiveBg());

        // Setup text input
        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        // Per-session input text is restored via restoreTextInputForSession() on tab switch
        // and panel show; on first creation just clear it.
        editText.setText("");

        // Record per-session focus: when the text input gains focus, remember that
        // input goes to the panel (true) for the current session.
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setFocusOnInputForCurrentSession(true);
            if (!hasFocus) dismissAutoCompleteSuggestions();
        });

        // Auto-complete + caret-reposition handled by AutoCompleteController in constructor.
        editText.setOnEditorActionListener((v, actionId, event) -> {
            TerminalSession session = getCurrentSession();
            if (session != null) {
                if (session.isRunning()) {
                    String textToSend = editText.getText().toString();
                    boolean hasText = textToSend.length() > 0;
                    // Remember non-empty sent messages in the history (dedup, newest first).
                    // NOTE: the history stores the raw text WITHOUT the trailing newline.
                    if (hasText) {
                        addToMessageHistory(textToSend);
                        // By default append a carriage return so the line is SUBMITTED (Enter).
                        // If the user disabled "Append Enter on send" in settings, write raw text
                        // only (no trailing newline) — this is what some users want when sending
                        // partial input that should not execute a command yet.
                        if (mPreferences.shouldTextInputAppendEnter()) {
                            session.write(textToSend + "\r");
                        } else {
                            session.write(textToSend);
                        }
                    } else {
                        // Empty field: just send a lone Enter (newline) to the session.
                        session.write("\r");
                    }

                    // Clear the field and the per-session saved text/caret BEFORE
                    // hiding the panel. Otherwise setTextInputVisible(false) ->
                    // saveTextInputForCurrentSession() re-persists the just-sent
                    // text (the field is still non-empty at that point) and it
                    // reappears when the panel is opened again.
                    // Use getText().clear() instead of setText("") so we mutate the
                    // Editable in place and don't trigger InputMethodManager.restartInput
                    // (which would drop the first key the user types afterwards).
                    // clearInput() drops only the text/caret/focus, preserving the
                    // per-session VISIBILITY flag so isTextInputVisible() and Back
                    // keep working when the panel stays open.
                    editText.getText().clear();
                    mTextInputState.clearInput(session.mHandle);

                    // What to do after sending depends on the single "Action on send"
                    // preference: do nothing, hide the input panel, or hide the keyboard
                    // (which also hides the input panel).
                    boolean hidePanel = mPreferences.shouldTextInputHideOnSend();
                    boolean hideKeyboard = mPreferences.shouldTextInputHideKeyboardOnSend();

                    if (hidePanel) {
                        setTextInputVisible(false);
                        // When the keyboard is also being hidden and the extra keys panel
                        // is tied to the keyboard ("hide extra keys when keyboard hidden"),
                        // collapse the extra keys panel directly so we don't briefly flash
                        // them on screen before the IME-visibility signal catches up. The
                        // panel and keyboard are dismissed in one go.
                        if (hideKeyboard && mPreferences.shouldHideExtraKeysWithKeyboard()
                                && mExtraKeysView != null) {
                            mExtraKeysView.setVisibility(View.GONE);
                        }
                        updateToggleTextInputButtonIcon();
                    }

                    if (hideKeyboard) {
                        mTermuxTerminalViewClient.hideSoftKeyboardAfterSend();
                    }
                } else {
                    mTermuxTerminalSessionActivityClient.removeFinishedSession(session);
                }
                editText.getText().clear();
            }
            return true;
        });

        // Restore text input panel visibility state for the current session (if any).
        // Falls back to the legacy global preference for sessions not yet tracked.
        // The text input panel and extra keys share one slot, so they stay inverted.
        // Pass applyFocus=false so we don't pop the keyboard at startup (respects
        // setSoftKeyboardState's startup-hidden preference).
        applyTextInputVisibilityForSession(getCurrentSession(), false);
    }





    private boolean isTerminalToolbarVisible() {
        LinearLayout toolbar = getTerminalToolbarContainer();
        if (toolbar == null || toolbar.getVisibility() != View.VISIBLE) return false;
        if (mExtraKeysView != null && mExtraKeysView.getVisibility() != View.VISIBLE
                && !isTextInputVisible()) return false;
        return true;
    }

    private boolean isFullScreenTerminalMode() {
        return mProperties.isUsingFullScreen()
            && (getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
    }


    public void setTerminalToolbarHeight() {
        final ExtraKeysView extraKeysView = getExtraKeysView();
        if (extraKeysView == null) return;

        ViewGroup.LayoutParams layoutParams = extraKeysView.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        extraKeysView.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final LinearLayout terminalToolbarContainer = getTerminalToolbarContainer();
        if (terminalToolbarContainer == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarContainer.setVisibility(showNow ? View.VISIBLE : View.GONE);

        // If hiding the toolbar while text input is visible, close text input first
        // so the toolbar state is consistent.
        if (!showNow && isTextInputVisible()) {
            setTextInputVisible(false);
            updateToggleTextInputButtonIcon();
        }

        // Update the pencil anchor so it stays at the bottom when toolbar is GONE.
        updateTextInputToggleButtonAnchor();

    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }

    /**
     * Save the current text input field content into the per-session map,
     * keyed by the current TerminalSession.mHandle.
     */
    public void saveTextInputForCurrentSession() {
        final TerminalSession session = getCurrentSession();
        if (session == null) return;
        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView == null) return;
        String text = textInputView.getText().toString();
        mTextInputState.saveInput(session.mHandle, text);
        // Remember the caret position so it can be restored on tab switch and on
        // panel hide/show for the same session. The EditText keeps its selection
        // even after losing focus, so getSelectionStart() is valid regardless of
        // focus — we always record it (no hasFocus() guard), otherwise hiding the
        // panel after the focus had already moved to the terminal would drop the
        // caret and it would jump to the end on re-open.
        mTextInputState.setCaret(session.mHandle, textInputView.getSelectionStart());
    }

    /**
     * Restore the text input field content for the given session (by mHandle)
     * into the EditText. Called on tab switch and panel show.
     */
    public void restoreTextInputForSession(@Nullable TerminalSession session) {
        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView == null) return;
        if (session == null) {
            textInputView.setText("");
            return;
        }
        String text = mTextInputState.getInputText(session.mHandle);
        mAutoCompleteCtrl.setRestoringInput(true);
        try {
            textInputView.setText(text != null ? text : "");
            // Restore the caret position saved for this session, if any. getCaret() returns
            // -1 when nothing was recorded, so only valid positions (>= 0) are applied.
            int caret = mTextInputState.getCaret(session.mHandle);
            if (caret >= 0) {
                textInputView.setSelection(Math.min(caret, textInputView.length()));
            }
        } finally {
            mAutoCompleteCtrl.setRestoringInput(false);
        }
    }

    /**
     * Remove the saved text input content for a session (e.g. when the session is closed),
     * so the per-session map does not grow with stale entries.
     */
    public void clearTextInputForSession(@NonNull TerminalSession session) {
        mTextInputState.clear(session);
    }

    /**
     * Record, for the current session, whether focus (input) is on the text
     * input panel (focusOnInput=true) or on the terminal view (false).
     */
    public void setFocusOnInputForCurrentSession(boolean focusOnInput) {
        mTextInputState.setFocusOnInput(getCurrentSession(), focusOnInput);
    }

    /**
     * Get whether, for the given session, focus was last on the text input
     * panel (true) or on the terminal view (false). Defaults to false
     * (terminal) for unknown sessions.
     */
    public boolean isFocusOnInputForSession(@Nullable TerminalSession session) {
        return mTextInputState.isFocusOnInput(session);
    }



    /**
     * Move the session tabs panel (session_tabs_container) to the top or bottom of the
     * screen. The terminal toolbar (extra keys + text input) always stays at the bottom;
     * only the tab strip moves. The ViewPager2 fills whatever space remains between them.
     */
    public void applyTabPanelPosition() {
        String position = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString("tab_panel_position", "top");
        LinearLayout tabsContainer = findViewById(R.id.session_tabs_container);
        androidx.viewpager2.widget.ViewPager2 pager = findViewById(R.id.terminal_view_pager);
        if (tabsContainer == null || pager == null) return;

        RelativeLayout.LayoutParams tabsLp = (RelativeLayout.LayoutParams) tabsContainer.getLayoutParams();
        RelativeLayout.LayoutParams pagerLp = (RelativeLayout.LayoutParams) pager.getLayoutParams();

        // Reset rules that change with position
        tabsLp.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
        tabsLp.removeRule(RelativeLayout.ABOVE);
        pagerLp.removeRule(RelativeLayout.ABOVE);
        pagerLp.removeRule(RelativeLayout.BELOW);

        if ("bottom".equals(position)) {
            // Tabs right above the toolbar; ViewPager fills above the tabs.
            // Keep the gap below the strip (screen edge), drop the one above it
            // so the terminal touches the tabs.
            tabsLp.addRule(RelativeLayout.ABOVE, R.id.terminal_toolbar_container);
            pagerLp.addRule(RelativeLayout.ABOVE, R.id.session_tabs_container);
            tabsContainer.setPadding(tabsContainer.getPaddingLeft(), 0,
                    tabsContainer.getPaddingRight(), dpToPx(2));
        } else {
            // Tabs at top; ViewPager fills between tabs and toolbar.
            // Keep the gap above the strip (status bar), drop the one below it
            // so the terminal touches the tabs.
            tabsLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            pagerLp.addRule(RelativeLayout.BELOW, R.id.session_tabs_container);
            pagerLp.addRule(RelativeLayout.ABOVE, R.id.terminal_toolbar_container);
            tabsContainer.setPadding(tabsContainer.getPaddingLeft(), dpToPx(2),
                    tabsContainer.getPaddingRight(), 0);
        }

        tabsContainer.setLayoutParams(tabsLp);
        pager.setLayoutParams(pagerLp);

        // When the tab panel sits at the bottom it rests directly above the toolbar,
        // so restore the text-input panel's previous 4dp top margin for breathing room
        // (see activity_termux.xml prior to the gap-removal commit). When the tab panel
        // is at the top the toolbar is flush at the screen bottom with no tabs above it,
        // so drop the top margin (current behaviour).
        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer != null) {
            ViewGroup.MarginLayoutParams tipLp =
                    (ViewGroup.MarginLayoutParams) textInputContainer.getLayoutParams();
            tipLp.topMargin = "bottom".equals(position) ? dpToPx(4) : 0;
            textInputContainer.setLayoutParams(tipLp);
        }

        // Keep the directory-history popup's inverted flag in sync with the
        // (possibly changed) tab panel position, so a swipe on the new-tab
        // button behaves correctly after the setting is toggled via broadcast.
        if (mDirectoryHistoryPopupCtrl != null) {
            mDirectoryHistoryPopupCtrl.setInverted(!"bottom".equals(position));
        }

        // Update the text-input toggle button anchor so it stays at the bottom
        // even when the toolbar is GONE (show_extra_keys = never).
        updateTextInputToggleButtonAnchor();

        // When the tab panel sits at the bottom it rests directly above the toolbar,
        // so the extra keys panel must keep its top margin to not touch the tabs.
        // When the tab panel is at the top the panel is flush at the screen bottom.
        ExtraKeysView extraKeysView = getExtraKeysView();
        if (extraKeysView != null) {
            extraKeysView.setTopMarginEnabled("bottom".equals(position));
        }
    }

    /**
     * Update the text-input toggle button's layout anchor to keep it at the bottom
     * of the screen even when the terminal toolbar is GONE (show_extra_keys = never).
     * When the toolbar is visible, the button is positioned ABOVE it (or above the
     * tabs if the tab panel is at the bottom). When the toolbar is GONE, the button
     * anchors to ALIGN_PARENT_BOTTOM so it doesn't fly to the top-right corner.
     */
    private void updateTextInputToggleButtonAnchor() {
        ImageButton pencil = findViewById(R.id.toggle_text_input_button);
        LinearLayout toolbar = getTerminalToolbarContainer();
        if (pencil == null || toolbar == null) return;

        RelativeLayout.LayoutParams penLp = (RelativeLayout.LayoutParams) pencil.getLayoutParams();
        penLp.removeRule(RelativeLayout.ABOVE);
        penLp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);

        if (toolbar.getVisibility() == View.VISIBLE) {
            String position = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getString("tab_panel_position", "top");
            if ("bottom".equals(position)) {
                penLp.addRule(RelativeLayout.ABOVE, R.id.session_tabs_container);
            } else {
                penLp.addRule(RelativeLayout.ABOVE, R.id.terminal_toolbar_container);
            }
        } else {
            // Toolbar is hidden — anchor button to the bottom of the screen.
            penLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        }
        pencil.setLayoutParams(penLp);
    }

    /** Whether the session tabs panel is configured to sit at the bottom of the screen. */
    public boolean isTabPanelAtBottom() {
        return "bottom".equals(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString("tab_panel_position", "top"));
    }

    /**
     * Re-apply the tab height mode (single-line or two-line) to all existing tab views
     * and to the add-tab / settings buttons in the tabs panel. Called on startup and
     * when the user changes the setting.
     */
    public void applyTabHeightMode() {
        // Update all existing tab views via the tabs controller.
        if (mTermuxSessionTabsController != null)
            mTermuxSessionTabsController.applyTabHeightMode();

        // Also update the add-tab button height.
        String mode = getSharedPreferences("termux_prefs", MODE_PRIVATE)
                .getString("tab_height_mode", "single");
        boolean doubleMode = "double".equals(mode);
        int buttonSizeDp = doubleMode ? 36 : 24;
        float density = getResources().getDisplayMetrics().density;
        int buttonSizePx = Math.round(buttonSizeDp * density);

        View addTabBtn = findViewById(R.id.new_session_tab_button);
        if (addTabBtn != null) {
            ViewGroup.LayoutParams lp = addTabBtn.getLayoutParams();
            lp.width = buttonSizePx;
            lp.height = buttonSizePx;
            addTabBtn.setLayoutParams(lp);
        }
    }

    private void setNewSessionButtonView() {
        // New session button is now in the tabs bar, handled in setTermuxSessionsListView
    }

    private void setToggleTextInputButtonView() {
        ImageButton toggleTextInputButton = findViewById(R.id.toggle_text_input_button);
        if (toggleTextInputButton != null) {
            // Load the persisted sent-message history once.
            mMessageHistoryCtrl.setMaxSize(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getInt("message_history_max", MESSAGE_HISTORY_MAX_DEFAULT));
            mMessageHistoryCtrl.setPerDirectoryEnabled(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getBoolean("per_directory_message_history", false));
            mMessageHistoryCtrl.setSaveClearedToHistory(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getBoolean("save_cleared_to_history", true));
            loadMessageHistory();

            // Hot-reload: when the user toggles per-directory history in Settings,
            // the mode switches immediately.
            getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(mPerDirPrefListener);

            // Load the persisted recent-directories history once.
            mDirectoryHistoryCtrl.setMaxSize(getSharedPreferences("termux_prefs", MODE_PRIVATE)
                    .getInt("directory_history_max", DIRECTORY_HISTORY_MAX_DEFAULT));
            loadDirectoryHistory();

            // A touch listener drives two gestures on the pencil button:
            //   - a plain tap toggles the text input panel (old click behaviour);
            //   - a swipe up (drag off the button) opens the message-history popup,
            //     which stays open while the finger is held; releasing over an item
            //     picks it, releasing elsewhere just dismisses.
            final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
            final float[] downXY = new float[2];
            final boolean[] gestureActive = { false };
            // Capture the panel state at gesture start — the EditText drops focus on
            // ACTION_DOWN, which may trigger an IME-hide and (via WindowInsetsListener)
            // auto-close the panel before ACTION_UP.
            final boolean[] panelOpenAtDown = { false };
            // Set once the finger has swiped down past the touch slop: a swipe down on
            // the button pastes the clipboard text into the text input panel at the cursor
            // (showing the panel first if it is hidden), and must not fall back to a plain
            // tap on release.
            final boolean[] swipeDownPending = { false };

            toggleTextInputButton.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downXY[0] = event.getRawX();
                        downXY[1] = event.getRawY();
                        panelOpenAtDown[0] = isTextInputVisible();
                        swipeDownPending[0] = false;
                        gestureActive[0] = true;
                        mButtonTouchInProgress = true;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!gestureActive[0]) return true;
                        float dy = event.getRawY() - downXY[1];
                        float dx = event.getRawX() - downXY[0];
                        // Open the history popup once the finger has dragged up past
                        // the touch slop (and the drag is more vertical than sideways).
                        if (!mPopupCtrl.isHistoryPopupShowing()
                                && dy < -touchSlop && Math.abs(dy) > Math.abs(dx)) {
                            mPopupCtrl.showMessageHistoryPopup(v);
                        }
                        // Swipe down past the touch slop (more vertical than sideways) pastes
                        // the clipboard text into the text input panel at the cursor, firing
                        // immediately upon recognition (not on release). Latched so a wiggle
                        // back up mid-drag does not paste again or fall back to a toggle.
                        if (!mPopupCtrl.isHistoryPopupShowing() && !swipeDownPending[0]
                                && dy > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                            swipeDownPending[0] = true;
                            pasteClipboardIntoTextInput();
                        }
                        if (mPopupCtrl.isHistoryPopupShowing()) {
                            // Keep the button visually active (filled with the stroke colour)
                            // while the finger is still held, even though it has left the button.
                            v.setPressed(true);
                            mPopupCtrl.updateHistoryHighlight(event.getRawX(), event.getRawY());
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        v.setPressed(false);
                        boolean wasPopup = mPopupCtrl.isHistoryPopupShowing();
                        if (wasPopup) {
                            int selected = mPopupCtrl.getHistoryHighlightIndex();
                            boolean isClearAllSelected = (selected == -3
                                    && mMessageHistoryCtrl != null
                                    && !mMessageHistoryCtrl.getHistoryList().isEmpty());
                            mPopupCtrl.dismissMessageHistoryPopup();
                            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                if (isClearAllSelected) {
                                    mPopupCtrl.confirmClearAllHistory();
                                } else if (selected == -2) {
                                    clearInputToHistory();
                                } else if (selected >= 0 && selected < mMessageHistoryCtrl.getHistoryList().size()) {
                                    onHistoryMessagePicked(mMessageHistoryCtrl.getHistoryList().get(selected));
                                }
                            }
                        } else if (swipeDownPending[0]) {
                            // The swipe-down paste already fired on recognition in ACTION_MOVE;
                            // on release (or cancel) it must never fall back to a toggle.
                        } else if (gestureActive[0]
                                && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            // No popup was opened: treat as a plain tap -> toggle panel.
                            boolean currentlyVisible = panelOpenAtDown[0];
                            setTextInputVisible(!currentlyVisible);
                            updateToggleTextInputButtonIcon();
                        }
                        gestureActive[0] = false;
                        swipeDownPending[0] = false;
                        mButtonTouchInProgress = false;
                        return true;
                    }
                }
                return false;
            });

            // Set initial visibility based on settings
            boolean enabled = isTextInputEnabled();
            toggleTextInputButton.setVisibility(enabled ? View.VISIBLE : View.GONE);

            // Set initial icon if visible
            if (enabled) {
                updateToggleTextInputButtonIcon();
            }

            // Set initial margin based on scrollbar state
            updateFloatingButtonMargin();

            // Apply the configured tab panel position (top/bottom).
            applyTabPanelPosition();

            // Apply the configured tab height mode (single-line / two-line).
            applyTabHeightMode();
        }
    }

    /**
     * Paste clipboard text into the text input field at the cursor position, replacing any
     * active selection. If the text input panel is currently hidden, it is shown first.
     * Called from a swipe-down gesture on the toggle button. A clipboard that is unset or
     * contains no text is a silent no-op (mirrors terminal paste behaviour).
     */
    private void pasteClipboardIntoTextInput() {
        String text = ShareUtils.getTextStringFromClipboardIfSet(this, true);
        if (text == null) return;

        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        if (editText == null) return;

        if (!isTextInputVisible()) {
            setTextInputVisible(true);
            updateToggleTextInputButtonIcon();
        }

        Editable editable = editText.getText();
        int selStart = editText.getSelectionStart();
        int selEnd   = editText.getSelectionEnd();
        if (editable != null) {
            if (selStart < 0) selStart = editable.length();
            if (selEnd   < 0) selEnd   = selStart;
            if (selStart > selEnd) {
                int tmp = selStart;
                selStart = selEnd;
                selEnd = tmp;
            }
            editable.replace(selStart, selEnd, text);
            editText.setSelection(selStart + text.length());
        } else {
            editText.setText(text);
            editText.setSelection(text.length());
        }

        setFocusOnInputForCurrentSession(true);
        saveTextInputForCurrentSession();
        editText.requestFocus();
    }

    public void updateToggleTextInputButtonIcon() {
        ImageButton toggleTextInputButton = findViewById(R.id.toggle_text_input_button);
        if (toggleTextInputButton != null) {
            boolean isVisible = isTextInputVisible();
            toggleTextInputButton.setImageResource(isVisible ? R.drawable.ic_keyboard_hide : R.drawable.ic_keyboard_show);
            toggleTextInputButton.setContentDescription(getString(R.string.action_toggle_text_input));
        }
    }

    /**
     * Update the floating toggle button's right margin based on the scrollbar visibility.
     * When the TerminalView has scrollable content (active transcript rows > 0), the
     * button gets marginEnd=28dp (gap from scrollbar). When content fits entirely in the
     * viewport (no scrollbar), marginEnd=6dp (same as marginBottom).
     */
    public static boolean isNightModeActive() {
        final NightMode appNightMode = NightMode.getAppNightMode();
        if (appNightMode == NightMode.SYSTEM) {
            return ThemeUtils.isSystemNightModeEnabled();
        } else {
            return (appNightMode == NightMode.TRUE);
        }
    }

    /**
     * Whether the given terminal view currently shows a scrollbar (i.e. has scrollable
     * transcript content). Used both for the button's base margin and to decide whether
     * the terminal's own right inset must be added to the button margin.
     */
    public static boolean hasScrollbar(@Nullable TerminalView view) {
        return view != null && view.mEmulator != null
            && view.mEmulator.getScreen().getActiveTranscriptRows() > 0;
    }

    public static int computeFloatingButtonMarginEnd(TerminalView view, android.content.res.Resources resources) {
        boolean hasScrollbar = hasScrollbar(view);
        float density = resources.getDisplayMetrics().density;
        return hasScrollbar ? Math.max((int)(30 * density + 0.5f) - 2, 0) : (int)(6 * density + 0.5f);
    }

    /**
     * Recompute and apply the floating button's SETTLED-state right margin from the current
     * terminal view's scrollbar state.
     *
     * No-op while the pager is animating a page scroll. In that window
     * {@code SessionPagerManager.updateFloatingButtonMarginForScroll()} owns the margin and drives
     * it frame by frame, interpolating between the leaving and the entering page. Writing the
     * settled value here would yank the button to its final position and the very next scroll
     * frame would pull it back — the jitter that showed up as the button trembling on every chunk
     * of terminal output while swiping onto a session that prints continuously.
     */
    public void updateFloatingButtonMargin() {
        if (isPagerScrollInProgress()) return;
        setFloatingButtonMarginEnd(computeSettledFloatingButtonMarginEnd(mTerminalView));
    }

    /**
     * The marginEnd the floating button should have once the pager is AT REST on {@code view}'s
     * page. Single source of truth: {@code SessionPagerManager} interpolates between two of these
     * while scrolling, so there must be exactly one implementation — a second, drifting copy would
     * reintroduce a jump at the seam where the scroll hands the margin back to the settled state.
     */
    public int computeSettledFloatingButtonMarginEnd(@Nullable TerminalView view) {
        int marginEnd = computeFloatingButtonMarginEnd(view, getResources());
        // The terminal's own right inset only pushes the button further in when this
        // page actually shows a scrollbar — that is when the button must clear the
        // terminal edge/scrollbar. Without a scrollbar the button keeps its standard
        // margin, independent of the terminal margins.
        if (hasScrollbar(view)) {
            marginEnd += getTerminalRightInsetPx();
        }
        return marginEnd;
    }

    /**
     * The terminal's right margin in pixels (from the "terminal-margin-right"
     * setting). The floating toggle-text-input button is offset by this amount so it
     * stays clear of the terminal's right edge (and its scrollbar) — previously the
     * button lived inside the margin-inset container and followed the margin
     * automatically; now the margins live on the terminal pages, so the button adds
     * this inset to its own marginEnd.
     */
    public int getTerminalRightInsetPx() {
        if (mProperties == null) return 0;
        return (int) (mProperties.getTerminalMarginRight()
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Directly set the floating button's right margin in pixels. Unlike
     * {@link #updateFloatingButtonMargin()} which recomputes the margin from the
     * current terminal view's scrollbar state, this pushes an explicit value so
     * the scroll callback can drive intermediate (interpolated) margins while a
     * ViewPager2 page swipe is in progress.
     */
    public void setFloatingButtonMarginEnd(int marginEndPx) {
        ImageButton toggleButton = findViewById(R.id.toggle_text_input_button);
        if (toggleButton == null) return;
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) toggleButton.getLayoutParams();
        if (params.rightMargin != marginEndPx) {
            params.rightMargin = marginEndPx;
            toggleButton.setLayoutParams(params);
        }
    }

    // ============================================================================
    //  Sent-message history
    // ============================================================================

    /**
     * Called when the current terminal session or working directory has changed
     * (e.g. tab switch). If per-directory message history is enabled, saves the
     * current directory's history and loads the new directory's entries into
     * mMessageHistoryCtrl.getHistoryList().
     *
     * Also performs lazy migration of global history when a real session CWD
     * is first encountered and the per-dir store has no entry for it yet
     * (handles the cold-start case where the session wasn't ready during
     * {@link #loadMessageHistoryPerDirectory()}).
     */
    public void onHistoryDirectoryChanged() {
        if (!mMessageHistoryCtrl.isPerDirectoryEnabled()) return;
        String oldCwd = mMessageHistoryCtrl.getHistoryCurrentDirectory();
        String newCwd = getCurrentCwdForHistory();
        mMessageHistoryCtrl.onHistoryDirectoryChanged(
                oldCwd != null ? oldCwd : newCwd, newCwd);
    }

    /**
     * Add a just-sent message to the history. Deduplicated by content: if the
     * same text already exists it is removed and re-inserted at the front, so a
     * re-sent message rises to the front (index 0), which is rendered at the
     * BOTTOM of the popup (newest = bottom, nearest the button). Persists the list.
     *
     * In per-directory mode, detects cross-directory moves (e.g. after `cd`
     * within the same tab) and cleanly separates histories so messages sent from
     * one directory never leak into another.
     */
    public void addToMessageHistory(@NonNull String message) {
        if (TextUtils.isEmpty(message)) return;
        String cwd = getCurrentCwdForHistory();
        mMessageHistoryCtrl.addToMessageHistory(message, cwd);
    }

    /**
     * Passively record a message (cleared input / text replaced by a history
     * pick): a brand-new message goes to the TOP, an already-present one keeps
     * its position. See {@link MessageHistoryController#addNewOnTop}.
     */
    public void addNewOnTop(@NonNull String message) {
        if (TextUtils.isEmpty(message)) return;
        String cwd = getCurrentCwdForHistory();
        mMessageHistoryCtrl.addNewOnTop(message, cwd);
    }

    /**
     * Returns the current working directory to use as a per-directory history key,
     * or a fallback if unavailable.
     */
    @NonNull
    @Override
    public String getCurrentCwdForHistory() {
        TerminalSession session = getCurrentSession();
        if (session != null) {
            String cwd = session.getCwd();
            if (!TextUtils.isEmpty(cwd)) return cwd;
        }
        return ".";
    }

    private void loadMessageHistory() {
        mMessageHistoryCtrl.load(getCurrentCwdForHistory());
    }

    private void saveMessageHistory() {
        mMessageHistoryCtrl.save();
    }




    // ── Auto-complete suggestions from message history ────────────────

    /**
     * Three-way dispatcher for the auto-complete popup:
     *
     * Path A (full rebuild) — called when the user deletes, replaces, pastes, or the
     * history has changed externally.  Re-scans the full mMessageHistoryCtrl.getHistoryList() and creates a
     * brand-new PopupWindow (dismiss + showAtLocation).
     *
     * Path B (additive filter) — called when the user only types more characters without
     * deleting any text.  Filters mCurrentSuggestions in place (O(maxCount) instead of
     * O(mMessageHistoryCtrl.getHistoryList())), removes non-matching views from the existing popup, top-ups
     * from history if the result is smaller than maxCount, recalculates bold spans, and
     * updates the popup size/position (one IPC instead of two).
     *
     * Path C (reposition only) — not truly a separate path here; when the text hasn't
     * changed w.r.t. the previous call the {@link OnGlobalLayoutListener} and
     * {@link OnTouchListener} already call {@link #repositionAutoCompletePopup()}
     * separately.  The dispatcher here always receives a text-change event.
     */
    public void updateAutoCompleteSuggestions() {
        // Handled by AutoCompleteController's internal TextWatcher.
    }

    /** Removed — handled by AutoCompleteController. */
    private void fullRescanSuggestions(@NonNull String text, int maxCount) { }

    /** Removed — handled by AutoCompleteController. */

    /** Removed — handled by AutoCompleteController. */

    /**

     * Build the display {@link android.text.SpannableString} for an auto-complete
     * suggestion. Applies the word-based leading truncation (the {@code "... "}
     * prefix added when the match starts mid-word) and, when the result would
     * exceed {@code maxLines} lines, manually truncates it and appends a trailing
     * {@code '…'}.
     *
     * <p>Why manual truncation instead of {@code TextView.setEllipsize(END)}:
     * on Android (API 21-28 in particular) {@code ellipsize=end} is only reliably
     * honored for <b>single-line</b> text. With {@code setMaxLines(n)} where
     * {@code n > 1} the framework routes to {@code StaticLayout} but the trailing
     * ellipsis on the last line is unreliable and frequently never appears. We
     * therefore measure and cut the text ourselves so the {@code '…'} is
     * guaranteed for long suggestions/messages regardless of OS version. The
     * matched input prefix is rendered in BOLD on top of the (possibly truncated)
     * display text.
     *
     * @param availWidth available text width in px (popup width minus padding);
     *                   pass {@code 0} to skip truncation (e.g. not yet laid out).
     */
    /** @return starting position of the last token in {@code s}, split on space or slash. */
    private static int wordStartOffset(@NonNull String s) {
        int i = s.length();
        while (i > 0) {
            char c = s.charAt(i - 1);
            if (c == ' ' || c == '/') return i;
            i--;
        }
        return 0;
    }


    public void dismissAutoCompleteSuggestions() {
        mAutoCompleteCtrl.dismiss();
    }


    /**
    public void showMessageHistoryPopup(@NonNull View anchor) {
        mPopupCtrl.showMessageHistoryPopup(anchor);
    }

    /** Wipe message history for ALL directories (per-directory mode only). */
    public void clearAllDirectoriesHistory() {
        mMessageHistoryCtrl.clearAllPerDirectory();
    }

    public void clearAllHistory() {
        mMessageHistoryCtrl.clearCurrent(getCurrentCwdForHistory());
    }

    /** Dismiss the history popup and reset highlight state. */
    public void dismissMessageHistoryPopup() {
        mPopupCtrl.dismissMessageHistoryPopup();
    }

    /** Bottom "Clear" item: remember the current input text in history, then empty the field. */
    private void clearInputToHistory() {
        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        if (editText == null) return;
        // "Clear" (save cleared text): a brand-new message goes to the TOP of the
        // history (pre-promote-switch behaviour); a message already in the history
        // KEEPS its position. See MessageHistoryController.addNewOnTop().
        if (!isTextInputVisible()) {
            setTextInputVisible(true);
            updateToggleTextInputButtonIcon();
        }

        String existing = editText.getText().toString();
        if (!TextUtils.isEmpty(existing) && mMessageHistoryCtrl.isSaveClearedToHistory()) {
            addNewOnTop(existing);
        }

        editText.setText("");
        setFocusOnInputForCurrentSession(true);
        saveTextInputForCurrentSession();
        editText.requestFocus();
    }

    /**
     * A history item was chosen (finger released over it). Opens the input panel
     * and inserts the picked message. If the panel already held some text, that
     * text is first pushed into the history (dedup) so it is not lost.
     */
    private void onHistoryMessagePicked(@NonNull String message) {
        final EditText editText = findViewById(R.id.terminal_toolbar_text_input);
        if (editText == null) return;

        if (!isTextInputVisible()) {
            setTextInputVisible(true);
            updateToggleTextInputButtonIcon();
        }

        mAutoCompleteCtrl.invalidateHistoryVersion();

        if (mPreferences.shouldInsertAtCursorOnHistoryPick()) {
            // NEW behaviour: insert at cursor / replace selection, auto-select inserted text.
            // Does NOT save existing text to history — that only happens on send and clear.
            Editable editable = editText.getText();
            if (editable == null) {
                editText.setText(message);
                editText.setSelection(message.length());
            } else {
                int selStart = editText.getSelectionStart();
                int selEnd   = editText.getSelectionEnd();

                if (selStart < 0) selStart = editable.length();
                if (selEnd   < 0) selEnd   = selStart;
                if (selStart > selEnd) {
                    int tmp = selStart;
                    selStart = selEnd;
                    selEnd = tmp;
                }

                editable.replace(selStart, selEnd, message);
                editText.setSelection(selStart, selStart + message.length());
            }
        } else {
            // LEGACY behaviour: replace the entire field, saving existing text to history.
            String existing = editText.getText().toString();
            if (!TextUtils.isEmpty(existing) && !existing.equals(message)
                    && mMessageHistoryCtrl.isSaveClearedToHistory()) {
                addNewOnTop(existing);
            }
            editText.setText(message);
            editText.setSelection(message.length());
        }
        setFocusOnInputForCurrentSession(true);
        saveTextInputForCurrentSession();

        editText.requestFocus();
        editText.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    // ============================================================================
    //  Recent directories history (for the "new tab" button popup)
    // ============================================================================

    /**
     * Record the current session's working directory into the directory history
     * (dedup, newest first), trimming to the configured max and persisting.
     * Returns the recorded path (or null if unavailable). Called whenever a
     * session becomes current, and right before the directory popup is shown,
     * so the latest "cd" is captured even if it hasn't been committed yet.
     */
    @Nullable
    public String recordCurrentDirectory() {
        return mDirectoryHistoryCtrl.recordCurrentDirectory(getCurrentSession());
    }

    /** Load the persisted directory history from preferences (JSON array). */
    private void loadDirectoryHistory() {
        mDirectoryHistoryCtrl.load();
    }

    /** Persist the current directory history to preferences as a JSON array. */
    private void saveDirectoryHistory() {
        mDirectoryHistoryCtrl.save();
    }

    /**
     * Alpha-composite {@code overlay} (with alpha) on top of {@code background}
     * (assumed opaque) using standard over operator.
     * @return Fully opaque ARGB colour.
     */
    private static int compositeColors(int background, int overlay) {
        return TermuxColorSchemeManager.compositeColors(background, overlay);
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

    /**
     * (Re)compute ALL UI colours derived from the terminal colour scheme and panel transparency
     * prefs — panel buttons, text selection highlight, context-popup backgrounds and separators.
     * Must be called whenever the scheme or the inactive-alpha slider changes so that every styled
     * element uses fresh colours without recomputing them on every draw / event.
     * <p>
     * Both light and dark scheme variants are covered: when the scheme switches, this runs again
     * and overwrites the cached fields with the new values.
     */
    public void recomputeUIColors() {
        mColorSchemeManager.recompute(getPreferences());
    }

    /**
     * Single apply-point for the Termux:Style colour scheme across every non-panel surface
     * (window background, status-bar theme, and any open context menu). Called after every
     * {@link #recomputeUIColors()} so a scheme or theme change restyles the whole activity at once.
     * <p>
     * The bottom panel / extra-keys / tabs are styled separately by
     * {@code TermuxTerminalSessionActivityClient.applyPanelColors()}, which reads the same cached
     * {@link TermuxColorSchemeManager} colours.
     */
    public void applySchemeColors() {
        TermuxColorSchemeManager csm = mColorSchemeManager;
        int schemeBg = csm.getSchemeBackground();
        boolean isLight = csm.isSchemeLight();

        // Window surface follows the scheme; the status and navigation bars are transparent so the
        // scheme background shows through, with matching icon/text appearance (dark on a light
        // scheme, light on a dark one) — the bars never stand out from or darken the terminal.
        Window window = getWindow();
        if (window != null) {
            applySystemBarColors(window, schemeBg, isLight);
        }
    }

    /**
     * Make the status and navigation bars fully transparent so the content (or the window
     * surface painted below with {@code surfaceBackground}) shows through unchanged, and set
     * their icon/text appearance: dark icons when {@code isLight} is true, light icons otherwise.
     * <p>
     * The activity theme sets {@code windowTranslucentStatus} / {@code windowTranslucentNavigation}
     * (for edge-to-edge drawing), which on API 21-28 draws a dark translucent scrim over both bars
     * and makes {@code setStatusBarColor()} / {@code setNavigationBarColor()} no-ops. Clearing those
     * flags and painting the window surface ourselves with the scheme colour kills the scrim while
     * the {@code LAYOUT_*} flags keep the content laid out edge-to-edge exactly as before, so the
     * terminal size does not change. On Android 10+ the system would additionally draw a contrast
     * scrim over the bars with gesture navigation, so that is disabled too — the transparent bars
     * then always show exactly what is behind them: the terminal background.
     */
    public static void applySystemBarColors(Window window, int surfaceBackground, boolean isLight) {
        if (window == null) return;
        View decorView = window.getDecorView();

        // Colour the window surface (visible through the transparent bars) with the scheme
        // background, so the bar areas always match the terminal colours exactly.
        decorView.setBackgroundColor(surfaceBackground);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ draws a translucent contrast scrim over the bars with gesture
            // navigation, darkening them even when transparent/coloured. Disable it.
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        int flags = decorView.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isLight) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isLight) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decorView.setSystemUiVisibility(flags);
    }


    /** @return Cached panel/button background colour. */
    public int getButtonBg() { return mColorSchemeManager.getButtonBg(); }
    /** @return Cached panel/button active background colour. */
    public int getButtonActiveBg() { return mColorSchemeManager.getButtonActiveBg(); }
    /** @return Cached panel/button text (scheme foreground) colour. */
    public int getButtonText() { return mColorSchemeManager.getButtonText(); }
    /** @return Cached text selection highlight colour. */
    public int getTextSelectionHighlightColor() { return mColorSchemeManager.getTextSelectionHighlightColor(); }
    /** @return Whether the current scheme is perceived as light. */
    public boolean isCachedSchemeLight() { return mColorSchemeManager.isSchemeLight(); }

    /**
     * Check if text input field is enabled in settings.
     * @return true if text input field should be shown, false otherwise
     */
    public boolean isTextInputEnabled() {
        return getSharedPreferences("termux_prefs", MODE_PRIVATE).getBoolean("text_input_enabled", true);
    }

    /**
     * Update the toggle text input button visibility based on settings.
     * Also updates the text input container visibility.
     */
    public void updateToggleTextInputButtonVisibility() {
        ImageButton toggleTextInputButton = findViewById(R.id.toggle_text_input_button);
        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        
        boolean enabled = isTextInputEnabled();
        boolean wasDisabled = toggleTextInputButton != null &&
                              toggleTextInputButton.getVisibility() != View.VISIBLE;
        
        if (toggleTextInputButton != null) {
            toggleTextInputButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        
        // Also update text input container visibility
        if (textInputContainer != null) {
            if (!enabled) {
                // Hide text input when disabled in settings
                textInputContainer.setVisibility(View.GONE);
            } else {
                // If setting was just enabled (transition from disabled to enabled),
                // show panel in visible state
                if (wasDisabled) {
                    setTextInputVisible(true);
                } else {
                    // Restore text input visibility based on saved state.
                    // Use setTextInputVisible so the extra keys slot stays inverted.
                    setTextInputVisible(isTextInputVisible());
                }
            }
        }
        
        // Update button icon after visibility state is finalized
        if (enabled && toggleTextInputButton != null) {
            updateToggleTextInputButtonIcon();
        }
    }

    private void setToggleKeyboardView() {
        if (mViewHelper != null) mViewHelper.setupToggleKeyboardButton(mTermuxActivityRootView);
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        // If the message-history popup is showing, dismiss it and hide the
        // text-input panel first — don't finish the activity.
        if (mPopupCtrl.isHistoryPopupShowing()) {
            mPopupCtrl.dismissMessageHistoryPopup();
            if (isTextInputVisible()) {
                setTextInputVisible(false);
                updateToggleTextInputButtonIcon();
            }
        } else if (isTextInputVisible()) {
            // Panel is open: "Back" closes the panel instead of exiting the app
            // (regardless of the "hide panel after send" setting).
            setTextInputVisible(false);
            updateToggleTextInputButtonIcon();
        } else {
            TermuxActivityUtils.finishActivityIfNotFinishing(this);
        }
    }

    public void finishActivityIfNotFinishing() {
        TermuxActivityUtils.finishActivityIfNotFinishing(this);
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        mPopupCtrl.onCreateContextMenu(menu, v, menuInfo);
        tintContextMenu();
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        TerminalView tv = getTerminalView();
        if (tv != null)
            tv.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return mPopupCtrl.onContextItemSelected(item);
    }

    /**
     * Ensure the long-press context menu follows the active Termux:Style colour scheme. The menu is
     * a framework popup themed by the activity context, which now carries the scheme via
     * {@code wrapActivityTheme} (see {@link #attachBaseContext}) — so it is inflated in the scheme
     * from the first frame. This method is kept only as a last-resort safety net for paths the theme
     * overlay does not reach (it runs synchronously, before the popup is drawn, so never repaints).
     */
    private void tintContextMenu() {
        TermuxColorSchemeManager csm = mColorSchemeManager;
        int schemeBg = csm.getSchemeBackground();
        int schemeFg = csm.getSchemeForeground();
        int dividerColor = csm.getDividerColor();
        int dividerHeight = Math.max(1, Math.round(getResources().getDisplayMetrics().density));

        View source = getTerminalView();
        applyContextMenuTint(source, csm, schemeBg, schemeFg, dividerColor, dividerHeight);
    }

    /** Apply the scheme colours to the context-menu popup, with a graceful fallback path. */
    private void applyContextMenuTint(@NonNull View source, @NonNull TermuxColorSchemeManager csm,
                                      int schemeBg, int schemeFg, int dividerColor, int dividerHeight) {
        android.widget.ListView listView = findContextMenuListView(source);
        if (listView != null) {
            tintContextMenuList(listView, schemeBg, schemeFg, dividerColor, dividerHeight, csm);
            return;
        }

        // Fallback: paint whatever popup view we can resolve (covers AppCompat PopupWindow path).
        View menuView = findContextMenuPopupView(source);
        if (menuView instanceof android.widget.ListView) {
            android.widget.ListView lv = (android.widget.ListView) menuView;
            tintContextMenuList(lv, schemeBg, schemeFg, dividerColor, dividerHeight, csm);
        } else if (menuView != null) {
            menuView.setBackgroundColor(schemeBg);
            TermuxSchemeTheme.tintViewTreeText(menuView, schemeFg);
        }
        // Last-resort fallback: set the popup window's background drawable directly.
        setContextMenuPopupBackground(source, schemeBg);
    }

    /** Tint a context-menu {@link ListView}: background, divider, selector, and every item's text. */
    private void tintContextMenuList(@NonNull android.widget.ListView listView, int schemeBg,
                                     int schemeFg, int dividerColor, int dividerHeight,
                                     @NonNull TermuxColorSchemeManager csm) {
        listView.setBackgroundColor(schemeBg);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(dividerColor));
        listView.setDividerHeight(dividerHeight);
        listView.setSelector(TermuxSchemeTheme.makeHighlightSelectorPublic(csm));
        // Tint both the whole tree and each visible item's title TextView explicitly (the menu
        // item title lives in a TextView whose colour the theme may re-assert on bind).
        TermuxSchemeTheme.tintViewTreeText(listView, schemeFg);
        for (int i = 0; i < listView.getChildCount(); i++) {
            View item = listView.getChildAt(i);
            if (item == null) continue;
            TextView title = item.findViewById(android.R.id.title);
            if (title != null) title.setTextColor(schemeFg);
            TermuxSchemeTheme.tintViewTreeText(item, schemeFg);
        }
    }

    /**
     * Locate the context-menu {@link android.widget.ListView} via reflection on the framework /
     * AppCompat helper that {@link View#showContextMenu()} creates. Returns it, or {@code null} if it
     * cannot be resolved on this Android version.
     */
    private static android.widget.ListView findContextMenuListView(@NonNull View source) {
        Object helper = getContextMenuHelper(source);
        if (helper == null) return null;
        // Framework path: MenuDialogHelper.getDialog() -> AlertDialog list view.
        try {
            java.lang.reflect.Method getDialog = helper.getClass().getMethod("getDialog");
            Object dialog = getDialog.invoke(helper);
            if (dialog instanceof android.app.Dialog) {
                android.view.View list = ((android.app.Dialog) dialog)
                        .findViewById(android.R.id.list);
                if (list instanceof android.widget.ListView) return (android.widget.ListView) list;
            }
        } catch (Exception ignored) {
        }
        // AppCompat path: MenuPopupHelper.mMenuView is the ListView.
        try {
            java.lang.reflect.Field menuViewField = helper.getClass().getDeclaredField("mMenuView");
            menuViewField.setAccessible(true);
            Object menuView = menuViewField.get(helper);
            if (menuView instanceof android.widget.ListView) return (android.widget.ListView) menuView;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Locate the context-menu popup view via reflection on the framework / AppCompat helper that
     * {@link View#showContextMenu()} creates. Returns the popup's root view, or {@code null} if it
     * cannot be resolved on this Android version.
     */
    private static View findContextMenuPopupView(@NonNull View source) {
        Object helper = getContextMenuHelper(source);
        if (helper == null) return null;
        // Framework path: MenuDialogHelper.getDialog() -> AlertDialog whose content view is the menu.
        try {
            java.lang.reflect.Method getDialog = helper.getClass().getMethod("getDialog");
            Object dialog = getDialog.invoke(helper);
            if (dialog instanceof android.app.Dialog) {
                return ((android.app.Dialog) dialog).getWindow().getDecorView();
            }
        } catch (Exception ignored) {
            // AppCompat may expose the popup window directly instead.
        }
        // AppCompat path: MenuPopupHelper.mPopup (PopupWindow) with mPopupView.
        try {
            java.lang.reflect.Field popupField = helper.getClass().getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popupWindow = popupField.get(helper);
            if (popupWindow instanceof android.widget.PopupWindow) {
                return (View) ((android.widget.PopupWindow) popupWindow).getContentView();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Reflect the {@link View#showContextMenu()} helper ({@code mContextMenuHelper}) off a view. */
    private static Object getContextMenuHelper(@NonNull View source) {
        try {
            java.lang.reflect.Field helperField = android.view.View.class
                    .getDeclaredField("mContextMenuHelper");
            helperField.setAccessible(true);
            return helperField.get(source);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Last-resort fallback: paint the popup window's background drawable directly. */
    private static void setContextMenuPopupBackground(@NonNull View source, int schemeBg) {
        Object helper = getContextMenuHelper(source);
        if (helper == null) return;
        try {
            java.lang.reflect.Field popupField = helper.getClass().getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popupWindow = popupField.get(helper);
            if (popupWindow instanceof android.widget.PopupWindow) {
                ((android.widget.PopupWindow) popupWindow)
                        .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(schemeBg));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void toggleKeepScreenOn() {
        TerminalView terminalView = getTerminalView();
        if (terminalView == null) return;
        if (terminalView.getKeepScreenOn()) {
            terminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            terminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }

    @Override
    public boolean isKeepScreenOn() {
        TerminalView terminalView = getTerminalView();
        return terminalView != null && terminalView.getKeepScreenOn();
    }

    @Override
    public void setKeepScreenOn(boolean keepOn) {
        TerminalView terminalView = getTerminalView();
        if (terminalView != null) terminalView.setKeepScreenOn(keepOn);
    }

    @Override
    public void showUrlSelection() {
        if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.showUrlSelection();
    }

    @Override
    public void shareSessionTranscript() {
        if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.shareSessionTranscript();
    }

    @Override
    public void shareSelectedText() {
        if (mTermuxTerminalViewClient != null) mTermuxTerminalViewClient.shareSelectedText();
    }

    @Override
    public void reportIssueFromTranscript() {
        ActivityUtils.startActivity(this, new Intent(this, ReportActivity.class));
    }

    @Override
    public void startHelpActivity() {
        ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
    }

    @Override
    public void startSettingsActivity() {
        ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        // Must run on the UI thread: PermissionUtils.requestPermissions() ends up calling
        // Activity.requestPermissions(), which is a no-op / throws off the UI thread on Android 11+,
        // so the permission dialog would never appear. All callers (onReceive, onActivityResult,
        // onRequestPermissionsResult) already run on the UI thread, so no threading is needed here.
        // Do not ask for permission again
        int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

        // If permission is granted, then also setup storage symlinks.
        if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
            TermuxActivity.this, requestCode, !isPermissionCallback)) {
            if (isPermissionCallback)
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

            // Create the storage symlinks off the UI thread (native symlink calls).
            new Thread(() -> TermuxInstaller.setupStorageSymlinks(TermuxActivity.this)).start();
        } else {
            if (isPermissionCallback)
                Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                    getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
        }
    }

    public static final int REQUEST_BOOTSTRAP_SETUP = 2001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        } else if (requestCode == REQUEST_BOOTSTRAP_SETUP) {
            if (resultCode == RESULT_OK && TermuxInstaller.isBootstrapInstalled(this)) {
                recreate();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
        applyExtraKeysSpecialButtonMode();
    }

    /**
     * Apply the {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE} property to the
     * {@link ExtraKeysView} so that special buttons (CTRL, ALT, SHIFT, FN) behave in sticky (latching)
     * or hold (active while pressed) mode.
     */
    private void applyExtraKeysSpecialButtonMode() {
        if (mExtraKeysView == null || mProperties == null) return;
        String mode = (String) mProperties.getInternalPropertyValue(
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE, true);
        ExtraKeysView.SpecialButtonMode buttonMode;
        if (TermuxPropertyConstants.IVALUE_EXTRA_KEYS_SPECIAL_BUTTON_MODE_HOLD.equals(mode))
            buttonMode = ExtraKeysView.SpecialButtonMode.HOLD;
        else
            buttonMode = ExtraKeysView.SpecialButtonMode.STICKY;
        mExtraKeysView.setSpecialButtonMode(buttonMode);
    }


    public LinearLayout getTerminalToolbarContainer() {
        return (LinearLayout) findViewById(R.id.terminal_toolbar_container);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }


    /**
     * Sync the pager adapter and tab strip with the live session list.
     * No-arg overload — delegates to the indexed version with -1.
     */
    public void termuxSessionListNotifyUpdated() {
        termuxSessionListNotifyUpdated(-1);
    }

    /**
     * Sync the pager adapter and tab strip with the live session list.
     *
     * @param preferredIndex When a tab has just been removed, the position of the removed tab in
     *                       the OLD list; the method selects the session that shifted into this
     *                       slot (the RIGHT neighbor).  Pass -1 for non-removal updates, which
     *                       falls back to restoring the current session's position.
     */
    public void termuxSessionListNotifyUpdated(int preferredIndex) {
        // The horizontal pager sync (adapter rebuild + page re-selection + per-session bookkeeping)
        // now lives in SessionPagerManager. It re-points mTerminalView to the correct page; we then
        // refresh the tab strip and snapshot below.
        //
        // NOTE: updateTabs() runs BEFORE the pager sync so that scrollStripToEnd() (called when
        // newCount > sessionCount) sets mEndScrollActive=true before onScrollFinished() fires from
        // the pager's setCurrentItem(false). This prevents snapToTabCenter() from centering the
        // new tab just before the end-scroll scrolls to the right edge — which was the root cause of
        // the jerky "double movement" on tab creation. getCurrentSession() during updateTabs() may
        // return the old session, but the selection is corrected by setCurrentSession() inside
        // onTerminalPageSelected() before any frame renders.
        if (mTermuxSessionTabsController != null && mServiceConnectionManager.getTermuxService() != null) {
            mTermuxSessionTabsController.updateTabs(mServiceConnectionManager.getTermuxService().getTermuxSessions());
        }

        if (mSessionPagerManager != null)
            mSessionPagerManager.termuxSessionListNotifyUpdated(preferredIndex);

        // Keep the open-tabs snapshot fresh while sessions are alive, so a later
        // exit (e.g. the notification's Exit action, which kills sessions before
        // onStop runs) still leaves a snapshot to restore on next launch.
        saveSessionSnapshot();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mServiceConnectionManager != null ? mServiceConnectionManager.getTermuxService() : null;
    }

    /** Restore open sessions from snapshot, if enabled. */
    public boolean restoreSessionSnapshot() {
        return mSessionSnapshotManager.restoreSessionSnapshot();
    }

    /** Per-session text input state (content, visibility, focus, caret). */
    @NonNull
    public SessionUiStateStore getTextInputState() {
        return mTextInputState;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public void setTerminalView(@Nullable TerminalView view) {
        // Remove listener from old view to avoid leaks
        if (mTerminalView != null) {
            mTerminalView.setOnScreenUpdateListener(null);
        }
        mTerminalView = view;
        if (view != null) {
            // During a ViewPager2 page switch the OnScreenUpdateListener must NOT override the
            // interpolated margin set by
            // SessionPagerManager.updateFloatingButtonMarginForScroll(). The guard lives inside
            // updateFloatingButtonMargin() itself and keys off the pager's OWN scroll state rather
            // than mTerminalPageSwitchInProgress: that flag is cleared one frame after
            // onPageSelected() fires, and onPageSelected() fires at the START of the settle, so it
            // was already down for most of the animation — which let every chunk of terminal output
            // overwrite the interpolated margin with the settled one and made the button tremble.
            view.setOnScreenUpdateListener(() -> updateFloatingButtonMargin());
            // Also update margin immediately for the new page. No-op while the pager is still
            // scrolling — the settled value is applied by the refresh SessionPagerManager posts
            // from onPageScrollStateChanged(IDLE).
            updateFloatingButtonMargin();
        }
    }

    /**
     * Resolve the {@link TerminalView} of the currently active pager page, resolving it live rather
     * than from the cached {@link #mTerminalView}. Returns {@link #getTerminalView()} when that is
     * already set, otherwise falls back to the pager's selected page so callers that run in a window
     * where {@code mTerminalView} has not yet been refreshed (e.g. extra-key input right after a
     * session is added to an empty pager, where {@code onPageSelected} is not re-fired for page 0)
     * still reach the correct, bound view instead of getting {@code null} and dropping the action.
     */
    @Nullable
    public TerminalView getActiveTerminalView() {
        if (mSessionPagerManager != null) return mSessionPagerManager.getActiveTerminalView();
        if (mTerminalView != null) return mTerminalView;
        return null;
    }

    public androidx.viewpager2.widget.ViewPager2 getTerminalPager() {
        return mSessionPagerManager != null ? mSessionPagerManager.getTerminalPager() : null;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    public TermuxSessionTabsController getTermuxSessionTabsController() {
        return mTermuxSessionTabsController;
    }

    @Override
    public void onToggleTextInput(boolean nowVisible) {
        mTextInputPanel.updateToggleTextInputButtonIcon();
    }

    @Nullable public EditText getTerminalToolbarTextInput() {
        return findViewById(R.id.terminal_toolbar_text_input);
    }

    public MessageHistoryController getMessageHistoryController() {
        return mMessageHistoryCtrl;
    }

    public TermuxColorSchemeManager getColorSchemeManager() {
        return mColorSchemeManager;
    }

    public TermuxTerminalViewClient getTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTerminalSessionActivityClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Override
    public void finishActivity() {
        TermuxActivityUtils.finishActivityIfNotFinishing(this);
    }

    @Override
    public void recreateActivity() {
        this.recreate();
    }

    @Override
    public void showKillSessionDialog(@NonNull TerminalSession session) {
        if (session == null) return;
        new TermuxDialogs(this).showKillSessionDialog(session, () -> {});
    }

    @Override
    public void onResetTerminalSession(@NonNull TerminalSession session) {
        if (session == null) return;
        session.reset();
        showToast(getResources().getString(R.string.msg_terminal_reset), true);
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResetTerminalSession();
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }

    /** @return The {@link TermuxSessionSnapshotManager} owning session snapshot/restore. */
    public TermuxSessionSnapshotManager getSessionSnapshotManager() {
        return mSessionSnapshotManager;
    }

    /** Persist the open tabs snapshot; delegates to {@link TermuxSessionSnapshotManager}. */
    public void saveSessionSnapshot() {
        mSessionSnapshotManager.saveSessionSnapshot();
    }


    /**
     * Set the shared slot below the tabs: text input container vs extra keys.
     * Showing the text input panel hides the extra keys and vice versa.
     * When the text input panel is hidden, the extra keys visibility also
     * respects the {@code hide_extra_keys_with_keyboard} preference.
     */
    /** True if the extra keys panel should currently be shown (honours preference). */
    private boolean shouldShowExtraKeys() {
        return !(mPreferences.shouldHideExtraKeysWithKeyboard() && !mSoftKeyboardVisible);
    }

    /**
     * Get the last known IME (soft keyboard) height in px as reported by
     * {@link WindowInsetsCompat.Type#ime()}.
     *
     * This is an independent signal of the real keyboard height, not derived from
     * {@code getWindowVisibleDisplayFrame()}, so {@link TermuxActivityRootView} can use it to reject
     * bottom-margin measurements that are physically impossible. Returns {@code 0} when the IME is
     * hidden or when insets are unavailable (API < 30 without {@code ADJUST_RESIZE}), in which case
     * the root view falls back to the legacy behaviour.
     */
    public int getLastImeBottomPx() {
        return mLastImeBottomPx;
    }

    /**
     * Called by the insets-based detection ({@link WindowInsetsCompat.Type#ime()}).
     * Stores the insets signal and re-evaluates combined visibility.
     */
    private void onImeInsetsChanged(boolean imeVisible) {
        mImeVisibleFromInsets = imeVisible;
        reevaluateImeVisibility();
    }

    /**
     * Combines insets-based and visible-frame-based IME detection via OR logic.
     * IME is considered visible if EITHER method reports it visible, covering
     * three fallback situations where one method is unreliable.
     */
    private void reevaluateImeVisibility() {
        boolean imeVisible = mImeVisibleFromInsets
            || (mImeDetector != null && mImeDetector.isImeVisible());
        onImeVisibilityChanged(imeVisible);
    }

    /**
     * Shared reaction to IME visibility changes. Both detection methods converge
     * here after combination via {@link #reevaluateImeVisibility()}.
     */
    private void onImeVisibilityChanged(boolean imeVisible) {
        boolean wasVisible = mSoftKeyboardVisible;
        mSoftKeyboardVisible = imeVisible;

        if (imeVisible != wasVisible) {
            // Any of these states means the change is NOT an honest user action we should
            // record or react to:
            //  - mIsPaused: system hides the IME when we go to background;
            //  - mJustResumed: transient post-return insets frames (400ms window);
            //  - mRestoringKeyboard / mPendingKeyboardRestore: runKeyboardRestore() is the
            //    authority and is still applying the real state;
            //  - isTerminalPageSwitchInProgress(): the shared IME must not churn mid-switch.
            boolean inTransition = mIsPaused
                    || mJustResumed
                    || mRestoringKeyboard
                    || mPendingKeyboardRestore
                    || isTerminalPageSwitchInProgress();

            // Record the keyboard INTENT only in honest foreground states.
            if (!inTransition) {
                mTextInputState.setSoftKeyboardVisibleIntent(imeVisible);
            }

            // Auto-close text input panel when keyboard hides
            if (!inTransition
                    && wasVisible && !imeVisible && isTextInputVisible()
                    && !mButtonTouchInProgress && !mPopupCtrl.isHistoryPopupShowing()) {
                dismissAutoCompleteSuggestions();
                setTextInputVisible(false);
                updateToggleTextInputButtonIcon();
            }

            // Auto-show/hide extra keys with keyboard. Kept OUTSIDE the transition guard on
            // purpose: the extra-keys panel must always mirror the REAL IME visibility.
            if (mExtraKeysView != null
                    && getTerminalToolbarContainer().getVisibility() == View.VISIBLE
                    && !isTextInputVisible()
                    && mPreferences.shouldHideExtraKeysWithKeyboard()) {
                mExtraKeysView.setVisibility(imeVisible ? View.VISIBLE : View.GONE);
                Logger.logDebug(LOG_TAG, "Auto-" + (imeVisible ? "showing" : "hiding") + " extra keys with keyboard");
            }

        }
    }

    /**
     * Set the shared slot below the tabs: text input container vs extra keys.
     * The text input panel and the extra keys share one overlapping slot (FrameLayout);
     * showing one hides the other. When the text input panel is hidden, the extra keys
     * visibility also respects the {@code hide_extra_keys_with_keyboard} preference.
     * State is applied instantly (no animation).
     *
     * @param visible true to show the text input panel, false to hide it
     */
    private void setTextInputSlotVisible(boolean visible) {
        final View container = findViewById(R.id.terminal_toolbar_text_input_container);
        final ExtraKeysView ekv = getExtraKeysView();
        final LinearLayout toolbar = getTerminalToolbarContainer();

        if (container == null) {
            if (ekv != null) ekv.setVisibility(visible ? View.GONE
                    : (shouldShowExtraKeys() ? View.VISIBLE : View.GONE));
            return;
        }

        // Reset any transform/alpha left over from a previous animation so the panel is
        // always shown in its natural state.
        container.setAlpha(1f);
        container.setTranslationY(0f);

        if (visible) {
            // If the toolbar container is GONE (show_extra_keys = never), temporarily
            // show it so the text input panel (a child of the toolbar) can become visible.
            if (toolbar != null && toolbar.getVisibility() != View.VISIBLE) {
                toolbar.setVisibility(View.VISIBLE);
                mToolbarTemporarilyShownForTextInput = true;
            }
            // Showing the input panel hides the extra keys.
            container.setVisibility(View.VISIBLE);
            if (ekv != null) ekv.setVisibility(View.GONE);
        } else {
            // Hiding the input panel reveals the extra keys (per preference).
            container.setVisibility(View.GONE);
            if (ekv != null)
                ekv.setVisibility(shouldShowExtraKeys() ? View.VISIBLE : View.GONE);
            // If we had temporarily shown the toolbar for the text input, restore it.
            if (mToolbarTemporarilyShownForTextInput && toolbar != null) {
                toolbar.setVisibility(View.GONE);
                mToolbarTemporarilyShownForTextInput = false;
            }
        }
    }

    /**
     * Set visibility of the text input panel.
     * @param visible true to show, false to hide
     */
    public void setTextInputVisible(boolean visible) {
        // Dismiss auto-complete suggestions when hiding the panel. Use the
        // controller's dismiss() (not the raw popup dismiss) so any in-progress
        // swipe-gesture suppression guard is also cleared and auto-complete is
        // not left permanently disabled.
        if (!visible && mAutoCompleteCtrl != null) mAutoCompleteCtrl.dismiss();
        else if (!visible) dismissAutoCompleteSuggestions();

        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer != null) {
            // The text input panel and the extra keys share one slot below the tabs:
            // showing one hides the other.
            setTextInputSlotVisible(visible);
            // Re-anchor the pencil button: when toolbar was GONE (show_extra_keys = never),
            // the pencil was at ALIGN_PARENT_BOTTOM. After showing the toolbar for text
            // input, it must move to ABOVE the toolbar so it doesn't overlap the input panel.
            updateTextInputToggleButtonAnchor();
            // Save state to preferences
            getSharedPreferences("termux_prefs", MODE_PRIVATE).edit().putBoolean(PREF_TEXT_INPUT_VISIBLE, visible).apply();

            // Track per-session panel visibility so each tab remembers its own state.
            final TerminalSession session = getCurrentSession();
            if (session != null) {
                mTextInputState.setVisible(session.mHandle, visible);
            }

            // Must come after mTextInputState.setVisible() so isTextInputVisible() is correct.

            // Switch focus based on visibility
            if (visible) {
                // Restore this session's saved input text before showing the panel.
                restoreTextInputForSession(getCurrentSession());
                // Focus on text input and show keyboard
                EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
                if (textInput != null) {
                    textInput.requestFocus();
                    textInput.post(() -> {
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        }
                    });
                }
            } else {
                // Save the current input text for this session before hiding the panel.
                saveTextInputForCurrentSession();
                // Focus on terminal view without reopening the keyboard
                if (mTermuxTerminalViewClient != null)
                    mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                if (mTerminalView != null) {
                    mTerminalView.requestFocus();
                }
            }
        }
    }

    /**
     * Get saved visibility state of text input panel for the current session.
     * Falls back to the legacy global preference for sessions not yet tracked.
     * @return true if should be visible, false otherwise
     */
    public boolean isTextInputVisible() {
        final TerminalSession session = getCurrentSession();
        if (session != null && mTextInputState.hasVisible(session.mHandle)) {
            return mTextInputState.isVisible(session.mHandle);
        }
        // New sessions (no recorded per-session state) default to hidden.
        return false;
    }

    /**
     * Apply the per-session text input panel visibility for the given session,
     * updating the container/extra-keys slot. When {@code applyFocus} is true,
     * also moves focus/keyboard to match the panel state (used on tab switch).
     * When false, only the slot visibility is set (used at startup so we do not
     * fight setSoftKeyboardState's startup keyboard-hidden preference).
     * Does not re-record per-session state.
     */
    public void applyTextInputVisibilityForSession(@Nullable TerminalSession session, boolean applyFocus) {
        // The GLOBAL keyboard intent (the last explicit show/hide action) decides whether the
        // keyboard comes up when focus lands on the panel. This is what makes
        // "keyboard was hidden before the switch -> stays hidden on the target session" hold,
        // while still restoring the keyboard for a session the user was actively typing into
        // (intent still true). Keeps the tab-switch path consistent with the resume path,
        // which passes the same intent explicitly.
        applyTextInputVisibilityForSession(session, applyFocus,
                mTextInputState.isSoftKeyboardVisibleIntent());
    }

    /**
     * As {@link #applyTextInputVisibilityForSession(TerminalSession, boolean)}, but lets the
     * caller decide whether the keyboard should be shown when focus lands on the panel
     * (the resume path passes the persisted keyboard intent so a keyboard hidden by the
     * user before backgrounding is not popped back up).
     */
    public void applyTextInputVisibilityForSession(@Nullable TerminalSession session,
                                                   boolean applyFocus,
                                                   boolean showKeyboardIfFocused) {
        View textInputContainer = findViewById(R.id.terminal_toolbar_text_input_container);
        if (textInputContainer == null) return;

        boolean enabled = isTextInputEnabled();
        boolean hasRecorded = session != null && mTextInputState.hasVisible(session.mHandle);
        boolean visible = enabled && (hasRecorded
                ? mTextInputState.isVisible(session.mHandle)
                : isTextInputVisible());

        // Startup / tab switch restore: just set the slot state (no animation).
        // Record the resolved per-session visibility so isTextInputVisible() and
        // onBackPressed() stay authoritative even when the panel was shown via a
        // tab-switch or startup restore (which otherwise left hasVisible()==false
        // and made Back finish the app instead of hiding the panel).
        if (session != null) {
            mTextInputState.setVisible(session.mHandle, visible);
        }
        setTextInputSlotVisible(visible);

        // Restore this session's saved text whenever the panel is visible, regardless of
        // where focus lands. Doing it here (a single site) avoids a second restore on tab
        // switch (the caller no longer restores separately), which previously widened a race
        // window around the deferred auto-complete recompute.
        if (visible) {
            restoreTextInputForSession(session);
        }

        if (applyFocus) {
            // On switch, restore where focus was last for this session:
            // on the panel (with keyboard) or on the terminal.
            if (visible && isFocusOnInputForSession(session)) {
                final EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
                if (textInput != null) {
                    // Wait for real sizes so requestFocus/showSoftInput hit a served
                    // view instead of a zero-sized (unservable) one.
                    whenViewLaidOut(textInput, () -> {
                        textInput.requestFocus();
                        if (showKeyboardIfFocused) {
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }
                        } else {
                            // Keyboard must stay hidden on this session: swallow the
                            // focus-triggered show and cancel any stray pending show so a
                            // previously scheduled runnable cannot pop it back.
                            if (mTermuxTerminalViewClient != null) {
                                mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                                mTermuxTerminalViewClient.cancelPendingSoftKeyboardShow();
                            }
                            KeyboardUtils.hideSoftKeyboard(TermuxActivity.this, textInput);
                        }
                    }, 6);
                }
            } else {
                // Panel hidden, or focus was on the terminal: focus the terminal — UNLESS the
                // text input EditText still holds focus. Stealing focus here is what makes a later
                // long-press on the input panel bubble its context menu up to the terminal menu
                // instead of selecting a word (long-press regression on the input panel). The
                // EditText is a sibling of the pager (not inside a page), so it must keep focus
                // across page switches when the user was typing into it.
                final EditText currentInput = findViewById(R.id.terminal_toolbar_text_input);
                if (currentInput == null || !currentInput.hasFocus()) {
                    if (mTermuxTerminalViewClient != null)
                        mTermuxTerminalViewClient.ignoreOnceSoftKeyboardOnFocus();
                    if (mTerminalView != null) {
                        mTerminalView.requestFocus();
                    }
                }
            }
        } else if (visible) {
            // At startup we still want the saved text restored into the field,
            // just without grabbing focus / popping the keyboard.
            restoreTextInputForSession(session);
        }
        updateToggleTextInputButtonIcon();
    }

    /** Apply per-session panel visibility with focus move (tab switch). */
    public void applyTextInputVisibilityForSession(@Nullable TerminalSession session) {
        applyTextInputVisibilityForSession(session, true);
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        TermuxActivityUtils.updateTermuxActivityStyling(context, recreateActivity);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        if (mBroadcastManager == null) {
            mBroadcastManager = new TermuxActivityBroadcastManager(this);
        }
        mBroadcastManager.register();
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        if (mBroadcastManager != null) {
            mBroadcastManager.unregister();
        }
    }



    @Override
    public void reloadActivityStyling() {
        reloadActivityStyling(true);
    }

    /**
     * Apply or clear the fullscreen window flag according to the current
     * {@link TermuxAppSharedProperties#isUsingFullScreen()} setting. Called on activity creation and
     * on every styling reload so that toggling the setting takes effect immediately.
     */
    private void setFullScreenFlags() {
        if (mProperties == null) return;
        if (mProperties.isUsingFullScreen())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    public void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            // Cache all UI colours from the (now-updated) COLOR_SCHEME so every consumer reads
            // fresh values without computing them on the fly.
            mColorSchemeManager.recompute(getPreferences());

            if (mExtraKeysView != null) {
                if (mTermuxTerminalExtraKeys != null)
                    mTermuxTerminalExtraKeys.reloadExtraKeys();
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.setDynamicFontSize(getPreferences().isExtraKeysDynamicFontSizeEnabled(this));
                mExtraKeysView.setRuntimeEdgeIndicatorsEnabled(getPreferences().isExtraKeysEdgeIndicatorsEnabled());
                applyExtraKeysSpecialButtonMode();
                mExtraKeysView.setButtonColors(
                    mColorSchemeManager.getButtonText(),
                    deriveActiveTextColor(mColorSchemeManager.getButtonText()),
                    mColorSchemeManager.getButtonBg(),
                    mColorSchemeManager.getButtonActiveBg()
                );
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

// Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();
        setFullScreenFlags();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        // Restyle the whole activity (window, status bar, open popups) from the new scheme.
        applySchemeColors();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();


        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        TermuxActivityUtils.startTermuxActivity(context);
    }

    public static Intent newInstance(@NonNull final Context context) {
        return TermuxActivityUtils.newInstance(context);
    }

    /** Start TermuxActivity and close all existing terminal sessions, opening a fresh one.
     * Used after a data restore so the user does not keep stale sessions. */
    public static void startTermuxActivityWithSessionReset(@NonNull final Context context) {
        TermuxActivityUtils.startTermuxActivityWithSessionReset(context);
    }

}
