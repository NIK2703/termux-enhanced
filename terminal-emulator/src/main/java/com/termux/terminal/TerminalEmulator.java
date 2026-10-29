package com.termux.terminal;

import android.graphics.Bitmap;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Java facade over the native foot terminal core.
 *
 * <p>The VT parser, screen + transcript grid, alternate buffer, palette mirror, selection
 * extraction and render planning live in {@code libtermux_terminal.so}. This class keeps the
 * API the session, view and key handling code was written against and translates native events
 * back into {@link TerminalOutput} callbacks.</p>
 */
public final class TerminalEmulator {

    public static final int MOUSE_LEFT_BUTTON = 0;
    public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
    public static final int MOUSE_WHEELUP_BUTTON = 64;
    public static final int MOUSE_WHEELDOWN_BUTTON = 65;

    public static final int UNICODE_REPLACEMENT_CHAR = 0xFFFD;

    private static final int DECSET_BIT_APPLICATION_CURSOR_KEYS = 1;
    private static final int DECSET_BIT_REVERSE_VIDEO = 1 << 1;
    private static final int DECSET_BIT_ORIGIN_MODE = 1 << 2;
    private static final int DECSET_BIT_AUTOWRAP = 1 << 3;
    private static final int DECSET_BIT_CURSOR_ENABLED = 1 << 4;
    private static final int DECSET_BIT_APPLICATION_KEYPAD = 1 << 5;
    private static final int DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 << 6;
    private static final int DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 << 7;
    private static final int DECSET_BIT_SEND_FOCUS_EVENTS = 1 << 8;
    private static final int DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 << 9;
    private static final int DECSET_BIT_BRACKETED_PASTE_MODE = 1 << 10;

    private static final String FOCUS_IN = "\033[I";
    private static final String FOCUS_OUT = "\033[O";

    public static final int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    public static final int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;

    public static final int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    public static final int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    public static final int TERMINAL_CURSOR_STYLE_BAR = 2;
    public static final int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;
    public static final Integer[] TERMINAL_CURSOR_STYLES_LIST = new Integer[]{
        TERMINAL_CURSOR_STYLE_BLOCK, TERMINAL_CURSOR_STYLE_UNDERLINE, TERMINAL_CURSOR_STYLE_BAR};

    public int mRows, mColumns;

    private int mCellWidthPixels, mCellHeightPixels;
    private final int mTranscriptRows;
    private final long mNativeHandle;
    private final TerminalOutput mSession;
    private final TerminalBuffer mScreen;
    /** Size-only facade for the alternate screen; the content lives in the same native grid. */
    final TerminalBuffer mAltBuffer;
    public final TerminalColors mColors = new TerminalColors();

    private TerminalSessionClient mClient;
    private String mTitle;
    private int mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE;
    private boolean mCursorBlinkingEnabled;
    private boolean mCursorBlinkState;
    private boolean mTerminalFocused;
    private boolean mAutoScrollDisabled;
    private int mModeBits;
    private int mCursorRow;
    private int mCursorCol;
    /** Absolute scroll count last reported by nativeAppend(). */
    private int mNativeScrollCounter;
    /** Value of mNativeScrollCounter at the last clearScrollCounter(). */
    private int mScrollBase;
    /** Native viewport top after the last append (external row coordinates). */
    private int mNativeViewTop;
    /** True when the last append scrolled only part of the screen. */
    private boolean mLastAppendPartialScroll;
    private int mSyncedColorVersion = -1;
    private int mSyncedDefaultVersion = -1;
    /** Reused packed append state; sized for the header plus one entry per visible row. */
    private int[] mAppendState;
    /** Reused row list for Android overlay damage (scrollbar band). */
    private int[] mOverlayDamageRows = new int[32];

    private static final Pattern PASTE_STRIP_PATTERN = Pattern.compile("(\u001B|[\u0080-\u009F])");
    private static final Pattern PASTE_NEWLINE_PATTERN = Pattern.compile("\r?\n");

