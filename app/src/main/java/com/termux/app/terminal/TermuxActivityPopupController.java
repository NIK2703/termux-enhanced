package com.termux.app.terminal;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.io.autocomplete.MessageHistoryController;
import com.termux.app.TermuxActivityUtils;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.FontUtils;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

public final class TermuxActivityPopupController {

    public interface Host {
        @Nullable TerminalSession getCurrentSession();
        @NonNull String getCurrentCwdForHistory();
        void reloadActivityStyling(); // recreate = true
        void recreateActivity(); // reloadActivityStyling(false)
        void finishActivity();
        TerminalView getTerminalView();

        // History / directory sync
        void onHistoryDirectoryChanged();

        // Keep-screen-on preference bridge
        boolean isKeepScreenOn();
        void setKeepScreenOn(boolean keepOn);

        // Context-menu terminal-view-client actions
        void showUrlSelection();
        void shareSessionTranscript();
        void shareSelectedText();
        void onResetTerminalSession(@NonNull TerminalSession session);
        void showKillSessionDialog(@NonNull TerminalSession session);
        void toggleKeepScreenOn();
        void reportIssueFromTranscript();
        void startHelpActivity();
        void startSettingsActivity();
    }

    @NonNull private final Context mContext;
    @NonNull private final Host mHost;

    // History popup state
    private final java.util.ArrayList<View> mHistoryItemViews = new java.util.ArrayList<>();
    /** Reused per-highlight / per-frame location buffer (avoids int[2] churn). */
    private final int[] mTmpLoc = new int[2];
    private int mHistoryHighlightIndex = -1;

    // Dependencies injected by the host activity (mirrors TermuxActivity fields).
    @Nullable private MessageHistoryController mMessageHistoryCtrl = null;
    private final TermuxColorSchemeManager mColorSchemeManager;

    // Live popup state (mirrors TermuxActivity fields).
    private PopupWindow mHistoryPopup = null;
    private ScrollView mHistoryScroll = null;
    private boolean mHistoryAutoScrolling = false;
    /** Last finger Y (screen) while the popup is open, for continuous edge auto-scroll. */
    private float mHistoryFingerY = 0f;
    /** Timestamp of the last auto-scroll tick, for frame-rate-independent velocity. */
    private long mHistoryLastScrollTimeMs = 0;

    // Context menu item ids (mirrors TermuxActivity definitions).
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

    // History popup synthetic-row tags (mirrors TermuxActivity definitions).
    private static final int MESSAGE_HISTORY_CLEAR_TAG = -2;
    private static final int MESSAGE_HISTORY_CLEAR_ALL_TAG = -3;


    public TermuxActivityPopupController(@NonNull Context context, @NonNull Host host,
                                          @NonNull TermuxColorSchemeManager colorSchemeManager) {
        mContext = context;
        mHost = host;
        mColorSchemeManager = colorSchemeManager;
    }

    public void setMessageHistoryController(@Nullable MessageHistoryController controller) {
        mMessageHistoryCtrl = controller;
    }


    public void showMessageHistoryPopup(@NonNull View anchor) {
        dismissMessageHistoryPopup();
        mHistoryItemViews.clear();
        mHistoryHighlightIndex = -1;

        // Sync per-directory history if the current directory changed since
        // the last swap (e.g. after `cd` or a tab switch where the client
        // callback was missed). Without this, the popup would show the
        // previous directory's history until the user sends a message.
        if (mMessageHistoryCtrl != null && mMessageHistoryCtrl.isPerDirectoryEnabled()) {
            String cwd = mHost.getCurrentCwdForHistory();
            if (!cwd.equals(mMessageHistoryCtrl.getHistoryCurrentDirectory())) {
                mHost.onHistoryDirectoryChanged();
            }
        }

        // Empty state detection: no history and no typed text.
        boolean hasHistory = mMessageHistoryCtrl != null && !mMessageHistoryCtrl.getHistoryList().isEmpty();
        final EditText inputField = ((Activity) mContext).findViewById(R.id.terminal_toolbar_text_input);
        final String inputText = inputField != null && inputField.getText() != null
                ? inputField.getText().toString() : "";
        boolean emptyState = !hasHistory && TextUtils.isEmpty(inputText);
        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);

        int padH = TermuxActivityUtils.dpToPx(mContext, 14);
        int padV = TermuxActivityUtils.dpToPx(mContext, 10);

