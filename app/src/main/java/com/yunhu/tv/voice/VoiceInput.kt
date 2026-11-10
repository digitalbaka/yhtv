package com.yunhu.tv.voice

/**
 * 语音输入抽象层：一次录音结束后调用 [stop]，返回识别出的文本。
 *
 * 当前只有调试实现，用来在没有麦克风的电视上跑通整条链路。
 * 将来的硬件方案（USB / 蓝牙麦克风）如需接入，另写一个实现即可，不在此处处理。
 */
interface VoiceInput {

    /** 实现标识，调试面板上展示当前用的是哪一套 */
    val id: String

    /** 结束本次录音并返回文本结果 */
    suspend fun stop(): Result<String>
}
