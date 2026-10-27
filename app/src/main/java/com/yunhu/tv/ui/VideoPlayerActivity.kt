package com.yunhu.tv.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.tv.App
import com.yunhu.tv.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 全屏视频播放：本地 HTTP 代理 + VideoView。
 * OkHttpDataSource 在这台老电视上虽然 TLS 通了，但硬解器 profile 不兼容 H.264 High，
 * 且 PlayerView Surface 尺寸异常（1x1）。改用系统 VideoView，通过本地 127.0.0.1 代理
 * 转发视频流（带 Referer 与宽松证书），利用 VideoView/MediaPlayer 内置的硬解 fallback。
 *
 * 遥控器：确定=播放/暂停，左右=快退/快进 10 秒，返回=退出。
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        private const val SEEK_STEP_SEC = 10
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var proxy: LocalVideoProxy? = null
    private var videoUrl: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pausedManually = false

    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvKeys.text = "确定键 播放/暂停   左右键 快退/快进 10 秒   返回键 退出"

        if (videoUrl.isEmpty()) {
            showPersistent("视频地址为空")
            return
        }

        setupVideoView()
        startProxyAndPlay()
    }

    private fun setupVideoView() {
        binding.videoView.setOnPreparedListener {
            binding.progressBar.max = it.duration
            showPersistent("缓冲中…")
            hideState()
        }
        binding.videoView.setOnErrorListener { _, what, extra ->
            showPersistent("播放错误 what=$what extra=$extra")
            true
        }
        binding.videoView.setOnCompletionListener {
            showPersistent("播放结束，按确定键重播")
        }
    }

    private fun startProxyAndPlay() {
        lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    LocalVideoProxy(App.mediaClient, videoUrl).also { it.start() }
                }
                proxy = p
                val localUrl = "http://127.0.0.1:${p.port}/video.mp4"
                binding.videoView.setVideoPath(localUrl)
                binding.videoView.start()
                handler.post(progressTick)
            } catch (e: Exception) {
                showPersistent("启动失败：${e.message}")
            }
        }
    }

    private fun updateProgress() {
        val vv = binding.videoView
        if (!vv.isPlaying && !pausedManually) return
        val dur = vv.duration.coerceAtLeast(0)
        val pos = vv.currentPosition.coerceAtLeast(0)
        binding.progressBar.max = dur.coerceAtLeast(1000)
        binding.progressBar.progress = pos
        binding.tvTime.text = "${formatTime(pos)} / ${if (dur > 0) formatTime(dur) else "--:--"}"
    }

    /** VideoView 的时间单位是毫秒（Int） */
    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vv = binding.videoView
        val stepMs = SEEK_STEP_SEC * 1000
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (!vv.isPlaying) {
                    if (vv.currentPosition >= vv.duration - 500) vv.seekTo(0)
                    vv.start()
                    pausedManually = false
                    flash("▶ 播放")
                } else {
                    vv.pause()
                    pausedManually = true
                    flash("❚❚ 暂停")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                vv.seekTo((vv.currentPosition - stepMs).coerceAtLeast(0))
                flash("◀ 快退 10 秒")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val target = vv.currentPosition + stepMs
                vv.seekTo(if (vv.duration > 0) target.coerceAtMost(vv.duration) else target)
                flash("▶ 快进 10 秒")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun flash(text: String) {
        binding.tvState.visibility = View.VISIBLE
        binding.tvState.text = text
        handler.removeCallbacks(hideStateRunnable)
        handler.postDelayed(hideStateRunnable, 900)
    }

    private val hideStateRunnable = Runnable { binding.tvState.visibility = View.GONE }

    private fun showPersistent(text: String) {
        handler.removeCallbacks(hideStateRunnable)
        binding.tvState.visibility = View.VISIBLE
        binding.tvState.text = text
    }

    private fun hideState() { binding.tvState.visibility = View.GONE }

    override fun onPause() { super.onPause(); binding.videoView.pause(); pausedManually = true }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { binding.videoView.stopPlayback() } catch (_: Exception) {}
        proxy?.stop()
        super.onDestroy()
    }
}

/**
 * 本地 HTTP 代理：单客户端单连接，仅处理最基本的 GET（含 Range）。
 * 收到请求后用共享 OkHttpClient 转发到真实 URL，响应逐字节写回 socket。
 */
private class LocalVideoProxy(
    private val client: OkHttpClient,
    private val targetUrl: String
) {
    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0

    fun start() {
        server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        Thread {
            val ss = server ?: return@Thread
            try {
                while (!ss.isClosed) {
                    val clientSocket = try { ss.accept() } catch (_: Exception) { break }
                    try { handle(clientSocket) } catch (_: Exception) {}
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val first = input.readLine() ?: return
        val parts = first.split(" ")
        if (parts.size < 2 || parts[0] != "GET") { writeError(socket, 400, "Bad Request"); return }

        val reqBuilder = Request.Builder().url(targetUrl).get()
        var rangeHeader: String? = null
        var line: String?
        while (input.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            val idx = line!!.indexOf(':')
            if (idx > 0) {
                val key = line!!.substring(0, idx).trim()
                val valV = line!!.substring(idx + 1).trim()
                if (key.equals("Range", ignoreCase = true)) rangeHeader = valV
            }
        }
        rangeHeader?.let { reqBuilder.header("Range", it) }

        val resp = client.newCall(reqBuilder.build()).execute()
        val out = socket.getOutputStream()

        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${resp.code} ${resp.message}").append("\r\n")
        sb.append("Content-Type: ${resp.header("Content-Type", "video/mp4")}").append("\r\n")
        resp.header("Content-Length")?.let { sb.append("Content-Length: $it").append("\r\n") }
        resp.header("Content-Range")?.let { sb.append("Content-Range: $it").append("\r\n") }
        sb.append("Accept-Ranges: bytes").append("\r\n")
        sb.append("Cache-Control: no-store").append("\r\n")
        sb.append("Connection: close").append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))

        resp.body?.byteStream()?.use { body ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = body.read(buf)
                if (r <= 0) break
                out.write(buf, 0, r)
            }
        }
        out.flush()
    }

    private fun writeError(socket: Socket, code: Int, msg: String) {
        try {
            val body = "HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(body.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
        } catch (_: Exception) {}
    }
}
