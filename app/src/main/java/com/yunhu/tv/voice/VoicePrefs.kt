package com.yunhu.tv.voice

import android.content.Context
import android.content.SharedPreferences
import com.yunhu.tv.App

/**
 * 语音相关的设置存储。
 *
 * 同一份 SharedPreferences 同时容纳 ASR 与 TTS 两套服务配置：
 * 本步只实现并展示 ASR 字段，TTS 字段先留好读写入口（第三步直接复用）。
 * token 一律以 [SecureStore] 加密后的密文保存，界面上只能看到「已填写」和尾号。
 */
object VoicePrefs {

    private const val SP = "voice_settings"

    data class AsrConfig(
        val provider: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val token: String = ""
    )

    object TtsScope {
        const val ALL = "all"
        const val NEW = "new"
        const val USER = "user"
        const val BOT = "bot"

        fun label(id: String): String = when (id) {
            ALL -> "全部消息"
            USER -> "指定用户"
            BOT -> "机器人消息"
            else -> "仅新消息"
        }
    }

    data class TtsSettings(
        val enabled: Boolean = false,
        val scope: String = TtsScope.NEW,
        /** 指定用户时只看这个名字 / ID */
        val targetUser: String = "",
        val engine: String = "system",
        val voice: String = "",
        val rate: Float = 1.0f,
        val provider: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val token: String = ""
    )

    private val sp: SharedPreferences
        get() = App.instance.getSharedPreferences(SP, Context.MODE_PRIVATE)

    var asr: AsrConfig
        get() = AsrConfig(
            provider = sp.getString("asr_provider", "") ?: "",
            baseUrl = sp.getString("asr_base_url", "") ?: "",
            model = sp.getString("asr_model", "") ?: "",
            token = SecureStore.decrypt(sp.getString("asr_token", "") ?: "")
        )
        set(value) = sp.edit()
            .putString("asr_provider", value.provider)
            .putString("asr_base_url", value.baseUrl)
            .putString("asr_model", value.model)
            .putString("asr_token", SecureStore.encrypt(value.token))
            .apply()

    /** 手机扫码页有效期：10 分钟 / 30 分钟 / 1 小时 / 永久（0 表示不过期），默认 30 分钟 */
    var qrTtlMs: Long
        get() = sp.getLong("qr_ttl_ms", 30 * 60 * 1000L)
        set(value) = sp.edit().putLong("qr_ttl_ms", value).apply()

    /** TTS 设置（与 ASR 共用同一份 SharedPreferences，token 同样加密） */
    var ttsSettings: TtsSettings
        get() = TtsSettings(
            enabled = sp.getBoolean("tts_enabled", false),
            scope = sp.getString("tts_scope", TtsScope.NEW) ?: TtsScope.NEW,
            targetUser = sp.getString("tts_target_user", "") ?: "",
            engine = sp.getString("tts_engine", "system") ?: "system",
            voice = sp.getString("tts_voice_name", "") ?: "",
            rate = sp.getFloat("tts_rate", 1.0f),
            provider = sp.getString("tts_provider", "") ?: "",
            baseUrl = sp.getString("tts_base_url", "") ?: "",
            model = sp.getString("tts_model", "") ?: "",
            token = SecureStore.decrypt(sp.getString("tts_token", "") ?: "")
        )
        set(value) = sp.edit()
            .putBoolean("tts_enabled", value.enabled)
            .putString("tts_scope", value.scope)
            .putString("tts_target_user", value.targetUser)
            .putString("tts_engine", value.engine)
            .putString("tts_voice_name", value.voice)
            .putFloat("tts_rate", value.rate)
            .putString("tts_provider", value.provider)
            .putString("tts_base_url", value.baseUrl)
            .putString("tts_model", value.model)
            .putString("tts_token", SecureStore.encrypt(value.token))
            .apply()

    /** 在线 TTS 是否配置齐全（缺任何一项都回退系统引擎） */
    fun isTtsOnlineReady(cfg: TtsSettings = ttsSettings): Boolean =
        cfg.baseUrl.isNotBlank() && cfg.model.isNotBlank() && cfg.token.isNotBlank()

    /** 三项齐全才算可用；任何一项缺失都走占位实现 */
    fun isAsrReady(cfg: AsrConfig = asr): Boolean =
        cfg.baseUrl.isNotBlank() && cfg.model.isNotBlank() && cfg.token.isNotBlank()

    /** 只露尾号，界面不回显完整密钥 */
    fun maskedTail(token: String): String {
        if (token.isEmpty()) return ""
        return if (token.length <= 4) "••••" else "••••" + token.takeLast(4)
    }

    /** 保存后用于界面展示的状态文案 */
    fun describeAsr(cfg: AsrConfig = asr): String {
        if (!isAsrReady(cfg)) return "未填写"
        val provider = AsrProviders.labelOf(cfg.provider)
        return "已填写（$provider ${maskedTail(cfg.token)}）"
    }
}