    public TerminalEmulator(TerminalOutput session, int columns, int rows, int cellWidthPixels,
                            int cellHeightPixels, Integer transcriptRows, TerminalSessionClient client) {
        mSession = session;
        mClient = client;
        mRows = rows;
        mColumns = columns;
        mAppendState = new int[TerminalNative.APPEND_STATE_HEADER_SIZE + rows];
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        mTranscriptRows = getTerminalTranscriptRows(transcriptRows);
        mNativeHandle = TerminalNative.nativeCreate(columns, rows, mTranscriptRows,
            cellWidthPixels, cellHeightPixels, mColors.mCurrentColors);
        mScreen = new TerminalBuffer(mNativeHandle, columns, mTranscriptRows, rows);
        mAltBuffer = new TerminalBuffer(mNativeHandle, columns, rows, rows);
        mCursorRow = TerminalNative.nativeCursorRow(mNativeHandle);
        mCursorCol = TerminalNative.nativeCursorCol(mNativeHandle);
        mNativeScrollCounter = (int) TerminalNative.nativeAbsoluteScrollCount(mNativeHandle);
        mScrollBase = mNativeScrollCounter;
        mNativeViewTop = TerminalNative.nativeViewTop(mNativeHandle);
        setCursorStyle();
        mModeBits = TerminalNative.nativeModeBits(mNativeHandle);
        mCursorBlinkState = true;
    }

