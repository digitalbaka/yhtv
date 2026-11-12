package com.yunhu.tv.tts

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.yunhu.tv.App
import com.yunhu.tv.voice.VoicePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 朗读调度：串行朗读、长文本先切句。
 *
 * - 默认系统 TTS；设置里选择在线引擎且配置齐全时才用在线引擎；
 * - 在线引擎失败 / 超时自动回退系统引擎，并通过 [onEngineChanged] 告知界面当前引擎；
 * - 新消息只往队列尾部追加，不打断正在朗读的内容，也不抢焦点。
 */
object TtsSpeaker {

    private const val TAG = "TtsSpeaker"

    /** 单句最大长度，避免整段丢进去被截断 */
    private const val MAX_SENTENCE = 60

    /** 当前引擎标识，设置页展示用 */
    @Volatile
    var currentEngineLabel: String = SystemTtsEngine.ID
        private set

    /** 引擎变化 / 失败提示回调（主线程之外调用，调用方自行切线程） */
    @Volatile
    var onEngineChanged: ((String) -> Unit)? = null

    private var system: SystemTtsEngine? = null
    private var online: OnlineTtsEngine? = null
    private var active: TtsEngine? = null
    /** 记录各自引擎已应用的设置，避免在线失败回退系统时漏掉初始化 */
    private var systemSettings: VoicePrefs.TtsSettings? = null
    private var onlineSettings: VoicePrefs.TtsSettings? = null

    /** 手动朗读（长按菜单）专用：同一时刻只念一条 */
    private val manualScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manualJob: Job? = null
    private var manualKey: String? = null
    private var manualRunning = false
    private var manualGeneration = 0

    /** 朗读一段文本（自动切句后追加到队列） */
    @Synchronized
    fun speak(text: String) {
        val settings = VoicePrefs.ttsSettings
        if (!settings.enabled) return
        val body = text.trim()
        if (body.isEmpty()) return
        val engine = ensureEngine(settings)
        val sentences = splitSentences(body)
        var ok = true
        sentences.forEach { s -> if (!engine.speak(s)) ok = false }
        if (!ok) {
            Log.w(TAG, "engine ${engine.id} unavailable, fallback to system")
            fallbackToSystem(settings, sentences)
        }
    }

    /**
     * 手动朗读一条消息（长按菜单）。
     *
     * - 不受朗读总开关限制：这是用户主动点击，开关关着也照念；
     * - 同一条正在朗读时再次调用直接忽略，不重复合成；
     * - 换一条时先停掉上一条，再逐句串行朗读，上一句念完才起下一句；
     * - 引擎不可用时弹一条可见提示，不崩溃。
     */
    fun speakManual(key: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val gen: Int
        synchronized(this) {
            if (manualRunning && manualKey == key) {
                Log.i(TAG, "manual speak ignored, still speaking: $key")
                return
            }
            if (manualRunning) {
                Log.i(TAG, "manual speak switch, stop previous: $manualKey")
                manualJob?.cancel()
                active?.stop()
            }
            manualGeneration++
            gen = manualGeneration
            manualKey = key
            manualRunning = true
        }
        Log.i(TAG, "manual speak start: $key")
        val settings = VoicePrefs.ttsSettings
        val engine = ensureEngine(settings)
        val sentences = splitSentences(body)
        manualJob = manualScope.launch {
            try {
                var current = engine
                for (sentence in sentences) {
                    if (!isCurrent(gen)) break
                    var ok = current.speakAwait(sentence)
                    if (!ok) {
                        // 在线失败 / 系统不可用：切到系统引擎续读这一句
                        if (!isCurrent(gen)) break
                        current = ensureSystem(settings)
                        ok = current.speakAwait(sentence)
                    }
                    if (!ok) {
                        notice("无法朗读：系统没有可用的中文语音引擎，请在电视设置里安装中文语音包后重试")
                        break
                    }
                }
            } finally {
                synchronized(this@TtsSpeaker) {
                    if (manualGeneration == gen) {
                        manualRunning = false
                        manualKey = null
                        manualJob = null
                    }
                }
            }
        }
    }

    private fun isCurrent(gen: Int): Boolean =
        synchronized(this) { manualGeneration == gen }

