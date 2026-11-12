package com.yunhu.pad.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.yunhu.pad.api.Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 在线 TTS（小米 MiMo 的 chat/completions 语音返回）。
 * 请求：POST {baseUrl}/chat/completions，Bearer 鉴权。
 * 请求体只有 model 和 messages 两个字段，待朗读文本放在 assistant 的 content 里。
 * 返回：choices[0].message.audio.data 是 base64 的 WAV。
 * key 只在请求头里出现一次，不写日志、不进异常文案。
 */
object OnlineTts {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var audioStream: FileInputStream? = null
    /** 停止当前播放时用它让挂起的播放协程正常返回，避免协程一直等 */
    private var onStopPlayback: (() -> Unit)? = null

    /** 在线合成并播放；任一环节失败都抛异常（文案各不相同），由调用方降级到系统 TTS */
    suspend fun speak(context: Context, text: String, ratePercent: Int) {
        val key = SecretStore.load(context)
        if (key.isEmpty()) throw IllegalStateException("在线引擎未配置")
        val base = TtsConfig.baseUrl.trim().trimEnd('/')
        val model = TtsConfig.model.trim()
        if (base.isEmpty() || model.isEmpty()) throw IllegalStateException("在线引擎未配置")

        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", text)
                )
            )

        val wavBytes = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                Api.client.newCall(req).execute().use { resp ->
                    // 非 2xx 也要把响应体读完，才能把服务端原因带给用户
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException(httpError(resp.code, raw))
                    parseAudio(raw)
                }
            }
        } catch (e: IOException) {
            // 连接拒绝、超时、DNS 失败、连接中断等，统一带中文前缀，原始英文只放括号里
            throw IllegalStateException(netError(e))
        }

        val file = File(context.cacheDir, "tts_online_audio.wav")
        withContext(Dispatchers.IO) { file.writeBytes(wavBytes) }
        play(file, ratePercent)
    }

    /** 服务端错误：带上状态码和响应体里的 message（截前 120 字） */
    private fun httpError(code: Int, raw: String): String {
        val reason = runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { root.optString("message") }
        }.getOrDefault("").ifBlank { raw }.trim().replace(Regex("\\s+"), " ")
        val brief = if (reason.length > 120) reason.take(120) + "…" else reason
        return if (brief.isEmpty()) "在线引擎失败（HTTP $code）"
        else "在线引擎失败（HTTP $code：$brief）"
    }

    /**
     * 连接层异常（拒绝连接、超时、DNS 失败、连接中断）：压成一行、截前 120 字，
     * 与 HTTP 那套保持一致；原始英文只作为括号内的细节，不整句上屏。
     */
    private fun netError(e: IOException): String {
        val reason = (e.message ?: e.javaClass.simpleName).trim().replace(Regex("\\s+"), " ")
        val brief = if (reason.length > 120) reason.take(120) + "…" else reason
        return if (brief.isEmpty()) "在线引擎失败（网络异常）"
        else "在线引擎失败（网络异常：$brief）"
    }

    /** 解析 choices[0].message.audio.data，三种失败各自可区分 */
    private fun parseAudio(raw: String): ByteArray {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IllegalStateException("在线引擎失败（响应不是 JSON）")
        }
        val audio = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optJSONObject("audio")
        val data = audio?.optString("data").orEmpty()
        if (data.isEmpty()) throw IllegalStateException("在线引擎失败（响应缺少 audio 字段）")
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            throw IllegalStateException("在线引擎失败（音频 base64 解码失败）")
        }
        if (bytes.isEmpty()) throw IllegalStateException("在线引擎失败（音频 base64 解码失败）")
        return bytes
    }

    /**
     * 用 FileDescriptor 播放（这台机器的媒体栈按路径打不开应用私有目录里的文件）。
     * 播放到结束或出错才返回；出错抛异常，交给调用方降级并提示。
     */
    private suspend fun play(file: File, ratePercent: Int) = withContext(Dispatchers.Main) {
        releaseCurrent()

        // 交给播放器前先校验，不满足就不进播放，提示里带上实际字节数
        val length = if (file.exists() && file.isFile) file.length() else -1L
        if (length <= 0L) {
            throw IllegalStateException("在线引擎失败（音频文件为空，实际 ${if (length < 0) 0 else length} 字节）")
        }
        if (!file.canRead()) {
            throw IllegalStateException("在线引擎失败（音频文件不可读，实际 $length 字节）")
        }

        suspendCancellableCoroutine<Unit> { cont ->
            val stream = try {
                FileInputStream(file)
            } catch (e: Exception) {
                cont.resumeWithException(IllegalStateException("在线引擎失败（音频文件打开失败：${e.message}）"))
                return@suspendCancellableCoroutine
            }
            val mp = MediaPlayer()
            player = mp
            audioStream = stream
            var finished = false

            /** 释放播放器、关流、让协程返回；error 为 null 表示正常结束 */
            fun finish(error: Throwable?) {
                if (finished) return
                finished = true
                onStopPlayback = null
                runCatching { stream.close() }
                runCatching { mp.release() }
                if (player === mp) player = null
                if (audioStream === stream) audioStream = null
                if (cont.isActive) {
                    if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
                }
            }

            onStopPlayback = { finish(null) }
            cont.invokeOnCancellation { finish(null) }

            mp.setOnCompletionListener { finish(null) }
            mp.setOnErrorListener { _, what, extra ->
                // 不吞错误：交给 finish 抛出，走降级提示链
                finish(IllegalStateException("在线音频播放失败（what=$what，extra=$extra）"))
                true
            }

            try {
                mp.setDataSource(stream.fd)
                mp.prepare()
                // 请求体不再带 speed，语速改由播放端生效（读不到就按原速播）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed((ratePercent / 100f).coerceIn(0.5f, 2f))
                    }
                }
                mp.start()
            } catch (e: Exception) {
                finish(IllegalStateException("在线音频播放失败（${e.javaClass.simpleName}：${e.message}）"))
            }
        }
    }

    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) releaseCurrent()
        else main.post { releaseCurrent() }
    }

    /** 只保留一个播放器实例，重复触发先释放旧的 */
    private fun releaseCurrent() {
        val stopper = onStopPlayback
        onStopPlayback = null
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        runCatching { audioStream?.close() }
        audioStream = null
        stopper?.invoke()
    }
}
