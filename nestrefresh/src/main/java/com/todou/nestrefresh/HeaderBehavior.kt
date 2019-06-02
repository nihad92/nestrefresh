package com.todou.nestrefresh

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.support.design.widget.CoordinatorLayout
import android.support.v4.math.MathUtils
import android.support.v4.view.ViewCompat
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller

abstract class HeaderBehavior<V : View> : BaseBehavior<V> {
    private var flingRunnable: Runnable? = null
    private lateinit var scroller: OverScroller
    var totalSpringOffset = 0f
    private var isBeingDragged: Boolean = false
    private var activePointerId = -1
    private var lastMotionY: Int = 0
    private var touchSlop = -1
    private var velocityTracker: VelocityTracker? = null
    private var isTouching: Boolean = false
    private var child: View? = null
    private var state = STATE_COLLAPSED

    private lateinit var animator: ValueAnimator
    private var endListener: EndListener? = null
    private var refreshHeaderCallback: RefreshHeaderCallback? = null

    private var originalOffset = 0
    private var hoveringRange = UNSET
    private var hoveringOffset: Int = 0

    val topBottomOffsetForScrollingSibling: Int
        get() = getTopAndBottomOffset()

    open val maxPullRefreshDown: Int
        get() = 0

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {}

    override fun onLayoutChild(parent: CoordinatorLayout, child: V, layoutDirection: Int): Boolean {
        this.child = child
        return super.onLayoutChild(parent, child, layoutDirection)
    }

