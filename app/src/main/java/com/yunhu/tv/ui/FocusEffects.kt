package com.yunhu.tv.ui

import android.view.View
import android.view.ViewGroup

/** 焦点缩放反馈：遥控器焦点进入时轻微放大 */
object FocusEffects {
    fun apply(v: View, scale: Float = 1.04f) {
        v.setOnFocusChangeListener { view, hasFocus ->
            view.animate().cancel()
            view.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(120)
                .start()
        }
    }

    fun applyToChildren(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.isFocusable) apply(c)
        }
    }
}
