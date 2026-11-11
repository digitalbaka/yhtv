package com.yunhu.tv.voice

import android.util.Log
import com.yunhu.tv.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容形态的语音识别：
 * POST {baseUrl}/audio/transcriptions，multipart 传 file + model，Bearer 鉴权。
 *
 * baseUrl / model / token 全部来自设置，构造函数里只接收值，不含任何内置地址。
 * 单次请求 30 秒超时，失败重试一次；错误信息带 HTTP 状态码与返回体摘要。
 */
class HttpAsrRecognizer(
    private val baseUrl: String,
    private val model: String,
    private val token: String
) : VoiceRecognizer {

    override val id: String = ID

    override suspend fun recognize(clip: AudioClip): Result<VoiceOutcome> = withContext(Dispatchers.IO) {
        val tempDir = App.instance.cacheDir
        val segments = WavChunker.split(clip, tempDir)
        // 非 WAV 没有解码器，切不了段：超长/超大时只能整段发，失败必须说清原因
        val unsplittable = segments.size == 1 && !isWav(clip) &&
            (clip.durationMs > WavChunker.SEGMENT_MS || clip.size > NO_CHUNK_WARN_BYTES)
        if (unsplittable) {
            Log.w(
                TAG,
                "asr: 非 wav 无法分段 mime=${clip.mime} dur=${clip.durationMs}ms size=${clip.size}B"
            )
        }
        val result = runCatching {
            try {
                val texts = segments.map { seg -> transcribeOne(seg) }
                val text = texts.filter { it.isNotBlank() }.joinToString("，")
                if (text.isBlank()) throw IllegalStateException("返回内容为空")
                VoiceOutcome(text = text, placeholder = false, engine = ID)
            } finally {
                segments.forEach { seg ->
                    if (seg.file != null && seg.file != clip.file) {
                        runCatching { seg.file.delete() }
                    }
                }
            }
        }
        if (result.isFailure && unsplittable) {
            val reason = result.exceptionOrNull()?.message ?: "未知错误"
            Log.w(TAG, "asr: 整段识别失败（无法分段）mime=${clip.mime} dur=${clip.durationMs}ms size=${clip.size}B reason=$reason")
            return@withContext Result.failure(
                IllegalStateException("当前格式不支持分段，请转成 wav 或分成几段（$reason）")
            )
        }
        result
    }

    private fun isWav(clip: AudioClip): Boolean =
        clip.mime.contains("wav", true) || clip.mime.contains("wave", true)

    /** 单段识别：30 秒超时，失败重试一次 */
    private fun transcribeOne(clip: AudioClip): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return requestOnce(clip)
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Thread.sleep(400)
            }
        }
        throw lastError ?: IllegalStateException("识别失败")
    }

    private fun requestOnce(clip: AudioClip): String {
        val media = clip.mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
        val fileName = when {
            clip.mime.contains("wav", true) -> "voice.wav"
            clip.mime.contains("mp4", true) || clip.mime.contains("m4a", true) -> "voice.m4a"
            clip.mime.contains("mpeg", true) || clip.mime.contains("mp3", true) -> "voice.mp3"
            clip.mime.contains("amr", true) -> "voice.amr"
            else -> "voice.webm"
        }
        val body: RequestBody = clip.file?.let { it.asRequestBody(media) }
            ?: clip.bytes.toRequestBody(media)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", fileName, body)
            .build()
        val url = baseUrl.trimEnd('/') + "/audio/transcriptions"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw AsrHttpException(resp.code, snippet(raw))
            }
            val text = try {
                JSONObject(raw).optString("text").trim()
            } catch (_: Exception) {
                throw IllegalStateException("返回格式不是 JSON：${snippet(raw)}")
            }
            if (text.isEmpty()) throw IllegalStateException("返回内容为空")
            return text
        }
    }

    /** 带 HTTP 状态码的识别异常，便于界面直接展示成人话 */
    class AsrHttpException(val code: Int, val detail: String) :
        IllegalStateException("HTTP $code $detail".trim())

    private fun snippet(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').take(160)

    companion object {
        const val ID = "OpenAI 兼容 ASR"
        private const val TAG = "VoiceAsr"
        private const val TIMEOUT_SECONDS = 30L

        /** 非 wav 无法分段时，超过这个体积也提醒（约 10MB） */
        private const val NO_CHUNK_WARN_BYTES = 10L * 1024 * 1024

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }
}
