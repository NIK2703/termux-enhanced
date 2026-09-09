package com.termux.app.terminal;

import android.content.Context;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.io.autocomplete.DirectoryHistoryPopupController;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.monet.MonetSchemeStore;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * Extracted View-setup helper for {@link TermuxActivity}.
 *
 * <p>Previously these methods lived directly on the {@link TermuxActivity} god class. They only
 * touch public API on the activity (getters / {@code findViewById} / shared static utilities) plus
 * an injected {@link DirectoryHistoryPopupController}, so this helper can be hosted in the
 * {@code com.termux.app.terminal} package without modifying {@link TermuxActivity}.
 *
 * <p>The activity wires this helper in a later step by constructing it with the activity + inflater
 * and calling the {@code setup*()} methods from {@code onCreate()}.
 */
public class TermuxActivityViewHelper {

    // Context-menu item ids (mirror the private constants on TermuxActivity).
    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_FONT_ID = 12;

    @NonNull
    private final TermuxActivity mActivity;
    @NonNull
    private final LayoutInflater mLayoutInflater;

    // Injected by the activity (set after construction) so the new-session tab button can drive
    // the directory-history swipe-up gesture without the helper reaching into activity-private state.
    @Nullable
    private DirectoryHistoryPopupController mDirectoryHistoryPopupCtrl;

    public TermuxActivityViewHelper(@NonNull TermuxActivity activity, @NonNull LayoutInflater layoutInflater) {
        this.mActivity = activity;
        this.mLayoutInflater = layoutInflater;
    }

    public void setDirectoryHistoryPopupController(@Nullable DirectoryHistoryPopupController controller) {
        this.mDirectoryHistoryPopupCtrl = controller;
    }

    // ============================================================================
    // View setup methods
    // ============================================================================

    /**
     * Wire the new-session button. The new-session button now lives in the tabs bar and is handled
     * in {@link #setupSessionsListView(View)}, so this is intentionally a no-op (kept for symmetry
     * with the original {@code TermuxActivity#setNewSessionButtonView()}).
     */
    public void setupNewSessionButton(@NonNull View rootView) {
        // New session button is now in the tabs bar, handled in setupSessionsListView().
    }

    /**
     * Wire the toggle-keyboard button. The toggle-keyboard button was removed (its functionality
     * moved to the extra keys), so this is intentionally a no-op (mirrors
     * {@code TermuxActivity#setToggleKeyboardView()}).
     */
    public void setupToggleKeyboardButton(@NonNull View rootView) {
        // Toggle keyboard button removed - functionality moved to extra keys.
    }

    /** Wire the horizontal session-pager tabs, including the new-session tab button. */
    public void setupSessionsListView(@NonNull View rootView) {
        // NOTE: The live wiring for the new-session (+) button (click, long-press and the
        // swipe-to-directory-history gesture with its active-background feedback) lives in
        // TermuxActivity.setTermuxSessionsListView(), which is the path actually invoked at
        // runtime (setupSessionsListView is currently dead code). Kept as a no-op to avoid
        // drifting duplicate gesture handlers.
    }

    /**
     * Apply the user-selected theme / night mode. Mirrors {@code TermuxActivity#applyTermuxTheme()}.
     * Note: the terminal color scheme repaint is intentionally NOT done here (see the original
     * method's documentation) — it runs later once the terminal view and client exist.
     */
    public void applyTheme() {
        // Update NightMode.APP_NIGHT_MODE.
        TermuxThemeUtils.setAppNightMode(mActivity.getProperties().getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, android will automatically trigger
        // recreation of the activity when uiMode/dark mode configuration changes so the day/night
        // theme takes effect.
        AppCompatActivityUtils.setNightMode(mActivity, NightMode.getAppNightMode().getName(), true);
    }

    /**
     * Minimal toolbar setup: show/hide the terminal toolbar container per the user preference.
     * (The full toolbar wiring — extra keys, text input, height — remains in
     * {@code TermuxActivity#setTerminalToolbarView(Bundle)} and is out of scope for this helper.)
     */
    public void setupToolbar() {
        LinearLayout terminalToolbarContainer = mActivity.getTerminalToolbarContainer();
        if (terminalToolbarContainer != null && mActivity.getPreferences().shouldShowTerminalToolbar())
            terminalToolbarContainer.setVisibility(View.VISIBLE);
    }

    // ============================================================================
    // Context menu (delegated from TermuxActivity)
    // ============================================================================

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession == null) return;

        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView == null) return;

        buildContextMenu(menu, mActivity, mActivity.getResources(), terminalView,
            currentSession.getPid(), currentSession.isRunning(),
            mActivity.getPreferences().shouldKeepScreenOn());
    }

    /**
     * Build the shared terminal context-menu items. The item set and ids are identical between
     * {@link TermuxActivityViewHelper} and {@link TermuxActivityPopupController}; only the way the
     * session / keep-screen-on state is obtained differs between the two hosts, so those are passed
     * in as parameters. The host-specific {@code onContextItemSelected} implementations still own
     * how each item is serviced and must use the same ids.
     */
    static void buildContextMenu(@NonNull ContextMenu menu, @NonNull Context context,
                                 @NonNull android.content.res.Resources resources,
                                 @NonNull TerminalView terminalView, int sessionPid, boolean sessionRunning,
                                 boolean keepScreenOn) {
        boolean autoFillEnabled = terminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!com.termux.shared.data.DataUtils.isNullOrEmpty(terminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE,
            resources.getString(R.string.action_kill_process, sessionPid))
            .setEnabled(sessionRunning);
        boolean stylingInstalled = PackageUtils.getContextForPackage(context,
            TermuxConstants.TERMUX_STYLING_PACKAGE_NAME) != null;
        // The "Style" item opens our own color-scheme picker, which on Android 12+ also offers the
        // wallpaper-derived Monet schemes — so it is useful even without Termux:Style.
        if (stylingInstalled || MonetSchemeStore.isSupported()) {
            menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        }
        if (stylingInstalled) {
            menu.add(Menu.NONE, CONTEXT_MENU_FONT_ID, Menu.NONE, R.string.action_font_terminal);
        }
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true).setChecked(keepScreenOn);
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    public void onContextMenuClosed(Menu menu) {
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of
        // tap for some reason.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.onContextMenuClosed(menu);
    }
}
