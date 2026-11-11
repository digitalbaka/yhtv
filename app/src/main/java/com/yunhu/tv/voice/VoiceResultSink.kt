package com.yunhu.tv.voice

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 语音文本的唯一出口。
 *
 * 拿到文本后先打日志，再交给当前打开的聊天页（填入输入框，不自动发送）。
 * 聊天页不在前台时按队列暂存（最多 3 条，满了丢最旧的）。
 */
object VoiceResultSink {

    const val TAG = "VoiceInput"

    private const val MAX_PENDING = 3

    /** 文本来源：人工敲的填输入框，文件产生的调试描述只留在面板里 */
    enum class Origin { TYPED, FILE, VOICE }

    interface Receiver {
        /** 主线程回调 */
        fun onVoiceText(outcome: VoiceOutcome)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var receiver: Receiver? = null

    /** 没有聊天页时暂存，等下次进入聊天页再填入；最多 3 条 */
    private val pending = ArrayDeque<VoiceOutcome>()

    fun attach(r: Receiver) {
        receiver = r
    }

    fun detach(r: Receiver) {
        if (receiver === r) receiver = null
    }

    /** 取走一条暂存结果（一次性） */
    fun takePending(): VoiceOutcome? = synchronized(pending) {
        if (pending.isEmpty()) null else pending.removeFirst()
    }

    /** @return true 表示已直接交给当前聊天页；false 表示已暂存或仅在面板展示 */
    fun dispatch(outcome: VoiceOutcome, origin: Origin = Origin.TYPED): Boolean {
        Log.i(TAG, "voice text ($origin, engine=${outcome.engine}, placeholder=${outcome.placeholder}) -> \"${outcome.text}\"")
        // 文件来源产出的是调试描述文字，不是用户要说的话，不能填进聊天输入框
        if (origin == Origin.FILE) {
            Log.i(TAG, "file source, kept in debug panel only")
            return false
        }
        val r = receiver
        if (r == null) {
            synchronized(pending) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(outcome)
            }
            Log.i(TAG, "no chat page attached, queued (size=${pending.size})")
            return false
        }
        main.post { r.onVoiceText(outcome) }
        return true
    }

    /** 语音来源（手机录音 / wav 识别）的便捷入口 */
    fun dispatchVoice(outcome: VoiceOutcome): Boolean = dispatch(outcome, Origin.VOICE)
}
