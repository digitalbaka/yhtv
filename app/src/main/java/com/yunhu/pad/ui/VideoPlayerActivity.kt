package com.yunhu.pad.ui

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.pad.App
import com.yunhu.pad.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 全屏媒体播放：视频 / 音频统一「下载到 cacheDir 再本地播放」。
 *
 * 这台电视的 vendor MediaPlayer 直接拒绝 http://127.0.0.1 的 loopback 地址
 * （setDataSource 后 20ms 就回 -10102，本地代理一条请求都收不到），所以流式代理不可用；
 * 先落盘成本地文件再交给 VideoView 播放最稳。
 *
 * 遥控器：确定=播放/暂停，左右=快退/快进 10 秒，返回=退出。
 *
 * 触屏手势（挂在本页根布局上，底部控制条区域不接管）：
 * 左半区竖滑=亮度、右半区竖滑=音量（实时生效）；横滑=进度预览、松手才 seek；双击=播放/暂停；单击=显隐控制条。
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        /** 音频（mp3/语音）标记：下载后用 .mp3 后缀，规避 MIME 嗅探问题 */
        const val EXTRA_IS_AUDIO = "is_audio"
        private const val SEEK_STEP_SEC = 10

        private const val GESTURE_NONE = 0
        private const val GESTURE_VERTICAL = 1
        private const val GESTURE_HORIZONTAL = 2

        /** 方向锁阈值（dp）：垂直/水平位移超过它才锁定方向，避免误触 */
        private const val GESTURE_SLOP_DP = 24f

        /** 横滑满屏宽度对应的进度跨度上限（秒），短滑不会跳过几分钟 */
        private const val SEEK_FULL_WIDTH_SEC = 120

        /** 竖滑满量程所需的高度比例：70% 屏高 ≈ 亮度 0→100% / 音量 0→最大 */
        private const val VERTICAL_RANGE_RATIO = 0.7f
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var videoUrl: String = ""
    private var isAudio: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var pausedManually = false

    // ---- 触屏手势状态 ----
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    /** 进入页面时的亮度覆盖值，-1 表示跟随系统；退出时恢复 */
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var gestureMode = GESTURE_NONE
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureOnLeftSide = false
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var startPositionMs = 0
    private var previewPositionMs = 0

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
        binding.tvKeys.text = "确定键 播放/暂停   左右键 快退/快进 10 秒   返回键 退出\n触屏：左半区竖滑调亮度，右半区竖滑调音量，横滑调进度，双击播放/暂停"

        if (videoUrl.isEmpty()) {
            showPersistent("媒体地址为空")
            return
        }

        setupGestures()
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

    // ---------------- 触屏手势 ----------------

    private fun setupGestures() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalBrightness = window.attributes.screenBrightness
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            /** 单击：等双击判定窗（系统默认约 300ms）过去后才显隐控制条 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (gestureMode != GESTURE_NONE) return false
                toggleControls()
                return true
            }

            /** 双击：暂停/播放，不再触发单击逻辑 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (gestureMode != GESTURE_NONE) return false
                togglePlayPause()
                return true
            }
        })
        binding.root.setOnTouchListener { _, ev -> handleGestureTouch(ev) }
    }

    private fun handleGestureTouch(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 底部控制条（进度条 + 时间）区域不接管手势，避免影响按钮点击
            if (binding.controls.visibility == View.VISIBLE &&
                ev.y >= binding.controls.top && ev.y <= binding.controls.bottom
            ) {
                return false
            }
            gestureMode = GESTURE_NONE
            gestureStartX = ev.x
            gestureStartY = ev.y
        }
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> handleGestureMove(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureMode == GESTURE_HORIZONTAL) commitHorizontalSeek()
                gestureMode = GESTURE_NONE
            }
        }
        return true
    }

    private fun handleGestureMove(ev: MotionEvent) {
        val threshold = GESTURE_SLOP_DP * resources.displayMetrics.density
        if (gestureMode == GESTURE_NONE) {
            val dx = ev.x - gestureStartX
            val dy = ev.y - gestureStartY
            // 位移没超过阈值：继续观望，不判定方向
            if (abs(dx) <= threshold && abs(dy) <= threshold) return
            if (abs(dy) > abs(dx)) {
                // 垂直锁定：垂直手势不会再触发进度
                gestureMode = GESTURE_VERTICAL
                gestureOnLeftSide = gestureStartX < binding.root.width / 2f
                startBrightness = effectiveBrightness()
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                // 从锁定那一刻重新起算，避免阈值内的位移造成跳变
                gestureStartY = ev.y
            } else {
                // 视频未就绪（duration<=0）时不进入水平手势，避免无意义的 seek
                val dur = binding.videoView.duration
                if (dur <= 0) return
                // 水平锁定：水平手势不会再调亮度/音量
                gestureMode = GESTURE_HORIZONTAL
                startPositionMs = binding.videoView.currentPosition.coerceAtLeast(0)
                previewPositionMs = startPositionMs
                gestureStartX = ev.x
            }
        }
        when (gestureMode) {
            GESTURE_VERTICAL -> applyVerticalDelta(gestureStartY - ev.y)
            GESTURE_HORIZONTAL -> previewHorizontalDelta(ev.x - gestureStartX)
        }
    }

    /** 纵向：实时改亮度 / 媒体音量 */
    private fun applyVerticalDelta(dy: Float) {
        val height = binding.root.height.coerceAtLeast(1)
        val ratio = dy / (height * VERTICAL_RANGE_RATIO)
        if (gestureOnLeftSide) {
            val value = (startBrightness + ratio).coerceIn(0f, 1f)
            val lp = window.attributes
            lp.screenBrightness = value
            window.attributes = lp
            showGestureHint("亮度 ${(value * 100).roundToInt()}%")
        } else {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (startVolume + ratio * max).roundToInt().coerceIn(0, max)
            if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            val pct = target * 100 / max
            showGestureHint(
                when {
                    target >= max -> "音量 $pct%（已到最大）"
                    target <= 0 -> "音量 $pct%（已到最小）"
                    else -> "音量 $pct%"
                }
            )
        }
    }

    /** 横向：只预览目标位置，松手才真正 seek */
    private fun previewHorizontalDelta(dx: Float) {
        val dur = binding.videoView.duration
        if (dur <= 0) return
        val width = binding.root.width.coerceAtLeast(1)
        // 满屏宽 ≈ min(视频时长, 120s)，短滑不会误跳几分钟
        val spanSec = min(dur / 1000, SEEK_FULL_WIDTH_SEC)
        val deltaMs = (dx / width * spanSec * 1000).roundToInt()
        previewPositionMs = (startPositionMs + deltaMs).coerceIn(0, dur)
        val sec = abs(deltaMs) / 1000
        val arrow = if (deltaMs >= 0) "▶" else "◀"
        showGestureHint("$arrow ${if (deltaMs >= 0) "快进" else "快退"} ${sec}s → ${formatTime(previewPositionMs)}")
    }

    private fun commitHorizontalSeek() {
        val vv = binding.videoView
        if (vv.duration <= 0) return
        val delta = previewPositionMs - startPositionMs
        vv.seekTo(previewPositionMs)
        if (vv.isPlaying) updateProgress()
        val sec = abs(delta) / 1000
        if (sec == 0) {
            flash("已定位到 ${formatTime(previewPositionMs)}")
        } else {
            flash("${if (delta > 0) "▶ 快进" else "◀ 快退"} ${sec} 秒")
        }
    }

    private fun toggleControls() {
        binding.controls.visibility =
            if (binding.controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun togglePlayPause() {
        val vv = binding.videoView
        if (!vv.isPlaying) {
            if (vv.duration > 0 && vv.currentPosition >= vv.duration - 500) vv.seekTo(0)
            vv.start()
            pausedManually = false
            flash("▶ 播放")
        } else {
            vv.pause()
            pausedManually = true
            flash("❚❚ 暂停")
        }
    }

    private fun effectiveBrightness(): Float {
        val attr = window.attributes.screenBrightness
        if (attr >= 0f) return attr
        // 尚未设置覆盖值时，用系统亮度作为起点；只读，不申请 WRITE_SETTINGS
        val sys = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (sys / 255f).coerceIn(0f, 1f)
    }

    /** 拖动过程中的常驻提示，不自动隐藏，避免每帧闪一下 */
    private fun showGestureHint(text: String) {
        handler.removeCallbacks(hideStateRunnable)
        binding.tvState.visibility = View.VISIBLE
        binding.tvState.text = text
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
                togglePlayPause()
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
        // 退出页面恢复默认亮度（不写系统设置，只撤销本窗口的覆盖值）
        val lp = window.attributes
        lp.screenBrightness = originalBrightness
        window.attributes = lp
        try { binding.videoView.stopPlayback() } catch (_: Exception) {}
        super.onDestroy()
    }
}
