package com.yunhu.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.tv.App
import com.yunhu.tv.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 全屏媒体播放：视频 / 音频统一「下载到 cacheDir 再本地播放」。
 *
 * 这台电视的 vendor MediaPlayer 直接拒绝 http://127.0.0.1 的 loopback 地址
 * （setDataSource 后 20ms 就回 -10102，本地代理一条请求都收不到），所以流式代理不可用；
 * 先落盘成本地文件再交给 VideoView 播放最稳。
 *
 * 遥控器：确定=播放/暂停，左右=快退/快进 10 秒，返回=退出。
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        /** 音频（mp3/语音）标记：下载后用 .mp3 后缀，规避 MIME 嗅探问题 */
        const val EXTRA_IS_AUDIO = "is_audio"
        private const val SEEK_STEP_SEC = 10
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var videoUrl: String = ""
    private var isAudio: Boolean = false
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
        isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvKeys.text = "确定键 播放/暂停   左右键 快退/快进 10 秒   返回键 退出"

        if (videoUrl.isEmpty()) {
            showPersistent("媒体地址为空")
            return
        }

        setupVideoView()
        startPlayback()
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

    private fun startPlayback() {
        lifecycleScope.launch {
            try {
                val file = downloadToCache()
                hideState()
                binding.videoView.setVideoPath(file.absolutePath)
                binding.videoView.start()
                handler.post(progressTick)
            } catch (e: Exception) {
                showPersistent("加载失败：${e.message}")
            }
        }
    }

    /**
     * 下载到 cacheDir 后本地播放（视频与音频同一条路径）。
     * 边下边在 tvState 上刷百分比，避免大视频看起来卡死。
     */
    private suspend fun downloadToCache(): File {
        showPersistent("缓冲中… 0%")
        return withContext(Dispatchers.IO) {
            val f = File(cacheDir, "media_play.${extensionOf(videoUrl)}")
            val req = Request.Builder().url(videoUrl).get().build()
            var lastPct = -1
            var totalBytes = 0L
            App.mediaClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("响应为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    f.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            totalBytes += n
                            if (total > 0) {
                                val pct = (totalBytes * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread { binding.tvState.text = "缓冲中… $pct%" }
                                }
                            }
                        }
                    }
                }
            }
            android.util.Log.i("VideoPlayer", "downloaded $totalBytes bytes -> ${f.absolutePath}")
            f
        }
    }

    /** 从 URL 推断容器后缀，MediaPlayer 更愿意按后缀选解析器 */
    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) return ext
        return if (isAudio) "mp3" else "mp4"
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
        super.onDestroy()
    }
}
