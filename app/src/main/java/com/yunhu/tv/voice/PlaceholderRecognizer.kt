package com.yunhu.tv.voice

import java.util.Locale

/**
 * 占位识别：不做任何网络请求，用真实音频时长生成「[语音 N 秒]」。
 *
 * 设置项留空、或真实识别失败时都会回退到这里，
 * 保证「语音 → 输入框」这条链路在没有密钥的电视上也能完整跑通。
 */
class PlaceholderRecognizer : VoiceRecognizer {

    override val id: String = ID

    override suspend fun recognize(clip: AudioClip): Result<VoiceOutcome> = runCatching {
        VoiceOutcome(
            text = "[语音 ${formatSeconds(clip.durationMs)} 秒]",
            placeholder = true,
            engine = ID
        )
    }

    private fun formatSeconds(ms: Long): String {
        val sec = ms.coerceAtLeast(0) / 1000.0
        return String.format(Locale.US, "%.1f", sec)
    }

    companion object {
        const val ID = "占位识别"
    }
}
