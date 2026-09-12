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

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = event.y - downY
                val dx = event.x - downX
                if (abs(dy) > 100f && abs(dy) > abs(dx) * 1.25f) {
                    if (dy < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
                    return true
                }
            }
        }
        return false
    }
}
