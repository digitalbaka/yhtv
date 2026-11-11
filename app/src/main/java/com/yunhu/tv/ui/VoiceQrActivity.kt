package com.yunhu.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.yunhu.tv.databinding.ActivityVoiceQrBinding
import com.yunhu.tv.net.AsrSetupServer
import com.yunhu.tv.net.LanHttpServer
import com.yunhu.tv.voice.VoicePrefs

/**
 * 「手机扫码填写语音识别配置」页面。
 *
 * TV 起一次性局域网 HTTP 服务，二维码里只放局域网地址 + 一次性口令；
 * 提交成功或超过设置的有效期后服务自动关闭，密钥不经过任何第三方。
 * 有效期在设置里可选 10 分钟 / 30 分钟 / 1 小时 / 永久（默认 30 分钟）。
 */
class VoiceQrActivity : AppCompatActivity(), LanHttpServer.Listener {

    companion object {
        private const val PORT = 8098
    }

    private lateinit var binding: ActivityVoiceQrBinding
    private var server: LanHttpServer? = null
    private var deadline = 0L
    private var ttlMs = 0L
    private var finishedHandled = false

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
        binding = ActivityVoiceQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FocusEffects.apply(binding.btnCancel)
        binding.btnCancel.requestFocus()

        binding.btnCancel.setOnClickListener {
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
            binding.btnCancel.text = "返回"
            return
        }
        server?.stop("已取消")
        val token = LanHttpServer.newToken()
        val s = AsrSetupServer.create(token, cacheDir, ttlMs) {
            runOnUiThread {
                binding.tvState.text = "配置已保存，正在返回设置页…"
            }
        }
        if (!s.start(PORT, this)) {
            binding.tvState.text = "局域网服务启动失败，请稍后重试"
            return
        }
        server = s
        val url = "http://$ip:${s.port}/asr/$token"
        binding.tvUrl.text = url
        binding.ivQr.setImageBitmap(QrCode.bitmap(url, 520))
        binding.tvState.text = "等待手机扫描…（提交后本页自动关闭）"
        binding.btnCancel.text = "关闭"

        deadline = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else 0L
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStopped(reason: String) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            handler.removeCallbacks(ticker)
            when (reason) {
                "已完成" -> {
                    binding.tvCount.text = ""
                    binding.tvState.text = "配置已保存"
                    if (!finishedHandled) {
                        finishedHandled = true
                        handler.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 900)
                    }
                }
                "已超时" -> {
                    // 旧链接立即失效；电视端自动换一个新口令并重新出码
                    binding.tvState.text = "二维码已过期，已重新生成，请重新扫描"
                    regenerate()
                }
                "已取消" -> {
                }
                else -> {
                    binding.tvCount.text = ""
                    binding.tvState.text = "服务已关闭：$reason"
                    binding.btnCancel.text = "关闭"
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
