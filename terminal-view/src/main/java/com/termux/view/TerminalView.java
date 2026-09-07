package com.termux.view;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EdgeEffect;
import android.widget.OverScroller;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;

import com.termux.view.support.ScrollImpulseTracker;
import com.termux.view.textselection.TextSelectionCursorController;

/** View displaying and interacting with a {@link TerminalSession}. */
public final class TerminalView extends View {

    /** Log terminal view key and IME events. */
    private static boolean TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

    /** The currently displayed terminal session, whose emulator is {@link #mEmulator}. */
    public TerminalSession mTermSession;
    /** Our terminal emulator whose session is {@link #mTermSession}. */
    public TerminalEmulator mEmulator;

    public TerminalRenderer mRenderer;

    /**
     * Horizontal pixel offset of the glyph grid, computed so that the leftover space
     * (from glyphs that do not fit the view) is split symmetrically left and right.
     * Applied by {@link TerminalRenderer#render} via canvas translation and mirrored in
     * the pixel-to-cell coordinate mappers.
     */
    float mGridOffsetX = 0f;
    /**
     * Vertical pixel offset of the glyph grid, computed so that the leftover space
     * (from glyphs that do not fit the view) is split symmetrically top and bottom.
     */
    float mGridOffsetY = 0f;

    public TerminalViewClient mClient;

    private TextSelectionCursorController mTextSelectionCursorController;

    private Handler mTerminalCursorBlinkerHandler;
    private TerminalCursorBlinkerRunnable mTerminalCursorBlinkerRunnable;
    private int mTerminalCursorBlinkerRate;
    private boolean mCursorInvisibleIgnoreOnce;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MIN = 100;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MAX = 2000;

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    int mTopRow;

    /** Reusable clip-bounds probe used in {@link #onDraw} to detect the dirty region. */
    private final Rect mClipBounds = new Rect();

    /** Last rendered cursor position (external row / column) for cursor-move dirty expansion. */
    private int mLastCursorRow = Integer.MIN_VALUE;
    private int mLastCursorCol = -1;
    int[] mDefaultSelectors = new int[]{-1,-1,-1,-1};

    float mScaleFactor = 1.f;
    final GestureAndScaleRecognizer mGestureRecognizer;

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private int mMouseScrollStartX = -1, mMouseScrollStartY = -1;
    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private long mMouseStartDownTime = -1;

    final OverScroller mScroller;

    /** What was left in from scrolling movement. Shared px accumulator for finger drag and mouse wheel. */
    float mScrollRemainder;

    // ── Fling physics in pixel space, rendered in whole glyph rows ──
    //
    // The OverScroller axis is PIXELS (0 = bottom of the screen, negative = into history),
    // so the fling obeys the exact system physics (SplineOverScroller, SCROLL_FRICTION,
    // real px/s velocities). Only the output is quantized to rows via pxToRows().
    // mTopRow remains the source of truth for the position; the px axis exists only
    // for the duration of a fling and is derived from mTopRow on start.

    /** Currently posted fling animation frame. */
    private Runnable mFlingRunnable;

    /** MotionEvent copy used during fling (for mouse wheel coordinates). */
    private MotionEvent mFlingEvent;

    /**
     * True when the fling streams RELATIVE rows to the application instead of moving
     * {@code mTopRow}: mouse tracking (wheel events) and the alternate buffer (arrow keys).
     * In both cases the application owns the scroll position, so the fling runs on a delta
     * axis whose absolute position is meaningless — only frame-to-frame deltas are emitted.
     */
    private boolean mFlingDeltaMode;

    /** Last fling position in whole rows (px axis / row height), used in delta mode. */
    private int mFlingLastRowPx;

    /** True when the current fling ends exactly at an edge (then no tail-cut, land precisely). */
    private boolean mFlingEndsAtEdge;

    /** True once the edge glow absorbed the fling impact (prevents repeated absorbs). */
    private boolean mFlingAbsorbedAtEdge;

    /** Raw gesture-space velocity of the current fling (px/s). */
    private float mFlingRawVelocity;

    /** Residual fling velocity captured when user touched during active fling. */
    private float mCapturedFlingVelocityY;

    /** Time when residual velocity was captured. */
    private long mCapturedFlingTime;

    /** Minimum fling velocity from ViewConfiguration. */
    private int mMinFlingVelocity;

    /** Maximum fling velocity (gesture px/s): the system value, no boost factor. */
    private float mMaxFlingVelocity;

    /** Pixels per mouse-wheel axis unit, system-calibrated (ViewConfiguration). */
    private float mWheelScrollFactorPx;

    /**
     * AOSP impulse accumulator for the wheel / precision-trackpad axis. {@code AXIS_VSCROLL} is a
     * <em>differential</em> axis and the system {@code VelocityTracker} tracks those with the
     * IMPULSE strategy (planar X/Y use LSQ2, which the platform GestureDetector already does for
     * us). This is that algorithm, used to turn a burst of wheel ticks into one velocity.
     */
    private final ScrollImpulseTracker mWheelImpulse = new ScrollImpulseTracker();

    /** Fires once a wheel series has settled, to launch a single fling from the summed impulse. */
    private final Runnable mWheelImpulseRunnable = new Runnable() {
        @Override
        public void run() {
            launchWheelImpulseFling();
        }
    };

    /** Optional overscroll edge glow (visual only, content never moves past the edge). */
    private EdgeEffect mEdgeGlowTop, mEdgeGlowBottom;

    /**
     * Carry the residual velocity of a fling interrupted by a touch into the next fling, so
     * re-swiping over still-moving text speeds it up instead of restarting it.
     *
     * <p><b>Not system behaviour.</b> AOSP lists (RecyclerView / AbsListView / ScrollView) call
     * {@code abortAnimation()} on ACTION_DOWN and throw the residual away; VelocityTracker is
     * re-armed from scratch for the new gesture. The "carry-over" people associate with system
     * lists comes for free from VelocityTracker measuring the <em>absolute</em> speed of the
     * finger, so a finger that chases the moving content already reports the content's speed.
     * Adding the scroller's residual on top of that double-counts the impulse and makes a
     * re-flick overshoot.</p>
     *
     * <p>OFF by default, i.e. the terminal matches the system. Flip to true for the old,
     * deliberately non-system flywheel feel.</p>
     */
    private static final boolean FLING_RESIDUAL_ENABLED = false;

    /** Weight of the captured residual, used only when {@link #FLING_RESIDUAL_ENABLED} is on. */
    private static final float FLING_RESIDUAL_FACTOR = 0.6f;
    /** Decay time constant (ms) of the captured residual. */
    private static final float FLING_CAPTURE_TAU_MS = 150f;

    /** Finish a fling early once it slows below this many rows/s: the remaining travel is
     *  on the order of a row, but the quantized steps become individually visible. */
    private static final float FLING_TAIL_ROWS_PER_SEC = 4f;

    /** Max synthetic wheel/key rows emitted per animation frame in delta (mouse tracking) mode. */
    private static final int FLING_DELTA_MAX_ROWS_PER_FRAME = 8;

    /**
     * Half-range of the px axis a delta (app-scrolled) fling runs on. There is no content edge
     * to clamp to, so this is only a guard against overflow, not a scroll limit: the physical
     * fling distance tops out near 30k px (at the maximum fling velocity on a xxxhdpi screen),
     * i.e. ~3% of this value. It must NEVER be lowered into the reach of the physics: hitting
     * the range makes OverScroller clamp the end position *and* shorten the duration to match
     * (SplineOverScroller#adjustDuration), so the harder the flick, the shorter the glide —
     * which reads as "the fling stopped the instant I lifted my finger".
     */
    private static final int FLING_DELTA_AXIS_LIMIT_PX = 1_000_000;

    /** Draw an overscroll edge glow instead of a purely hard stop. OFF by default: commit
     *  0fab152e deliberately chose a hard decelerated stop without overscroll bounce/glow. */
    private static final boolean EDGE_GLOW_ENABLED = false;

    /**
     * Turn a wheel gesture into a fling once it settles (Chromebook-style inertia). Ticks are
     * summed with the AOSP IMPULSE algorithm and the result is applied as a single fling, rather
     * than restarting the fling on every tick. OFF by default: system Android lists apply the
     * wheel distance immediately and never fling on the wheel.
     */
    private static final boolean WHEEL_FLING_ENABLED = false;

    /**
     * Quiet period after the last wheel tick before the summed impulse becomes a fling. Equal to
     * the AOSP VelocityTracker horizon: once the newest sample is that old, the gesture is over by
     * the platform's own definition, and {@code getVelocity()} would start returning 0 anyway.
     */
    private static final long WHEEL_IMPULSE_SETTLE_MS = 100L;

    /**
     * Axis lock for the current finger gesture. Once the dominant direction is decided by the
     * first significant displacement, it is locked until the next {@code ACTION_DOWN} so an
     * accidental tilt cannot be reinterpreted as a horizontal ViewPager2 session swipe (or vice
     * versa). The terminal only ever scrolls vertically (history); a horizontal-dominant gesture
     * is left to the ViewPager2 pager.
     */
    private static final int SCROLL_AXIS_UNDECIDED = 0;
    private static final int SCROLL_AXIS_VERTICAL = 1;
    private static final int SCROLL_AXIS_HORIZONTAL = 2;
    private int mScrollAxis = SCROLL_AXIS_UNDECIDED;
    /** Touch position captured at {@link #onDown(float, float)} to measure total displacement. */
    private float mScrollDownX, mScrollDownY;
    /** Minimum total displacement (px) before the scroll axis is locked. */
    private final int mScrollAxisSlop;

    // ── Interactive scrollbar (draggable thumb) ──

    /** Width of the interactive scrollbar touch region in pixels. */
    private final int mScrollbarWidth;
    /** Fixed scrollbar thumb height in dp. */
    private static final int SCROLLBAR_THUMB_SIZE_DP = 64;
    /** Scrollbar thumb height in pixels (inited once from {@link #SCROLLBAR_THUMB_SIZE_DP}). */
    private final int mScrollbarThumbSizePx;
    /** Radius for the rounded corners of the scrollbar track and thumb. */
    private static final int SCROLLBAR_CORNER_RADIUS = 12;
    /** Extra touch tolerance (px) around the thumb on each side for finger targeting. */
    private final int mScrollbarThumbTouchSlop;

    /** Whether the user is currently dragging the scrollbar thumb. */
    private boolean mScrollbarDragging;
    /** Finger Y of the previous drag event (incremental delta tracking). */
    private float mScrollbarDragStartRawY;

    /** Paint for the scrollbar track (a thin vertical strip). */
    private final Paint mScrollbarTrackPaint;
    /** Paint for the draggable thumb. */
    private final Paint mScrollbarThumbPaint;
    /** Paint for the thumb while being dragged (brighter). */
    private final Paint mScrollbarThumbActivePaint;
    /** Paint for the thumb outline in resting state (coloured with the active colour). */
    private final Paint mScrollbarThumbStrokePaint;
    /**
     * Pre-computed scrollbar thumb colours, applied once when the alpha preference changes
     * (or the colour scheme changes) rather than recomputed on every frame. Initialized with
     * the hardcoded defaults (~5% / ~12%) so a fresh install without the preference set still
     * renders correctly.
     */
    private int mScrollbarInactiveColor = 0x0D000000;
    private int mScrollbarActiveColor   = 0x1F000000;
    /** True once {@link #setScrollbarColors(int, int)} has been called. */
    private boolean mScrollbarColorsSet;

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    int mCombiningAccent;

