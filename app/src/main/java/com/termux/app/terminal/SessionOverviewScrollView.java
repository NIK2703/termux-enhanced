package com.termux.app.terminal;

import com.termux.app.TermuxAppConstants;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import com.termux.view.ElasticOverdrag;

/**
 * Scroll view for the overview grid.
 *
 * <p>A normal {@link ScrollView} measures its child with an unspecified height. The grid still
 * needs to know how tall the visible viewport is so it can size a 2x2 page of cards and then
 * append extra rows. This view passes that height down before measuring.</p>
 *
 * <p>The vertical over-drag is the same elastic ("rubber band") model as the session tab strip
 * and the terminal pager: {@link ElasticHorizontalScrollView} is the horizontal adapter and this
 * is the vertical one. They share {@link ElasticOverdrag} for the pull curve, cap, spring and
 * fling impulse; only the gesture plumbing and the axis differ.</p>
 *
 * <p>A structural change can leave the grid shorter than the current scroll offset. The platform
 * clamps the scroll during layout; {@link OnContentClampListener} reports that clamp to the
 * structural transition, which folds it into the same card/scroll animation. {@link
 * #settleScrollTo(int)} remains for an opening restore or an active-card reveal, using the same
 * 250 ms decelerating curve.</p>
 *
 * <p>A grid with four or fewer cards fills the viewport without needing to scroll, but it still
 * has the same two ends as a longer grid. Its drag is therefore fed to the elastic layer as well:
 * {@link #canScrollVertically(int)} reports the fitted grid as scrollable while a touch is down,
 * which is what makes {@link ScrollView} run its normal drag tracking and call {@link
 * #overScrollBy}; the zero scroll range then turns the whole delta into a pull.</p>
 */
public class SessionOverviewScrollView extends ScrollView {

    /** Receives real scroll-position changes (not the elastic translation). */
    public interface OnScrollListener {
        void onScrollChanged(int scrollY);
    }

    /** Receives scroll-position clamps caused by a layout, before any settle is started. */
    public interface OnContentClampListener {
        void onContentClamped(int scrollBefore, int scrollAfter);
    }

    /** Called before the first touch of a gesture is dispatched to the content. */
    public interface OnTouchDownListener {
        void onTouchDown();
    }

    @Nullable
    private OnScrollListener mOnScrollListener;
    @Nullable
    private OnContentClampListener mOnContentClampListener;
    @Nullable
    private OnTouchDownListener mOnTouchDownListener;

    /** The first (top) end: the content is pulled DOWN, so translationY > 0. */
    private static final int DIRECTION_TOP = -1;

    /** The last (bottom) end: the content is pulled UP, so translationY < 0. */
    private static final int DIRECTION_BOTTOM = 1;

    /**
     * How long the scroller may be silent before later frames are no longer assumed to belong to
     * this view's fling. Mirrors {@link TermuxAppConstants.Overview#FLING_IDLE_MS}.
     */
    private static final long FLING_IDLE_NANOS =
            TermuxAppConstants.Overview.FLING_IDLE_MS * 1_000_000L;

    /** How long a programmatic reveal scroll takes. */
    private static final long SCROLL_SETTLE_DURATION_MS = 250L;

