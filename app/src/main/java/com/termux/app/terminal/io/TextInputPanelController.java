package com.termux.app.terminal.io;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
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
 *
 * <p>Owns the panel's height: it rests at the size of the button that opens it and grows with the
 * text, up to the height the XML declares and the quarter-of-the-window limit — see
 * {@link #updatePanelHeight()}.
 */
public final class TextInputPanelController {

    public interface Host {
        @Nullable TerminalSession getCurrentSession();
    }

    @NonNull private final Host mHost;
    @NonNull private final SessionUiStateStore mTextInputState;

    @Nullable private EditText mEditText;
    @Nullable private View mTextInputContainer;
    @Nullable private ImageButton mToggleTextInputButton;

    /**
     * The input panel may never occupy more than a {@code 1/MAX_HEIGHT_FRACTION} share of the
     * height of the area it shares with the terminal — a quarter. At the height the XML declares
     * the limit never engages in a phone-sized window, but the same fixed height would swallow
     * most of a short window (floating bubble, split-screen, landscape with keyboard up) —
     * exactly where the user loses the output they were typing against.
     */
    private static final int MAX_HEIGHT_FRACTION = 4;

    /**
     * Ceiling for the panel height in px, as declared in XML. Captured once in {@link #setup},
     * so the clamp is always expressed against the designed height instead of against whatever the
     * panel happens to measure right now (which would make the limit ratchet: each shrink would
     * become the new "original", and the panel could never grow back when the window does).
     *
     * <p>Measured on the FIELD, not the container: the container is {@code wrap_content} with the
     * field as its only child, so the field height IS the visible panel height (the plain
     * {@code <shape>} background draws inside bounds), and it is the single value the XML
     * declares — capping the container would clip the field inside a correct-looking frame.
     * Container vertical margins are excluded: they are spacing to neighbours, not panel size.
     */
    private int mPanelBaseHeightPx = -1;

    /**
     * Floor for the panel height in px: the height of the toggle button, which the panel takes the
     * place of. A one-line draft fits in a button-sized strip, and the panel then grows upwards
     * with the text — so an empty panel is not a tall empty box. Zero when the panel does NOT take
     * the button's place; see {@link #mOverTerminal}.
     */
    private int mPanelRestHeightPx = -1;

    /** The button's own height in px, kept across arrangement switches as the overlay's floor. */
    private int mToggleRestHeightPx = -1;

    /**
     * The ceiling actually in force: {@link #mPanelBaseHeightPx} narrowed to the quarter of the
     * window ({@link #mHeightCapPx}). {@code -1} until the first layout pass supplies a usable
     * content box, during which the panel keeps whatever height the XML gave it.
     */
    private int mHeightCapPx = -1;

    /** Panel height in px currently written into the layout params. -1 until {@link #setup}. */
    private int mAppliedPanelHeightPx = -1;

    /**
     * Whether the panel floats over the terminal, and so is sized from its text. In the extra keys'
     * place it keeps the fixed height the XML declares and merely scrolls, which is what it always
     * did; the text-driven height belongs to the overlay only.
     */
    private boolean mOverTerminal = false;

    public TextInputPanelController(@NonNull Context context,
                                    @NonNull Host host,
                                    @NonNull SessionUiStateStore textInputState) {
        mHost = host;
        mTextInputState = textInputState;
    }

    public void setup(@Nullable Bundle savedInstanceState, @NonNull View rootView) {
        mEditText = rootView.findViewById(R.id.terminal_toolbar_text_input);
        mTextInputContainer = rootView.findViewById(R.id.terminal_toolbar_text_input_container);
        mToggleTextInputButton = rootView.findViewById(R.id.toggle_text_input_button);
        capturePanelHeights();

        if (mEditText != null) {
            // The panel is sized FROM the text, so every edit has to be able to resize it —
            // including the ones made for it: autocorrect, the clipboard paste, a suggestion
            // accepted by the autocomplete popup, and a draft restored for another tab.
            mEditText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override public void afterTextChanged(Editable s) { updatePanelHeight(); }
            });
        }
    }

    /**
     * Record the designed ceiling and the resting floor, so {@link #updatePanelHeight()} has both
     * ends of the range as constants. See the two fields for why each is read off a particular
     * view: the ceiling off the field (the one value the XML declares for the panel's size), the
     * floor off the button (whose place the panel takes).
     */
    private void capturePanelHeights() {
        if (mPanelBaseHeightPx > 0) return;
        if (mEditText != null) {
            ViewGroup.LayoutParams lp = mEditText.getLayoutParams();
            if (lp != null && lp.height > 0) {
                mPanelBaseHeightPx = lp.height;
                mAppliedPanelHeightPx = lp.height;
            }
        }
        if (mToggleTextInputButton != null) {
            ViewGroup.LayoutParams lp = mToggleTextInputButton.getLayoutParams();
            if (lp != null && lp.height > 0) {
                mToggleRestHeightPx = lp.height;
                if (mOverTerminal) mPanelRestHeightPx = lp.height;
            }
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

        final int capPx = Math.min(mPanelBaseHeightPx, maxPanelHeightForContentHeight(sharedHeightPx));
        if (capPx == mHeightCapPx) return;

        mHeightCapPx = capPx;
        // The ceiling just moved, so a panel that had grown into the old one has to give the
        // difference back — and a panel that was held back by the old one may now have room.
        updatePanelHeight();
    }

    /**
     * Switch the panel between the two arrangements' height rules and re-apply them: driven by the
     * text when it floats over the terminal, the fixed XML height when it takes the extra keys'
     * place. Safe to call while the panel is on screen — the arrangement is meant to be switchable
     * with it open.
     */
    public void setOverTerminal(boolean overTerminal) {
        if (mOverTerminal == overTerminal) return;
        mOverTerminal = overTerminal;
        // The resting floor is the toggle button's height, which only means anything for the
        // overlay; dropping it here is what returns the panel to the full height it used to have.
        mPanelRestHeightPx = overTerminal ? mToggleRestHeightPx : -1;
        mAppliedPanelHeightPx = -1;
        updatePanelHeight();
    }

    /**
     * Size the panel to its content: it rests at the height of the button that opens it, grows
     * with the text, and is held to the ceiling {@link #applyPanelHeightLimitForContentView}
     * computed — past which the field scrolls internally, as it always has.
     *
     * <p>The height wanted comes from the text {@link Layout}, not from the field: the field is
     * being resized here, so asking it would just return the current answer. The layout reports
     * the height of the whole text block, which is what grows.
     *
     * <p>In the extra keys' place there is no floor and nothing to grow from: the panel is the
     * fixed height the XML declares, capped, and the field scrolls inside it.
     *
     * <p>The floor wins over the ceiling. A window so short that its quarter is below the resting
     * height is one where a smaller-than-the-button panel would be the only thing on screen; the
     * quarter exists to stop the panel eating the terminal, not to shrink the panel out of use.
     */
    public void updatePanelHeight() {
        final EditText editText = mEditText;
        if (editText == null || mPanelBaseHeightPx <= 0) return;

        final int ceilingPx = mHeightCapPx > 0 ? Math.min(mPanelBaseHeightPx, mHeightCapPx)
            : mPanelBaseHeightPx;
        int targetPx;
        if (!mOverTerminal) {
            targetPx = ceilingPx;
        } else {
            final Layout textLayout = editText.getLayout();
            final int wantedPx = textLayout == null ? 0 : textLayout.getHeight();
            final int floorPx = mPanelRestHeightPx > 0 ? mPanelRestHeightPx : mPanelBaseHeightPx;
            targetPx = Math.max(floorPx, Math.min(ceilingPx, wantedPx));
        }
        if (targetPx == mAppliedPanelHeightPx) return;

        ViewGroup.LayoutParams lp = editText.getLayoutParams();
        if (lp == null) return;
        lp.height = targetPx;
        editText.setLayoutParams(lp);
        mAppliedPanelHeightPx = targetPx;
    }
}
