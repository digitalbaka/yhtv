package com.yunhu.tv.voice

import android.util.Log

/**
 * 识别管线：按设置里是否配置齐全，选用真识别或占位识别。
 *
 * 规则（第三步起收紧）：
 * - 只要 baseUrl / model / token 都填了，就不再产出占位文本；
 *   识别失败时给出可见的错误结果（HTTP 状态 + 返回体摘要），由界面提示用户；
 * - 只有真的没配置时才用 [PlaceholderRecognizer]，并带上「未配置识别密钥」说明。
 */
object VoicePipeline {

    private const val TAG = VoiceResultSink.TAG

    suspend fun transcribe(clip: AudioClip): VoiceOutcome {
        val cfg = VoicePrefs.asr
        val placeholder = PlaceholderRecognizer()
        if (!VoicePrefs.isAsrReady(cfg)) {
            val outcome = placeholder.recognize(clip).getOrElse {
                VoiceOutcome("[语音 0.0 秒]", true, placeholder.id, it.message ?: "占位识别失败")
            }
            Log.i(TAG, "asr not configured, placeholder -> \"${outcome.text}\"")
            return outcome.copy(
                note = "未配置识别密钥，当前为占位文本（设置 → 语音识别设置）"
            )
        }

        val recognizer = HttpAsrRecognizer(cfg.baseUrl, cfg.model, cfg.token)
        Log.i(TAG, "asr start: engine=${recognizer.id} size=${clip.size}B dur=${clip.durationMs}ms mime=${clip.mime}")
        return recognizer.recognize(clip).fold(
            onSuccess = { outcome ->
                if (outcome.text.isBlank()) {
                    Log.w(TAG, "asr returned empty text")
                    VoiceOutcome(
                        text = "",
                        placeholder = false,
                        engine = recognizer.id,
                        note = "识别结果为空，请再说一遍或调低环境噪音"
                    )
                } else {
                    Log.i(TAG, "asr ok: ${outcome.text.length} chars, engine=${recognizer.id}")
                    outcome
                }
            },
            onFailure = { e ->
                // 只记状态码与错误摘要，绝不记 token
                val reason = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "asr failed: $reason")
                VoiceOutcome(
                    text = "",
                    placeholder = false,
                    engine = recognizer.id,
                    note = "识别失败：$reason"
                )
            }
        )
    }
}
