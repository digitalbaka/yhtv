package com.yunhu.pad.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 系统原生 TTS 封装。
 * 只在主线程创建与使用；初始化完成后按中文（zh-CN）可用性给出三态结果，
 * 不把「引擎没装中文语音」当成静默失败。
 */
class SystemTts(private val context: Context) {

    enum class Availability { UNKNOWN, READY, NO_ZH_ENGINE, INIT_FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var initializing = false
    private val pending = mutableListOf<(Availability) -> Unit>()
    /** speakAwait 的完成回调，读完/出错时触发，用于逐段串行朗读 */
    private var doneCallback: (() -> Unit)? = null

    @Volatile
    var availability: Availability = Availability.UNKNOWN
        private set

    /** 朗读过程中的错误回调，用于给出可见提示；回调在主线程 */
    var onSpeakError: ((String) -> Unit)? = null

    /** 查询中文可用性；未初始化则触发一次初始化 */
    fun check(callback: (Availability) -> Unit) {
        if (availability != Availability.UNKNOWN) {
            callback(availability)
            return
        }
        pending.add(callback)
        ensureInit()
    }

    private fun ensureInit() {
        if (tts != null || initializing) return
        initializing = true
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post { onInitFinished(status) }
        }
    }

    private fun onInitFinished(status: Int) {
        initializing = false
        availability = when {
            status != TextToSpeech.SUCCESS -> Availability.INIT_FAILED
            !supportsChinese() -> Availability.NO_ZH_ENGINE
            else -> Availability.READY
        }
        if (availability == Availability.READY) {
            tts?.setSpeechRate(TtsConfig.ratePercent / 100f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    finishUtterance()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onSpeakError?.invoke("系统朗读失败")
                    finishUtterance()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onSpeakError?.invoke("系统朗读失败")
                    finishUtterance()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    finishUtterance()
                }
            })
        }
        val callbacks = pending.toList()
        pending.clear()
        callbacks.forEach { it(availability) }
    }

    private fun supportsChinese(): Boolean {
        val engine = tts ?: return false
        val result = try {
            engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false
        }
        return try {
            engine.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    /** 只有中文可用时才真正发声；返回是否已交给引擎 */
    fun speak(text: String, ratePercent: Int): Boolean {
        val engine = tts ?: return false
        if (availability != Availability.READY) return false
        return try {
            engine.setSpeechRate(ratePercent / 100f)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    /** 朗读并挂起到读完/出错（长按朗读逐段串行用）；返回是否已交给引擎 */
    suspend fun speakAwait(text: String, ratePercent: Int): Boolean =
        suspendCancellableCoroutine { cont ->
            doneCallback = { if (cont.isActive) cont.resume(true) }
            if (!speak(text, ratePercent)) {
                doneCallback = null
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { doneCallback = null }
        }

    private fun finishUtterance() {
        val callback = doneCallback
        doneCallback = null
        callback?.invoke()
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        availability = Availability.UNKNOWN
        initializing = false
    }

    private companion object {
        const val UTTERANCE_ID = "yhpad-tts"
    }
}
