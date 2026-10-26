package com.yunhu.tv.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFmt {

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val mdHm = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 会话列表/消息时间：今天显示 HH:mm，否则显示 MM-dd HH:mm */
    fun format(ms: Long): String {
        if (ms <= 0) return ""
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { timeInMillis = ms }
        return if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
        ) {
            hm.format(Date(ms))
        } else {
            mdHm.format(Date(ms))
        }
    }
}
