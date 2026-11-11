package com.yunhu.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yunhu.tv.databinding.ActivityVoiceDebugBinding
import com.yunhu.tv.voice.AudioSource
import com.yunhu.tv.voice.HttpAsrRecognizer
import com.yunhu.tv.voice.PlaceholderRecognizer
import com.yunhu.tv.voice.VoiceOutcome
import com.yunhu.tv.voice.VoicePipeline
import com.yunhu.tv.voice.VoicePrefs
import com.yunhu.tv.voice.VoiceResultSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // 打开面板默认聚焦「执行」，不自动弹软键盘
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        binding.root.isFocusableInTouchMode = true
        binding.etText.clearFocus()
        binding.btnDispatch.requestFocus()

        val active = if (VoicePrefs.isAsrReady(VoicePrefs.asr)) HttpAsrRecognizer.ID else PlaceholderRecognizer.ID
        binding.tvImpl.text =
            "当前实现：$active（${PlaceholderRecognizer.ID} / ${HttpAsrRecognizer.ID}，同一接口可互换）"
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
        val typed = binding.etText.text.toString().trim()
        if (typed.isNotEmpty()) {
            val outcome = VoiceOutcome(text = typed, placeholder = false, engine = "手动输入")
            val delivered = VoiceResultSink.dispatch(outcome, VoiceResultSink.Origin.TYPED)
            binding.tvStatus.text = if (delivered) {
                "得到文本：$typed\n已填入聊天输入框（未发送）"
            } else {
                "得到文本：$typed\n聊天页未打开，已暂存，进入聊天后自动填入"
            }
            return
        }

        val uri = wavUri
        if (uri == null) {
            binding.tvStatus.text = "请输入文本，或选择一个 wav 文件"
            return
        }
        val name = wavName ?: "本地文件"
        lifecycleScope.launch {
            binding.tvStatus.text = "识别中…"
            runCatching {
                val clip = withContext(Dispatchers.IO) {
                    AudioSource.readFromUri(this@VoiceDebugPanelActivity, uri, name)
                }
                VoicePipeline.transcribe(clip)
            }.onSuccess { outcome ->
                // wav 来源只在面板展示，不写进聊天输入框（避免把调试描述当消息发出去）
                VoiceResultSink.dispatch(outcome, VoiceResultSink.Origin.FILE)
                binding.tvStatus.text = "结果（仅面板展示）：${outcome.text}\n引擎：${outcome.engine}" +
                    if (outcome.note.isNotEmpty()) "\n说明：${outcome.note}" else ""
            }.onFailure { e ->
                binding.tvStatus.text = "失败：${e.message}"
            }
        }
    }
}
