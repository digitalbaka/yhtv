package com.yunhu.tv.tts

/**
 * 朗读引擎抽象：系统 TTS 与在线 TTS 是同一接口下的可互换实现。
 *
 * 朗读不抢焦点、不打断正在播放的内容：实现内部用队列追加。
 */
interface TtsEngine {

    /** 引擎标识，设置页与界面提示里展示 */
    val id: String

    /**
     * 初始化（可重复调用）。
     * @param onReady false 表示引擎不可用，调用方应回退到系统引擎并提示
     */
    fun prepare(voiceName: String?, rate: Float, onReady: (Boolean) -> Unit)

    /** 追加一句到朗读队列；返回 false 表示当前引擎不可用 */
    fun speak(text: String): Boolean

    fun stop()

    fun release()
}
