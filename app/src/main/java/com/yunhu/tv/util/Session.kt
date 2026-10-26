package com.yunhu.tv.util

import android.content.Context
import android.content.SharedPreferences

/** 登录态管理，token 持久化在 SharedPreferences */
object Session {
    private const val SP = "session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_AVATAR = "avatar"
    private const val KEY_DEVICE_ID = "device_id"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
        if (deviceId.isEmpty()) {
            val id = "tv-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
        }
    }

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var userId: String
        get() = sp.getString(KEY_USER_ID, "") ?: ""
        set(v) = sp.edit().putString(KEY_USER_ID, v).apply()

    var nickname: String
        get() = sp.getString(KEY_NICKNAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NICKNAME, v).apply()

    var avatarUrl: String
        get() = sp.getString(KEY_AVATAR, "") ?: ""
        set(v) = sp.edit().putString(KEY_AVATAR, v).apply()

    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, "") ?: ""

    val isLoggedIn: Boolean get() = token.isNotEmpty()

    fun clear() {
        val dev = deviceId
        sp.edit().clear().apply()
        sp.edit().putString(KEY_DEVICE_ID, dev).apply()
    }
}
