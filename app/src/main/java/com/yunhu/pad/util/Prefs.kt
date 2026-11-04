package com.yunhu.pad.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地设置项统一收口。所有开关类设置都在这里读写，
 * 页面通过 Prefs.xxx 访问，避免各自持有 SharedPreferences 实例。
 */
object Prefs {

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private const val NAME = "yhpad_settings"

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun store(): SharedPreferences {
        val s = sp ?: throw IllegalStateException("Prefs.init 未调用")
        return s
    }

    /** 外观：跟随系统 / 浅色 / 深色。默认深色，保持原电视版观感 */
    var themeMode: Int
        get() = store().getInt("theme_mode", THEME_DARK)
        set(value) = store().edit().putInt("theme_mode", value).apply()

    /** 动态头像（GIF）开关，默认开启，保持原有行为 */
    var dynamicAvatar: Boolean
        get() = store().getBoolean("dynamic_avatar", true)
        set(value) = store().edit().putBoolean("dynamic_avatar", value).apply()

    /** 回车键发送，默认开启 */
    var enterToSend: Boolean
        get() = store().getBoolean("enter_to_send", true)
        set(value) = store().edit().putBoolean("enter_to_send", value).apply()

    /** 自定义聊天背景：应用私有目录下的绝对路径，空串表示默认 */
    var chatBackgroundPath: String
        get() = store().getString("chat_bg_path", "").orEmpty()
        set(value) = store().edit().putString("chat_bg_path", value).apply()

    /**
     * 最近一次保存的社区草稿 ID。文档只提供按 ID 取草稿，没有草稿列表接口，
     * 因此本地记住最后一次草稿，供「草稿箱」恢复。
     */
    var lastDraftId: Long
        get() = store().getLong("last_draft_id", 0)
        set(value) = store().edit().putLong("last_draft_id", value).apply()

    var lastDraftBaId: Long
        get() = store().getLong("last_draft_ba_id", 0)
        set(value) = store().edit().putLong("last_draft_ba_id", value).apply()
}
