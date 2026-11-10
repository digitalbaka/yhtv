package com.yunhu.tv.voice

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 调试实现：不需要任何麦克风硬件。
 *
 * 两种来源：
 * 1. 调试面板里直接敲的文本；
 * 2. 用系统文件选择器选中的本地 wav（只读出字节数验证可读性，不做语音识别）。
 */
class DebugVoiceInput(
    private val context: Context,
    private val typedText: String,
    private val wavUri: Uri? = null,
    private val wavName: String? = null
) : VoiceInput {

    override val id: String = ID

    override suspend fun stop(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (wavUri != null) {
                val size = readSize(wavUri)
                Log.i(TAG, "wav picked: name=$wavName size=$size bytes uri=$wavUri")
                // 没有识别引擎，用可读的占位文本让链路继续往下走
                typedText.trim().ifEmpty { "[wav] ${wavName ?: "本地文件"} · $size 字节" }
            } else {
                val text = typedText.trim()
                require(text.isNotEmpty()) { "请输入文本，或选择一个 wav 文件" }
                text
            }
        }
    }

    /** 读一遍文件，确认真的能读出来（不落盘、不缓存） */
    private fun readSize(uri: Uri): Long {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
            }
            if (total == 0L) throw IllegalStateException("所选文件为空")
            return total
        }
        throw IllegalStateException("无法读取所选文件")
    }

    companion object {
        const val ID = "debug / DebugVoiceInput"
        private const val TAG = VoiceResultSink.TAG
    }
}