        // Empty state: no history and no typed text → render the hint inside the same
        // scheme-styled popup. Previously this was a system Toast, which is drawn by
        // SystemUI's own theme: it ignores the app's color scheme entirely and came
        // out white-on-white in light mode on some ROMs.
        if (emptyState) {
            TextView hint = new TextView(mContext);
            hint.setText(mContext.getString(R.string.message_history_empty));
            hint.setGravity(Gravity.CENTER);
            hint.setTextColor(mColorSchemeManager.getHistoryTextColor());
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setPadding(padH, padV, padH, padV);
            content.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Synthetic "CLEAR HISTORY…" row pinned at the TOP of the popup.
        // Selecting it opens a confirmation dialog; confirming wipes all history.
        // Shown only when there is history to clear. Coexists with the bottom
        // "Clear" row (clears the input), it is not a replacement for it.
        if (mMessageHistoryCtrl != null && !mMessageHistoryCtrl.getHistoryList().isEmpty()) {
            TextView tv = new TextView(mContext);
            tv.setText(mContext.getString(R.string.message_history_clear_all));
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_ALL_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator below the clear all row to visually group it.
            View sep = new View(mContext);
            sep.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
            content.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, TermuxActivityUtils.dpToPx(mContext, 1)));
        }
        // Displayed order (spec): newest at the BOTTOM (nearest the pencil button,
        // first reached by a swipe-up), oldest at the top. A re-sent message moves
        // to index 0 (front) of mMessageHistoryCtrl.getHistoryList(), so iterate in REVERSE (end -> 0)
        // to fill the vertical layout top-to-bottom with the newest last (bottom).
        if (mMessageHistoryCtrl != null) {
            for (int i = mMessageHistoryCtrl.getHistoryList().size() - 1; i >= 0; i--) {
                final String message = mMessageHistoryCtrl.getHistoryList().get(i);
                TextView tv = new TextView(mContext);
                // Preview: collapse newlines to spaces, wrap to at most 2 lines and add
                // an ellipsis when the message is longer than that.
                tv.setText(message.replace("\n", " ").trim());
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setPadding(padH, padV, padH, padV);
                tv.setClickable(true);
                // Tag with the real history index so highlight/selection maps back.
                tv.setTag(i);
                content.addView(tv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                mHistoryItemViews.add(tv);
            }
        }

        // Synthetic "Clear" row pinned at the BOTTOM of the popup (nearest the
        // pencil button): remembers the current input text in history, then empties
        // the input field. Shown only when the input panel actually has text.
        if (!TextUtils.isEmpty(inputText)) {
            TextView tv = new TextView(mContext);
            tv.setText(mContext.getString(R.string.message_history_clear));
            tv.setGravity(Gravity.CENTER);
            tv.setAllCaps(true);
            tv.setTextColor(mColorSchemeManager.getHistoryTextColor());
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setPadding(padH, padV, padH, padV);
            tv.setClickable(true);
            tv.setTag(MESSAGE_HISTORY_CLEAR_TAG);
            content.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mHistoryItemViews.add(tv);

            // Thin separator above the bottom "Clear" row acts as a visual
            // divider between the history list and the action.  Only meaningful
            // when there IS a history list to separate it from.
            if (mMessageHistoryCtrl != null && !mMessageHistoryCtrl.getHistoryList().isEmpty()) {
                View sepBottom = new View(mContext);
                sepBottom.setBackgroundColor(mColorSchemeManager.getHistoryPopupSepColor());
                content.addView(sepBottom, content.getChildCount() - 1,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, TermuxActivityUtils.dpToPx(mContext, 1)));
            }
        }

        int popupWidth = Math.min(
                mContext.getResources().getDisplayMetrics().widthPixels - TermuxActivityUtils.dpToPx(mContext, 24),
                TermuxActivityUtils.dpToPx(mContext, 320));

        // Wrap in a ScrollView: the popup is a bounded box (never edge-to-edge),
        // and a taller history scrolls inside it. Kept for edge auto-scroll while
        // the finger drags near the top/bottom of the box.
        android.widget.ScrollView scroll = new android.widget.ScrollView(mContext);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mHistoryScroll = scroll;
        // Clip children to the popup's rounded corners so highlights and
        // separators near the edges don't spill outside the rounded shape.
        // Guard against 0 dims during WRAP_CONTENT resize: if w or h is 0
        // the outline is left empty (no clipping) instead of clipping to nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scroll.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h, TermuxActivityUtils.dpToPx(mContext, 12));
                    }
                }
            });
            scroll.setClipToOutline(true);
        }

        mHistoryPopup = new PopupWindow(scroll, popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // Smooth elevation shadow — background drawable must be fully opaque for the
        // WindowManager to derive a valid Outline (GradientDrawable.getOutline bails
        // when alpha < 255).  The 10% visual transparency is applied to the ScrollView
        // itself via setAlpha(), which does not affect the popup's background outline.
        // Larger elevation (16dp) for a bigger shadow, but outline alpha is
        // reduced so the shadow renders more transparent/softer.
        mHistoryPopup.setElevation(TermuxActivityUtils.dpToPx(mContext, 16));
        // Background: rounded rect, fully opaque scheme composite colour.
        // getOutline() is overridden to call outline.setAlpha() — this controls
        // the shadow opacity independently from the elevation size.
        GradientDrawable popupBgDrawable = new GradientDrawable() {
            @Override
            public void getOutline(@NonNull Outline outline) {
                super.getOutline(outline);
                if (!outline.isEmpty()) {
                    // Keep elevation large, but make the shadow softer/transparent.
                    // setAlpha requires API 31+.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        outline.setAlpha(0.65f);
                    }
                }
            }
        };
        popupBgDrawable.setShape(GradientDrawable.RECTANGLE);
        popupBgDrawable.setCornerRadius(TermuxActivityUtils.dpToPx(mContext, 12));
        popupBgDrawable.setColor(mColorSchemeManager.getHistoryPopupBg()); // must be opaque for getOutline
        mHistoryPopup.setBackgroundDrawable(popupBgDrawable);
        // 10% visual transparency on the content (not the background drawable, so the
        // elevation shadow outline stays valid).
        scroll.setAlpha(0.9f);
        mHistoryPopup.setClippingEnabled(true);
        // Do NOT let the popup intercept touches: the pencil button keeps the
        // gesture so we track the finger over items via raw coordinates.
        mHistoryPopup.setTouchable(false);
        mHistoryPopup.setFocusable(false);

        // Anchor above the button, right-aligned to it.
        mHistoryPopup.showAsDropDown(anchor, 0, 0, Gravity.START);
        // Reposition to sit ABOVE the anchor instead of below: measure content
        // then offset upward. showAsDropDown places below, so we shift up here.
        content.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeight = content.getMeasuredHeight();
        // Bounded box: min(content, configured max, room above the button).
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        // Gap between the button's top and the popup's bottom edge.
        int popupGap = mContext.getResources().getDimensionPixelSize(R.dimen.message_history_popup_gap);
        int roomAbove = Math.max(TermuxActivityUtils.dpToPx(mContext, 48), anchorLoc[1] - TermuxActivityUtils.dpToPx(mContext, 8) - popupGap);
        int maxHeight = Math.min(mContext.getResources().getDimensionPixelSize(R.dimen.message_history_popup_max_height), roomAbove);
        int popupHeight = Math.min(contentHeight, maxHeight);
        mHistoryPopup.update(anchor,
                0,
                -(anchor.getHeight() + popupHeight + popupGap),
                popupWidth,
                popupHeight);

        // Open at the END of the list: newest is at the bottom, so start scrolled
        // fully down so the newest messages (nearest the button) are visible.
        // jump straight to the bottom — fullScroll(FOCUS_DOWN) animates, which looks
        // like the list is scrolling past entries as the popup appears.
        final android.widget.ScrollView scrollRef1 = scroll;
        scrollRef1.post(() -> {
            View child = scrollRef1.getChildAt(0);
            if (child != null) scrollRef1.scrollTo(0, child.getHeight());
        });
    }

    /** Wipe message history for ALL directories (per-directory mode only). */
    private void clearAllDirectoriesHistory() {
        if (mMessageHistoryCtrl != null) mMessageHistoryCtrl.clearAllPerDirectory();
    }

    /** Wipe the message history for the current context (global or current directory). */
    private void clearAllHistory() {
        if (mMessageHistoryCtrl != null) mMessageHistoryCtrl.clearCurrent(mHost.getCurrentCwdForHistory());
    }

    /** Whether the history popup is currently showing. */
    public boolean isHistoryPopupShowing() {
        return mHistoryPopup != null && mHistoryPopup.isShowing();
    }

    /** Get the currently highlighted history index (or -1 / tag value for synthetic rows). */
    public int getHistoryHighlightIndex() {
        return mHistoryHighlightIndex;
    }

    /** Update the history highlight based on finger position (raw screen coords). */
    public void updateHistoryHighlight(float rawX, float rawY) {
        mHistoryFingerY = rawY;
        if (mHistoryScroll == null || !isHistoryPopupShowing()) {
            mHistoryAutoScrolling = false;
        }
        // Kick off the edge auto-scroll loop at most once; the loop reschedules
        // itself. Calling this on every ACTION_MOVE must NOT spawn extra loops.
        startHistoryAutoScroll();

        int newIndex = -1;
        int[] loc = mTmpLoc;
        for (View tv : mHistoryItemViews) {
            tv.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + tv.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + tv.getHeight()) {
                Object tag = tv.getTag();
                if (tag instanceof Integer) newIndex = (Integer) tag;
                break;
            }
        }
        if (newIndex == mHistoryHighlightIndex) return;
        mHistoryHighlightIndex = newIndex;

        for (View tv : mHistoryItemViews) {
            Object tag = tv.getTag();
            boolean active = tag instanceof Integer && (Integer) tag == mHistoryHighlightIndex;
            if (active) {
                tv.setBackgroundColor(mColorSchemeManager.getHistoryHighlightFill());
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    /**
     * Start the edge auto-scroll loop, but only if it is not already running.
     * This must be idempotent: {@link #updateHistoryHighlight(float, float)} is
     * invoked on every ACTION_MOVE, and if each call spawned its own loop the
     * loops would accumulate and the scroll speed would multiply with every
     * finger movement. The running loop reschedules itself via
     * {@link #autoScrollHistoryNearEdge()}.
     */
    private void startHistoryAutoScroll() {
        if (mHistoryScroll == null || !isHistoryPopupShowing() || mHistoryAutoScrolling) {
            return;
        }
        mHistoryAutoScrolling = true;
        mHistoryLastScrollTimeMs = SystemClock.uptimeMillis();
        mHistoryScroll.postOnAnimation(this::autoScrollHistoryNearEdge);
    }

    /**
     * Tick of the edge auto-scroll loop. Continuously scrolls the popup's
     * ScrollView while the finger rests/drags within an edge band at the top or
     * bottom (like the keyboard-accent popup: it keeps moving even without
     * finger motion). Driven by a self-rescheduling postDelayed loop that stops
     * once the finger leaves the band or the popup closes.
     */
    private void autoScrollHistoryNearEdge() {
        if (mHistoryScroll == null || !isHistoryPopupShowing()) {
            mHistoryAutoScrolling = false;
            return;
        }
        int[] loc = mTmpLoc;
        mHistoryScroll.getLocationOnScreen(loc);
        int top = loc[1];
        int bottom = loc[1] + mHistoryScroll.getHeight();
        int band = TermuxActivityUtils.dpToPx(mContext, 36);      // edge-sensitive zone
        int maxStep = TermuxActivityUtils.dpToPx(mContext, 24);   // max px scrolled per 16ms reference interval

        // Time-based step: scale by actual frame time so scroll speed is
        // consistent across 60/90/120 Hz displays (Choreographer / postOnAnimation).
        long now = SystemClock.uptimeMillis();
        float frameRatio;
        if (mHistoryLastScrollTimeMs == 0) {
            frameRatio = 1f;   // first tick
        } else {
            long dt = Math.min(now - mHistoryLastScrollTimeMs, 48L); // cap at 3× reference
            frameRatio = dt / 16f;
        }
        mHistoryLastScrollTimeMs = now;

        float rawY = mHistoryFingerY;
        int step;
        if (rawY < top + band) {
            float t = Math.min(1f, (top + band - rawY) / band);
            step = -Math.round(maxStep * t * frameRatio);
        } else if (rawY > bottom - band) {
            float t = Math.min(1f, (rawY - (bottom - band)) / band);
            step = Math.round(maxStep * t * frameRatio);
        } else {
            mHistoryAutoScrolling = false;   // left the band; stop the loop
            return;
        }

        mHistoryScroll.scrollBy(0, step);

        // Reschedule on next vsync (Choreographer) for smooth frame-aligned scrolling.
        mHistoryScroll.postOnAnimation(this::autoScrollHistoryNearEdge);
    }
    public void dismissMessageHistoryPopup() {
        if (mHistoryPopup != null) {
            try { mHistoryPopup.dismiss(); } catch (Exception ignored) {}
            mHistoryPopup = null;
        }
        mHistoryItemViews.clear();
        mHistoryScroll = null;
        mHistoryAutoScrolling = false;   // stop any pending edge-scroll loop
        mHistoryFingerY = 0f;
        mHistoryLastScrollTimeMs = 0;
        mHistoryHighlightIndex = -1;
    }

    /**
     * Ask the user to confirm wiping the message history. In per-directory mode
     * the dialog has three buttons: OK (current directory only), All (all
     * directories), Cancel. In global mode it stays as OK + Cancel.
     */
    public void confirmClearAllHistory() {
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mContext)
                .setTitle(mContext.getString(R.string.message_history_clear_question))
                .setNegativeButton(android.R.string.cancel, null);

        if (mMessageHistoryCtrl != null && mMessageHistoryCtrl.isPerDirectoryEnabled()) {
            builder.setMessage(mContext.getString(R.string.message_history_clear_current_only_question))
                    .setPositiveButton(mContext.getString(R.string.message_history_clear_ok), (d, w) -> clearAllHistory())
                    .setNeutralButton(mContext.getString(R.string.message_history_clear_all_btn), (d, w) -> clearAllDirectoriesHistory());
        } else {
            builder.setMessage(mContext.getString(R.string.message_history_clear_all_question))
                    .setPositiveButton(android.R.string.ok, (d, w) -> clearAllHistory());
        }

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
    }

    /** "Clear message history..." item: ask for confirmation, then wipe all history. */
    private void confirmClearHistory() {
        final MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(mContext);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setTitle(mContext.getString(R.string.message_history_clear_dialog_title));
        String msg = (mMessageHistoryCtrl != null && mMessageHistoryCtrl.isPerDirectoryEnabled())
                ? mContext.getString(R.string.message_history_clear_confirm_current)
                : mContext.getString(R.string.message_history_clear_confirm_all);
        b.setMessage(msg);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            clearAllHistory();
            showToast(mContext.getString(R.string.message_history_cleared), true);
        });
        b.setNegativeButton(android.R.string.no, null);
        androidx.appcompat.app.AlertDialog dialog = b.create();
        dialog.show();
    }

    public void onBackPressed() {
        mHost.finishActivity();
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        ((TermuxActivity) mContext).showToast(text, longDuration);
    }

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        TerminalSession currentSession = mHost.getCurrentSession();
        if (currentSession == null) return;

        TerminalView terminalView = mHost.getTerminalView();
        if (terminalView == null) return;

        TermuxActivityViewHelper.buildContextMenu(menu, mContext, mContext.getResources(), terminalView,
            currentSession.getPid(), currentSession.isRunning(), mHost.isKeepScreenOn());
    }

    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = mHost.getCurrentSession();
        TerminalView terminalView = mHost.getTerminalView();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SELECT_URL_ID:
                mHost.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mHost.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mHost.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                if (terminalView != null) terminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                if (terminalView != null) terminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                mHost.onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                mHost.showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_FONT_ID:
                showFontPicker();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                mHost.toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                mHost.startHelpActivity();
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                mHost.startSettingsActivity();
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mHost.reportIssueFromTranscript();
                return true;
            default:
                return false;
        }
    }

    private void showStylingDialog() {
        // Show our own color-scheme picker (the same dialog used in Settings) for the currently
        // active theme, applying the choice live to that theme. This assigns the scheme to the
        // active light/dark theme instead of the shared colors.properties, so per-theme selection
        // stays consistent whether chosen from here or from Settings.
        final boolean isNight = TermuxActivity.isNightModeActive();
        ColorSchemeUtils.showColorSchemeDialog(mContext, isNight, mContext.getString(R.string.color_scheme_dialog_title),
            mContext.getString(R.string.error_styling_not_installed),
            () -> TermuxActivityUtils.updateTermuxActivityStyling(mContext, false));
    }

    /**
     * Show the Termux:Style font picker dialog. Lists every font shipped by the installed
     * Termux:Style plugin and applies the selected one live without an activity restart.
     * If Termux:Style is not installed, shows a "not installed" message.
     */
    private void showFontPicker() {
        FontUtils.showFontDialog(mContext, mContext.getString(R.string.error_styling_not_installed),
            () -> {
                TermuxActivityUtils.updateTermuxActivityStyling(mContext, false);
                showToast(mContext.getResources().getString(R.string.msg_terminal_font_applied), true);
            });
    }
}
