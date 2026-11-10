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

    /** 文本来源：人工敲的可以填输入框，文件产生的描述文字只能留在面板里 */
    enum class Origin { TYPED, FILE }

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

    /** @return true 表示已直接交给当前聊天页；false 表示已暂存或仅在面板展示 */
    fun dispatch(text: String, origin: Origin = Origin.TYPED): Boolean {
        Log.i(TAG, "voice text ($origin) -> \"$text\"")
        // 文件来源产出的是调试描述文字，不是用户要说的话，不能填进聊天输入框
        if (origin == Origin.FILE) {
            Log.i(TAG, "file source, kept in debug panel only")
            return false
        }
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