    /** Decelerating curve shared by programmatic reveal scrolls; stateless. */
    private static final PathInterpolator SCROLL_SETTLE_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0.2f, 1f);

    /** Touch slop for deciding a fitted grid's gesture is vertical before ScrollView takes it. */
    private final int mTouchSlop;

    /**
     * True from {@code ACTION_DOWN} until the matching up/cancel. A fitted grid is reported as
     * scrollable only while a touch is down; at rest it keeps the honest "nothing to scroll"
     * state.
     */
    private boolean mTouchGestureActive;

    /** Down position of the primary pointer; keeps horizontal card swipes from being stolen. */
    private float mTouchDownX;
    private float mTouchDownY;
    private int mTouchDownPointerId = MotionEvent.INVALID_POINTER_ID;

    /** Accumulated (undamped) finger travel past the top / bottom end, in px. Never negative. */
    private float mTopRawPx;
    private float mBottomRawPx;

    /** The damped displacement currently applied as the content's {@code translationY}, in px. */
    private float mTranslationPx;

    /**
     * Extra content translation used while a programmatic scroll glides to a new position, in px.
     * Positive moves the grid down, negative up. The real scroll position is already at the
     * target, so this offset keeps the first frame exactly where the user was looking and then
     * eases to zero. Zero when idle.
     */
    private float mScrollSettlePx;

    /**
     * Structural offset owned by the layout-transition coordinator. Unlike the two settle fields
     * above, this one has no animator inside the scroll view: one external animator drives card
     * translations and this offset together.
     */
    private float mProgrammaticOffsetPx;

    /** The last {@code translationY} written to the grid, including all offsets above. */
    private float mContentTranslationPx;

    @Nullable
    private ValueAnimator mScrollSettle;

    @Nullable
    private ValueAnimator mSpring;

    /** The fly-out of a fling impact (the band being stretched); null unless one is running. */
    @Nullable
    private ValueAnimator mImpact;

    // ── fling bookkeeping ──────────────────────────────────────────────────────────────────
    // One flag per hole in "a fling ran into the end of the list":
    //   mFlingFrameSeen   — a fling frame arrived since the last settle check (still travelling),
    //   mFlingImpactSpent — the one bounce per fling has already been played,
    //   mDragPullAbsorbed — the release that launched this fling already held a pull, so the
    //                       impulse must not be stacked on top of it.
    private boolean mFlingFrameSeen;
    private boolean mFlingImpactSpent;
    private boolean mDragPullAbsorbed;

    /**
     * True while the scroller frames arriving are a fling this view launched. Armed by
     * {@link #fling(int)} and disarmed after {@link #TermuxAppConstants.Overview.FLING_IDLE_MS} of silence, so a programmatic
     * scroll finishing on an end is not mistaken for an impact.
     */
    private boolean mFlingArmed;

    /** Timestamp of the previous fling frame, for {@link #onFlingFrame}'s speed measurement. */
    private long mLastFlingFrameNanos;

    /** Last fling activity, used only by the disarm timer; distinct from the speed timestamp. */
    private long mFlingLastActivityNanos;

    /** True while a settle check is already posted, so fling frames do not repost it. */
    private boolean mSettleCheckPending;

    /**
     * The speed the fling is carrying, in px/s — refreshed by every frame the list absorbed and
     * left alone by the frames it did not, so it still holds the last speed the fling had when a
     * frame leaves the grid pinned to an end.
     */
    private float mFlingArrivalPxPerSec;

    /** The velocity the fling was launched with, as handed to {@link #fling(int)}. */
    private float mFlingLaunchVelocityPx;

    /** Deferred {@link #settleNow()} — see {@link #ensureSettled()}. */
    private final Runnable mSettleRunnable = this::settleNow;

    /**
     * {@link #mFlingArmed}'s expiry. Fling frames only refresh a timestamp, so this runnable is
     * posted once and reposts itself while frames keep arriving instead of being removed and
     * re-posted from every frame.
     */
    private final Runnable mFlingDisarm = this::disarmFling;

    /** Repost while fling frames keep arriving; otherwise let the armed state lapse. */
    private void disarmFling() {
        if (System.nanoTime() - mFlingLastActivityNanos < FLING_IDLE_NANOS) {
            postOnAnimationDelayed(mFlingDisarm, TermuxAppConstants.Overview.FLING_IDLE_MS);
        } else {
            mFlingArmed = false;
        }
    }

    public SessionOverviewScrollView(Context context) {
        this(context, null);
    }

    public SessionOverviewScrollView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SessionOverviewScrollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SessionOverviewScrollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
                                     int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        // The platform's edge glow / stretch would stack on top of the elastic displacement.
        // OVER_SCROLL_NEVER also pins View#overScrollBy's clamp to exactly [0, scrollRange],
        // which makes the unconsumed-delta arithmetic in overScrollBy() exact.
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The overview is a full-screen surface with no need for a scroll affordance; the card
        // grid itself is the content. Removing the bar also avoids a strip appearing over the
        // right-most card while the grid is pulled.
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** Set the listener for real scroll changes; pass {@code null} to detach it. */
    public void setOnScrollListener(@Nullable OnScrollListener listener) {
        mOnScrollListener = listener;
    }

    /** Set the listener notified when a layout pass clamps the real scroll offset. */
    public void setOnContentClampListener(@Nullable OnContentClampListener listener) {
        mOnContentClampListener = listener;
    }

    /** Set the listener that lands a structural transition before a new touch gesture starts. */
    public void setOnTouchDownListener(@Nullable OnTouchDownListener listener) {
        mOnTouchDownListener = listener;
    }

    /**
     * Set the structural offset owned by the one layout-transition animator.
     *
     * <p>The real scroll offset is already at its target; this value only keeps the first frame
     * visually where it was and eases to zero. It is deliberately not animated here.</p>
     */
    public void setProgrammaticScrollOffsetPx(float px) {
        final float value = isFinite(px) ? px : 0f;
        if (mProgrammaticOffsetPx == value) return;
        mProgrammaticOffsetPx = value;
        applyContentTranslation();
    }

    public float getProgrammaticScrollOffsetPx() {
        return mProgrammaticOffsetPx;
    }

    /** Visual displacement currently applied to the grid by every offset source. */
    public float getContentVisualOffsetPx() {
        return mTranslationPx + mScrollSettlePx + mProgrammaticOffsetPx;
    }

    /**
     * Displacement from settle and structural sources only, excluding the elastic finger pull.
     * A layout transition folds this into its own offset when it takes ownership of the grid.
     */
    float getSettleOffsetPx() {
        return mScrollSettlePx + mProgrammaticOffsetPx;
    }

    /** Drop all settle state and the structural offset without animating. */
    public void finishContentTransition() {
        cancelScrollSettleAnimator();
        mScrollSettlePx = 0f;
        mProgrammaticOffsetPx = 0f;
        applyContentTranslation();
    }

    /**
     * Stop any running scroll settle without moving the grid. The reached displacement is kept as
     * a static structural offset so a caller can capture the exact visual bounds that are on
     * screen (selection) before the offset is dropped later.
     */
    public void freezeContentTransition() {
        cancelScrollSettleAnimator();
        mProgrammaticOffsetPx += mScrollSettlePx;
        mScrollSettlePx = 0f;
        applyContentTranslation();
    }

    /**
     * Glide the content to a new scroll offset with a 250 ms decelerating curve.
     *
     * <p>The platform's {@link #smoothScrollTo(int, int)} would use a different scroller and can
     * be interrupted by a layout-driven {@code scrollTo}. Here the real offset is moved to the
     * target in one step, then the grid is translated back by the same distance and eased to
     * zero. The first frame therefore shows exactly what was on screen before.</p>
     *
     * @param target requested scroll offset; clamped to the current scroll range
     * @return true when an animation was started
     */
    public boolean settleScrollTo(int target) {
        final int maxScroll = Math.max(0,
                computeVerticalScrollRange() - computeVerticalScrollExtent());
        final int clamped = Math.max(0, Math.min(maxScroll, target));
        final int current = getScrollY();
        // The real offset is already at the target while a scroll settle is running. Do not
        // restart its clock when the add path asks for the same position again a frame later.
        if (mScrollSettle != null && clamped == current) return true;

        // Keep whatever is currently on screen, including a structural offset, then hand the
        // combined displacement to the scroll settle.
        final float visualOffset = mScrollSettlePx + mProgrammaticOffsetPx;
        cancelScrollSettleAnimator();
        mProgrammaticOffsetPx = 0f;
        mScrollSettlePx = visualOffset + (clamped - current);
        scrollTo(0, clamped);
        if (mScrollSettlePx == 0f) {
            applyContentTranslation();
            return false;
        }
        applyContentTranslation();

        final ValueAnimator animator = ValueAnimator.ofFloat(mScrollSettlePx, 0f);
        animator.setDuration(SCROLL_SETTLE_DURATION_MS);
        animator.setInterpolator(SCROLL_SETTLE_INTERPOLATOR);
        animator.addUpdateListener(animation -> {
            if (mScrollSettle != animation) return;
            mScrollSettlePx = (Float) animation.getAnimatedValue();
            applyContentTranslation();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mScrollSettle != animation) return;
                mScrollSettle = null;
                mScrollSettlePx = 0f;
                applyContentTranslation();
            }
        });
        mScrollSettle = animator;
        animator.start();
        return true;
    }

    /** True while any settle, including a structural transition, is moving the grid. */
    public boolean isContentSettling() {
        return mScrollSettle != null || mProgrammaticOffsetPx != 0f;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final int before = getScrollY();
        super.onLayout(changed, left, top, right, bottom);
        final int after = getScrollY();
        if (mOnContentClampListener != null && before != after) {
            mOnContentClampListener.onContentClamped(before, after);
        }
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (mOnScrollListener != null) mOnScrollListener.onScrollChanged(top);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final View child = getChildAt(0);
        if (child instanceof SessionOverviewGridView) {
            ((SessionOverviewGridView) child).setViewportHeight(
                    MeasureSpec.getSize(heightMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    // ── gesture input ──────────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>{@link ScrollView} refuses to start a drag when it has nowhere to scroll. A grid that
     * fits the viewport is exactly that case, but it still needs the drag for its elastic pull.
     * Reporting it as scrollable only for the duration of a touch lets ScrollView keep owning the
     * gesture plumbing; {@link #overScrollBy} then sees a zero range and treats the whole delta as
     * an end pull. At rest the view keeps reporting the truth, so accessibility and focus code see
     * a grid with nothing to scroll.</p>
     */
    @Override
    public boolean canScrollVertically(int direction) {
        if (super.canScrollVertically(direction)) return true;
        return direction != 0 && mTouchGestureActive && !hasScrollableContent();
    }

    /**
     * {@inheritDoc}
     *
     * <p>On a fitted grid the vertical drag belongs to the elastic layer, but a horizontal card
     * swipe must still reach the card. ScrollView's own interception test looks only at vertical
     * distance, so a diagonal swipe would be stolen before the card could start its drag. Track
     * the primary pointer and refuse interception until vertical movement dominates; once it
     * does, ScrollView runs its normal path with the zero-range delta handled by
     * {@link #overScrollBy}.</p>
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!hasScrollableContent()) {
            final int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mTouchDownX = ev.getX();
                mTouchDownY = ev.getY();
                mTouchDownPointerId = ev.getPointerId(0);
            } else if (action == MotionEvent.ACTION_MOVE) {
                final int index = ev.findPointerIndex(mTouchDownPointerId);
                if (index >= 0) {
                    final float dx = Math.abs(ev.getX(index) - mTouchDownX);
                    final float dy = Math.abs(ev.getY(index) - mTouchDownY);
                    if (dy <= mTouchSlop || dx > dy) return false;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mTouchDownPointerId = MotionEvent.INVALID_POINTER_ID;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    /** True when the grid is taller than the viewport, i.e. the scroll range is non-zero. */
    private boolean hasScrollableContent() {
        final View child = getChildAt(0);
        if (child == null) return false;
        return child.getHeight() > getHeight() - getPaddingTop() - getPaddingBottom();
    }

    /**
     * The single hook into the grid's own scrolling. Called with the delta the widget is about to
     * apply, before it is clamped — the only moment the unconsumed part is still visible.
     * {@code maxOverScrollY} is ignored: with {@link #OVER_SCROLL_NEVER} the framework forces it
     * to 0, so honouring a non-zero margin here would let the framework scroll past the end and
     * count it as finger travel as well.
     *
     * <p>A grid with four or fewer cards fits the viewport, so {@code scrollRangeY} is 0. A touch
     * frame then has no real scroll offset to consume and the whole delta is finger travel past
     * an end, which feeds the same pull curve as a longer grid. Non-touch frames (a fling) stay
     * off in that case: with no range there is no arrival speed to absorb. Runtime rather than
     * one-off at attach time because the range changes as sessions come and go.</p>
     */
    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
                                   int scrollRangeX, int scrollRangeY,
                                   int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        if (deltaY != 0) {
            if (scrollRangeY > 0) {
                final int wanted = scrollY + deltaY;
                final int clamped = Math.max(0, Math.min(scrollRangeY, wanted));
                // Where the grid will actually stop. The difference is finger travel it had
                // nowhere to put — the over-pull — and the rest is travel it absorbed, i.e. real
                // scroll.
                final int unconsumedPx = Math.abs(wanted - clamped);
                final int consumedPx = Math.abs(deltaY) - unconsumedPx;
                // Direction from the delta (direction of travel), not from where the frame ends
                // up: a clamped fling finishes with `wanted` exactly on an end, and a sign test
                // on `wanted` would read a fling into the top as a bottom-end pull.
                final int direction = deltaY < 0 ? DIRECTION_TOP : DIRECTION_BOTTOM;
                if (isTouchEvent) {
                    if (unconsumedPx > 0) onDragDelta(direction, consumedPx, unconsumedPx);
                    else bleedIntoScroll(consumedPx);
                } else {
                    onFlingFrame(direction, Math.abs(deltaY), consumedPx, clamped, scrollRangeY);
                }
            } else if (isTouchEvent && scrollY == 0) {
                // The grid fits the viewport: there is no real scroll offset to consume, so every
                // pixel of the frame is finger travel past an end. Feed it to the same pull curve.
                final int direction = deltaY < 0 ? DIRECTION_TOP : DIRECTION_BOTTOM;
                onEdgePull(direction, Math.abs(deltaY));
            }
        }
        final boolean handled = super.overScrollBy(deltaX, deltaY, scrollX, scrollY,
                scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent);
        return handled;
    }

    /**
     * One drag frame that ran past an end. A frame can do both at once — the last of the scroll
     * and the first of the pull — when the finger crosses the boundary mid-frame. The scroll half
     * is handed back first, so the pull the frame leaves behind is measured from the boundary
     * rather than from the frame's start.
     */
    private void onDragDelta(int direction, int consumedPx, int unconsumedPx) {
        if (consumedPx > 0) bleedIntoScroll(consumedPx);
        if (unconsumedPx > 0) onEdgePull(direction, unconsumedPx);
    }

    /**
     * One fling frame: either still travelling (bleed + record speed) or left pinned to an end —
     * the impulse {@link #onEdgeAbsorb} wants.
     *
     * <p>A {@link ScrollView} keeps its {@code OverScroller} private, so the speed is measured:
     * refreshed from {@code |deltaY| / dt} only on frames the grid absorbed (for a clamped fling
     * that last in-range frame is the arrival speed), falling back to the launch velocity when a
     * flick started on an end and never had an in-range frame. Measuring the pinned frame's own
     * delta would read the scroller's leftover as a frame step and saturate the band on every
     * bounce.</p>
     */
    private void onFlingFrame(int direction, int requestedPx, int consumedPx,
                              int clampedPosition, int scrollRangeY) {
        mFlingFrameSeen = true;

        final long now = System.nanoTime();
        mFlingLastActivityNanos = now;
        final float dtSeconds = mLastFlingFrameNanos == 0L
                ? 0f : (now - mLastFlingFrameNanos) / 1_000_000_000f;
        mLastFlingFrameNanos = now;

        if (consumedPx > 0) {
            // In range: one frame's travel over that frame's own duration is a real speed.
            if (dtSeconds > 0f && isFinite(dtSeconds)) {
                mFlingArrivalPxPerSec = requestedPx / dtSeconds;
            }
            bleedIntoScroll(consumedPx);
            // It is a fling giving the pull back — nothing else will notice if this one stops
            // before the displacement reaches 0. A normal in-range fling has no displacement, so
            // posting the settle check every frame would only keep a no-op callback alive.
            if (mTranslationPx != 0f) ensureSettled();
        }

        if (mFlingImpactSpent || mDragPullAbsorbed || !mFlingArmed) return;
        // "At an end": the position is pinned to one of the two ends — landed exactly on one or
        // was clamped back onto one. Range 0 never arrives here: overScrollBy() gates on it.
        if (clampedPosition != 0 && clampedPosition != scrollRangeY) return;

        final float velocity = mFlingArrivalPxPerSec > 0f
                ? mFlingArrivalPxPerSec
                : Math.abs(mFlingLaunchVelocityPx);
        mFlingImpactSpent = true;
        onEdgeAbsorb(direction, velocity);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Handled here rather than in {@link #onTouchEvent} because this is the only place that
     * sees the whole gesture: a tap on a card never reaches {@code onTouchEvent}, so a release
     * hooked there would leave the previous pull hanging. The rest runs after {@code super}, so
     * the fling launched on ACTION_UP is registered before the spring is armed and the held pull
     * is recorded before {@link #onEdgeRelease} consumes it.</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mTouchGestureActive = true;
            if (mOnTouchDownListener != null) {
                // A tap on a card never reaches onTouchEvent, so finish the structural transition
                // here before the card/drag controller sees the gesture.
                mOnTouchDownListener.onTouchDown();
            }
        }
        final boolean handled = super.dispatchTouchEvent(ev);
        if (action == MotionEvent.ACTION_DOWN) {
            mDragPullAbsorbed = false;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mTouchGestureActive = false;
            mTouchDownPointerId = MotionEvent.INVALID_POINTER_ID;
            if (action == MotionEvent.ACTION_CANCEL) {
                reset();
            } else {
                // Read before the release zeroes the accumulators: this release is already being
                // answered by the pull's own spring, so the fling it launched must not add an
                // impulse on top.
                mDragPullAbsorbed = mTranslationPx != 0f;
                onEdgeRelease();
            }
        }
        return handled;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the start of a fling: its launch velocity, the state that makes the bounce happen
     * at most once per fling, and the lifetime of {@link #mFlingArmed}.</p>
     */
    @Override
    public void fling(int velocityY) {
        mFlingLaunchVelocityPx = velocityY;
        mFlingArrivalPxPerSec = 0f;
        mFlingImpactSpent = false;
        mFlingFrameSeen = false;
        mFlingArmed = true;
        mLastFlingFrameNanos = 0L;
        mFlingLastActivityNanos = System.nanoTime();
        postOnAnimationDelayed(mFlingDisarm, TermuxAppConstants.Overview.FLING_IDLE_MS);
        super.fling(velocityY);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A programmatic scroll repositions the grid for a reason unrelated to the finger
     * ({@code SessionOverviewController} drives {@code scrollTo} to reveal the active card), so a
     * held pull is released into its spring rather than left hanging. Gated on the position
     * actually moving, because {@code ScrollView#onLayout} ends by calling this with the current
     * values — a no-op that must not be read as a reposition.</p>
     */
    @Override
    public void scrollTo(int x, int y) {
        final int before = getScrollY();
        super.scrollTo(x, y);
        if (getScrollY() != before && mTranslationPx != 0f) {
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            startSpring(mTranslationPx);
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDetachedFromWindow() {
        // A view pulled out of the window can no longer be sprung back, and whatever displacement
        // it was holding must not survive into the next attachment.
        cancelScrollSettleAnimator();
        mScrollSettlePx = 0f;
        mProgrammaticOffsetPx = 0f;
        mTouchGestureActive = false;
        mTouchDownPointerId = MotionEvent.INVALID_POINTER_ID;
        reset();
        super.onDetachedFromWindow();
    }

    // ── gesture input, physics side ────────────────────────────────────────────────────────

    /**
     * The finger travelled {@code deltaPx} further past one end. Always positive — an increasing
     * pull; {@code direction} is {@link #DIRECTION_TOP} (content moves down) or
     * {@link #DIRECTION_BOTTOM} (content moves up).
     */
    private void onEdgePull(int direction, float deltaPx) {
        if (!(deltaPx > 0f) || !isFinite(deltaPx)) return;
        cancelAnimators();
        // A new pull first pays off whatever the opposite end still holds, so "at most one end
        // is ever displaced" holds by construction.
        if (direction == DIRECTION_TOP) {
            float returned = Math.min(mBottomRawPx, deltaPx);
            mBottomRawPx = clampRaw(mBottomRawPx - returned);
            mTopRawPx = clampRaw(mTopRawPx + deltaPx - returned);
        } else {
            float returned = Math.min(mTopRawPx, deltaPx);
            mTopRawPx = clampRaw(mTopRawPx - returned);
            mBottomRawPx = clampRaw(mBottomRawPx + deltaPx - returned);
        }
        apply();
    }

    /** Finger lifted (or the gesture was cancelled): spring back to the end. */
    private void onEdgeRelease() {
        if (mTranslationPx == 0f) {
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            return;
        }
        mTopRawPx = 0f;
        mBottomRawPx = 0f;
        startSpring(mTranslationPx);
    }

    /**
     * A fling ran into the end: convert a slice of its arrival speed into extra pull on the end
     * that was actually hit, then spring back. Same {@link ElasticOverdrag#impact} call as the
     * tab strip and pager, so a given gesture thresholds and caps identically on every surface.
     */
    private void onEdgeAbsorb(int direction, float velocityPxPerSec) {
        if (!(velocityPxPerSec > 0f) || !isFinite(velocityPxPerSec)) {
            onEdgeRelease();
            return;
        }
        cancelAnimators();
        final ElasticOverdrag.Impact impact =
                ElasticOverdrag.impact(velocityPxPerSec, extentPx(), 0f, density());
        final float base = (direction == DIRECTION_TOP) ? mTopRawPx : mBottomRawPx;
        startImpact(direction, base, impact);
    }

    /**
     * Play the fly-out half of an impact: the band being stretched by the impulse. Samples are
     * raw travel integrated in displacement, so the content follows the band being loaded and
     * starts at the impulse's own speed. The last sample hands the stretch to
     * {@link #onEdgeRelease()}.
     */
    private void startImpact(int direction, float baseRaw,
                             @Nullable ElasticOverdrag.Impact impact) {
        cancelAnimators();
        if (impact == null || impact.durationMs <= 0L || !(impact.peakTravelPx() > 0f)) {
            onEdgeRelease();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(impact.durationMs);
        // Linear: the samples ARE the timing. Shared instance — allocating one per fling for an
        // interpolator that is, by definition, stateless is pointless.
        animator.setInterpolator(ElasticOverdrag.LINEAR);
        animator.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            if (!isFinite(progress)) return;
            float raw = clampRaw(baseRaw + impact.travelAt(progress));
            if (direction == DIRECTION_TOP) mTopRawPx = raw;
            else mBottomRawPx = raw;
            apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mImpact = null;
                onEdgeRelease();
            }
        });
        mImpact = animator;
        animator.start();
    }

    /**
     * The grid scrolled for real by {@code giveBackPx}: hand the held pull back one pixel of
     * finger travel per pixel of scroll, so a pull-then-return gesture stays continuous. Payback
     * goes to the raw accumulator, never the damped value — the pull curve is concave, so
     * subtracting from the displacement would pay back faster than the finger bought it.
     */
    private void bleedIntoScroll(float giveBackPx) {
        if (mTranslationPx == 0f) return;
        cancelAnimators();
        // Only the side that currently holds the displacement can be giving anything back.
        if (mTranslationPx > 0f) mTopRawPx = clampRaw(mTopRawPx - giveBackPx);
        else mBottomRawPx = clampRaw(mBottomRawPx - giveBackPx);
        // The damped value follows from the raw, so it is re-derived rather than decremented.
        apply();
    }

    // ── displacement ───────────────────────────────────────────────────────────────────────

    private void apply() {
        // One height read for the whole frame: damp() would otherwise read extentPx() twice and
        // setTranslation() a third time.
        final float h = extentPx();
        setTranslation(damp(mTopRawPx, h) - damp(mBottomRawPx, h), h);
    }

    /** {@link #setTranslation(float, float)} with the height read fresh. */
    private void setTranslation(float px) {
        setTranslation(px, extentPx());
    }

    /**
     * Write the elastic component of the content's translation. It is finite and bounded by the
     * pull cap; the shrink settle is applied on top in {@link #applyContentTranslation()}.
     */
    private void setTranslation(float px, float heightPx) {
        px = PagerOverscrollController.coerceTranslation(px, heightPx);
        mTranslationPx = px;
        applyContentTranslation();
    }

    /**
     * Write the single {@code translationY} the grid can have: the elastic pull, the programmatic
     * reveal settle and the structural offset. Routing every writer through one place keeps a
     * settle that is still running from being overwritten by a pull, or the other way round.
     */
    private void applyContentTranslation() {
        float px = mTranslationPx + mScrollSettlePx + mProgrammaticOffsetPx;
        if (!isFinite(px)) {
            // Every input is individually finite; this is belt and braces against a bad animator
            // sample poisoning the view's transform.
            mTranslationPx = 0f;
            mScrollSettlePx = 0f;
            mProgrammaticOffsetPx = 0f;
            px = 0f;
        }
        // An unchanged translation must not dirty the RenderNode. Every settle/reset ends here,
        // and most of them are writing a value that is already on screen.
        if (px == mContentTranslationPx) return;
        mContentTranslationPx = px;
        View content = getChildAt(0);
        if (content != null) content.setTranslationY(px);
    }

    /**
     * The grid's extent along the drag axis — the viewport height; every scale of the effect is a
     * fraction of it. Read on demand, not cached, so rotation / split-screen / density changes
     * reflow the physics instead of keeping a stale pixel value.
     */
    private float extentPx() {
        return Math.max(1f, getHeight());
    }

    private float maxPullPx() {
        return ElasticOverdrag.maxPull(extentPx());
    }

    /** The display density, read on demand so a move to another display is picked up. */
    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    /** {@link #damp(float)} against an extent the caller already has — see {@link #apply()}. */
    private float damp(float rawPx, float heightPx) {
        return PagerOverscrollController.dampStatic(rawPx, heightPx);
    }

    /** Inverse of {@link #damp(float)}: the raw travel that produces {@code dampedPx}. */
    private float inverseDamp(float dampedPx) {
        return PagerOverscrollController.inverseDampStatic(dampedPx, extentPx());
    }

    /** Keep the raw accumulator finite and bounded: positive, never NaN/Infinity, never > cap. */
    private float clampRaw(float rawPx) {
        return PagerOverscrollController.clampRawStatic(rawPx, extentPx());
    }

    private static boolean isFinite(float v) {
        return PagerOverscrollController.isFinite(v);
    }

    /** Stop the programmatic-scroll animator without touching the offset it has reached. */
    private void cancelScrollSettleAnimator() {
        final ValueAnimator settle = mScrollSettle;
        mScrollSettle = null;
        if (settle == null) return;
        settle.removeAllUpdateListeners();
        settle.removeAllListeners();
        settle.cancel();
    }

    // ── the "never displaced while idle" invariant ─────────────────────────────────────────

    /**
     * Guarantee the grid is never left displaced once the gesture is over.
     *
     * <p>{@link #bleedIntoScroll} strips animation listeners before cancelling (so the content
     * does not snap mid-gesture), which also removes the only guaranteed writer of a clean 0; a
     * fling can keep the bleed running long after the finger is gone. So after every fling frame
     * that gives the pull back we post a check for leftover displacement, re-posting while frames
     * keep arriving ({@link #mFlingFrameSeen}). The check <em>animates</em> what it finds rather
     * than zeroing it — a clean 0 would be a visible snap of up to the full cap.
     */
    private void ensureSettled() {
        if (mSettleCheckPending) return;
        mSettleCheckPending = true;
        postOnAnimation(mSettleRunnable);
    }

    private void settleNow() {
        mSettleCheckPending = false;
        final boolean flingStillRunning = mFlingFrameSeen;
        mFlingFrameSeen = false;
        if (mSpring != null || mImpact != null) return;
        if (flingStillRunning) {
            // A fling frame arrived since the last check, so the scroll is still in flight and
            // may still be holding the displacement. Look again next frame instead of deciding.
            ensureSettled();
            return;
        }
        if (mTranslationPx != 0f) {
            // Displaced with no animator: the return was cancelled mid-flight. Hand the leftover
            // to the spring instead of zeroing it — an instant reset would snap up to the cap.
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            startSpring(mTranslationPx);
            return;
        }
        // A raw accumulator on its own can never be on screen (a non-zero raw always damps to a
        // non-zero displacement), so there is nothing to animate — just drop it.
        if (mTopRawPx != 0f || mBottomRawPx != 0f) reset();
    }

    // ── spring back ────────────────────────────────────────────────────────────────────────

    private void startSpring(float from) {
        cancelAnimators();
        if (!isFinite(from) || from == 0f) {
            // Nothing sane to animate from: drop the displacement instead of animating NaN.
            mTopRawPx = 0f;
            mBottomRawPx = 0f;
            setTranslation(0f);
            return;
        }
        float max = maxPullPx();
        from = Math.max(-max, Math.min(max, from));
        ValueAnimator animator = ValueAnimator.ofFloat(from, 0f);
        animator.setDuration(ElasticOverdrag.SPRING_DURATION_MS);
        animator.setInterpolator(ElasticOverdrag.SPRING);
        animator.addUpdateListener(animation ->
                setTranslation((Float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSpring = null;
                mTopRawPx = 0f;
                mBottomRawPx = 0f;
                setTranslation(0f);
            }
        });
        mSpring = animator;
        animator.start();
    }

    /** Drop any held displacement right now, without animating. */
    private void reset() {
        cancelAnimators();
        mSettleCheckPending = false;
        removeCallbacks(mSettleRunnable);
        removeCallbacks(mFlingDisarm);
        mFlingFrameSeen = false;
        mTopRawPx = 0f;
        mBottomRawPx = 0f;
        setTranslation(0f);
    }

    /** Stop both the fly-out and the return, leaving the displacement exactly where it is. */
    private void cancelAnimators() {
        cancelImpact();
        cancelSpring();
    }

    private void cancelImpact() {
        ValueAnimator impact = mImpact;
        mImpact = null;
        if (impact == null) return;
        // Same reasoning as the spring: cancel() would deliver onAnimationEnd, which releases
        // into the spring and would fight whatever is taking over.
        impact.removeAllUpdateListeners();
        impact.removeAllListeners();
        impact.cancel();
    }

    private void cancelSpring() {
        ValueAnimator spring = mSpring;
        mSpring = null;
        if (spring == null) return;
        // Unhook before cancelling: cancel() fires onAnimationEnd too, which would snap the
        // content to the boundary mid-gesture.
        spring.removeAllUpdateListeners();
        spring.removeAllListeners();
        spring.cancel();
        // The spring drove translationY directly, so the accumulators are stale — re-seed them
        // so a pull that interrupts the bounce continues from the position on screen.
        if (mTopRawPx == 0f && mBottomRawPx == 0f && mTranslationPx != 0f) {
            if (mTranslationPx > 0f) mTopRawPx = inverseDamp(mTranslationPx);
            else mBottomRawPx = inverseDamp(-mTranslationPx);
        }
    }
}
