package com.termux.view;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalSession;

/**
 * The interface for communication between {@link TerminalView} and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * {@link View#setOnKeyListener(View.OnKeyListener)}, etc. It must be set for the
 * {@link TerminalView} through {@link TerminalView#setTerminalViewClient(TerminalViewClient)}.
 */
public interface TerminalViewClient {

    /**
     * Callback function on scale events according to {@link ScaleGestureDetector#getScaleFactor()}.
     */
    float onScale(float scale);



    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    void onSingleTapUp(MotionEvent e);

    boolean shouldBackButtonBeMappedToEscape();

    boolean shouldEnforceCharBasedInput();

    boolean shouldUseCtrlSpaceWorkaround();

    boolean isTerminalViewSelected();



    void copyModeChanged(boolean copyMode);



    boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session);

    boolean onKeyUp(int keyCode, KeyEvent e);

    boolean onLongPress(MotionEvent event);



    boolean readControlKey();

    boolean readAltKey();

    boolean readShiftKey();

    boolean readFnKey();



    /**
     * Called for a code point that is about to be sent to the terminal, both for a hardware key
     * press ({@link TerminalView#onKeyDown}) and for text an IME committed.
     *
     * <p>The four modifier flags are resolved by {@link TerminalView} and handed in rather than
     * re-read here. The extra-keys Ctrl/Alt/Shift/Fn buttons are <em>one-shot</em>: reading one
     * clears it. A client that read them again would therefore see a modifier that
     * {@link TerminalView} had already spent, and a client that read them before
     * {@link TerminalView} did would take the modifier away from the key that was about to use it.
     *
     * @return {@code true} if the code point was consumed and must not reach the terminal.
     */
    boolean onCodePoint(int codePoint, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown,
                        TerminalSession session);


    /**
     * Called when {@link TerminalView#mEmulator} is set, on a specific view.
     *
     * <p>The source view is passed in because this client is shared by <em>every</em> page of the
     * session pager: a bind of a background page reaches this callback too, so a handler that means
     * "the emulator that just appeared" must act on {@code terminalView} and never on the activity's
     * currently selected view. Acting on the latter made a background page bind reset the
     * <em>active</em> session's {@code autoScrollDisabled} flag, which the next chunk of output then
     * turned into "scrolled into history, jumped back to the bottom" — see
     * {@code TermuxTerminalViewClient#onEmulatorSet}.
     */
    void onEmulatorSet(@NonNull TerminalView terminalView);


    void logError(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

}
