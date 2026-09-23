package com.termux.app.terminal.io;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.terminal.TerminalSession;

/**
 * Controller for the terminal "text input" panel and its toggle button.
 * Wired into TermuxActivity to hold view references and save/restore text state.
 */
public final class TextInputPanelController {

    public interface Host {
        @Nullable TerminalSession getCurrentSession();
        void onToggleTextInput(boolean nowVisible);
    }

    @NonNull private final Context mContext;
    @NonNull private final Host mHost;
    @NonNull private final SessionUiStateStore mTextInputState;

    @Nullable private EditText mEditText;
    @Nullable private View mTextInputContainer;
    @Nullable private ImageButton mToggleTextInputButton;

    private static final String PREF_TEXT_INPUT_VISIBLE = "text_input_visible";
    private static final String PREF_TEXT_INPUT_ENABLED = "text_input_enabled";

    /**
     * The input panel may never occupy more than a {@code 1/MAX_HEIGHT_FRACTION} share of the
     * height of the area it shares with the terminal — a quarter. At the height the XML declares
     * the limit never engages in a phone-sized window, but the same fixed height would swallow
     * most of a short window (floating bubble, split-screen, landscape with keyboard up) —
     * exactly where the user loses the output they were typing against.
     */
    private static final int MAX_HEIGHT_FRACTION = 4;

    /**
     * Panel height in px as laid out in XML — the unclamped maximum the limit is applied to.
     * Captured once in {@link #setup}, so the clamp is always expressed against the designed
     * height instead of against whatever the panel happens to measure right now (which would
     * make the limit ratchet: each shrink would become the new "original", and the panel could
     * never grow back when the window does).
     */
    private int mPanelBaseHeightPx = -1;

    /** Panel height in px currently written into the layout params. -1 until {@link #setup}. */
    private int mAppliedPanelHeightPx = -1;

    public TextInputPanelController(@NonNull Context context,
                                    @NonNull Host host,
                                    @NonNull SessionUiStateStore textInputState) {
        mContext = context;
        mHost = host;
        mTextInputState = textInputState;
    }

    public void setup(@Nullable Bundle savedInstanceState, @NonNull View rootView) {
        mEditText = rootView.findViewById(R.id.terminal_toolbar_text_input);
        mTextInputContainer = rootView.findViewById(R.id.terminal_toolbar_text_input_container);
        mToggleTextInputButton = rootView.findViewById(R.id.toggle_text_input_button);
        capturePanelBaseHeight();
    }

    /**
     * Record the designed panel height so {@link #applyPanelHeightLimitForContentView} has a
     * constant to clamp. Measured on the FIELD, not the container: the container is
     * {@code wrap_content} with the field as its only child, so the field height IS the visible
     * panel height (the plain {@code <shape>} background draws inside bounds), and it is the
     * single value the XML declares — capping the container would clip the field inside a
     * correct-looking frame. Container vertical margins are excluded: they are spacing to
     * neighbours, not panel size.
     */
    private void capturePanelBaseHeight() {
        if (mEditText == null || mPanelBaseHeightPx > 0) return;
        ViewGroup.LayoutParams lp = mEditText.getLayoutParams();
        if (lp != null && lp.height > 0) {
            mPanelBaseHeightPx = lp.height;
            mAppliedPanelHeightPx = lp.height;
        }
    }

    /**
     * The height limit for a panel sharing {@code contentHeightPx} px with the terminal, in px.
     * <p>
     * Integer division on purpose: the cap is a hard "no more than a quarter", so it rounds DOWN.
     * A non-positive height (nothing measured yet, or a window too degenerate to reason about)
     * yields {@code 0}, which callers must read as "no usable measurement", not as a real cap.
     */
    public static int maxPanelHeightForContentHeight(int contentHeightPx) {
        return contentHeightPx <= 0 ? 0 : contentHeightPx / MAX_HEIGHT_FRACTION;
    }