    override fun onInterceptTouchEvent(parent: CoordinatorLayout, child: V, ev: MotionEvent): Boolean {
        if (this.touchSlop < 0) {
            this.touchSlop = ViewConfiguration.get(parent.context).scaledTouchSlop
        }
        val action = ev.action
        if (action == 2 && this.isBeingDragged) {
            return true
        } else {
            val activePointerId: Int
            val pointerIndex: Int
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (childInHeaderCanScroll(child, ev.rawX, ev.rawY)) {
                        return super.onInterceptTouchEvent(parent, child, ev)
                    }
                    resetTotalSpringOffset()
                    this.isBeingDragged = false
                    activePointerId = ev.x.toInt()
                    pointerIndex = ev.y.toInt()
                    if (this.canDragView(child) && parent.isPointInChildBounds(child, activePointerId, pointerIndex)) {
                        this.lastMotionY = pointerIndex
                        this.activePointerId = ev.getPointerId(0)
                        this.ensureVelocityTracker()
                    }
                    onTouchStart()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    this.isBeingDragged = false
                    isTouching = false
                    this.activePointerId = -1
                    if (this.velocityTracker != null) {
                        this.velocityTracker!!.recycle()
                        this.velocityTracker = null
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    activePointerId = this.activePointerId
                    if (activePointerId != -1) {
                        pointerIndex = ev.findPointerIndex(activePointerId)
                        if (pointerIndex != -1) {
                            val y = ev.getY(pointerIndex).toInt()
                            val yDiff = Math.abs(y - this.lastMotionY)
                            if (yDiff > this.touchSlop) {
                                this.isBeingDragged = true
                                this.lastMotionY = y
                            }
                        }
                    }
                }
            }

            if (this.velocityTracker != null) {
                this.velocityTracker!!.addMovement(ev)
            }

            return this.isBeingDragged
        }
    }

    internal open fun childInHeaderCanScroll(view: V, x: Float, y: Float): Boolean {
        return false
    }

    override fun onTouchEvent(parent: CoordinatorLayout, child: V, ev: MotionEvent): Boolean {
        if (this.touchSlop < 0) {
            this.touchSlop = ViewConfiguration.get(parent.context).scaledTouchSlop
        }

        val activePointerIndex: Int
        val y: Int
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerIndex = ev.x.toInt()
                y = ev.y.toInt()
                if (!parent.isPointInChildBounds(child, activePointerIndex, y) || !this.canDragView(child)) {
                    return false
                }

                this.lastMotionY = y
                this.activePointerId = ev.getPointerId(0)
                this.ensureVelocityTracker()
            }
            MotionEvent.ACTION_UP -> {
                this.isTouching = false
                if (this.velocityTracker != null) {
                    this.velocityTracker!!.addMovement(ev)
                    this.velocityTracker!!.computeCurrentVelocity(1000)
                    val yvel = this.velocityTracker!!.getYVelocity(this.activePointerId)
                    this.fling(parent, child, -this.getScrollRangeForDragFling(child), 0, yvel)
                }
                this.isBeingDragged = false
                this.isTouching = false
                this.activePointerId = -1
                if (this.velocityTracker != null) {
                    this.velocityTracker!!.recycle()
                    this.velocityTracker = null
                }
                doOnCancel()
            }
            MotionEvent.ACTION_CANCEL -> {
                this.isBeingDragged = false
                this.isTouching = false
                this.activePointerId = -1
                if (this.velocityTracker != null) {
                    this.velocityTracker!!.recycle()
                    this.velocityTracker = null
                }
                doOnCancel()
            }
            MotionEvent.ACTION_MOVE -> {
                activePointerIndex = ev.findPointerIndex(this.activePointerId)
                if (activePointerIndex == -1) {
                    return false
                }

                y = ev.getY(activePointerIndex).toInt()
                var dy = this.lastMotionY - y
                if (!this.isBeingDragged && Math.abs(dy) > this.touchSlop) {
                    this.isBeingDragged = true
                    if (dy > 0) {
                        dy -= this.touchSlop
                    } else {
                        dy += this.touchSlop
                    }
                }

                if (this.isBeingDragged) {
                    this.lastMotionY = y
                    this.scroll(parent, child, dy, this.getMaxDragOffset(child), 0, ViewCompat.TYPE_TOUCH)
                }
            }
        }

        if (this.velocityTracker != null) {
            this.velocityTracker!!.addMovement(ev)
        }

        return true
    }

    private fun onTouchStart() {
        isTouching = true
        stopHeaderFlingIfNeeded()
        cancelAnimatorIfNeeded()
    }

    private fun stopHeaderFlingIfNeeded() {
        if (isTouching && this.flingRunnable != null) {
            if (child != null) child!!.removeCallbacks(this.flingRunnable)
            this.flingRunnable = null
        }
    }

    private fun doOnCancel() {
        animateBackIfNeeded()
    }

    private fun resetTotalSpringOffset() {
        totalSpringOffset = calculateScrollUnconsumed().toFloat()
    }

    private fun cancelAnimatorIfNeeded() {
        if (this::animator.isInitialized) {
            animator.takeIf { it.isRunning }?.cancel()
        }
    }

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: V,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int
    ): Boolean {
        isTouching = true
        if (axes and 2 != 0) {
            cancelAnimatorIfNeeded()
        }
        return super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, axes, type)
    }

    override fun onNestedScrollAccepted(
        coordinatorLayout: CoordinatorLayout,
        child: V,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int
    ) {
        super.onNestedScrollAccepted(coordinatorLayout, child, directTargetChild, target, axes, type)
        totalSpringOffset = calculateScrollUnconsumed().toFloat()
    }

    override fun onStopNestedScroll(coordinatorLayout: CoordinatorLayout, child: V, target: View, type: Int) {
        super.onStopNestedScroll(coordinatorLayout, child, target, type)
        isTouching = false
        animateBackIfNeeded()
    }

    private fun calculateScrollUnconsumed(): Int {
        return if (getTopAndBottomOffset() <= 0) 0 else (-Math.log((1 - getTopAndBottomOffset().toFloat() / maxPullRefreshDown).toDouble()) * maxPullRefreshDown.toDouble() * 2.0).toInt()
    }

    fun setHeaderTopBottomOffset(parent: CoordinatorLayout, header: V, newOffset: Int, type: Int): Int {
        return this.setHeaderTopBottomOffset(parent, header, newOffset, Int.MIN_VALUE, Int.MAX_VALUE, type)
    }

    fun setHeaderTopBottomOffset(
        parent: CoordinatorLayout,
        header: V,
        newOffset: Int,
        minOffset: Int,
        maxOffset: Int,
        type: Int
    ): Int {
        var newOffset = newOffset
        var curOffset = getTopAndBottomOffset()
        var consumed = 0
        val dyPre = topBottomOffsetForScrollingSibling - newOffset
        if (minOffset != 0 && curOffset >= minOffset && curOffset <= maxOffset
            && totalSpringOffset == 0f
        ) {
            newOffset = MathUtils.clamp(newOffset, minOffset, maxOffset)
            if (curOffset != newOffset) {
                setTopAndBottomOffset(newOffset)
                consumed = curOffset - newOffset
            }
        }

        if (curOffset < 0) {
            return consumed
        }
        var unConsumed = dyPre - consumed

        if (unConsumed != 0 && type == ViewCompat.TYPE_TOUCH) {
            if (unConsumed > 0 && totalSpringOffset > 0) {
                if (unConsumed > totalSpringOffset) {
                    consumed += unConsumed - totalSpringOffset.toInt()
                    totalSpringOffset = 0f
                } else {
                    totalSpringOffset -= unConsumed.toFloat()
                    consumed += unConsumed
                }
                setTopAndBottomOffset(calculateScrollOffset())
                setStateInternal(STATE_DRAGGING)
            }

            if (unConsumed < 0) {
                totalSpringOffset -= unConsumed.toFloat()
                setTopAndBottomOffset(calculateScrollOffset())
                setStateInternal(STATE_DRAGGING)
                consumed += unConsumed
            }
        }

        unConsumed = dyPre - consumed
        if (unConsumed != 0) {
            totalSpringOffset = 0f
            curOffset = getTopAndBottomOffset()
            newOffset = topBottomOffsetForScrollingSibling - unConsumed
            if (minOffset != 0 && curOffset >= minOffset && curOffset <= maxOffset) {
                newOffset = MathUtils.clamp(newOffset, minOffset, maxOffset)
                if (curOffset != newOffset) {
                    setTopAndBottomOffset(newOffset)
                    consumed += curOffset - newOffset
                }
            }
        }

        return consumed
    }

    override fun setTopAndBottomOffset(offset: Int): Boolean {
        if (refreshHeaderCallback != null) {
            refreshHeaderCallback!!.onScroll(offset, (offset - originalOffset).toFloat() / hoveringRange, state)
        }
        return super.setTopAndBottomOffset(offset)
    }

    private fun calculateScrollOffset(): Int {
        return (maxPullRefreshDown * (1f - Math.exp((-(totalSpringOffset / maxPullRefreshDown.toFloat() / 2f)).toDouble()))).toInt()
    }

    fun scroll(
        coordinatorLayout: CoordinatorLayout,
        header: V,
        dy: Int,
        minOffset: Int,
        maxOffset: Int,
        type: Int
    ): Int {
        return this.setHeaderTopBottomOffset(
            coordinatorLayout,
            header,
            this.topBottomOffsetForScrollingSibling - dy,
            minOffset,
            maxOffset,
            type
        )
    }

    fun fling(
        coordinatorLayout: CoordinatorLayout,
        layout: V,
        minOffset: Int,
        maxOffset: Int,
        velocityY: Float
    ): Boolean {
        if (this.flingRunnable != null) {
            layout.removeCallbacks(this.flingRunnable)
            this.flingRunnable = null
        }

        if (!this::scroller.isInitialized) {
            this.scroller = OverScroller(layout.context)
        }

        this.scroller.fling(0, getTopAndBottomOffset(), 0, Math.round(velocityY), 0, 0, minOffset, maxOffset)
        if (this.scroller.computeScrollOffset()) {
            this.flingRunnable = this@HeaderBehavior.FlingRunnable(coordinatorLayout, layout)
            ViewCompat.postOnAnimation(layout, this.flingRunnable)
            return true
        } else {
            this.onFlingFinished(coordinatorLayout, layout)
            return false
        }
    }

    fun onFlingFinished(parent: CoordinatorLayout, layout: V) {
        animateBackIfNeeded()
    }

    internal open fun canDragView(view: V): Boolean {
        return false
    }

    internal open fun getMaxDragOffset(view: V): Int {
        return -view.height
    }

    internal open fun getScrollRangeForDragFling(view: V): Int {
        return view.height
    }

    private fun ensureVelocityTracker() {
        if (this.velocityTracker == null) {
            this.velocityTracker = VelocityTracker.obtain()
        }

    }

    private inner class FlingRunnable internal constructor(
        private val parent: CoordinatorLayout,
        private val layout: V?
    ) : Runnable {

        override fun run() {
            if (this.layout != null) {
                if (this@HeaderBehavior.scroller.computeScrollOffset()) {
                    this@HeaderBehavior.setHeaderTopBottomOffset(
                        this.parent,
                        this.layout,
                        this@HeaderBehavior.scroller.currY,
                        ViewCompat.TYPE_NON_TOUCH
                    )
                    ViewCompat.postOnAnimation(this.layout, this)
                } else {
                    this@HeaderBehavior.onFlingFinished(this.parent, this.layout)
                }
            }

        }
    }

    private fun animateBackIfNeeded() {
        if (totalSpringOffset > 0 && !isTouching) {
            animateOffsetToState(
                if (getTopAndBottomOffset() >= hoveringOffset)
                    STATE_HOVERING
                else
                    STATE_COLLAPSED
            )
        }
    }

    fun setOriginalOffset(originalOffset: Int) {
        this.originalOffset = originalOffset
    }

    fun setHoveringRange(hoveringRange: Int) {
        this.hoveringRange = hoveringRange
        hoveringOffset = originalOffset + this.hoveringRange
    }

    private fun animateOffsetToState(endState: Int) {
        if (isTouching) {
            return
        }
        val from = getTopAndBottomOffset()
        val to = if (endState == STATE_HOVERING) hoveringOffset else originalOffset
        if (from == to || from < 0) {
            setStateInternal(endState)
            return
        } else {
            setStateInternal(STATE_SETTLING)
        }

        if (!this::animator.isInitialized) {
            animator = ValueAnimator()
            animator.duration = SPRING_ANIMATION_TIME.toLong()
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { animation -> setTopAndBottomOffset(animation.animatedValue as Int) }
            endListener = EndListener(endState)
            animator.addListener(endListener)
        } else {
            if (animator.isRunning) {
                animator.cancel()
            }
            endListener!!.setEndState(endState)
        }
        animator.setIntValues(from, to)
        animator.start()
    }

    private fun setStateInternal(state: Int) {
        if (state == this.state) {
            return
        }
        this.state = state
        if (refreshHeaderCallback != null) {
            refreshHeaderCallback!!.onStateChanged(state)
        }
    }

    fun setState(state: Int) {
        if (state != STATE_COLLAPSED && state != STATE_HOVERING) {
            throw IllegalArgumentException("Illegal state argument: $state")
        } else if (state != this.state) {
            animateOffsetToState(state)
        }
    }

    fun setSpringHeaderCallback(callback: RefreshHeaderCallback) {
        refreshHeaderCallback = callback
    }

    interface RefreshHeaderCallback {
        fun onScroll(offset: Int, fraction: Float, nextState: Int)

        fun onStateChanged(newState: Int)
    }

    private inner class EndListener(private var mEndState: Int) : AnimatorListenerAdapter() {
        private var mCanceling: Boolean = false

        fun setEndState(finalState: Int) {
            mEndState = finalState
        }

        override fun onAnimationStart(animation: Animator) {
            mCanceling = false
        }

        override fun onAnimationCancel(animation: Animator) {
            mCanceling = true
        }

        override fun onAnimationEnd(animation: Animator) {
            if (!mCanceling) {
                setStateInternal(mEndState)
            }
        }
    }

    companion object {
        private val SPRING_ANIMATION_TIME = 200

        private val INVALID_POINTER = -1

        val STATE_COLLAPSED = 1
        val STATE_HOVERING = 2
        val STATE_DRAGGING = 3
        val STATE_SETTLING = 4

        private val UNSET = Integer.MIN_VALUE
    }
}