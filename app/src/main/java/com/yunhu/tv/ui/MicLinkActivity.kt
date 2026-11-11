package com.yunhu.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.yunhu.tv.databinding.ActivityMicLinkBinding
import com.yunhu.tv.net.LanHttpServer
import com.yunhu.tv.net.MicServer
import com.yunhu.tv.voice.VoiceOutcome
import com.yunhu.tv.voice.VoicePrefs

/**
 * 「手机当麦克风」页面：TV 显示二维码，手机扫码录音，
 * 音频只在手机与电视之间传输（不经过第三方），识别结果填入聊天输入框。
 */
class MicLinkActivity : AppCompatActivity(), LanHttpServer.Listener {

    companion object {
        private const val PORT = 8099
    }

    private lateinit var binding: ActivityMicLinkBinding
    private var server: LanHttpServer? = null
    private var deadline = 0L
    private var ttlMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (ttlMs <= 0) {
                binding.tvCount.text = "永久有效"
                return
            }
            val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            binding.tvCount.text = "有效期剩余 %d:%02d".format(remain / 60000, remain % 60000 / 1000)
            if (remain > 0) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FocusEffects.apply(binding.btnClose)
        binding.btnClose.requestFocus()

        binding.btnClose.setOnClickListener {
            server?.stop("已取消")
            finish()
        }

        ttlMs = VoicePrefs.qrTtlMs
        regenerate()
    }

    /** 生成一次性链接与二维码；超时后也用它重新生成 */
    private fun regenerate() {
        val ip = LanHttpServer.localIpv4()
        if (ip == null) {
            binding.tvState.text = "电视没有连上局域网，请先连 Wi-Fi 再试"
            binding.btnClose.text = "返回"
            return
        }
        server?.stop("已取消")
        val token = LanHttpServer.newToken()
        val s = MicServer.create(token, cacheDir, ttlMs) { outcome ->
            runOnUiThread {
                if (!isDestroyed) showOutcome(outcome)
            }
        }
        if (!s.start(PORT, this)) {
            binding.tvState.text = "局域网服务启动失败，请稍后重试"
            return
        }
        server = s
        val url = "http://$ip:${s.port}/voice/$token"
        binding.tvUrl.text = url
        binding.ivQr.setImageBitmap(QrCode.bitmap(url, 520))
        binding.tvState.text = "等待手机扫描并录音…"
        binding.btnClose.text = "关闭"

        deadline = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else 0L
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun showOutcome(outcome: VoiceOutcome) {
        binding.tvState.text = when {
            outcome.placeholder -> "已收到语音：${outcome.text}\n这是占位文本（${outcome.note}），已交给聊天输入框"
            outcome.text.isBlank() -> "识别失败：${outcome.note}\n请检查设置里的地址 / 模型 / 密钥"
            else -> "识别结果：${outcome.text}\n已交给聊天输入框（未发送）"
        }
    }

    override fun onStopped(reason: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            handler.removeCallbacks(ticker)
            when (reason) {
                "已超时" -> {
                    binding.tvState.text = "二维码已过期，已重新生成，请重新扫描"
                    regenerate()
                }
                "已取消" -> binding.tvCount.text = ""
                else -> {
                    binding.tvCount.text = ""
                    binding.tvState.text = "服务已关闭：$reason"
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        server?.stop("已取消")
        server = null
        super.onDestroy()
    }
}