    /**
     * Cap the panel at a quarter of the height of the area it shares with the terminal.
     * <p>
     * Uses the CONTENT BOX of {@code contentView} (height minus vertical padding), not raw
     * height: that padding is where system-bar and keyboard insets land, so the content box is
     * exactly what the toolbar and terminal divide. Raw height still counts the status-bar strip
     * and — when the keyboard covers the window without a platform resize (floating keyboard, a
     * ROM ignoring ADJUST_RESIZE, an under-reporting IME) — the keyboard strip; capping against
     * it would let the panel eat everything the user can see.
     * <p>
     * Call on every layout of that root view; do NOT gate on "height changed": a padding-only
     * change leaves measured height identical while the content box shrinks, and a bounds gate
     * would miss it. Only a change in the target height touches layout, so this cannot
     * re-trigger itself. Clamped params persist while hidden. No floor: honouring the quarter
     * in a degenerate window is the point, and the field scrolls internally.
     *
     * @param contentView activity root view; not-yet-laid-out (or non-positive content box) is
     *                    ignored — the next layout pass re-applies the limit.
     */
    public void applyPanelHeightLimitForContentView(@NonNull View contentView) {
        if (mEditText == null || mPanelBaseHeightPx <= 0) return;

        final int sharedHeightPx = contentView.getHeight()
            - contentView.getPaddingTop() - contentView.getPaddingBottom();
        if (sharedHeightPx <= 0) return;

        final int targetHeightPx =
            Math.min(mPanelBaseHeightPx, maxPanelHeightForContentHeight(sharedHeightPx));
        if (targetHeightPx == mAppliedPanelHeightPx) return;

        ViewGroup.LayoutParams lp = mEditText.getLayoutParams();
        if (lp == null) return;
        lp.height = targetHeightPx;
        mEditText.setLayoutParams(lp);
        mAppliedPanelHeightPx = targetHeightPx;
    }

    /** Designed (unclamped) panel height in px, or -1 before {@link #setup}. */
    public int getPanelBaseHeightPx() { return mPanelBaseHeightPx; }

    /** Panel height in px currently applied, or -1 before {@link #setup}. */
    public int getAppliedPanelHeightPx() { return mAppliedPanelHeightPx; }

    public void saveTextInputForCurrentSession() {
        TerminalSession session = mHost.getCurrentSession();
        if (session == null || mEditText == null) return;
        String handle = session.mHandle;
        String text = mEditText.getText() != null ? mEditText.getText().toString() : "";
        mTextInputState.saveInput(handle, text);
        mTextInputState.setCaret(handle, mEditText.getSelectionStart());
        mTextInputState.setVisible(handle, mTextInputContainer != null
                && mTextInputContainer.getVisibility() == View.VISIBLE);
        mTextInputState.setFocusOnInput(handle, mEditText.hasFocus());
    }

    public void restoreTextInputForSession(@Nullable String sessionHandle) {
        if (mEditText == null) return;
        if (sessionHandle == null) {
            mEditText.setText("");
            return;
        }
        String text = mTextInputState.getInputText(sessionHandle);
        mEditText.setText(text != null ? text : "");
        int caret = mTextInputState.getCaret(sessionHandle);
        if (caret >= 0) {
            mEditText.setSelection(Math.min(caret, mEditText.length()));
        }
    }

    public void clearTextInputForSession(@NonNull String sessionHandle) {
        mTextInputState.clear(sessionHandle);
    }

    public void setFocusOnInputForCurrentSession(boolean focusOnInput) {
        TerminalSession session = mHost.getCurrentSession();
        if (session == null) return;
        mTextInputState.setFocusOnInput(session, focusOnInput);
        if (focusOnInput && mEditText != null) mEditText.requestFocus();
        if (!focusOnInput && mEditText != null && mEditText.hasFocus()) mEditText.clearFocus();
    }

    public void updateToggleTextInputButtonIcon() {
        if (mToggleTextInputButton == null) return;
        boolean isVisible = mTextInputContainer != null
                && mTextInputContainer.getVisibility() == View.VISIBLE;
        mToggleTextInputButton.setImageResource(isVisible
                ? com.termux.R.drawable.ic_keyboard_hide
                : com.termux.R.drawable.ic_keyboard_show);
    }

    @Nullable public EditText getEditText() { return mEditText; }
    @Nullable public View getTextInputContainer() { return mTextInputContainer; }
    @Nullable public ImageButton getToggleTextInputButton() { return mToggleTextInputButton; }
}
