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
     * height of the area it shares with the terminal — a quarter. The panel is a convenience
     * layered over the terminal, never a replacement for it: at the height the XML declares it is
     * already small enough that this limit never engages in a phone-sized window, but the same
     * fixed height would swallow most of a short window (the floating bubble, split-screen,
     * landscape with the keyboard up) — and that is exactly where the user loses the output they
     * were typing against.
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

    /** Cache view references. */
    public void setup(@Nullable Bundle savedInstanceState, @NonNull View rootView) {
        mEditText = rootView.findViewById(R.id.terminal_toolbar_text_input);
        mTextInputContainer = rootView.findViewById(R.id.terminal_toolbar_text_input_container);
        mToggleTextInputButton = rootView.findViewById(R.id.toggle_text_input_button);
        capturePanelBaseHeight();
    }

    /**
     * Record the designed panel height so {@link #applyPanelHeightLimitForContentView} has a
     * constant to clamp. The height lives on the FIELD, not on the container: the container is
     * {@code wrap_content} with the field as its only child, so the field's height IS the height
     * of the visible panel (background, stroke and all — the background is a plain
     * {@code <shape>}, which draws inside the bounds without adding insets), and it is the single
     * value the XML declares. Capping the container instead would leave the field overflowing a
     * shorter container, i.e. a clipped field inside a correct-looking frame.
     * <p>
     * The container's vertical margins are deliberately NOT part of the measurement. They are
     * spacing between the panel and its neighbours (and the top margin is set from the unrelated
     * tab-panel-position preference), not panel: folding them in would make the panel's own size
     * depend on where the tab strip happens to sit.
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
     * Cap the panel at a quarter of the height of the area it shares with the terminal, i.e. never
     * let it be taller than {@code min(designed height, sharedHeight / 4)}.
     * <p>
     * <b>Which height.</b> {@code contentView} is the activity's root view and the measurement
     * used is its CONTENT BOX — measured height minus vertical padding — not its raw height. That
     * padding is where this window's system-bar and keyboard insets land (see
     * {@link com.termux.app.terminal.TermuxActivityRootView}), so the content box is exactly the
     * box the toolbar and the terminal divide between them, and the rule is therefore "the panel
     * never takes more than a quarter of what it competes for". The raw height is not that box: it
     * still counts the strip the status bar covers, and — when the keyboard covers the window
     * without the platform resizing it (a floating/undocked keyboard, a ROM that ignores
     * ADJUST_RESIZE, an IME reporting less than it occupies) — the strip the keyboard covers.
     * Capping against the raw height there would let the panel eat everything the user can
     * actually see. The full screen rect is wrong for the same reason and one more: in the bubble
     * it is far taller than the window, so it would cap nothing at all where the cap matters most.
     * <p>
     * <b>When.</b> Call this on every layout of that root view. The method decides for itself
     * whether anything has to change, so callers must NOT put their own "only if the height
     * changed" gate in front of it: a padding-only change (the keyboard top-up above) leaves the
     * root view's measured height identical while the content box shrinks, and a bounds-based gate
     * would miss precisely that case. Only a change in the target height touches the layout, so
     * the layout pass this method can trigger cannot feed itself.
     * <p>
     * The clamped layout params persist while the panel is hidden, so the panel is already the
     * right size when it is next shown — no visibility callback is needed here.
     * <p>
     * No floor is applied: a window short enough to push the cap below one line of text is a
     * degenerate window, and honouring the quarter there is the whole point of the rule. The field
     * scrolls internally (fixed height, {@code scrollbars="vertical"}), so a short panel stays
     * usable instead of clipping its text.
     *
     * @param contentView the root view of the activity whose content box is the shared area; a
     *                    view that is not laid out yet (or one with no positive content box) is
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

    /** Record the current input state for the current session. */
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

    /** Restore text + caret for session handle into cached EditText. */
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

    /** Remove saved state for a closed session. */
    public void clearTextInputForSession(@NonNull String sessionHandle) {
        mTextInputState.clear(sessionHandle);
    }

    /** Mark whether the text input panel has focus for the current session. */
    public void setFocusOnInputForCurrentSession(boolean focusOnInput) {
        TerminalSession session = mHost.getCurrentSession();
        if (session == null) return;
        mTextInputState.setFocusOnInput(session, focusOnInput);
        if (focusOnInput && mEditText != null) mEditText.requestFocus();
        if (!focusOnInput && mEditText != null && mEditText.hasFocus()) mEditText.clearFocus();
    }

    /** Update the toggle button icon to reflect the current visible state. */
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
