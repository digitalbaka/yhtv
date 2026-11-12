package com.yunhu.pad.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yunhu.pad.databinding.ActivityTtsKeySetupBinding
import com.yunhu.pad.tts.LocalConfigServer
import com.yunhu.pad.tts.QrCode

/**
 * 在线 TTS 配置入口：启动只监听局域网的临时表单页，把链接展示出来（可复制/手机扫码打开）。
 * 页面销毁即关闭服务；保存成功后服务也会自动关闭。
 */
class TtsKeySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsKeySetupBinding
    private var server: LocalConfigServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var saved = false
    private var qrBitmap: Bitmap? = null

    private val ticker = object : Runnable {
        override fun run() {
            val s = server ?: return
            if (saved) return
            if (s.remainingMillis() <= 0) {
                s.stop()
                hideQr()
                binding.tvRemaining.text = "链接已过期"
                binding.tvStatus.text = "链接已过期，请返回设置页重新打开配置页获取新链接。"
                binding.btnCopyUrl.isEnabled = false
                return
            }
            binding.tvRemaining.text = "剩余有效期：${formatRemaining(s.remainingMillis())}"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTtsKeySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopyUrl.setOnClickListener { copyUrl() }
        binding.btnStop.setOnClickListener {
            server?.stop()
            hideQr()
            binding.tvStatus.text = "配置服务已关闭。"
            binding.btnCopyUrl.isEnabled = false
            handler.removeCallbacks(ticker)
        }

        startServer()
    }

    private fun startServer() {
        val s = LocalConfigServer(this) { model ->
            if (!isFinishing && !isDestroyed) {
                saved = true
                handler.removeCallbacks(ticker)
                hideQr()
                binding.tvStatus.text = "已保存：模型 $model\n服务已关闭，可返回设置页测试朗读。"
                binding.tvRemaining.text = ""
                binding.btnCopyUrl.isEnabled = false
            }
        }
        if (!s.start()) {
            binding.tvUrl.text = "无法启动配置页"
            binding.tvStatus.text = "请确认平板已连接 Wi-Fi 局域网后重试。"
            binding.btnCopyUrl.isEnabled = false
            binding.btnStop.isEnabled = false
            return
        }
        server = s
        binding.tvUrl.text = s.url
        binding.tvStatus.text = "等待填写…"
        renderQr(s.url)
        handler.post(ticker)
    }

    /** 二维码只在本页存活、链接有效期间显示；不落盘、不打日志 */
    private fun renderQr(url: String) {
        if (url.isEmpty()) return
        val sizePx = (220 * resources.displayMetrics.density).toInt()
        val bitmap = QrCode.render(url, sizePx) ?: return
        qrBitmap = bitmap
        binding.ivQr.setImageBitmap(bitmap)
        binding.ivQr.visibility = View.VISIBLE
        binding.tvQrHint.visibility = View.VISIBLE
    }

    private fun hideQr() {
        binding.ivQr.setImageDrawable(null)
        binding.ivQr.visibility = View.GONE
        binding.tvQrHint.visibility = View.GONE
        qrBitmap?.recycle()
        qrBitmap = null
    }

    private fun copyUrl() {
        val url = binding.tvUrl.text.toString()
        if (url.isEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tts-config-url", url))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun formatRemaining(ms: Long): String {
        val total = (ms / 1000).toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        server?.stop()
        hideQr()
        super.onDestroy()
    }
}
