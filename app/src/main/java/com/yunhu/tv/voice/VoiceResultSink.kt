package com.yunhu.tv.voice

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 语音文本的唯一出口。
 *
 * 拿到文本后先打日志，再交给当前打开的聊天页（填入输入框，不自动发送）。
 * 聊天页不在前台时文本会被丢弃，并在日志里留一条记录。
 */
object VoiceResultSink {

    const val TAG = "VoiceInput"

    interface Receiver {
        /** 主线程回调 */
        fun onVoiceText(text: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var receiver: Receiver? = null

    /** 没有聊天页时暂存一条，等下次进入聊天页再填入 */
    @Volatile
    private var pending: String? = null

    fun attach(r: Receiver) {
        receiver = r
    }

    fun detach(r: Receiver) {
        if (receiver === r) receiver = null
    }

    /** 取走暂存文本（一次性） */
    fun takePending(): String? {
        val p = pending ?: return null
        pending = null
        return p
    }

    /** @return true 表示已直接交给当前聊天页；false 表示已暂存 */
    fun dispatch(text: String): Boolean {
        Log.i(TAG, "voice text -> \"$text\"")
        val r = receiver
        if (r == null) {
            pending = text
            Log.i(TAG, "no chat page attached, queued for next chat")
            return false
        }
        main.post { r.onVoiceText(text) }
        return true
    }
}
