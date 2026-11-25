package com.pr0gramm.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

class FeedDrawerLayout(context: Context, attrs: AttributeSet?) : DrawerLayout(context, attrs) {
    var feedSwipeEnabled = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var trackingSwipe = false
    private var openedWithSwipe = false
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                openedWithSwipe = false
                downX = event.x
                downY = event.y
                val backGestureInset = ViewCompat.getRootWindowInsets(this)
                    ?.getInsets(WindowInsetsCompat.Type.systemGestures())?.left ?: 0
                trackingSwipe = feedSwipeEnabled && !isDrawerVisible(GravityCompat.START) &&
                        downX >= backGestureInset && downX < width / 4f
            }
            MotionEvent.ACTION_MOVE -> if (trackingSwipe && feedSwipeEnabled) {
                val horizontal = event.x - downX
                val vertical = abs(event.y - downY)
                if (vertical > touchSlop && vertical >= abs(horizontal) || horizontal < -touchSlop) {
                    trackingSwipe = false
                } else if (horizontal > 2 * touchSlop && horizontal > 2 * vertical) {
                    trackingSwipe = false
                    openedWithSwipe = true
                    openDrawer(GravityCompat.START)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                trackingSwipe = false
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (openedWithSwipe) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                openedWithSwipe = false
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
