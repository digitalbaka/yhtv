package com.yunhu.tv.tts

import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 在线 TTS（chat/completions 语音返回），与平板端协议一致。
 *
 * 请求：POST {baseUrl}/chat/completions，Bearer 鉴权，请求体只有 model 和 messages。
 * 返回：choices[0].message.audio.data 是 base64 的 WAV。
 *
 * 地址 / 模型 / 音色 / token 全部来自设置；任何一项缺失或请求失败、超时，
 * 都由 [TtsSpeaker] 回退到系统引擎，并在界面提示当前用的是哪个引擎。
 */
class OnlineTtsEngine(
    private val baseUrl: String,
    private val model: String,
    private val token: String,
    private val voiceName: String
) : TtsEngine {

    override val id: String = ID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private var player: MediaPlayer? = null
    private var audioStream: FileInputStream? = null

    /** TtsSpeaker 在 prepare 里传进来的语速百分比，播放时用 PlaybackParams 生效 */
    @Volatile
    private var ratePercent: Int = 100

    override fun prepare(voiceName: String?, rate: Float, onReady: (Boolean) -> Unit) {
        stopped.set(false)
        ratePercent = (rate * 100).toInt().coerceIn(50, 200)
        onReady(true)
    }

    override fun speak(text: String): Boolean {
        scope.launch {
            try {
                val bytes = synthesize(text)
                if (stopped.get()) return@launch
                withContext(Dispatchers.Main) { play(bytes) }
            } catch (e: Exception) {
                TtsSpeaker.onOnlineFailure(text, e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    /**
     * 手动朗读用：合成一句、播完再返回，保证上一段没念完不起下一段。
     * 失败时只回报 false，由 [TtsSpeaker] 切到系统引擎续读，避免重复朗读。
     */
    override suspend fun speakAwait(text: String): Boolean {
        if (stopped.get()) return false
        return try {
            val bytes = synthesize(text)
            if (stopped.get()) return false
            withContext(Dispatchers.Main) { playAwait(bytes) }
        } catch (e: Exception) {
            TtsSpeaker.onOnlineFailure("", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** 合成一句，返回解码后的 WAV 字节；失败抛带可读原因的异常 */
    private suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || model.trim().isEmpty()) {
            throw IllegalStateException("在线引擎未配置")
        }
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val body = JSONObject()
            .put("model", model.trim())
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", text)
                )
            )
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                // 非 2xx 也要把响应体读完，才能把服务端原因带给用户
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException(httpError(resp.code, raw))
                parseAudio(raw)
            }
        } catch (e: IOException) {
            throw IllegalStateException(netError(e))
        }
    }

    /** 服务端错误：带状态码和响应体里的 message（截前 120 字） */
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

    /** 连接层异常：压成一行，原始英文只放括号里 */
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

    /** 合成结果落盘（固定文件名，避免缓存目录堆积） */
    private fun writeCache(bytes: ByteArray): File {
        val dir = com.yunhu.tv.App.instance.cacheDir
        val f = File(dir, CACHE_NAME)
        f.writeBytes(bytes)
        return f
    }

    private fun play(bytes: ByteArray) {
        runCatching {
            releasePlayer()
            val file = writeCache(bytes)
            val stream = FileInputStream(file)
            audioStream = stream
            player = MediaPlayer().apply {
                setOnCompletionListener {
                    releasePlayer()
                    file.delete()
                }
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    file.delete()
                    true
                }
                setDataSource(stream.fd)
                prepare()
                applyRate(this)
                start()
            }
        }.onFailure {
            releasePlayer()
            TtsSpeaker.onOnlineFailure("", it.message ?: "播放失败")
        }
    }

    private suspend fun playAwait(bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            var finished = false
            val file = try {
                writeCache(bytes)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val stream = try {
                FileInputStream(file)
            } catch (e: Exception) {
                file.delete()
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            audioStream = stream
            val mp = MediaPlayer()
            player = mp

            fun finish(ok: Boolean) {
                if (finished) return
                finished = true
                releasePlayer()
                file.delete()
                if (cont.isActive) runCatching { cont.resume(ok) }
            }

            mp.setOnCompletionListener { finish(true) }
            mp.setOnErrorListener { _, _, _ ->
                finish(false)
                true
            }
            cont.invokeOnCancellation { finish(false) }

            runCatching {
                mp.setDataSource(stream.fd)
                mp.prepare()
                applyRate(mp)
                mp.start()
            }.onFailure {
                TtsSpeaker.onOnlineFailure("", it.message ?: "播放失败")
                finish(false)
            }
        }

    /** 语速在 prepare 之后、start 之前生效；读不到就按原速播 */
    private fun applyRate(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            mp.playbackParams = mp.playbackParams.setSpeed((ratePercent / 100f).coerceIn(0.5f, 2f))
        }
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { audioStream?.close() }
        audioStream = null
    }

    override fun stop() {
        stopped.set(true)
        releasePlayer()
    }

    override fun release() = stop()

    companion object {
        const val ID = "在线 TTS"
        private const val CACHE_NAME = "tts_online_audio.wav"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
