package com.termux.app.terminal;

import com.termux.app.TermuxAppConstants;

import android.content.Context;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.monet.MonetSchemeStore;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.view.TerminalView;

/**
 * Extracted View-setup helper for {@link TermuxActivity} — these methods previously lived on the
 * activity god class. They only touch public API on the activity (getters / {@code findViewById} /
 * shared static utilities), so they can live in {@code com.termux.app.terminal} without modifying
 * {@link TermuxActivity}. The activity constructs this helper and calls the {@code setup*()} methods
 * from {@code onCreate()}.
 */
public class TermuxActivityViewHelper {

    // Context-menu item ids (mirror the private constants on TermuxActivity).

    @NonNull
    private final TermuxActivity mActivity;

    public TermuxActivityViewHelper(@NonNull TermuxActivity activity, @NonNull LayoutInflater layoutInflater) {
        this.mActivity = activity;
    }

    // ── View setup ──

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

    // ── Context menu (delegated from TermuxActivity) ──

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

        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.SELECT_URL, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.SHARE_TRANSCRIPT, Menu.NONE, R.string.action_share_transcript);
        if (!com.termux.shared.data.DataUtils.isNullOrEmpty(terminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.RESET_TERMINAL, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.KILL_PROCESS, Menu.NONE,
            resources.getString(R.string.action_kill_process, sessionPid))
            .setEnabled(sessionRunning);
        boolean stylingInstalled = PackageUtils.getContextForPackage(context,
            TermuxConstants.TERMUX_STYLING_PACKAGE_NAME) != null;
        // The "Style" item opens our own color-scheme picker, which on Android 12+ also offers the
        // wallpaper-derived Monet schemes — so it is useful even without Termux:Style.
        if (stylingInstalled || MonetSchemeStore.isSupported()) {
            menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.STYLING, Menu.NONE, R.string.action_style_terminal);
        }
        if (stylingInstalled) {
            menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.FONT, Menu.NONE, R.string.action_font_terminal);
        }
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on)
            .setCheckable(true).setChecked(keepScreenOn);
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.HELP, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.SETTINGS, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, TermuxAppConstants.ContextMenu.REPORT, Menu.NONE, R.string.action_report_issue);
    }
}
