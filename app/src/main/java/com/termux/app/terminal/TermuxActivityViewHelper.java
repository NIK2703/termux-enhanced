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
 * Extracted View-setup helper for {@link TermuxActivity} — these methods previously lived on the
 * activity god class. They only touch public API on the activity (getters / {@code findViewById} /
 * shared static utilities) plus an injected {@link DirectoryHistoryPopupController}, so they can
 * live in {@code com.termux.app.terminal} without modifying {@link TermuxActivity}. The activity
 * constructs this helper and calls the {@code setup*()} methods from {@code onCreate()}.
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

    // ── View setup ──

    /** No-op: the new-session button now lives in the tabs bar (see
     *  {@link #setupSessionsListView(View)}); kept for symmetry with the original activity method. */
    public void setupNewSessionButton(@NonNull View rootView) {
    }

    /** No-op: the toggle-keyboard button was removed (functionality moved to the extra keys). */
    public void setupToggleKeyboardButton(@NonNull View rootView) {
    }

    /** Intentionally a no-op: the live wiring for the new-session (+) button lives in
     *  {@code TermuxActivity#setTermuxSessionsListView()} (the path invoked at runtime), so no
     *  duplicate gesture handler is kept here. */
    public void setupSessionsListView(@NonNull View rootView) {
    }

    /**
     * Apply the user-selected theme / night mode. Mirrors {@code TermuxActivity#applyTermuxTheme()}.
     * Note: the terminal color scheme repaint is intentionally NOT done here — it runs later once
     * the terminal view and client exist.
     */
    public void applyTheme() {
        TermuxThemeUtils.setAppNightMode(mActivity.getProperties().getNightMode());

        // If NightMode.SYSTEM is set, Android automatically recreates the activity on
        // uiMode/dark-mode configuration changes so the day/night theme takes effect.
        AppCompatActivityUtils.setNightMode(mActivity, NightMode.getAppNightMode().getName(), true);
    }

    /**
     * Minimal toolbar setup: show/hide the terminal toolbar container per the user preference.
     * (The full toolbar wiring remains in {@code TermuxActivity#setTerminalToolbarView(Bundle)}.)
     */
    public void setupToolbar() {
        LinearLayout terminalToolbarContainer = mActivity.getTerminalToolbarContainer();
        if (terminalToolbarContainer != null && mActivity.getPreferences().shouldShowTerminalToolbar())
            terminalToolbarContainer.setVisibility(View.VISIBLE);
    }

    // ── Context menu (delegated from TermuxActivity) ──

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
     * {@link TermuxActivityViewHelper} and {@link TermuxActivityPopupController}; only how the
     * session / keep-screen-on state is obtained differs, so those are passed in. Host-specific
     * {@code onContextItemSelected} implementations must use the same ids.
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
        // Triggered twice if the back button (not a tap) dismisses the menu.
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView != null) terminalView.onContextMenuClosed(menu);
    }
}