    /**
     * The current AutoFill type returned for {@link View#getAutofillType()} by {@link #getAutofillType()}.
     *
     * The default is {@link #AUTOFILL_TYPE_NONE} so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create. This value should be updated
     * to required value, like {@link #AUTOFILL_TYPE_TEXT} before calling
     * {@link AutofillManager#requestAutofill(View)} so that AutoFill UI shows. The updated value
     * set will automatically be restored to {@link #AUTOFILL_TYPE_NONE} in
     * {@link #autofill(AutofillValue)} so that AutoFill UI isn't shown anymore by calling
     * {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillType = AUTOFILL_TYPE_NONE;

    /**
     * The current AutoFill type returned for {@link View#getImportantForAutofill()} by
     * {@link #getImportantForAutofill()}.
     *
     * The default is {@link #IMPORTANT_FOR_AUTOFILL_NO} so that view is not considered important
     * for AutoFill. This value should be updated to required value, like
     * {@link #IMPORTANT_FOR_AUTOFILL_YES} before calling {@link AutofillManager#requestAutofill(View)}
     * so that Android and apps consider the view as important for AutoFill to process the request.
     * The updated value set will automatically be restored to {@link #IMPORTANT_FOR_AUTOFILL_NO} in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO;

    /**
     * The current AutoFill hints returned for {@link View#getAutofillHints()} ()} by {@link #getAutofillHints()} ()}.
     *
     * The default is an empty `string[]`. This value should be updated to required value. The
     * updated value set will automatically be restored an empty `string[]` in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    private String[] mAutoFillHints = new String[0];

    private final boolean mAccessibilityEnabled;

    /** The {@link KeyEvent} is generated from a virtual keyboard, like manually with the {@link KeyEvent#KeyEvent(int, int)} constructor. */
    public final static int KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD; // -1

    /** The {@link KeyEvent} is generated from a non-physical device, like if 0 value is returned by {@link KeyEvent#getDeviceId()}. */
    public final static int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;

    private static final String LOG_TAG = "TerminalView";

    public TerminalView(Context context, AttributeSet attributes) { // NO_UCD (unused code)
        super(context, attributes);
        mGestureRecognizer = new GestureAndScaleRecognizer(context, new GestureAndScaleRecognizer.Listener() {

            boolean scrolledWithFinger;

            @Override
            public boolean onUp(MotionEvent event) {
                // mScrollRemainder is deliberately NOT reset here: system lists keep their
                // sub-pixel position across gestures, and the leftover is always < 1 row.
                // It is only dropped when the geometry changes (zoom, session, cancel) — see
                // stopFlingAndClear().
                clearCapturedFlingVelocity();
                releaseEdgeGlow();
                if (mEmulator != null && mEmulator.isMouseTrackingActive() && !event.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolledWithFinger) {
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
                    return true;
                }
                scrolledWithFinger = false;
                mScrollAxis = SCROLL_AXIS_UNDECIDED;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                if (mEmulator == null) return true;

                if (isSelectingText()) {
                    stopTextSelectionMode();
                    return true;
                }
                requestFocus();
                mClient.onSingleTapUp(event);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e, float distanceX, float distanceY) {
                if (mEmulator == null) return true;
                if (mEmulator.isMouseTrackingActive() && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    // If moving with mouse pointer while pressing button, report that instead of scroll.
                    // This means that we never report moving with button press-events for touch input,
                    // since we cannot just start sending these events without a starting press event,
                    // which we do not do for touch input, only mouse in onTouchEvent().
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                } else {
                    interruptFlingForNewTouch();
                    if (mScrollAxis == SCROLL_AXIS_UNDECIDED) {
                        float totalX = Math.abs(e.getX() - mScrollDownX);
                        float totalY = Math.abs(e.getY() - mScrollDownY);
                        if (totalX < mScrollAxisSlop && totalY < mScrollAxisSlop) {
                            return true; // not enough movement to decide yet
                        }
                        mScrollAxis = (totalY >= totalX) ? SCROLL_AXIS_VERTICAL : SCROLL_AXIS_HORIZONTAL;
                    }
                    if (mScrollAxis == SCROLL_AXIS_VERTICAL && getParent() != null) {
                        // Claim the gesture: stop ViewPager2 from paging sessions while we scroll
                        // the terminal history vertically.
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    // Horizontal axis: session paging belongs to the ViewPager2 — ignore here.
                    if (mScrollAxis == SCROLL_AXIS_HORIZONTAL) {
                        return true;
                    }
                    scrolledWithFinger = true;
                    if (EDGE_GLOW_ENABLED && !mEmulator.isMouseTrackingActive() && !mEmulator.isAlternateBufferActive()) {
                        // Pulling past an edge feeds the glow (visual only) instead of the
                        // scroll accumulator — content never moves past the boundary.
                        // Sign convention (matches doScroll() and the px fling axis, which is
                        // verified against the working upstream drag path): distanceY < 0 moves
                        // INTO HISTORY (mTopRow decreases), distanceY > 0 towards the bottom.
                        float h = Math.max(1, getHeight());
                        int transcript = mEmulator.getScreen().getActiveTranscriptRows();
                        if (distanceY < 0 && mTopRow <= -transcript && mEdgeGlowTop != null) {
                            mEdgeGlowTop.onPull(-distanceY / h, 1f - e.getX() / Math.max(1, getWidth()));
                            invalidate();
                            return true;
                        }
                        if (distanceY > 0 && mTopRow >= 0 && mEdgeGlowBottom != null) {
                            mEdgeGlowBottom.onPull(distanceY / h, e.getX() / Math.max(1, getWidth()));
                            invalidate();
                            return true;
                        }
                    }
                    distanceY += mScrollRemainder;
                    int deltaRows = (int) (distanceY / mRenderer.mFontLineSpacing);
                    mScrollRemainder = distanceY - deltaRows * mRenderer.mFontLineSpacing;
                    doScroll(e, deltaRows);
                }
                return true;
            }

            @Override
            public boolean onScale(float focusX, float focusY, float scale) {
                if (mEmulator == null || isSelectingText()) return true;
                stopFlingAndClear();
                mScaleFactor *= scale;
                mScaleFactor = mClient.onScale(mScaleFactor);
                return true;
            }

            @Override
            public boolean onFling(final MotionEvent e2, float velocityX, float velocityY) {
                if (mEmulator == null) return true;
                scrolledWithFinger = true;
                return startFling(e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(float x, float y) {
                interruptFlingForNewTouch();
                scrolledWithFinger = false;
                mScrollAxis = SCROLL_AXIS_UNDECIDED;
                mScrollDownX = x;
                mScrollDownY = y;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                // Do not treat is as a single confirmed tap - it may be followed by zoom.
                return false;
            }

            @Override
            public void onLongPress(MotionEvent event) {
                if (mGestureRecognizer.isInProgress()) return;
                stopFlingAndClear();
                if (mClient.onLongPress(event)) return;
                if (!isSelectingText()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    startTextSelectionMode(event);
                }
            }

            @Override
            public void onCancel(MotionEvent event) {
                stopFlingAndClear();
                releaseEdgeGlow();
                scrolledWithFinger = false;
                mScrollAxis = SCROLL_AXIS_UNDECIDED;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        mScroller = new OverScroller(context);
        // No overscroll "bounce"/edge-glow: scrolling decelerates via spline and stops hard at the edges.
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mAccessibilityEnabled = am.isEnabled();
        ViewConfiguration vc = ViewConfiguration.get(context);
        mScrollAxisSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        // System maximum: fling physics now runs in px/s, so no boost factor is needed.
        // (GestureDetector already clamps to this value; this is just a belt-and-braces clamp.)
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        mWheelScrollFactorPx = vc.getScaledVerticalScrollFactor();
        if (EDGE_GLOW_ENABLED) {
            mEdgeGlowTop = new EdgeEffect(context);
            mEdgeGlowBottom = new EdgeEffect(context);
        }

        // Interactive scrollbar paint
        mScrollbarWidth = (int) (24 * context.getResources().getDisplayMetrics().density + 0.5f);
        mScrollbarThumbSizePx = (int) (SCROLLBAR_THUMB_SIZE_DP * context.getResources().getDisplayMetrics().density + 0.5f);
        mScrollbarThumbTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop() + 8;
        mScrollbarTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mScrollbarTrackPaint.setColor(0x33FFFFFF);
        mScrollbarThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mScrollbarThumbPaint.setColor(0x88FFFFFF);
        mScrollbarThumbActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mScrollbarThumbActivePaint.setColor(0xBBFFFFFF);
        mScrollbarThumbStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mScrollbarThumbStrokePaint.setStyle(Paint.Style.STROKE);
        mScrollbarThumbStrokePaint.setStrokeWidth(0.5f * context.getResources().getDisplayMetrics().density);
        mScrollbarThumbStrokePaint.setColor(0xBBFFFFFF);
    }



    /**
     * @param client The {@link TerminalViewClient} interface implementation to allow
     *                           for communication between {@link TerminalView} and its client.
     */
    public void setTerminalViewClient(TerminalViewClient client) {
        this.mClient = client;
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsTerminalViewKeyLoggingEnabled(boolean value) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value;
    }



    /**
     * Attach a {@link TerminalSession} to this view.
     *
     * @param session The {@link TerminalSession} this view will be displaying.
     */
    public boolean attachSession(TerminalSession session) {
        if (session == mTermSession) return false;
        stopFlingAndClear();
        mTopRow = 0;

        mTermSession = session;
        mEmulator = null;
        mCombiningAccent = 0;
        mLastCursorRow = Integer.MIN_VALUE;
        mLastCursorCol = -1;

        updateSize();

        // System scrollbar is disabled — we draw our own interactive thumb.
        setVerticalScrollBarEnabled(false);

        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Guard against a null client (e.g. an as-yet-unbound placeholder page that briefly gains
        // IME focus). Returning null tells the IME there is no input target, which is safe.
        if (mClient == null) return null;
        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText. This is necessary if an activity is
        // initially started with the alternate view or if activity is returned to from another app
        // and the alternate view was the one selected the last time.
        if (mClient.isTerminalViewSelected()) {
            if (mClient.shouldEnforceCharBasedInput()) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                // Affects mostly Samsung stock keyboards.
                // https://github.com/termux/termux-app/issues/686
                // However, this is not a valid value as per AOSP since `InputType.TYPE_CLASS_*` is
                // not set and it logs a warning:
                // W/InputAttributes: Unexpected input class: inputType=0x00080090 imeOptions=0x02000000
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/inputmethods/LatinIME/java/src/com/android/inputmethod/latin/InputAttributes.java;l=79
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                //
                // Previous keyboard issues:
                // https://github.com/termux/termux-packages/issues/25
                // https://github.com/termux/termux-app/issues/87.
                // https://github.com/termux/termux-app/issues/126.
                // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
                outAttrs.inputType = InputType.TYPE_NULL;
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType =  InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {

            @Override
            public boolean finishComposingText() {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "IME: finishComposingText()");
                super.finishComposingText();

                sendTextToTerminal(getEditable());
                getEditable().clear();
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
                }
                super.commitText(text, newCursorPosition);

                if (mEmulator == null) return true;

                Editable content = getEditable();
                sendTextToTerminal(content);
                content.clear();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: deleteSurroundingText(" + leftLength + ", " + rightLength + ")");
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++) sendKeyEvent(deleteKey);
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            void sendTextToTerminal(CharSequence text) {
                stopTextSelectionMode();
                final int textLengthInChars = text.length();
                for (int i = 0; i < textLengthInChars; i++) {
                    char firstChar = text.charAt(i);
                    int codePoint;
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                        } else {
                            // At end of string, with no low surrogate following the high:
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                        }
                    } else {
                        codePoint = firstChar;
                    }

                    // Check onKeyDown() for details.
                    if (mClient.readShiftKey())
                        codePoint = Character.toUpperCase(codePoint);

                    boolean ctrlHeld = false;
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n') {
                            // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                            // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                            // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                            // check the behaviour).
                            codePoint = '\r';
                        }

                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true;
                        switch (codePoint) {
                            case 31:
                                codePoint = '_';
                                break;
                            case 30:
                                codePoint = '^';
                                break;
                            case 29:
                                codePoint = ']';
                                break;
                            case 28:
                                codePoint = '\\';
                                break;
                            default:
                                codePoint += 96;
                                break;
                        }
                    }

                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, ctrlHeld, false);
                }
            }

        };
    }

    @Override
    protected int computeVerticalScrollRange() {
        return mEmulator == null ? 1 : mEmulator.getScreen().getActiveRows();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return mEmulator == null ? 1 : mEmulator.mRows;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return mEmulator == null ? 1 : mEmulator.getScreen().getActiveRows() + mTopRow - mEmulator.mRows;
    }

    public void onScreenUpdated() {
        onScreenUpdated(false);
    }

    public void onScreenUpdated(boolean skipScrolling) {
        if (mEmulator == null) return;
        final int oldTopRow = mTopRow;

        int rowsInHistory = mEmulator.getScreen().getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory;

        if (mTopRow == 0) {
            skipScrolling = true;
        } else if (mScrollbarDragging) {
            // While the user holds the scrollbar thumb, do NOT anchor the view to
            // incoming output: the auto-scroll-disabled branch below would shift
            // mTopRow by the scroll counter and slide the thumb out from under the
            // finger (drift). Keep mTopRow locked to the finger position so the thumb
            // stays put and new lines simply scroll under it.
            skipScrolling = true;
            mTopRow = fingerYtoTopRowCentered(mScrollbarDragStartRawY);
            mEmulator.setAutoScrollDisabled(mTopRow != 0);
        } else if (isFlingActive()) {
            // During a fling the OverScroller owns mTopRow exclusively (runFlingFrame applies
            // absolute row targets). The follow-text compensation below would fight the scroller
            // and jitter, so it is skipped; the snap-to-bottom further down is skipped too via
            // skipScrolling. The scroll counter is still consumed at the end of this method,
            // meaning text under the viewport simply drifts with the output until the fling ends.
            skipScrolling = true;
        } else if (isSelectingText() || mEmulator.isAutoScrollDisabled()) {

            int rowShift = mEmulator.getScrollCounter();
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                if (isSelectingText())
                    stopTextSelectionMode();

                if (mEmulator.isAutoScrollDisabled()) {
                    mTopRow = -rowsInHistory;
                    skipScrolling = true;
                }
            } else {
                skipScrolling = true;
                mTopRow -= rowShift;
                decrementYTextSelectionCursors(rowShift);
            }
        }

        if (!skipScrolling && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars();
            }
            mTopRow = 0;
        }

