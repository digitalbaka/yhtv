package com.yunhu.tv.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yunhu.tv.App
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    /** 每次 prepare 重建，供 [speakAwait] 等待初始化结果 */
    @Volatile
    private var readySignal = CompletableDeferred<Boolean>()

    /** 正在等待读完的句子：utteranceId -> 完成信号 */
    private val awaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override fun prepare(voiceName: String?, rate: Float, onReady: (Boolean) -> Unit) {
        this.rate = rate.coerceIn(0.5f, 2.0f)
        failed = null
        val signal = CompletableDeferred<Boolean>()
        readySignal = signal
        val ctx: Context = App.instance
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine == null) {
                    failed = "系统语音引擎初始化失败"
                    signal.complete(false)
                    onReady(false)
                    return@TextToSpeech
                }
                // 设中文后检查语言包是否可用：缺失时记下原因并回调，界面给可见提示
                val langResult = engine.setLanguage(Locale.CHINA)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    failed = "系统缺少中文语音包"
                    signal.complete(false)
                    onReady(false)
                    return@TextToSpeech
                }
                if (voiceName != null && voiceName.isNotEmpty()) {
                    runCatching {
                        engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
                    }
                }
                engine.setSpeechRate(this.rate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { awaiters.remove(it)?.complete(true) }
                    }
                    @Deprecated("老 API")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { awaiters.remove(it)?.complete(false) }
                    }
                })
                ready = true
                signal.complete(true)
                flushPending()
                onReady(true)
            } else {
                failed = "系统没有可用的 TTS 引擎"
                signal.complete(false)
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

    /** 手动朗读用：排队并等到这一句念完（onDone / onError / stop 都会唤醒） */
    override suspend fun speakAwait(text: String): Boolean {
        if (!readySignal.await()) return false
        val engine = tts ?: return false
        if (failed != null) return false
        val utteranceId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        awaiters[utteranceId] = done
        engine.setSpeechRate(rate)
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            awaiters.remove(utteranceId)
            return false
        }
        return try {
            done.await()
        } finally {
            awaiters.remove(utteranceId)
        }
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
        // 唤醒所有等待中的句子，避免手动朗读卡在 await 上
        awaiters.values.forEach { it.complete(false) }
        awaiters.clear()
    }

    override fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        awaiters.values.forEach { it.complete(false) }
        awaiters.clear()
    }

    val unavailableReason: String? get() = failed

    companion object {
        const val ID = "系统 TTS"
    }
}
