package com.yunhu.pad.tts

import android.content.Context
import android.content.SharedPreferences

/**
 * 朗读相关本地设置。
 * 在线引擎的 key 不落在这里，统一交给 [SecretStore] 用 AndroidKeyStore 加密后单独存放；
 * 这里只保存 baseUrl / 模型名这类非敏感信息。
 */
object TtsConfig {

    /** 朗读范围：全部消息 */
    const val SCOPE_ALL = 0

    /** 朗读范围：仅 @我 的消息 */
    const val SCOPE_MENTION = 1

    private const val NAME = "yhpad_tts"

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun store(): SharedPreferences =
        sp ?: throw IllegalStateException("TtsConfig.init 未调用")

    /** 消息朗读总开关，默认关闭 */
    var readEnabled: Boolean
        get() = store().getBoolean("read_enabled", false)
        set(value) = store().edit().putBoolean("read_enabled", value).apply()

    /** 朗读范围，默认全部消息 */
    var scope: Int
        get() = store().getInt("scope", SCOPE_ALL)
        set(value) = store().edit().putInt("scope", value).apply()

    /** 语速百分比，100 为正常语速 */
    var ratePercent: Int
        get() = store().getInt("rate_percent", 100).coerceIn(50, 200)
        set(value) = store().edit().putInt("rate_percent", value.coerceIn(50, 200)).apply()

    var baseUrl: String
        get() = store().getString("online_base_url", "").orEmpty()
        set(value) = store().edit().putString("online_base_url", value).apply()

    var model: String
        get() = store().getString("online_model", "").orEmpty()
        set(value) = store().edit().putString("online_model", value).apply()

    /** 音色：可为空，为空时请求里不传 voice，由服务商决定默认音色 */
    var voice: String
        get() = store().getString("online_voice", "").orEmpty()
        set(value) = store().edit().putString("online_voice", value).apply()

    /** 三项齐全才算配置完成 */
    fun onlineConfigured(ctx: Context): Boolean =
        baseUrl.isNotBlank() && model.isNotBlank() && SecretStore.hasKey(ctx)

    fun clearOnline(ctx: Context) {
        baseUrl = ""
        model = ""
        voice = ""
        SecretStore.clear(ctx)
    }
}