    private fun notice(text: String) {
        Log.w(TAG, text)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(App.instance, text, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(App.instance, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 停止朗读（例如退出聊天页），不释放引擎 */
    fun stop() {
        synchronized(this) {
            manualGeneration++
            manualJob?.cancel()
            manualJob = null
            manualKey = null
            manualRunning = false
        }
        active?.stop()
    }

    fun release() {
        synchronized(this) {
            manualGeneration++
            manualJob?.cancel()
            manualJob = null
            manualKey = null
            manualRunning = false
        }
        online?.release()
        system?.release()
        online = null
        system = null
        active = null
        systemSettings = null
        onlineSettings = null
    }

    /** 在线引擎失败时由 [OnlineTtsEngine] 回调：回退系统引擎并补读失败的那句 */
    internal fun onOnlineFailure(text: String, reason: String) {
        Log.w(TAG, "online tts failed: $reason")
        val settings = VoicePrefs.ttsSettings
        online?.release()
        online = null
        val s = ensureSystem(settings)
        currentEngineLabel = "${SystemTtsEngine.ID}（在线失败已回退：$reason）"
        onEngineChanged?.invoke(currentEngineLabel)
        if (text.isNotBlank()) splitSentences(text).forEach { s.speak(it) }
    }

    private fun ensureEngine(settings: VoicePrefs.TtsSettings): TtsEngine {
        if (settings.engine == "online" && VoicePrefs.isTtsOnlineReady(settings)) {
            val o = online
            if (o != null && onlineSettings == settings) {
                active = o
                currentEngineLabel = o.id
                return o
            }
            val created = OnlineTtsEngine(settings.baseUrl, settings.model, settings.token, settings.voice)
            created.prepare(settings.voice, settings.rate) { }
            online = created
            active = created
            currentEngineLabel = created.id
            onlineSettings = settings
            return created
        }
        return ensureSystem(settings)
    }

    private fun ensureSystem(settings: VoicePrefs.TtsSettings): TtsEngine {
        val s = system ?: SystemTtsEngine().also { system = it }
        if (systemSettings != settings) {
            s.prepare(settings.voice, settings.rate) { ok ->
                if (!ok) {
                    currentEngineLabel = "${SystemTtsEngine.ID}（引擎不可用）"
                    onEngineChanged?.invoke(currentEngineLabel)
                }
            }
            systemSettings = settings
        }
        active = s
        currentEngineLabel = SystemTtsEngine.ID
        return s
    }

    private fun fallbackToSystem(settings: VoicePrefs.TtsSettings, sentences: List<String>) {
        val s = ensureSystem(settings)
        sentences.forEach { s.speak(it) }
        onEngineChanged?.invoke(currentEngineLabel)
    }

    /** 长文本先切句：按中英文标点切开，再按长度兜底切块 */
    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        text.forEach { ch ->
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' ||
                ch == '!' || ch == '?' || ch == ';' || ch == '.' && sb.length > 1
            ) {
                pushSentence(out, sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) pushSentence(out, sb.toString())
        return if (out.isEmpty()) listOf(text.take(MAX_SENTENCE)) else out
    }

    private fun pushSentence(out: MutableList<String>, raw: String) {
        val s = raw.trim()
        if (s.isEmpty()) return
        if (s.length <= MAX_SENTENCE) {
            out.add(s)
            return
        }
        var i = 0
        while (i < s.length) {
            out.add(s.substring(i, (i + MAX_SENTENCE).coerceAtMost(s.length)))
            i += MAX_SENTENCE
        }
    }

    /** 判断一条消息是否在朗读范围内 */
    fun shouldSpeak(
        settings: VoicePrefs.TtsSettings,
        senderName: String,
        senderChatId: String,
        senderChatType: Int,
        isMine: Boolean
    ): Boolean {
        if (!settings.enabled) return false
        if (isMine) return false
        return when (settings.scope) {
            VoicePrefs.TtsScope.ALL -> true
            VoicePrefs.TtsScope.BOT -> senderChatType == 3
            VoicePrefs.TtsScope.USER -> {
                val target = settings.targetUser.trim()
                target.isNotEmpty() && (target == senderName || target == senderChatId)
            }
            else -> true
        }
    }
}
