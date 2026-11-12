package com.yunhu.tv.tts

import android.media.MediaPlayer
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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 在线 TTS（OpenAI 兼容 /audio/speech）——可选项。
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

    override fun prepare(voiceName: String?, rate: Float, onReady: (Boolean) -> Unit) {
        stopped.set(false)
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

    private suspend fun playAwait(bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            runCatching {
                player?.release()
                val f = File.createTempFile("tts-", ".mp3", com.yunhu.tv.App.instance.cacheDir)
                f.writeBytes(bytes)
                f.deleteOnExit()
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    f.delete()
                    if (cont.isActive) runCatching { cont.resume(true) }
                }
                mp.setOnErrorListener { it, _, _ ->
                    it.release()
                    if (player === it) player = null
                    f.delete()
                    if (cont.isActive) runCatching { cont.resume(false) }
                    true
                }
                mp.prepare()
                mp.start()
            }.onFailure {
                TtsSpeaker.onOnlineFailure("", it.message ?: "播放失败")
                if (cont.isActive) runCatching { cont.resume(false) }
            }
            cont.invokeOnCancellation {
                runCatching {
                    player?.stop()
                    player?.release()
                }
                player = null
            }
        }

    private fun synthesize(text: String): ByteArray {
        val payload = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", if (voiceName.isNotBlank()) voiceName else "alloy")
            .put("response_format", "mp3")
            .toString()
        val url = baseUrl.trimEnd('/') + "/audio/speech"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty().take(120)
                throw IllegalStateException("HTTP ${resp.code} $body".trim())
            }
            return resp.body?.bytes() ?: throw IllegalStateException("返回内容为空")
        }
    }

    private fun play(bytes: ByteArray) {
        runCatching {
            player?.release()
            val f = File.createTempFile("tts-", ".mp3", com.yunhu.tv.App.instance.cacheDir)
            f.writeBytes(bytes)
            f.deleteOnExit()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    f.delete()
                }
                prepare()
                start()
            }
        }.onFailure { TtsSpeaker.onOnlineFailure("", it.message ?: "播放失败") }
    }

    override fun stop() {
        stopped.set(true)
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    override fun release() = stop()

    companion object {
        const val ID = "在线 TTS"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
        }
    }
}
