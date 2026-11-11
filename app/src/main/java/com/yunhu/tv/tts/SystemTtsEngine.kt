package com.yunhu.tv.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yunhu.tv.App
import java.util.Locale
import java.util.UUID

/**
 * 系统 TTS 引擎（默认）。
 *
 * 引擎缺失 / 初始化失败时只回调提示，不抛异常、不崩溃。
 */
class SystemTtsEngine : TtsEngine {

    override val id: String = ID

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed: String? = null
    private var rate: Float = 1.0f
    /** 引擎还在初始化时先缓存，就绪后按顺序补读，避免第一条消息被吞掉 */
    private val pending = ArrayList<String>()

    override fun prepare(voiceName: String?, rate: Float, onReady: (Boolean) -> Unit) {
        this.rate = rate.coerceIn(0.5f, 2.0f)
        val ctx: Context = App.instance
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine == null) {
                    failed = "系统语音引擎初始化失败"
                    onReady(false)
                    return@TextToSpeech
                }
                engine.language = Locale.CHINA
                if (voiceName != null && voiceName.isNotEmpty()) {
                    runCatching {
                        engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
                    }
                }
                engine.setSpeechRate(this.rate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("老 API")
                    override fun onError(utteranceId: String?) {}
                })
                ready = true
                flushPending()
                onReady(true)
            } else {
                failed = "系统没有可用的 TTS 引擎"
                onReady(false)
            }
        }
    }

    override fun speak(text: String): Boolean {
        val engine = tts
        if (failed != null) return false
        if (!ready || engine == null) {
            // 初始化还没完成：先排队，就绪后补读（不丢弃第一条消息）
            if (pending.size < 8) pending.add(text)
            return true
        }
        engine.setSpeechRate(rate)
        // QUEUE_ADD：新消息追加在后面，不打断正在朗读的消息
        return engine.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()) == TextToSpeech.SUCCESS
    }

    private fun flushPending() {
        val engine = tts ?: return
        if (!ready) return
        val list = ArrayList(pending)
        pending.clear()
        engine.setSpeechRate(rate)
        list.forEach {
            engine.speak(it, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    val unavailableReason: String? get() = failed

    companion object {
        const val ID = "系统 TTS"
    }
}
