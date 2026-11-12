package com.yunhu.tv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.yunhu.tv.databinding.ActivityVoiceQrBinding
import com.yunhu.tv.net.LanHttpServer
import com.yunhu.tv.net.TtsSetupServer

/**
 * 「手机扫码填写在线 TTS 配置」页面。
 *
 * TV 起一次性局域网 HTTP 服务，二维码里只放局域网地址 + 一次性口令；
 * 有效期固定 5 分钟，到期只提示不续期（需要重新点「扫码填写」拿新链接），
 * 页面关闭、超时、保存成功三种情况都会停服务。
 */
class TtsQrActivity : AppCompatActivity(), LanHttpServer.Listener {

    companion object {
        private const val PORT = 8100

        /** 与平板一致：固定 5 分钟，不自动续期 */
        private const val TTL_MS = 5 * 60 * 1000L
    }

    private lateinit var binding: ActivityVoiceQrBinding
    private var server: LanHttpServer? = null
    private var expireAt = 0L
    private var finishedHandled = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val remain = expireAt - System.currentTimeMillis()
            if (remain <= 0) {
                binding.tvCount.text = "已过期"
                return
            }
            binding.tvCount.text = "有效期剩余 %d:%02d".format(remain / 60000, remain % 60000 / 1000)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = "手机扫码填写在线 TTS 配置"
        FocusEffects.apply(binding.btnCancel)
        binding.btnCancel.requestFocus()

        binding.btnCancel.setOnClickListener {
            server?.stop("已取消")
            finish()
        }

        startServer()
    }

    private fun startServer() {
        val ip = LanHttpServer.localIpv4()
        if (ip == null) {
            binding.tvState.text = "电视没有连上局域网，请先连 Wi-Fi 再试"
            binding.btnCancel.text = "返回"
            return
        }
        val token = LanHttpServer.newToken()
        val s = TtsSetupServer.create(token, cacheDir, TTL_MS) {
            runOnUiThread {
                if (!isDestroyed) binding.tvState.text = "配置已保存，正在返回设置页…"
            }
        }
        // 绑定到局域网 IP；绑不上时由 LanHttpServer 退回 0.0.0.0（仍拒绝非局域网来源）
        if (!s.start(PORT, this, ip)) {
            binding.tvState.text = "局域网服务启动失败，请稍后重试"
            return
        }
        server = s
        val url = "http://$ip:${s.port}/tts/$token"
        binding.tvUrl.text = url
        binding.ivQr.setImageBitmap(QrCode.bitmap(url, 520))
        binding.tvState.text = "等待手机扫描…（有效期 5 分钟，保存后本页自动关闭）"
        binding.btnCancel.text = "关闭"

        expireAt = System.currentTimeMillis() + TTL_MS
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
                    binding.tvState.text = "配置已保存，正在返回设置页…"
                    if (!finishedHandled) {
                        finishedHandled = true
                        handler.postDelayed({ if (!isFinishing && !isDestroyed) finish() }, 900)
                    }
                }
                "已超时" -> {
                    // 决策 A：到期只提示，不自动换口令重出码
                    binding.tvCount.text = "已过期"
                    binding.tvState.text = "二维码已过期（有效期 5 分钟），请返回后重新点击「扫码填写」获取新链接。"
                    binding.ivQr.setImageDrawable(null)
                    binding.btnCancel.text = "关闭"
                }
                "已取消" -> Unit
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
        binding.ivQr.setImageDrawable(null)
        super.onDestroy()
    }
}
