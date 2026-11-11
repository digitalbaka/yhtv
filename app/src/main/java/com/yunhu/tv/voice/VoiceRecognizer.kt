package com.yunhu.tv.voice

import java.io.File

/**
 * 一段待识别的音频。
 *
 * 音频可能是手机网页录制的 webm/opus，也可能是本地 wav / m4a / amr / mp3。
 * 大文件走 [file]（流式上传，不整段进内存）；小文件可以直接给 [bytes]。
 */
data class AudioClip(
    val bytes: ByteArray,
    /** 上传时的 Content-Type，例如 audio/webm、audio/wav */
    val mime: String,
    /** 真实录音时长（毫秒），占位文本用它生成「[语音 N 秒]」 */
    val durationMs: Long,
    /** 落盘的音频文件；有值时优先用它上传 */
    val file: File? = null
) {
    val size: Long
        get() = file?.length() ?: bytes.size.toLong()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioClip) return false
        return bytes.contentEquals(other.bytes) &&
            mime == other.mime &&
            durationMs == other.durationMs &&
            file == other.file
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + (file?.hashCode() ?: 0)
        return result
    }
}

/**
 * 一次识别结果。
 *
 * @property text 要填入聊天输入框的文本
 * @property placeholder true 表示这是占位文本（未配置 ASR 或识别失败），不是真实识别结果
 * @property engine 产出这段文本的实现标识，便于日志排查
 * @property note 占位 / 回退原因，用于界面提示
 */
data class VoiceOutcome(
    val text: String,
    val placeholder: Boolean,
    val engine: String,
    val note: String = ""
)

/**
 * 语音识别抽象。
 *
 * 占位实现与真识别实现是同一个接口下的可互换实现：
 * 设置里填好服务商、地址、模型、token 后，管线自动改用 [HttpAsrRecognizer]，
 * 不需要改动录音 / 派发 / 输入框这一段。
 */
interface VoiceRecognizer {

    /** 实现标识，设置页与日志里展示 */
    val id: String

    /** 识别一段音频；失败通过 Result.failure 返回，由上层决定是否回退 */
    suspend fun recognize(clip: AudioClip): Result<VoiceOutcome>
}
