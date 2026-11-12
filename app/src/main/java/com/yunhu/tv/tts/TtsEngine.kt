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

    /**
     * 追加一句并等待读完；返回 false 表示这一句没能念出来。
     * 默认实现退化为 [speak]（不等待），系统 TTS 与在线 TTS 各自覆盖为真正串行。
     */
    suspend fun speakAwait(text: String): Boolean = speak(text)

    fun stop()

    fun release()
}
