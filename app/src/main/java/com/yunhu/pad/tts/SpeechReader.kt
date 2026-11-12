package com.yunhu.pad.tts

import android.widget.Toast
import com.yunhu.pad.App
import com.yunhu.pad.api.ChatMsg
import com.yunhu.pad.api.ContentType
import com.yunhu.pad.api.WsClient
import com.yunhu.pad.util.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 消息朗读调度。
 * 优先级：在线引擎（已配置时）→ 系统 TTS → 可见提示。
 * 任何一步失败都不能静默，必须给出能看懂的提示。
 */
object SpeechReader {

    private const val MAX_SPEAK_CHARS = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val seenIds = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > 50
    }

    private lateinit var systemTts: SystemTts
    private var lastWarnText = ""
    private var lastWarnAt = 0L

    private val wsListener = object : WsClient.Listener {
        override fun onPush(msg: ChatMsg) = handlePush(msg)
    }

    fun attach(app: App) {
        systemTts = SystemTts(app)
        systemTts.onSpeakError = { showToast(it) }
        App.wsClient.addListener(wsListener)
    }

    /** 供设置页查询系统朗读能力 */
    fun checkSystemAvailability(callback: (SystemTts.Availability) -> Unit) {
        if (!::systemTts.isInitialized) {
            callback(SystemTts.Availability.UNKNOWN)
            return
        }
        systemTts.check(callback)
    }

    /** 立即朗读一段文本（设置页「测试朗读」用） */
    fun speakNow(text: String) {
        if (text.isBlank()) return
        speak(text.trim())
    }

    private fun handlePush(msg: ChatMsg) {
        if (!TtsConfig.readEnabled) return
        if (msg.isRecalled) return
        if (msg.contentType != ContentType.TEXT && msg.contentType != ContentType.MARKDOWN) return
        // 自己发的消息不朗读
        if (msg.direction == "right" || msg.sender.chatId == Session.userId) return
        if (TtsConfig.scope == TtsConfig.SCOPE_MENTION && !msg.content.mentionedIds.contains(Session.userId)) return
        if (msg.msgId.isNotEmpty()) {
            if (seenIds.containsKey(msg.msgId)) return
            seenIds[msg.msgId] = true
        }
        val text = msg.content.text.trim()
        if (text.isEmpty()) return
        speak(if (text.length > MAX_SPEAK_CHARS) text.take(MAX_SPEAK_CHARS) + "…" else text)
    }

    private fun speak(text: String) {
        val app = App.instance
        val rate = TtsConfig.ratePercent
        if (TtsConfig.onlineConfigured(app)) {
            scope.launch {
                try {
                    OnlineTts.speak(app, text, rate)
                } catch (e: Exception) {
                    speakWithSystem(app, text, rate, onlineError = e.message ?: "在线引擎失败")
                }
            }
        } else {
            speakWithSystem(app, text, rate, onlineError = null)
        }
    }

    /** onlineError 为 null 表示没走在线引擎；非空时把在线失败的具体原因带进提示 */
    private fun speakWithSystem(app: App, text: String, rate: Int, onlineError: String?) {
        if (!::systemTts.isInitialized) {
            systemTts = SystemTts(app)
            systemTts.onSpeakError = { showToast(it) }
        }
        systemTts.check { availability ->
            when (availability) {
                SystemTts.Availability.READY -> {
                    if (!systemTts.speak(text, rate)) {
                        warn("系统朗读启动失败，请检查系统语音设置")
                    }
                }
                SystemTts.Availability.NO_ZH_ENGINE -> {
                    warn(
                        if (onlineError != null) "$onlineError，且本机缺少中文语音引擎，无法朗读"
                        else "当前设备缺少中文语音引擎，中文消息无法朗读；可在设置中配置在线 TTS 引擎"
                    )
                }
                SystemTts.Availability.INIT_FAILED -> {
                    warn(
                        if (onlineError != null) "$onlineError，且系统语音初始化失败，无法朗读"
                        else "系统语音初始化失败，无法朗读；可在设置中配置在线 TTS 引擎"
                    )
                }
                SystemTts.Availability.UNKNOWN -> {}
            }
        }
    }

    /** 同一句提示 60 秒内不重复弹，避免连续消息刷屏 */
    private fun warn(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastWarnText && now - lastWarnAt < 60_000) return
        lastWarnText = text
        lastWarnAt = now
        showToast(text)
    }

    private fun showToast(text: String) {
        Toast.makeText(App.instance, text, Toast.LENGTH_LONG).show()
    }
}