    private static int getTerminalTranscriptRows(Integer transcriptRows) {
        if (transcriptRows == null || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN
            || transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX) {
            return DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        return transcriptRows;
    }

    public void setTerminalFocused(boolean focused) {
        if (mTerminalFocused == focused) return;
        mTerminalFocused = focused;
        if ((mModeBits & DECSET_BIT_SEND_FOCUS_EVENTS) == 0) return;
        mSession.write(focused ? FOCUS_IN : FOCUS_OUT);
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        setCursorStyle();
        setCursorBlinkState(true);
    }

    public TerminalBuffer getScreen() {
        return mScreen;
    }

    public boolean isAlternateBufferActive() {
        return TerminalNative.nativeIsAlternate(mNativeHandle);
    }

    public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
        if (column < 1) column = 1;
        if (column > mColumns) column = mColumns;
        if (row < 1) row = 1;
        if (row > mRows) row = mRows;

        if (mouseButton == MOUSE_LEFT_BUTTON_MOVED
            && (mModeBits & DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) == 0) {
            return;
        }
        if ((mModeBits & DECSET_BIT_MOUSE_PROTOCOL_SGR) != 0) {
            mSession.write(String.format("\033[<%d;%d;%d" + (pressed ? 'M' : 'm'), mouseButton, column, row));
        } else {
            mouseButton = pressed ? mouseButton : 3;
            boolean outOfBounds = column > 255 - 32 || row > 255 - 32;
            if (!outOfBounds) {
                byte[] data = {'\033', '[', 'M', (byte) (32 + mouseButton), (byte) (32 + column), (byte) (32 + row)};
                mSession.write(data, 0, data.length);
            }
        }
    }

    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        final boolean gridChanged = mRows != rows || mColumns != columns;
        mCellWidthPixels = cellWidthPixels;
        mCellHeightPixels = cellHeightPixels;
        if (columns < 2 || rows < 2) {
            throw new IllegalArgumentException("rows=" + rows + ", columns=" + columns);
        }
        syncPalette();
        TerminalNative.nativeResize(mNativeHandle, columns, rows, cellWidthPixels, cellHeightPixels);
        mNativeViewTop = TerminalNative.nativeViewTop(mNativeHandle);
        if (!gridChanged) return;
        mColumns = columns;
        mRows = rows;
        if (mAppendState.length < TerminalNative.APPEND_STATE_HEADER_SIZE + rows) {
            mAppendState = new int[TerminalNative.APPEND_STATE_HEADER_SIZE + rows];
        }
        mScreen.updateSize(columns, mTranscriptRows, rows);
        mAltBuffer.updateSize(columns, rows, rows);
        mModeBits = TerminalNative.nativeModeBits(mNativeHandle);
        mCursorRow = TerminalNative.nativeCursorRow(mNativeHandle);
        mCursorCol = TerminalNative.nativeCursorCol(mNativeHandle);
        mNativeScrollCounter = (int) TerminalNative.nativeAbsoluteScrollCount(mNativeHandle);
    }

    public boolean hasSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        return mColumns == columns && mRows == rows
            && mCellWidthPixels == cellWidthPixels && mCellHeightPixels == cellHeightPixels;
    }

    public int getCursorRow() {
        return mCursorRow;
    }

    public int getCursorCol() {
        return mCursorCol;
    }

    /** Get the terminal cursor style, one of {@link #TERMINAL_CURSOR_STYLES_LIST}. */
    public int getCursorStyle() {
        return mCursorStyle;
    }

    /** Apply the cursor style configured by the app, replacing any DECSCUSR override. */
    public void setCursorStyle() {
        int cursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE;
        if (mClient != null) {
            Integer configured = mClient.getTerminalCursorStyle();
            if (configured != null) {
                for (int style : TERMINAL_CURSOR_STYLES_LIST) {
                    if (style == configured) {
                        cursorStyle = style;
                        break;
                    }
                }
            }
        }
        mCursorStyle = cursorStyle;
        TerminalNative.nativeSetCursorStyle(mNativeHandle, cursorStyle);
    }

    public boolean isReverseVideo() {
        return (mModeBits & DECSET_BIT_REVERSE_VIDEO) != 0;
    }

    public boolean isCursorEnabled() {
        return (mModeBits & DECSET_BIT_CURSOR_ENABLED) != 0;
    }

    public boolean shouldCursorBeVisible() {
        if (!isCursorEnabled()) return false;
        return !mCursorBlinkingEnabled || mCursorBlinkState;
    }

    public void setCursorBlinkingEnabled(boolean cursorBlinkingEnabled) {
        mCursorBlinkingEnabled = cursorBlinkingEnabled;
    }

    public boolean isCursorBlinkingEnabled() {
        return mCursorBlinkingEnabled;
    }

    public void setCursorBlinkState(boolean cursorBlinkState) {
        mCursorBlinkState = cursorBlinkState;
    }

    public boolean isKeypadApplicationMode() {
        return (mModeBits & DECSET_BIT_APPLICATION_KEYPAD) != 0;
    }

    public boolean isCursorKeysApplicationMode() {
        return (mModeBits & DECSET_BIT_APPLICATION_CURSOR_KEYS) != 0;
    }

    public boolean isMouseTrackingActive() {
        return (mModeBits & (DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
            | DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) != 0;
    }

    /** Feed raw PTY bytes into the native VT parser. */
    public void append(byte[] buffer, int length) {
        if (length <= 0) return;
        syncPalette();
        int oldCursorStyle = mCursorStyle;
        int oldModeBits = mModeBits;

        int[] state;
        int stateLength;
        while (true) {
            state = mAppendState;
            stateLength = TerminalNative.nativeAppendInto(mNativeHandle, buffer, length, state);
            if (stateLength >= 0) break;
            // The native screen grew before this append; size for the worst case and retry.
            mAppendState = new int[-stateLength];
        }
        int newCursorStyle;
        if (stateLength >= TerminalNative.APPEND_STATE_HEADER_SIZE) {
            // The header carries everything the view needs for this update, so no
            // follow-up JNI queries are required for the append itself.
            final int anchorBase = state[5];
            final int historySize = state[6];
            mScreen.invalidateHistoryCache(anchorBase);
            mAltBuffer.invalidateHistoryCache(anchorBase);
            mScreen.setCachedHistorySize(historySize);
            mAltBuffer.setCachedHistorySize(historySize);

            int dirtyCount = state[0];
            if (dirtyCount < 0) dirtyCount = 0;
            final int available = stateLength - TerminalNative.APPEND_STATE_HEADER_SIZE;
            if (dirtyCount > available) {
                // A truncated payload must not leave undrawn rows behind.
                mScreen.markAllDirty();
                dirtyCount = available;
            }
            final int flags = state[7];
            if ((flags & TerminalNative.APPEND_FLAG_FULL_DAMAGE) != 0) {
                // The native model cannot retire this damage until every visible
                // row has reached a full-clip frame. A partial row set is not
                // enough, so replace the mirror with the same full-damage state.
                mScreen.markAllDirty();
            } else {
                for (int i = 0; i < dirtyCount; i++) {
                    mScreen.markRowDirty(state[TerminalNative.APPEND_STATE_HEADER_SIZE + i]);
                }
            }

            mModeBits = state[1];
            newCursorStyle = state[2];
            mCursorRow = state[3];
            mCursorCol = state[4];
            mNativeScrollCounter = state[5];
            mNativeViewTop = state[8];
            mLastAppendPartialScroll =
                (flags & TerminalNative.APPEND_FLAG_PARTIAL_SCROLL) != 0;
        } else {
            // Native append failed (or an older library is installed): the dirty set is
            // unknown, so repaint everything and refresh the caches through the legacy calls.
            mScreen.invalidateHistoryCache();
            mAltBuffer.invalidateHistoryCache();
            mScreen.markAllDirty();
            mModeBits = TerminalNative.nativeModeBits(mNativeHandle);
            newCursorStyle = TerminalNative.nativeCursorStyle(mNativeHandle);
            mCursorRow = TerminalNative.nativeCursorRow(mNativeHandle);
            mCursorCol = TerminalNative.nativeCursorCol(mNativeHandle);
            mNativeScrollCounter = (int) TerminalNative.nativeAbsoluteScrollCount(mNativeHandle);
            mNativeViewTop = TerminalNative.nativeViewTop(mNativeHandle);
            mLastAppendPartialScroll = false;
        }

        handleModeChanges(oldModeBits, mModeBits);
        if (newCursorStyle != mCursorStyle) {
            mCursorStyle = newCursorStyle;
            // A shape change repaints no cell, so dirty-row tracking needs a nudge.
            mScreen.markRowDirty(mCursorRow);
        } else if (oldCursorStyle != mCursorStyle) {
            mScreen.markRowDirty(mCursorRow);
        }

        drainEvents();
    }

    private void handleModeChanges(int oldModes, int newModes) {
        int changed = oldModes ^ newModes;
        if ((changed & DECSET_BIT_CURSOR_ENABLED) != 0 && mClient != null) {
            mClient.onTerminalCursorStateChange((newModes & DECSET_BIT_CURSOR_ENABLED) != 0);
        }
        if ((changed & DECSET_BIT_SEND_FOCUS_EVENTS) != 0
            && (newModes & DECSET_BIT_SEND_FOCUS_EVENTS) != 0) {
            mSession.write(mTerminalFocused ? FOCUS_IN : FOCUS_OUT);
        }
    }

    private void drainEvents() {
        String events = TerminalNative.nativeDrainEvents(mNativeHandle);
        if (events == null || events.isEmpty()) return;
        for (String record : events.split("\n", -1)) {
            if (record.isEmpty()) continue;
            dispatchEvent(record);
        }
    }

    private void dispatchEvent(String record) {
        String[] fields = record.split("\t", -1);
        switch (fields[0]) {
            case "title":
                if (fields.length > 1) setTitle(decodeBase64(fields[1]));
                break;
            case "reset_title":
                setTitle(null);
                break;
            case "bell":
                mSession.onBell();
                break;
            case "clipboard_store":
                if (fields.length > 1) mSession.onCopyTextToClipboard(decodeBase64(fields[1]));
                break;
            case "clipboard_load":
                // Termux only supports copying through OSC 52, never reading the clipboard.
                break;
            case "write":
                if (fields.length > 1) mSession.write(decodeBase64(fields[1]));
                break;
            case "colors":
                refreshPaletteFromNative();
                mSession.onColorsChanged();
                break;
            case "notification":
                dispatchNotification(fields);
                break;
            case "cursor_blink":
                // The app owns blinking; DECSCUSR blinking bits are not surfaced by Termux.
                break;
            default:
                break;
        }
    }

    private void dispatchNotification(String[] fields) {
        if (fields.length < 8) return;
        String id = decodeBase64(fields[1]);
        String title = decodeBase64(fields[2]);
        String body = decodeBase64(fields[3]);
        int occasions;
        int silent;
        int report;
        int focus;
        try {
            occasions = Integer.parseInt(fields[4]);
            silent = Integer.parseInt(fields[5]);
            report = Integer.parseInt(fields[6]);
            focus = Integer.parseInt(fields[7]);
        } catch (NumberFormatException e) {
            return;
        }
        TerminalNotification notification = new TerminalNotification(
            id,
            title.isEmpty() ? null : title,
            body.isEmpty() ? null : body,
            occasions,
            silent != 0,
            report != 0,
            focus != 0);
        mSession.onNotification(notification);
    }

    private void setTitle(String newTitle) {
        String oldTitle = mTitle;
        mTitle = newTitle;
        if (!Objects.equals(oldTitle, newTitle)) {
            mSession.titleChanged(oldTitle, newTitle);
        }
    }

    public String getTitle() {
        return mTitle;
    }

    /**
     * Keep the native colour mirror in sync with Java-owned {@link TerminalColors}. Theme
     * changes replace the array contents without notifying the emulator, so this runs before
     * output and rendering.
     */
    private void syncPalette() {
        final int colorVersion = mColors.getVersion();
        final int defaultVersion = TerminalColors.COLOR_SCHEME.getVersion();
        if (colorVersion == mSyncedColorVersion && defaultVersion == mSyncedDefaultVersion) return;
        mSyncedColorVersion = colorVersion;
        mSyncedDefaultVersion = defaultVersion;
        boolean changed = TerminalNative.nativeSetPalette(mNativeHandle,
            mColors.mCurrentColors, TerminalColors.COLOR_SCHEME.mDefaultColors);
        if (changed) mScreen.markAllDirty();
    }

    private void refreshPaletteFromNative() {
        int[] palette = TerminalNative.nativePalette(mNativeHandle);
        if (palette != null) {
            System.arraycopy(palette, 0, mColors.mCurrentColors, 0,
                Math.min(palette.length, mColors.mCurrentColors.length));
        }
    }

    /**
     * Synchronize the native viewport with the view's {@code mTopRow}. Called
     * immediately before drawing and after output compensation; the render call
     * cannot do this itself because a partial frame starts at an arbitrary row
     * inside the viewport.
     */
    public void setViewport(int topRow) {
        mNativeViewTop = TerminalNative.nativeSetView(mNativeHandle, topRow);
    }

    /** Retire native damage for visible rows that reached the Canvas. */
    public void commitRenderRows(int[] rows, int count) {
        if (rows == null || count <= 0) return;
        TerminalNative.nativeCommitRows(mNativeHandle, rows, count);
    }

    /**
     * Mark a contiguous external-row range dirty for an Android overlay (the scrollbar band).
     * The native dirty bits stay authoritative; Java only mirrors them for invalidation.
     */
    public void damageOverlayRows(int first, int last) {
        if (first > last) return;
        final int count = last - first + 1;
        if (mOverlayDamageRows.length < count) mOverlayDamageRows = new int[count];
        for (int i = 0; i < count; i++) mOverlayDamageRows[i] = first + i;
        TerminalNative.nativeDamageRows(mNativeHandle, mOverlayDamageRows, count);
    }

    /**
     * Replay a native scroll on the renderer's retained ARGB surface before it
     * draws the newly exposed rows. The native core applies foot's own scroll
     * damage mover to the pixel band. Returns false when the surface could not
     * be moved and the caller must repaint every row in the frame.
     */
    public boolean scrollSurfaceRows(Bitmap bitmap, int start, int end,
                                     int lines, boolean reverse) {
        return TerminalNative.nativeScrollSurfaceRows(
            mNativeHandle, bitmap, start, end, lines, reverse);
    }

    /** Build the packed render command list for one frame. The ABI is documented by
     * {@code TerminalRenderer} and implemented in the selected native core. */
    public int[] getRenderCommands(int topRow, int selectionY1, int selectionY2,
                                   int selectionX1, int selectionX2, int backgroundAlpha,
                                   float fontWidth, int fontLineSpacing, int fontAscent,
                                   int columns, int rows) {
        syncPalette();
        return TerminalNative.nativeRender(mNativeHandle, topRow, selectionY1, selectionY2,
            selectionX1, selectionX2, shouldCursorBeVisible(), mCursorStyle, backgroundAlpha,
            fontWidth, fontLineSpacing, fontAscent, columns, rows);
    }

    /**
     * Fill a caller-owned buffer with the packed render payload. Returns the payload length
     * or {@code -required} when the buffer is too small.
     */
    public int fillRenderCommands(int[] out, int topRow, int selectionY1, int selectionY2,
                                  int selectionX1, int selectionX2, int backgroundAlpha,
                                  float fontWidth, int fontLineSpacing, int fontAscent,
                                  int columns, int rows) {
        syncPalette();
        return TerminalNative.nativeRenderInto(mNativeHandle, topRow, selectionY1, selectionY2,
            selectionX1, selectionX2, shouldCursorBeVisible(), mCursorStyle, backgroundAlpha,
            fontWidth, fontLineSpacing, fontAscent, columns, rows, out);
    }

    /** UTF-16 payload referenced by TEXT commands of the most recent render. */
    public int[] getRenderText() {
        return TerminalNative.nativeRenderText(mNativeHandle);
    }

    /** Fetch an ARGB32 sixel surface by the id/revision in an OP_IMAGE command. */
    public int[] getSixelPixels(long imageId, int revision, int width, int height) {
        return TerminalNative.nativeSixelPixels(mNativeHandle, imageId, revision, width, height);
    }

    public int getScrollCounter() {
        final int delta = mNativeScrollCounter - mScrollBase;
        return delta > 0 ? delta : 0;
    }

    /**
     * Native viewport top after the last append. The view uses the difference from its
     * previous {@code mTopRow} to compensate follow-text exactly, including chunks that
     * scroll more than half of the ring buffer (where an offset delta is ambiguous).
     */
    public int getNativeViewTop() {
        return mNativeViewTop;
    }

    public void clearScrollCounter() {
        // The absolute counter is carried by the append state, so consuming the delta is
        // a plain Java field update instead of another JNI transition.
        mScrollBase = mNativeScrollCounter;
    }

    /**
     * Legacy "partial scroll" marker from the most recent append. The view no longer
     * promotes this to a whole-screen repaint: native row damage now also covers slots
     * moved by a scroll region while the viewport is scrolled up. Kept for compatibility
     * with existing terminal-emulator callers.
     */
    public boolean consumePartialScrollFlag() {
        final boolean value = mLastAppendPartialScroll;
        mLastAppendPartialScroll = false;
        return value;
    }

    public boolean isAutoScrollDisabled() {
        return mAutoScrollDisabled;
    }

    public void setAutoScrollDisabled(boolean disabled) {
        mAutoScrollDisabled = disabled;
    }

    public void toggleAutoScrollDisabled() {
        mAutoScrollDisabled = !mAutoScrollDisabled;
    }

    /** Reset terminal state so the user can interact with it regardless of present state. */
    public void reset() {
        TerminalNative.nativeReset(mNativeHandle);
        mScreen.invalidateHistoryCache();
        mAltBuffer.invalidateHistoryCache();
        mScreen.markAllDirty();
        mAltBuffer.markAllDirty();
        refreshPaletteFromNative();
        setCursorStyle();
        mModeBits = TerminalNative.nativeModeBits(mNativeHandle);
        mCursorRow = TerminalNative.nativeCursorRow(mNativeHandle);
        mCursorCol = TerminalNative.nativeCursorCol(mNativeHandle);
        mNativeScrollCounter = (int) TerminalNative.nativeAbsoluteScrollCount(mNativeHandle);
        // headless_reset() moves the native baseline to the current absolute count.
        mScrollBase = mNativeScrollCounter;
        mLastAppendPartialScroll = false;
        mCursorBlinkState = true;
        drainEvents();
        mSession.onColorsChanged();
    }

    public String getSelectedText(int x1, int y1, int x2, int y2) {
        return mScreen.getSelectedText(x1, y1, x2, y2);
    }

    /** If DECSET 2004 is set, prefix paste with "\033[200~" and suffix with "\033[201~". */
    public void paste(String text) {
        text = PASTE_STRIP_PATTERN.matcher(text).replaceAll("");
        text = PASTE_NEWLINE_PATTERN.matcher(text).replaceAll("\r");
        boolean bracketed = (mModeBits & DECSET_BIT_BRACKETED_PASTE_MODE) != 0;
        if (bracketed) mSession.write("\033[200~");
        mSession.write(text);
        if (bracketed) mSession.write("\033[201~");
    }

    private static String decodeBase64(String value) {
        if (value.isEmpty()) return "";
        return new String(decodeBase64Bytes(value), StandardCharsets.UTF_8);
    }

    private static byte[] decodeBase64Bytes(String value) {
        int length = value.length();
        int padding = 0;
        while (length > 0 && value.charAt(length - 1) == '=') {
            padding++;
            length--;
        }
        int outLength = length * 6 / 8;
        byte[] out = new byte[outLength];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < length; i++) {
            int digit = base64Digit(value.charAt(i));
            if (digit < 0) continue;
            buffer = (buffer << 6) | digit;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (index < out.length) out[index++] = (byte) (buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        if (index == out.length) return out;
        byte[] trimmed = new byte[index];
        System.arraycopy(out, 0, trimmed, 0, index);
        return trimmed;
    }

    private static int base64Digit(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    @Override
    public String toString() {
        return "TerminalEmulator[size=" + mColumns + "x" + mRows + "]";
    }
}
