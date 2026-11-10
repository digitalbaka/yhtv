package com.yunhu.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.tv.databinding.ActivityVoiceDebugBinding
import com.yunhu.tv.voice.DebugVoiceInput
import com.yunhu.tv.voice.VoiceResultSink
import kotlinx.coroutines.launch
import java.io.File

/**
 * 语音输入调试面板，仅在 debuggable 包里从「我的」页进入。
 * 让没有任何麦克风的电视也能验证「拿到文本 → 交给聊天页」这条链路。
 */
class VoiceDebugPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceDebugBinding
    private var wavUri: Uri? = null
    private var wavName: String? = null

    private val pickWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPicked(uri)
    }

    private val requestRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showWavChooser() else binding.tvStatus.text = "未授权读取存储，无法列出 wav"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvImpl.text = "当前实现：${DebugVoiceInput.ID}"
        FocusEffects.apply(binding.btnPickWav)
        FocusEffects.apply(binding.btnDispatch)

        binding.btnPickWav.setOnClickListener { pickWavFile() }
        binding.btnDispatch.setOnClickListener { runOnce() }
    }

    private fun pickWavFile() {
        try {
            pickWav.launch(arrayOf("audio/*", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            // 这台电视没有系统文件选择器（DocumentsUI），退回内置的简易列表
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                showWavChooser()
            } else {
                requestRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun showWavChooser() {
        val files = wavCandidates()
        if (files.isEmpty()) {
            binding.tvStatus.text = "没找到 wav 文件，请先把文件放到 Download 目录"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择 wav（${files.size} 个）")
            .setItems(files.map { it.name }.toTypedArray()) { _, i -> onPicked(Uri.fromFile(files[i])) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun wavCandidates(): List<File> {
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            getExternalFilesDir(null),
            File("/sdcard")
        )
        return dirs.flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav", true) } ?: emptyList()
        }.distinctBy { it.absolutePath }
    }

    private fun onPicked(uri: Uri?) {
        if (uri == null) return
        wavUri = uri
        wavName = uri.lastPathSegment ?: uri.toString()
        binding.tvWav.text = "已选择：$wavName"
        // SAF 给的 content:// 才需要持久读权限
        if (uri.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }

    private fun runOnce() {
        val input = DebugVoiceInput(
            context = this,
            typedText = binding.etText.text.toString(),
            wavUri = wavUri,
            wavName = wavName
        )
        lifecycleScope.launch {
            binding.tvStatus.text = "执行中…"
            input.stop()
                .onSuccess { text ->
                    val delivered = VoiceResultSink.dispatch(text)
                    binding.tvStatus.text = if (delivered) {
                        "得到文本：$text\n已填入聊天输入框（未发送）"
                    } else {
                        "得到文本：$text\n聊天页未打开，已暂存，进入聊天后自动填入"
                    }
                }
                .onFailure { e ->
                    binding.tvStatus.text = "失败：${e.message}"
                }
        }
    }
}