        mEmulator.clearScrollCounter();

        repaintAfterUpdate(oldTopRow);
        if (mAccessibilityEnabled) setContentDescription(getText());

        if (mOnScreenUpdateListener != null) mOnScreenUpdateListener.onScreenUpdated();
    }

    /**
     * Choose between a full and a partial (dirty-rows) repaint after the emulator changed, and
     * invalidate accordingly. A full repaint is required when the view scrolled, a selection or
     * scrollbar drag is in progress, or the buffer flagged everything dirty (scroll, resize,
     * buffer switch, color reset). Otherwise only the rows the buffer marked dirty — plus the
     * old/new cursor rows if the cursor moved — are invalidated.
     */
    private void repaintAfterUpdate(int oldTopRow) {
        TerminalBuffer screen = mEmulator.getScreen();

        // Thread-safety note: the dirty-state writer (mEmulator.append(), invoked only from
        // TerminalSession.MainThreadHandler.handleMessage()) and the reader (this method, invoked
        // from onScreenUpdated() on the main thread) execute on the same (main) thread: TerminalSession
        // is always constructed on the main thread, so its mMainThreadHandler binds to the main
        // Looper, and no other code path calls append(). first/last therefore can never be read torn
        // (first > last) and no synchronization is needed. The only off-main dirty write is the
        // one-shot cold-start emulator init (initSessionEmulatorOnBackgroundThread → updateSize →
        // initializeEmulator → TerminalEmulator.<init> → reset → markAllDirty; at that point
        // mEmulator == null so the resize() branch is unreachable off-main), which happens-before any
        // rendering via the runOnUiThread pager sync. If append() ever moves off the main thread, the
        // dirty range must be read atomically (or fall back to a full invalidate on inconsistency)
        // before the optimistic clearDirtyState() below can drop a pending repaint.

        // Cursor external row equals the screen-relative row (screen rows map to external 0..mRows-1).
        int cursorExtRow = mEmulator.getCursorRow();
        int cursorCol = mEmulator.getCursorCol();
        boolean cursorMoved = (cursorExtRow != mLastCursorRow) || (cursorCol != mLastCursorCol);
        int prevCursorExtRow = mLastCursorRow;
        mLastCursorRow = cursorExtRow;
        mLastCursorCol = cursorCol;

        boolean fullRepaint =
            mTopRow != oldTopRow
            || isSelectingText()
            || mScrollbarDragging
            || screen.isAllDirty();

        if (fullRepaint) {
            screen.clearDirtyState();
            invalidate();
            return;
        }

        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        if (screen.hasDirtyRows()) {
            first = screen.getFirstDirtyRow();
            last = screen.getLastDirtyRow();
        }
        screen.clearDirtyState();

        // A cursor move with no cell change (e.g. arrow keys) still needs the old cursor cell
        // erased and the new one drawn.
        if (cursorMoved) {
            if (prevCursorExtRow != Integer.MIN_VALUE) {
                first = Math.min(first, prevCursorExtRow);
                last = Math.max(last, prevCursorExtRow);
            }
            first = Math.min(first, cursorExtRow);
            last = Math.max(last, cursorExtRow);
        }

        if (first > last) return; // nothing needs pixels
        invalidateRowRange(first, last);
    }

    /**
     * View-y of the top edge of an external row (must match TerminalRenderer's row layout).
     *
     * This relies on the invariant that the glyph grid is pinned to the top of the view, i.e.
     * {@code mGridOffsetY == -mRenderer.mFontLineSpacingAndAscent} exactly (set in
     * {@link #updateSize()}, where both values are whole pixels). Substituting that identity makes
     * {@code rowToPixelTop(r) == (r - mTopRow) * mFontLineSpacing}, the same integer band the
     * renderer draws each row into after its {@code canvas.translate(xOffset, yOffset)}. If the
     * grid is ever re-pinned (e.g. vertically centered) or the offsets stop being snapped to whole
     * pixels, the 1px rounding mismatch between this method and the renderer will produce seams.
     */
    private int rowToPixelTop(int externalRow) {
        return Math.round(mGridOffsetY + mRenderer.mFontLineSpacingAndAscent
            + (externalRow - mTopRow) * (float) mRenderer.mFontLineSpacing);
    }

    /** Invalidate the full-width pixel band covering external rows [first, last], clamped to visible rows. */
    private void invalidateRowRange(int first, int last) {
        if (mEmulator == null || mRenderer == null) { invalidate(); return; }
        int visTop = mTopRow;
        int visBottom = mTopRow + mEmulator.mRows - 1;
        if (last < visTop || first > visBottom) return;
        first = Math.max(first, visTop);
        last = Math.min(last, visBottom);
        int top = rowToPixelTop(first);
        int bottom = rowToPixelTop(last + 1);
        if (top < 0) top = 0;
        if (bottom > getHeight()) bottom = getHeight();
        if (top >= bottom) return;
        invalidate(0, top, getWidth(), bottom);
    }

    /** Invalidate only the cell(s) holding the cursor (used by the cursor blinker). */
    private void invalidateCursorCell() {
        if (mEmulator == null || mRenderer == null) { invalidate(); return; }
        if (isSelectingText() || mScrollbarDragging) { invalidate(); return; }
        int col = mEmulator.getCursorCol();
        int extRow = mEmulator.getCursorRow();
        int left = (int) Math.floor(col * mRenderer.mFontWidth + mGridOffsetX);
        // Cover up to two cells so a block cursor over a wide (wcwidth==2) char is fully included.
        int right = (int) Math.ceil((col + 2) * mRenderer.mFontWidth + mGridOffsetX);
        int top = rowToPixelTop(extRow);
        int bottom = rowToPixelTop(extRow + 1);
        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (right > getWidth()) right = getWidth();
        if (bottom > getHeight()) bottom = getHeight();
        if (left >= right || top >= bottom) return;
        invalidate(left, top, right, bottom);
    }

    /** This must be called by the hosting activity in {@link Activity#onContextMenuClosed(Menu)}
     * when context menu for the {@link TerminalView} is started by
     * {@link TextSelectionCursorController#ACTION_MORE} is closed. */
    public void onContextMenuClosed(Menu menu) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText();
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    public void setTextSize(int textSize) {
        mRenderer = new TerminalRenderer(textSize, mRenderer == null ? Typeface.MONOSPACE : mRenderer.mTypeface);
        updateSize();
    }

    public void setTypeface(Typeface newTypeface) {
        mRenderer = new TerminalRenderer(mRenderer.mTextSize, newTypeface);
        updateSize();
        invalidate();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account. E.g. if scrolled 3 lines up and the event
     * position is in the top left, column will be -3 if relativeToScroll is
     * true and 0 if relativeToScroll is false.
     * @return Array with the column and row.
     */
    public int[] getColumnAndRow(MotionEvent event, boolean relativeToScroll) {
        int column = (int) ((event.getX() - mGridOffsetX) / mRenderer.mFontWidth);
        int row = (int) ((event.getY() - mGridOffsetY - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);
        if (relativeToScroll) {
            row += mTopRow;
        }
        return new int[] { column, row };
    }

    /** Send a single mouse event code to the terminal. */
    void sendMouseEventCode(MotionEvent e, int button, boolean pressed) {
        int[] columnAndRow = getColumnAndRow(e, false);
        int x = columnAndRow[0] + 1;
        int y = columnAndRow[1] + 1;
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.getDownTime()) {
                x = mMouseScrollStartX;
                y = mMouseScrollStartY;
            } else {
                mMouseStartDownTime = e.getDownTime();
                mMouseScrollStartX = x;
                mMouseScrollStartY = y;
            }
        }
        mEmulator.sendMouseEvent(button, x, y, pressed);
    }

    // ── Fling / impulse scroll helpers ──

    private boolean isFlingActive() {
        return mFlingRunnable != null && !mScroller.isFinished();
    }

    private void stopFlingAnimation() {
        if (mFlingRunnable != null) {
            removeCallbacks(mFlingRunnable);
            mFlingRunnable = null;
        }
        mScroller.forceFinished(true);
        mFlingRawVelocity = 0f;
        mFlingDeltaMode = false;
        mFlingLastRowPx = 0;
        mFlingEndsAtEdge = false;
        mFlingAbsorbedAtEdge = false;
        recycleFlingEvent();
    }

    /**
     * Stop the fling and drop every piece of gesture state, including the sub-row scroll
     * accumulator. Used when the geometry or the context changes and a leftover pixel remainder
     * would be meaningless: pinch zoom (row height changes), session switch, cancel, scrollbar
     * thumb drag.
     */
    private void stopFlingAndClear() {
        stopFlingAnimation();
        clearCapturedFlingVelocity();
        mScrollRemainder = 0f;
    }

    public void stopFling() {
        stopFlingAndClear();
    }

    private void clearCapturedFlingVelocity() {
        mCapturedFlingVelocityY = 0f;
        mCapturedFlingTime = 0;
    }

    private void captureCurrentFlingVelocity() {
        if (!isFlingActive()) return;
        // OverScroller#getCurrVelocity() returns the NORM of the per-axis velocities — always
        // >= 0 — so the direction must come from the velocity the fling was launched with. The
        // scroller axis is the negated gesture velocity, hence residual = sign(raw) * |v|.
        // (The previous code negated the norm directly, which pinned every captured residual to
        // the same direction regardless of where the fling was actually going.)
        float magnitude = Math.abs(mScroller.getCurrVelocity());
        if (magnitude == 0f) magnitude = Math.abs(mFlingRawVelocity);
        if (magnitude == 0f) return;
        mCapturedFlingVelocityY = (mFlingRawVelocity >= 0f ? magnitude : -magnitude);
        mCapturedFlingTime = SystemClock.uptimeMillis();
    }

    /**
     * Hand the sub-row leftover of the fling's pixel axis over to the shared drag accumulator, so
     * a finger that picks up where a fling stopped continues from the exact pixel the fling was
     * on. System lists never lose sub-pixel position between gestures; we cannot render the
     * leftover, but we do not have to throw it away either.
     */
    private void captureFlingRemainder() {
        if (mFlingDeltaMode) return; // delta mode has no absolute position to derive it from
        final int rowHeight = mRenderer != null ? mRenderer.mFontLineSpacing : 0;
        if (rowHeight <= 0) return;
        final int currPx = mScroller.getCurrY();
        mScrollRemainder = currPx - pxToRows(currPx, rowHeight) * rowHeight;
    }

    private void interruptFlingForNewTouch() {
        if (!isFlingActive()) return;
        if (FLING_RESIDUAL_ENABLED) captureCurrentFlingVelocity();
        captureFlingRemainder();
        stopFlingAnimation();
    }

    private float getDecayedCapturedVelocity(long now) {
        if (mCapturedFlingVelocityY == 0f) return 0f;
        long dt = now - mCapturedFlingTime;
        if (dt <= 0) return mCapturedFlingVelocityY;
        if (dt > FLING_CAPTURE_TAU_MS * 4f) return 0f;
        double decay = Math.exp(-dt / (double) FLING_CAPTURE_TAU_MS);
        return (float) (mCapturedFlingVelocityY * decay);
    }

    private float combineFlingVelocity(float residual, float gesture) {
        if (residual == 0f) return clampFlingVelocity(gesture);
        if (gesture == 0f) return 0f;
        float result;
        if (Math.signum(residual) == Math.signum(gesture)) {
            result = gesture + residual * FLING_RESIDUAL_FACTOR;
        } else {
            result = gesture + residual;
            if (Math.abs(result) < mMinFlingVelocity) result = 0f;
        }
        return clampFlingVelocity(result);
    }

    private float clampFlingVelocity(float velocity) {
        if (velocity > mMaxFlingVelocity) return mMaxFlingVelocity;
        if (velocity < -mMaxFlingVelocity) return -mMaxFlingVelocity;
        return velocity;
    }

    private void recycleFlingEvent() {
        if (mFlingEvent != null) {
            mFlingEvent.recycle();
            mFlingEvent = null;
        }
    }

    private boolean startFling(MotionEvent e, float velocityX, float velocityY) {
        if (mEmulator == null) return true;
        if (mScrollAxis == SCROLL_AXIS_UNDECIDED) {
            if (Math.abs(velocityY) >= Math.abs(velocityX)) {
                mScrollAxis = SCROLL_AXIS_VERTICAL;
            } else {
                mScrollAxis = SCROLL_AXIS_HORIZONTAL;
            }
        }
        if (mScrollAxis == SCROLL_AXIS_HORIZONTAL) {
            stopFlingAndClear();
            return true;
        }
        final float rawVelocity;
        if (FLING_RESIDUAL_ENABLED) {
            if (isFlingActive() && mCapturedFlingVelocityY == 0f) {
                captureCurrentFlingVelocity();
            }
            long now = SystemClock.uptimeMillis();
            rawVelocity = combineFlingVelocity(getDecayedCapturedVelocity(now), velocityY);
        } else {
            // System behaviour (AbsListView / RecyclerView / ScrollView): use the new gesture's
            // velocity as-is and throw the interrupted fling's residual away. Any "carry-over"
            // is already physically in that number, because VelocityTracker measures the absolute
            // speed of the finger — a finger chasing the moving content reports its speed.
            rawVelocity = clampFlingVelocity(velocityY);
        }
        clearCapturedFlingVelocity();
        MotionEvent newFlingEvent = MotionEvent.obtain(e);
        stopFlingAnimation();
        if (Math.abs(rawVelocity) < mMinFlingVelocity) {
            newFlingEvent.recycle();
            return true;
        }
        final int rowHeight = mRenderer != null ? mRenderer.mFontLineSpacing : 0;
        if (rowHeight <= 0) {
            newFlingEvent.recycle();
            return true;
        }
        // The fling runs on a PIXEL axis (0 = bottom, negative = into history) so that the
        // OverScroller applies the exact system physics to real px/s velocities; rows are
        // derived only when frames are applied in runFlingFrame(). Distances therefore match
        // system scrolling and are independent of the font size.
        boolean mouseTracking = mEmulator.isMouseTrackingActive();
        // Mouse tracking AND the alternate buffer both mean the application owns the scroll
        // position: there is no transcript row to map the fling onto, the view can only stream
        // relative rows to it (wheel events with mouse tracking, arrow keys otherwise). Both
        // therefore run on the delta axis; doScroll() picks the right channel per row.
        final boolean appScrolled = mouseTracking || mEmulator.isAlternateBufferActive();
        int startPx, minPx, maxPx;
        if (appScrolled) {
            startPx = 0;
            minPx = -FLING_DELTA_AXIS_LIMIT_PX;
            maxPx = FLING_DELTA_AXIS_LIMIT_PX;
        } else {
            int transcriptRows = mEmulator.getScreen().getActiveTranscriptRows();
            if (transcriptRows <= 0) {
                newFlingEvent.recycle();
                return true;
            }
            startPx = Math.min(0, Math.max(-transcriptRows, mTopRow)) * rowHeight;
            minPx = -transcriptRows * rowHeight;
            maxPx = 0;
        }
        mFlingDeltaMode = appScrolled;
        mFlingLastRowPx = pxToRows(startPx, rowHeight);
        mFlingRawVelocity = rawVelocity;
        mFlingEvent = newFlingEvent;
        mScroller.fling(0, startPx, 0, -Math.round(rawVelocity), 0, 0, minPx, maxPx);
        int finalPx = mScroller.getFinalY();
        mFlingEndsAtEdge = (finalPx == minPx || finalPx == maxPx);
        mFlingAbsorbedAtEdge = false;
        mFlingRunnable = new Runnable() {
            @Override
            public void run() {
                runFlingFrame();
            }
        };
        postOnAnimation(mFlingRunnable);
        return true;
    }

    private void runFlingFrame() {
        if (mEmulator == null || mFlingEvent == null) {
            stopFlingAnimation();
            return;
        }
        // The delivery channel must not change mid-flight: a fling that started streaming
        // relative rows to the application cannot continue once the application turns mouse
        // tracking off or leaves the alternate buffer, because its rows would suddenly start
        // moving mTopRow instead of being forwarded to it.
        boolean appScrolled = mEmulator.isMouseTrackingActive() || mEmulator.isAlternateBufferActive();
        if (appScrolled != mFlingDeltaMode) {
            stopFlingAnimation();
            return;
        }
        boolean more = mScroller.computeScrollOffset();
        final int rowHeight = mRenderer.mFontLineSpacing;
        final int currPx = mScroller.getCurrY();
        int newRow = pxToRows(currPx, rowHeight);

        int diff;
        if (mFlingDeltaMode) {
            diff = newRow - mFlingLastRowPx;
            mFlingLastRowPx = newRow;
            // Rate-limit synthetic wheel events: one frame must not burst a huge batch
            // into the terminal application (tracking stays absolute, events are capped).
            if (diff > FLING_DELTA_MAX_ROWS_PER_FRAME) diff = FLING_DELTA_MAX_ROWS_PER_FRAME;
            else if (diff < -FLING_DELTA_MAX_ROWS_PER_FRAME) diff = -FLING_DELTA_MAX_ROWS_PER_FRAME;
        } else {
            // Absolute mapping: the fractional remainder lives inside the scroller,
            // so no quantization error accumulates across frames.
            diff = newRow - mTopRow;
        }
        if (diff != 0) {
            doScroll(mFlingEvent, diff);
        }

        if (EDGE_GLOW_ENABLED && !mFlingDeltaMode && !mFlingAbsorbedAtEdge) {
            // The fling hit an edge with remaining velocity: absorb it into the glow once.
            float v = mScroller.getCurrVelocity();
            if (v > mMinFlingVelocity) {
                int transcriptPx = mEmulator.getScreen().getActiveTranscriptRows() * rowHeight;
                if (currPx <= -transcriptPx && mEdgeGlowTop != null) {
                    mEdgeGlowTop.onAbsorb((int) v);
                    mFlingAbsorbedAtEdge = true;
                    invalidate();
                } else if (currPx >= 0 && mEdgeGlowBottom != null) {
                    mEdgeGlowBottom.onAbsorb((int) v);
                    mFlingAbsorbedAtEdge = true;
                    invalidate();
                }
            }
        }

        if (more) {
            // Tail-cut: below ~FLING_TAIL_ROWS_PER_SEC rows/s the remaining travel is on the
            // order of a row, but the quantized steps become individually visible ("staircase"
            // tail). Skip the cut when the fling ends at an edge, so it lands exactly on the
            // boundary (the spline edge deceleration is the desired finish there).
            // getCurrVelocity() is a norm on current Android; take the magnitude anyway so the
            // threshold can never silently become direction-dependent.
            if (!mFlingEndsAtEdge
                    && Math.abs(mScroller.getCurrVelocity()) < FLING_TAIL_ROWS_PER_SEC * rowHeight) {
                captureFlingRemainder();
                stopFlingAnimation();
                return;
            }
            postOnAnimation(mFlingRunnable);
        } else {
            captureFlingRemainder();
            stopFlingAnimation();
        }
    }

    /**
     * Quantize a pixel position on the fling axis to whole rows. Truncation toward zero
     * matches the drag path's {@code (int) (distanceY / mFontLineSpacing)}: the fractional
     * part stays inside the scroller and never accumulates error.
     */
    private static int pxToRows(int px, int rowHeight) {
        return px / rowHeight;
    }

    /**
     * Start (or restart) a fling from the current position at {@code velocityY} gesture px/s.
     * Positive velocity scrolls into the history, matching {@code GestureDetector#onFling}.
     *
     * <p>The optional wheel-inertia mode is the only caller: it feeds this the velocity the AOSP
     * impulse accumulator derived from a whole series of wheel ticks.</p>
     */
    public void accelerateFling(float velocityY) {
        if (mEmulator == null) return;
        MotionEvent e;
        if (mFlingEvent != null) {
            e = MotionEvent.obtain(mFlingEvent);
        } else {
            long now = SystemClock.uptimeMillis();
            e = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, getWidth() / 2f, getHeight() / 2f, 0);
        }
        startFling(e, 0f, velocityY);
        e.recycle();
    }

    /**
     * A wheel series has settled — turn the impulse summed from its ticks into a single fling.
     *
     * <p>{@code AXIS_VSCROLL} is a <em>differential</em> axis: its values are already deltas, not
     * positions, so a least-squares fit is the wrong model. AOSP tracks such axes with the IMPULSE
     * strategy; this applies exactly that velocity. Launching the fling once, after the series
     * ends, is what keeps the motion smooth — firing one per tick would restart the scroller on
     * every notch and stutter.</p>
     */
    private void launchWheelImpulseFling() {
        // axis-units/s -> px/s (getScaledVerticalScrollFactor() is px per axis unit).
        // Positive axis = scroll up = into history = positive gesture-space velocity, the same
        // convention GestureDetector#onFling and doScroll() use.
        final float velocityY = mWheelImpulse.getVelocity() * mWheelScrollFactorPx;
        mWheelImpulse.clear();
        if (Math.abs(velocityY) < mMinFlingVelocity) return;
        accelerateFling(velocityY);
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    void doScroll(MotionEvent event, int rowsDown) {
        boolean up = rowsDown < 0;
        int amount = Math.abs(rowsDown);
        for (int i = 0; i < amount; i++) {
            if (mEmulator.isMouseTrackingActive()) {
                sendMouseEventCode(event, up ? TerminalEmulator.MOUSE_WHEELUP_BUTTON : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true);
            } else if (mEmulator.isAlternateBufferActive()) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                handleKeyCode(up ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 0);
            } else {
                mTopRow = Math.min(0, Math.max(-(mEmulator.getScreen().getActiveTranscriptRows()), mTopRow + (up ? -1 : 1)));
                // While the user is scrolled up, follow the text instead of the
                // buffer: onScreenUpdated() compensates mTopRow for the rows
                // shifted out of the transcript when it grows past the limit.
                // Re-enable auto-scrolling once the user reaches the bottom.
                mEmulator.setAutoScrollDisabled(mTopRow != 0);
                if (!awakenScrollBars()) invalidate();
            }
        }
    }

    /** Overriding {@link View#onGenericMotionEvent(MotionEvent)}. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getAction() == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            float axis = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (axis == 0f) return true;

            if (WHEEL_FLING_ENABLED) {
                // Optional Chromebook-style inertia. AXIS_VSCROLL is differential, so the ticks
                // are summed with the AOSP IMPULSE strategy (the same one VelocityTracker uses
                // for this axis) instead of being treated as positions. Every tick resets the
                // settle timer; the fling is launched once, when the series ends.
                mWheelImpulse.addSample(event.getEventTime(), axis);
                removeCallbacks(mWheelImpulseRunnable);
                postDelayed(mWheelImpulseRunnable, WHEEL_IMPULSE_SETTLE_MS);
            }

            // The wheel distance itself is applied immediately, exactly like a system list: in
            // pixels, through the same px -> rows quantization the finger-drag path uses, so
            // fractional deltas (precision touchpads) accumulate in the shared remainder instead
            // of being dropped.
            float px = -axis * mWheelScrollFactorPx + mScrollRemainder;
            int deltaRows = (int) (px / mRenderer.mFontLineSpacing);
            mScrollRemainder = px - deltaRows * mRenderer.mFontLineSpacing;
            if (deltaRows != 0) {
                doScroll(event, deltaRows);
            }
            return true;
        }
        return false;
    }

    // ── Interactive scrollbar helpers ──

    /** Check if a horizontal coordinate falls inside the scrollbar touch region. */
    private boolean isInScrollbarRegion(float x) {
        return x >= (getWidth() - mScrollbarWidth);
    }

    /** Check if a touch point is on or near the scrollbar thumb (within touch slop). */
    private boolean isOnThumb(float x, float y) {
        if (!isInScrollbarRegion(x)) return false;
        int range = getScrollbarRange();
        if (range <= 0) return false;
        RectF thumb = computeThumbRect();
        return y >= (thumb.top - mScrollbarThumbTouchSlop)
            && y <= (thumb.bottom + mScrollbarThumbTouchSlop);
    }

    /**
     * Compute the vertical range (in rows) the scrollbar can cover.
     * Returns 0 when there is nothing to scroll.
     */
    private int getScrollbarRange() {
        if (mEmulator == null) return 0;
        return mEmulator.getScreen().getActiveTranscriptRows();
    }

    /**
     * Return the rectangle (in view coordinates) where the scrollbar thumb sits.
     * The track occupies the full view height; the thumb is positioned proportionally.
     */
    private RectF computeThumbRect() {
        int range = getScrollbarRange();
        if (range <= 0) return new RectF(); // no history → zero-size thumb

        float viewW = getWidth();
        float viewH = getHeight();
        float trackLeft = viewW - mScrollbarWidth;
        float thumbW = mScrollbarWidth - 4; // 2px inset on each side

        // Thumb height: fixed size (not proportional)
        float thumbH = Math.min(mScrollbarThumbSizePx, viewH);
        if (thumbH > viewH) thumbH = viewH;

        // Vertical position: offset from top of the track
        // At mTopRow = 0 (bottom) → thumb at bottom of track
        // At mTopRow = -range (top of history) → thumb at top of track
        float scrollFraction = (range + mTopRow) / (float) range; // 1 at bottom, 0 at top
        float maxOffset = viewH - thumbH;
        float thumbTop = scrollFraction * maxOffset;

        return new RectF(trackLeft + 2, thumbTop, trackLeft + 2 + thumbW, thumbTop + thumbH);
    }

    /**
     * Map a finger Y coordinate to the {@link #mTopRow} value, keeping the thumb
     * CENTRE glued under the finger (the user grabs the thumb by its centre, so the
     * centre — not the thumb's top edge — tracks the finger). Clamped to
     * [-getScrollbarRange(), 0].
     */
    private int fingerYtoTopRowCentered(float fingerY) {
        int range = getScrollbarRange();
        if (range <= 0) return 0;

        float viewH = getHeight();
        float thumbH = Math.min(mScrollbarThumbSizePx, viewH);
        float maxOffset = Math.max(viewH - thumbH, 1f);
        // Centre the thumb under the finger so grabbing it does not jump.
        float center = fingerY - thumbH / 2f;
        float scrollFraction = center / maxOffset;
        if (scrollFraction < 0f) scrollFraction = 0f;
        if (scrollFraction > 1f) scrollFraction = 1f;
        return (int) (scrollFraction * range - range);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    @TargetApi(23)
    public boolean onTouchEvent(MotionEvent event) {
        if (mEmulator == null) {
            // The placeholder ("new session") page hosts an intentionally unbound TerminalView
            // (no session yet). A bound view clears the parent ViewPager2's
            // requestDisallowInterceptTouchEvent(true) flag on ACTION_DOWN (see onDown), which is
            // what lets a horizontal session swipe be paged. The unbound view never reaches the
            // recognizer, so after a background→resume the leftover disallow flag would stick and
            // the right-swipe would just edge-overscroll instead of paging to the placeholder.
            // Reset it here for ACTION_DOWN so the placeholder page stays swipeable.
            if (event.getAction() == MotionEvent.ACTION_DOWN && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            stopFlingAndClear();
            return true;
        }
        final int action = event.getAction();

        // ── Scrollbar drag handling ──
        // Intercept touch in the scrollbar region before anything else.
        // While dragging we consume all events; once the drag ends, the
        // gesture recognizer gets nothing so it won't interpret the
        // scrollbar gesture as a terminal scroll or long-press.
        if (!isSelectingText()
                && !event.isFromSource(InputDevice.SOURCE_MOUSE)
                && event.getPointerCount() == 1) {
            if (mScrollbarDragging) {
                switch (action) {
                    case MotionEvent.ACTION_MOVE: {
                        int range = getScrollbarRange();
                        if (range > 0) {
                            // Absolute mapping: lock the thumb centre to the current
                            // finger position. Identical to the old incremental delta
                            // while the range is stable, but also stays glued to the
                            // finger when the transcript grows under us — onScreenUpdated()
                            // recomputes mTopRow the same way for held, stationary drags.
                            mScrollbarDragStartRawY = event.getY();
                            mTopRow = fingerYtoTopRowCentered(mScrollbarDragStartRawY);
                            // Same rule as scrollUp(): scrolled away from the bottom
                            // disables follow-to-bottom; reaching the bottom again
                            // re-enables it. Without this the emulator still thinks
                            // auto-scroll is on and new lines snap the view back to
                            // the bottom mid-drag.
                            mEmulator.setAutoScrollDisabled(mTopRow != 0);
                            invalidate();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mScrollbarDragging = false;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                        invalidate();
                        return true;
                }
            } else if (action == MotionEvent.ACTION_DOWN && isOnThumb(event.getX(), event.getY())) {
                stopFlingAndClear();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                mScrollbarDragging = true;
                // Don't jump the thumb: grab the centre and map it straight to mTopRow,
                // so the thumb stays under the finger and new output doesn't slide it.
                mScrollbarDragStartRawY = event.getY();
                invalidate();
                return true;
            }
        }

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event);
            mGestureRecognizer.onTouchEvent(event);
            return true;
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu();
                return true;
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null) {
                    ClipData.Item clipItem = clipData.getItemAt(0);
                    if (clipItem != null) {
                        CharSequence text = clipItem.coerceToText(getContext());
                        if (!TextUtils.isEmpty(text)) mEmulator.paste(text.toString());
                    }
                }
            } else if (mEmulator.isMouseTrackingActive()) { // BUTTON_PRIMARY.
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.getAction() == MotionEvent.ACTION_DOWN);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                        break;
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyPreIme(keyCode=" + keyCode + ", event=" + event + ")");
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill();
            if (isSelectingText()) {
                stopTextSelectionMode();
                return true;
            } else if (mClient.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                switch (event.getAction()) {
                    case KeyEvent.ACTION_DOWN:
                        return onKeyDown(keyCode, event);
                    case KeyEvent.ACTION_UP:
                        return onKeyUp(keyCode, event);
                }
            }
        } else if (mClient.shouldUseCtrlSpaceWorkaround() &&
                   keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event);
        }
        return super.onKeyPreIme(keyCode, event);
    }

    /**
     * Key presses in software keyboards will generally NOT trigger this listener, although some
     * may elect to do so in some situations. Do not rely on this to catch software key presses.
     * Gboard calls this when shouldEnforceCharBasedInput() is disabled (InputType.TYPE_NULL) instead
     * of calling commitText(), with deviceId=-1. However, Hacker's Keyboard, OpenBoard, LG Keyboard
     * call commitText().
     *
     * This function may also be called directly without android calling it, like by
     * `TerminalExtraKeys` which generates a KeyEvent manually which uses {@link KeyCharacterMap#VIRTUAL_KEYBOARD}
     * as the device (deviceId=-1), as does Gboard. That would normally use mappings defined in
     * `/system/usr/keychars/Virtual.kcm`. You can run `dumpsys input` to find the `KeyCharacterMapFile`
     * used by virtual keyboard or hardware keyboard. Note that virtual keyboard device is not the
     * same as software keyboard, like Gboard, etc. Its a fake device used for generating events and
     * for testing.
     *
     * We handle shift key in `commitText()` to convert codepoint to uppercase case there with a
     * call to {@link Character#toUpperCase(int)}, but here we instead rely on getUnicodeChar() for
     * conversion of keyCode, for both hardware keyboard shift key (via effectiveMetaState) and
     * `mClient.readShiftKey()`, based on value in kcm files.
     * This may result in different behaviour depending on keyboard and android kcm files set for the
     * InputDevice for the event passed to this function. This will likely be an issue for non-english
     * languages since `Virtual.kcm` in english only by default or at least in AOSP. For both hardware
     * shift key (via effectiveMetaState) and `mClient.readShiftKey()`, `getUnicodeChar()` is used
     * for shift specific behaviour which usually is to uppercase.
     *
     * For fn key on hardware keyboard, android checks kcm files for hardware keyboards, which is
     * `Generic.kcm` by default, unless a vendor specific one is defined. The event passed will have
     * {@link KeyEvent#META_FUNCTION_ON} set. If the kcm file only defines a single character or unicode
     * code point `\\uxxxx`, then only one event is passed with that value. However, if kcm defines
     * a `fallback` key for fn or others, like `key DPAD_UP { ... fn: fallback PAGE_UP }`, then
     * android will first pass an event with original key `DPAD_UP` and {@link KeyEvent#META_FUNCTION_ON}
     * set. But this function will not consume it and android will pass another event with `PAGE_UP`
     * and {@link KeyEvent#META_FUNCTION_ON} not set, which will be consumed.
     *
     * Now there are some other issues as well, firstly ctrl and alt flags are not passed to
     * `getUnicodeChar()`, so modified key values in kcm are not used. Secondly, if the kcm file
     * for other modifiers like shift or fn define a non-alphabet, like { fn: '\u0015' } to act as
     * DPAD_LEFT, the `getUnicodeChar()` will correctly return `21` as the code point but action will
     * not happen because the `handleKeyCode()` function that transforms DPAD_LEFT to `\033[D`
     * escape sequence for the terminal to perform the left action would not be called since its
     * called before `getUnicodeChar()` and terminal will instead get `21 0x15 Negative Acknowledgement`.
     * The solution to such issues is calling `getUnicodeChar()` before the call to `handleKeyCode()`
     * if user has defined a custom kcm file, like done in POC mentioned in #2237. Note that
     * Hacker's Keyboard calls `commitText()` so don't test fn/shift with it for this function.
     * https://github.com/termux/termux-app/pull/2237
     * https://github.com/agnostic-apollo/termux-app/blob/terminal-code-point-custom-mapping/terminal-view/src/main/java/com/termux/view/TerminalView.java
     *
     * Key Character Map (kcm) and Key Layout (kl) files info:
     * https://source.android.com/devices/input/key-character-map-files
     * https://source.android.com/devices/input/key-layout-files
     * https://source.android.com/devices/input/keyboard-devices
     * AOSP kcm and kl files:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/data/keyboards
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/packages/InputDevices/res/raw
     *
     * KeyCodes:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/keycodes.h
     *
     * `dumpsys input`:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1917
     *
     * Loading of keymap:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1644
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/Keyboard.cpp;l=41
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/InputDevice.cpp
     * OVERLAY keymaps for hardware keyboards may be combined as well:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=165
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=831
     *
     * Parse kcm file:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=727
     * Parse key value:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=981
     *
     * `KeyEvent.getUnicodeChar()`
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java;l=2716
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/view/KeyCharacterMap.java;l=368
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/jni/android_view_KeyCharacterMap.cpp;l=117
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=231
     *
     * Keyboard layouts advertised by applications, like for hardware keyboards via #ACTION_QUERY_KEYBOARD_LAYOUTS
     * Config is stored in `/data/system/input-manager-state.xml`
     * https://github.com/ris58h/custom-keyboard-layout
     * Loading from apps:
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1221
     * Set:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=89
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=543
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/apps/Settings/src/com/android/settings/inputmethod/KeyboardLayoutDialogFragment.java;l=167
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1385
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/PersistentDataStore.java
     * Get overlay keyboard layout
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=2158
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp;l=616
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem() + ", event=" + event + ")");
        if (mEmulator == null) return true;
        if (isSelectingText()) {
            stopTextSelectionMode();
        }

        if (mClient.onKeyDown(keyCode, event, mTermSession)) {
            invalidate();
            return true;
        } else if (event.isSystem() && (!mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event);
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession.write(event.getCharacters());
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event);
        }

        final int metaState = event.getMetaState();
        final boolean controlDown = event.isCtrlPressed() || mClient.readControlKey();
        final boolean leftAltDown = (metaState & KeyEvent.META_ALT_LEFT_ON) != 0 || mClient.readAltKey();
        final boolean shiftDown = event.isShiftPressed() || mClient.readShiftKey();
        final boolean rightAltDownFromEvent = (metaState & KeyEvent.META_ALT_RIGHT_ON) != 0;

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed() || leftAltDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed() && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "handleKeyCode() took key event");
            return true;
        }

        // Clear Ctrl since we handle that ourselves:
        int bitsToClear = KeyEvent.META_CTRL_MASK;
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        int effectiveMetaState = event.getMetaState() & ~bitsToClear;

        if (shiftDown) effectiveMetaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mClient.readFnKey()) effectiveMetaState |= KeyEvent.META_FUNCTION_ON;

        int result = event.getUnicodeChar(effectiveMetaState);
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar(" + effectiveMetaState + ") returned: " + result);
        if (result == 0) {
            return false;
        }

        int oldCombiningAccent = mCombiningAccent;
        if ((result & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0)
                inputCodePoint(event.getDeviceId(), mCombiningAccent, controlDown, leftAltDown);
            mCombiningAccent = result & KeyCharacterMap.COMBINING_ACCENT_MASK;
        } else {
            if (mCombiningAccent != 0) {
                int combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result);
                if (combinedChar > 0) result = combinedChar;
                mCombiningAccent = 0;
            }
            inputCodePoint(event.getDeviceId(), result, controlDown, leftAltDown);
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate();

        return true;
    }

    public void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient.logInfo(LOG_TAG, "inputCodePoint(eventSource=" + eventSource + ", codePoint=" + codePoint + ", controlDownFromEvent=" + controlDownFromEvent + ", leftAltDownFromEvent="
                + leftAltDownFromEvent + ")");
        }

        if (mTermSession == null) return;

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator.setCursorBlinkState(true);

        final boolean controlDown = controlDownFromEvent || mClient.readControlKey();
        final boolean altDown = leftAltDownFromEvent || mClient.readAltKey();

        if (mClient.onCodePoint(codePoint, controlDown, mTermSession)) return;

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z') {
                codePoint = codePoint - 'a' + 1;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                codePoint = codePoint - 'A' + 1;
            } else if (codePoint == ' ' || codePoint == '2') {
                codePoint = 0;
            } else if (codePoint == '[' || codePoint == '3') {
                codePoint = 27; // ^[ (Esc)
            } else if (codePoint == '\\' || codePoint == '4') {
                codePoint = 28;
            } else if (codePoint == ']' || codePoint == '5') {
                codePoint = 29;
            } else if (codePoint == '^' || codePoint == '6') {
                codePoint = 30; // control-^
            } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                codePoint = 31;
            } else if (codePoint == '8') {
                codePoint = 127; // DEL
            }
        }

        if (codePoint > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                // Work around bluetooth keyboards sending funny unicode characters instead
                // of the more normal ones from ASCII that terminal programs expect - the
                // desire to input the original characters should be low.
                switch (codePoint) {
                    case 0x02DC: // SMALL TILDE.
                        codePoint = 0x007E; // TILDE (~).
                        break;
                    case 0x02CB: // MODIFIER LETTER GRAVE ACCENT.
                        codePoint = 0x0060; // GRAVE ACCENT (`).
                        break;
                    case 0x02C6: // MODIFIER LETTER CIRCUMFLEX ACCENT.
                        codePoint = 0x005E; // CIRCUMFLEX ACCENT (^).
                        break;
                }
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mTermSession.writeCodePoint(altDown, codePoint);
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    public boolean handleKeyCode(int keyCode, int keyMod) {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator.setCursorBlinkState(true);

        if (handleKeyCodeAction(keyCode, keyMod))
            return true;

        TerminalEmulator term = mTermSession.getEmulator();
        String code = KeyHandler.getCode(keyCode, keyMod, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode());
        if (code == null) return false;
        mTermSession.write(code);
        return true;
    }

    public boolean handleKeyCodeAction(int keyCode, int keyMod) {
        boolean shiftDown = (keyMod & KeyHandler.KEYMOD_SHIFT) != 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    long time = SystemClock.uptimeMillis();
                    MotionEvent motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0, 0, 0);
                    doScroll(motionEvent, keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mEmulator.mRows : mEmulator.mRows);
                    motionEvent.recycle();
                    return true;
                }
        }

       return false;
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyUp(keyCode=" + keyCode + ", event=" + event + ")");

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true;

        if (mClient.onKeyUp(keyCode, event)) {
            invalidate();
            return true;
        } else if (event.isSystem()) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event);
        }

        return true;
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateSize();
    }

    /** Check if the terminal size in rows and columns should be updated. */
    public void updateSize() {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return;

        // Set to 80 and 24 if you want to enable vttest.
        int newColumns = Math.max(4, (int) (viewWidth / mRenderer.mFontWidth));
        int newRows = Math.max(4, (viewHeight - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);

        // The glyph grid is pinned to the TOP edge of the view: the first row's top sits at
        // y=0 and all leftover vertical space (from rows that do not fit the view) stays at
        // the bottom. The grid occupies [0, columns*fontWidth) horizontally and
        // [mFontLineSpacingAndAscent, mFontLineSpacingAndAscent + rows*lineSpacing) vertically
        // in translated coordinates, so the vertical shift is exactly
        // -mFontLineSpacingAndAscent (top gap = mFontLineSpacingAndAscent + mGridOffsetY = 0),
        // leaving a bottom gap of viewHeight - rows*lineSpacing. Horizontally the leftover
        // space is still split symmetrically. The offsets are snapped to whole pixels below so
        // that adjacent rows' background rects keep integer edges and do not show anti-aliased
        // seams. Recompute unconditionally: a sub-cell resize leaves columns/rows unchanged but
        // still changes the leftover space.
        float gridWidth = newColumns * mRenderer.mFontWidth;
        float newGridOffsetX = Math.max(0f, (viewWidth - gridWidth) / 2f);
        float newGridOffsetY = -mRenderer.mFontLineSpacingAndAscent;
        // Snap to whole pixels: with a fractional offset, adjacent rows' background rects share
        // fractional edges; anti-aliased SRC_OVER compositing then leaves 25% of the drawColor
        // background showing through each row seam (visible hairlines on non-default backgrounds).
        newGridOffsetX = Math.round(newGridOffsetX);
        newGridOffsetY = Math.round(newGridOffsetY);
        // rowToPixelTop()/invalidateRowRange() and TerminalRenderer's row layout are consistent only
        // while the grid stays pinned to the top of the view, i.e. mGridOffsetY is exactly
        // -mFontLineSpacingAndAscent (both are whole pixels here, so round() is a no-op). Assert the
        // invariant so any future re-pin (vertical centering etc.) fails loudly instead of producing
        // 1px-seam artifacts.
        assert newGridOffsetY == -mRenderer.mFontLineSpacingAndAscent : "grid must stay top-pinned";
        boolean gridOffsetChanged = (newGridOffsetX != mGridOffsetX) || (newGridOffsetY != mGridOffsetY);
        mGridOffsetX = newGridOffsetX;
        mGridOffsetY = newGridOffsetY;

        if (mEmulator == null || (newColumns != mEmulator.mColumns || newRows != mEmulator.mRows)) {
            stopFlingAndClear();
            mTermSession.updateSize(newColumns, newRows, (int) mRenderer.getFontWidth(), mRenderer.getFontLineSpacing());
            mEmulator = mTermSession.getEmulator();
            mClient.onEmulatorSet();

            // Update mTerminalCursorBlinkerRunnable inner class mEmulator on session change
            if (mTerminalCursorBlinkerRunnable != null)
                mTerminalCursorBlinkerRunnable.setEmulator(mEmulator);

            mTopRow = 0;
            scrollTo(0, 0);
            invalidate();
        } else if (gridOffsetChanged) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mEmulator == null) {
            // Use the static COLOR_SCHEME's default background as a placeholder while no emulator
            // is attached. This avoids a hardcoded 0XFF000000 being shown during the gap between
            // activity recreation (recreate / system day-night swap) and session reattachment,
            // which would otherwise flash black independent of the current night mode.
            canvas.drawColor(TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        } else {
            // render the terminal view and highlight any selected text
            int[] sel = mDefaultSelectors;
            if (mTextSelectionCursorController != null) {
                mTextSelectionCursorController.getSelectors(sel);
            }

            // The framework clips the canvas to the invalidated region. If that region covers the
            // whole view this is a full repaint; otherwise a partial (dirty-rows) repaint. Using the
            // clip as the source of truth is safe: if the clip is looser than expected we simply
            // render a few extra rows, never produce artifacts.
            boolean hasClip = canvas.getClipBounds(mClipBounds);
            Rect dirtyRect = (!hasClip || isFullRepaint(mClipBounds)) ? null : mClipBounds;
            mRenderer.render(mEmulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3],
                mGridOffsetX, mGridOffsetY, dirtyRect);

            // Text selection handles are only meaningful on a full repaint: while selecting,
            // repaintAfterUpdate() forces full repaints (and invalidateCursorCell() falls back to a
            // full invalidate), so renderTextSelection() would never have a partial clip anyway.
            if (dirtyRect == null) {
                // render the text selection handles
                renderTextSelection();
            }

            // The scrollbar track sits on the full-width right edge, and invalidateRowRange() uses
            // invalidate(0, top, getWidth(), bottom) — a full-width band. That band always covers the
            // scrollbar zone, and render() fills it with the background color, wiping any scrollbar
            // pixels in it. So drawScrollbar() must run on every frame (even partial ones) to restore
            // the thumb; gating it to full repaints would gouge the thumb out on each partial repaint.
            drawScrollbar(canvas);

            // Overscroll glow (optional, EDGE_GLOW_ENABLED): purely visual overlay, the glyph
            // content itself is never offset past the edges.
            drawEdgeGlow(canvas);
        }
    }

    /** Release both edge glows (finger up / gesture cancel). No-op when glow is disabled. */
    private void releaseEdgeGlow() {
        if (mEdgeGlowTop != null) mEdgeGlowTop.onRelease();
        if (mEdgeGlowBottom != null) mEdgeGlowBottom.onRelease();
    }

    /** Draw the optional overscroll glow over the content; keeps the animation frames going. */
    private void drawEdgeGlow(Canvas canvas) {
        if (!EDGE_GLOW_ENABLED) return;
        boolean needsInvalidate = false;
        final int width = getWidth();
        final int height = getHeight();
        if (mEdgeGlowTop != null && !mEdgeGlowTop.isFinished()) {
            final int restore = canvas.save();
            mEdgeGlowTop.setSize(width, height);
            needsInvalidate |= mEdgeGlowTop.draw(canvas);
            canvas.restoreToCount(restore);
        }
        if (mEdgeGlowBottom != null && !mEdgeGlowBottom.isFinished()) {
            final int restore = canvas.save();
            // Rotate the canvas 180° about the view centre so the glow lands on the bottom edge.
            canvas.rotate(180f, width / 2f, height / 2f);
            mEdgeGlowBottom.setSize(width, height);
            needsInvalidate |= mEdgeGlowBottom.draw(canvas);
            canvas.restoreToCount(restore);
        }
        if (needsInvalidate) {
            postInvalidateOnAnimation();
        }
    }

    private boolean isFullRepaint(Rect clip) {
        return clip.left <= 0 && clip.top <= 0 && clip.right >= getWidth() && clip.bottom >= getHeight();
    }

    /**
     * Push pre-computed scrollbar thumb colours (already alpha-blended against the appropriate
     * dark/light base) from the settings/app layer. Called once when the alpha preference changes
     * or the colour scheme is applied — the colour is computed ONE time, not on every frame.
     */
    public void setScrollbarColors(int inactiveColor, int activeColor) {
        mScrollbarInactiveColor = inactiveColor;
        mScrollbarActiveColor = activeColor;
        mScrollbarColorsSet = true;
        invalidate();
    }

    /**
     * Draw the interactive scrollbar thumb on the right edge of the view.
     * The thumb colour is pre-computed by the app layer (see {@link #setScrollbarColors(int, int)})
     * so the alpha is applied ONCE when the preference changes instead of on every frame.
     * Falls back to scheme-derived computation when the setter has not been called yet
     * (e.g. before the activity fully wires up).
     */
    private void drawScrollbar(Canvas canvas) {
        int range = getScrollbarRange();
        if (range <= 0) return; // no history → nothing to draw

        RectF thumbRect = computeThumbRect();
        if (thumbRect.width() <= 0 || thumbRect.height() <= 0) return;

        int color;
        if (mScrollbarColorsSet) {
            // Use the pre-computed colour (alpha baked in once at preference-change time).
            color = mScrollbarDragging ? mScrollbarActiveColor : mScrollbarInactiveColor;
        } else {
            // Fallback: derive from the terminal scheme background on-the-fly (old behaviour).
            int bg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
            boolean isLight = TerminalColors.getPerceivedBrightnessOfColor(bg) >= 130;
            int alpha = mScrollbarDragging ? 0x1F : 0x0D;
            int base = isLight ? 0x000000 : 0xFFFFFF;
            color = (alpha << 24) | base;
        }

        Paint paint = mScrollbarDragging ? mScrollbarThumbActivePaint : mScrollbarThumbPaint;
        paint.setColor(color);
        float radius = thumbRect.width() / 2f;
        canvas.drawRoundRect(thumbRect, radius, radius, paint);

        // Resting state gets an outline in the active colour.
        if (!mScrollbarDragging) {
            int strokeColor;
            if (mScrollbarColorsSet) {
                strokeColor = mScrollbarActiveColor;
            } else {
                int bg = TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
                boolean isLight = TerminalColors.getPerceivedBrightnessOfColor(bg) >= 130;
                strokeColor = (0x1F << 24) | (isLight ? 0x000000 : 0xFFFFFF);
            }
            mScrollbarThumbStrokePaint.setColor(strokeColor);
            canvas.drawRoundRect(thumbRect, radius, radius, mScrollbarThumbStrokePaint);
        }
    }

    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    private CharSequence getText() {
        return mEmulator.getScreen().getSelectedText(0, mTopRow, mEmulator.mColumns, mTopRow + mEmulator.mRows);
    }

    public int getCursorX(float x) {
        return (int) ((x - mGridOffsetX) / mRenderer.mFontWidth);
    }

    public int getCursorY(float y) {
        return (int) (((y - mGridOffsetY - 40) / mRenderer.mFontLineSpacing) + mTopRow);
    }

    public int getPointX(int cx) {
        if (cx > mEmulator.mColumns) {
            cx = mEmulator.mColumns;
        }
        return Math.round(cx * mRenderer.mFontWidth + mGridOffsetX);
    }

    public int getPointY(int cy) {
        return Math.round((cy - mTopRow) * mRenderer.mFontLineSpacing + mGridOffsetY);
    }

    public int getTopRow() {
        return mTopRow;
    }

    public void setTopRow(int mTopRow) {
        this.mTopRow = mTopRow;
    }

    public float getGridOffsetX() {
        return mGridOffsetX;
    }

    public float getGridOffsetY() {
        return mGridOffsetY;
    }



    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void autofill(AutofillValue value) {
        if (value.isText()) {
            mTermSession.write(value.getTextValue().toString());
        }

        resetAutoFill();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getAutofillType() {
        return mAutoFillType;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public String[] getAutofillHints() {
        return mAutoFillHints;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public AutofillValue getAutofillValue() {
        return AutofillValue.forText("");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getImportantForAutofill() {
        return mAutoFillImportance;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private synchronized void resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = AUTOFILL_TYPE_NONE;
        mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO;
        mAutoFillHints = new String[0];
    }

    public AutofillManager getAutoFillManagerService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        try {
            Context context = getContext();
            if (context == null) return null;
            return context.getSystemService(AutofillManager.class);
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e);
            return null;
        }
    }

    public boolean isAutoFillEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            return autofillManager != null && autofillManager.isEnabled();
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e);
            return false;
        }
    }

    public synchronized void requestAutoFillUsername() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_USERNAME} :
                null);
    }

    public synchronized void requestAutoFillPassword() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_PASSWORD} :
            null);
    }

    public synchronized void requestAutoFill(String[] autoFillHints) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (autoFillHints == null || autoFillHints.length < 1) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = AUTOFILL_TYPE_TEXT;
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_YES;
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints;
                autofillManager.requestAutofill(this);
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e);
        }
    }

    public synchronized void cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (mAutoFillType == AUTOFILL_TYPE_NONE) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                resetAutoFill();
                autofillManager.cancel();
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e);
        }
    }





    /**
     * Set terminal cursor blinker rate. It must be between {@link #TERMINAL_CURSOR_BLINK_RATE_MIN}
     * and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}, otherwise it will be disabled.
     *
     * The {@link #setTerminalCursorBlinkerState(boolean, boolean)} must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns {@code true} if setting blinker rate was successfully set, otherwise [@code false}.
     */
    public synchronized boolean setTerminalCursorBlinkerRate(int blinkRate) {
        boolean result;

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient.logError(LOG_TAG, "The cursor blink rate must be in between " + TERMINAL_CURSOR_BLINK_RATE_MIN + "-" + TERMINAL_CURSOR_BLINK_RATE_MAX + ": " + blinkRate);
            mTerminalCursorBlinkerRate = 0;
            result = false;
        } else {
            mClient.logVerbose(LOG_TAG, "Setting cursor blinker rate to " + blinkRate);
            mTerminalCursorBlinkerRate = blinkRate;
            result = true;
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient.logVerbose(LOG_TAG, "Cursor blinker disabled");
            stopTerminalCursorBlinker();
        }

        return result;
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if {@link #mTerminalCursorBlinkerRate} does not equal 0 and is between
     * {@link #TERMINAL_CURSOR_BLINK_RATE_MIN} and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}.
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that {@link #mEmulator} is set, otherwise wait for the
     * {@link TerminalViewClient#onEmulatorSet()} event after calling {@link #attachSession(TerminalSession)}
     * for the first session added in the activity since blinking will not start if {@link #mEmulator}
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after {@link #attachSession(TerminalSession)} since {@link #updateSize()}
     * may return without setting {@link #mEmulator} since width/height may be 0. Its called again in
     * {@link #onSizeChanged(int, int, int, int)}. Calling on onResume() if emulator is already set
     * is necessary, since onEmulatorSet() may not be called after activity is started after device
     * display timeout with double tap and not power button.
     *
     * It should also be called on the
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}
     * callback when cursor is enabled or disabled so that blinker is disabled if cursor is not
     * to be shown. It should also be checked if activity is visible if blinker is to be started
     * before calling this.
     *
     * It should also be called after terminal is reset with {@link TerminalSession#reset()} in case
     * cursor blinker was disabled before reset due to call to
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}.
     *
     * How cursor blinker starting works is by registering a {@link Runnable} with the looper of
     * the main thread of the app which when run, toggles the cursor blinking state and re-registers
     * itself to be called with the delay set by {@link #mTerminalCursorBlinkerRate}. When cursor
     * blinking needs to be disabled, we just cancel any callbacks registered. We don't run our own
     * "thread" and let the thread for the main looper do the work for us, whose usage is also
     * required to update the UI, since it also handles other calls to update the UI as well based
     * on a queue.
     *
     * Note that when moving cursor in text editors like nano, the cursor state is quickly
     * toggled `-> off -> on`, which would call this very quickly sequentially. So that if cursor
     * is moved 2 or more times quickly, like long hold on arrow keys, it would trigger
     * `-> off -> on -> off -> on -> ...`, and the "on" callback at index 2 is automatically
     * cancelled by next "off" callback at index 3 before getting a chance to be run. For this case
     * we log only if {@link #TERMINAL_VIEW_KEY_LOGGING_ENABLED} is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to {@code true}, then it will also be checked if the
     *                                 cursor is even enabled by {@link TerminalEmulator} before
     *                                 starting the cursor blinker.
     */
    public synchronized void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker();

        if (mEmulator == null) return;

        mEmulator.setCursorBlinkingEnabled(false);

        // The cursor visibility/shape may have just changed (DECSET 25 hide/show, DECSCUSR) with no
        // cell content change, so dirty-row tracking would not repaint it. Redraw the cursor cell so
        // a hidden cursor is erased and a shown one appears even when blinking is disabled.
        invalidateCursorCell();

        if (start) {
            // If cursor blinker is not enabled or is not valid
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX)
                return;
            // If cursor blinder is to be started only if cursor is enabled
            else if (startOnlyIfCursorEnabled && ! mEmulator.isCursorEnabled()) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mClient.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled");
                return;
            }

            // Start cursor blinker runnable
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate " + mTerminalCursorBlinkerRate);
            if (mTerminalCursorBlinkerHandler == null)
                mTerminalCursorBlinkerHandler = new Handler(Looper.getMainLooper());
            mTerminalCursorBlinkerRunnable = new TerminalCursorBlinkerRunnable(mEmulator, mTerminalCursorBlinkerRate);
            mEmulator.setCursorBlinkingEnabled(true);
            mTerminalCursorBlinkerRunnable.run();
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private void stopTerminalCursorBlinker() {
        if (mTerminalCursorBlinkerHandler != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Stopping cursor blinker");
            mTerminalCursorBlinkerHandler.removeCallbacks(mTerminalCursorBlinkerRunnable);
        }
    }

    private class TerminalCursorBlinkerRunnable implements Runnable {

        private TerminalEmulator mEmulator;
        private final int mBlinkRate;

        // Initialize with false so that initial blink state is visible after toggling
        boolean mCursorVisible = false;

        public TerminalCursorBlinkerRunnable(TerminalEmulator emulator, int blinkRate) {
            mEmulator = emulator;
            mBlinkRate = blinkRate;
        }

        public void setEmulator(TerminalEmulator emulator) {
            mEmulator = emulator;
        }

        public void run() {
            try {
                if (mEmulator != null) {
                    // Toggle the blink state and then invalidate() the view so
                    // that onDraw() is called, which then calls TerminalRenderer.render()
                    // which checks with TerminalEmulator.shouldCursorBeVisible() to decide whether
                    // to draw the cursor or not
                    mCursorVisible = !mCursorVisible;
                    //mClient.logVerbose(LOG_TAG, "Toggling cursor blink state to " + mCursorVisible);
                    mEmulator.setCursorBlinkState(mCursorVisible);
                    invalidateCursorCell();
                }
            } finally {
                // Recall the Runnable after mBlinkRate milliseconds to toggle the blink state
                mTerminalCursorBlinkerHandler.postDelayed(this, mBlinkRate);
            }
        }
    }



    /**
     * Define functions required for text selection and its handles.
     */
    TextSelectionCursorController getTextSelectionCursorController() {
        if (mTextSelectionCursorController == null) {
            mTextSelectionCursorController = new TextSelectionCursorController(this);

            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mTextSelectionCursorController);
            }
        }

        return mTextSelectionCursorController;
    }

    private void showTextSelectionCursors(MotionEvent event) {
        getTextSelectionCursorController().show(event);
    }

    private boolean hideTextSelectionCursors() {
        return getTextSelectionCursorController().hide();
    }

    private void renderTextSelection() {
        if (mTextSelectionCursorController != null)
            mTextSelectionCursorController.render();
    }

    public boolean isSelectingText() {
        if (mTextSelectionCursorController != null) {
            return mTextSelectionCursorController.isActive();
        } else {
            return false;
        }
    }

    /**
     * Tint the terminal text-selection drag handles to {@code color} so they match the active
     * colour scheme, mirroring the input-panel selection handles.
     */
    public void setTextSelectionHandleColor(int color) {
        getTextSelectionCursorController().setHandleColor(color);
    }

    /**
     * Tint the text-selection ActionMode CAB bar and its title text to {@code bgColor} /
     * {@code textColor} so they match the active colour scheme.
     */
    public void setTextSelectionActionModeColors(int bgColor, int textColor) {
        getTextSelectionCursorController().setActionModeColors(bgColor, textColor);
    }

    /** Get the currently selected text if selecting. */
    public String getSelectedText() {
        if (isSelectingText() && mTextSelectionCursorController != null)
            return mTextSelectionCursorController.getSelectedText();
        else
            return null;
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mTextSelectionCursorController != null ? mTextSelectionCursorController.getStoredSelectedText() : null;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        if (mTextSelectionCursorController != null) mTextSelectionCursorController.unsetStoredSelectedText();
    }

    private ActionMode getTextSelectionActionMode() {
        if (mTextSelectionCursorController != null) {
            return mTextSelectionCursorController.getActionMode();
        } else {
            return null;
        }
    }

    public void startTextSelectionMode(MotionEvent event) {
        stopFlingAndClear();

        if (!requestFocus()) {
            return;
        }

        showTextSelectionCursors(event);
        mClient.copyModeChanged(isSelectingText());

        invalidate();
    }

    public void stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient.copyModeChanged(isSelectingText());
            invalidate();
        }
    }

    private void decrementYTextSelectionCursors(int decrement) {
        if (mTextSelectionCursorController != null) {
            mTextSelectionCursorController.decrementYTextSelectionCursors(decrement);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mTextSelectionCursorController != null) {
            getViewTreeObserver().addOnTouchModeChangeListener(mTextSelectionCursorController);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(mWheelImpulseRunnable);
        mWheelImpulse.clear();
        stopFlingAndClear();

        if (mTextSelectionCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode();

            getViewTreeObserver().removeOnTouchModeChangeListener(mTextSelectionCursorController);
            mTextSelectionCursorController.onDetached();
        }
    }



    /**
     * Define functions required for long hold toolbar.
     */
    private final Runnable mShowFloatingToolbar = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void run() {
            if (getTextSelectionActionMode() != null) {
                getTextSelectionActionMode().hide(0);  // hide off.
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            int delay = ViewConfiguration.getDoubleTapTimeout();
            postDelayed(mShowFloatingToolbar, delay);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    void hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            removeCallbacks(mShowFloatingToolbar);
            getTextSelectionActionMode().hide(-1);
        }
    }

    public void updateFloatingToolbarVisibility(MotionEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getTextSelectionActionMode() != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar();
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }

    /** Listener for screen update events. */
    public interface OnScreenUpdateListener {
        void onScreenUpdated();
    }

    private OnScreenUpdateListener mOnScreenUpdateListener;

    public void setOnScreenUpdateListener(@Nullable OnScreenUpdateListener listener) {
        mOnScreenUpdateListener = listener;
    }

}
