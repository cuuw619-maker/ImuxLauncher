package com.imux.launcher

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

class GestureFrameLayout(context: Context) : FrameLayout(context) {
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var gestureTriggered = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureTriggered) return true
                val dy = event.y - downY
                val dx = event.x - downX
                if (abs(dy) > dp(72f) && abs(dy) > abs(dx) * 1.2f) {
                    gestureTriggered = true
                    if (dy < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestureTriggered = false
        }
        return false
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
