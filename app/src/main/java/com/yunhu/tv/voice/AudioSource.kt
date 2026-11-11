package com.yunhu.tv.voice

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 本地音频读取与 WAV 时长解析（调试面板选 wav 时用）。
 * 只读进内存，不落盘、不缓存。
 */
object AudioSource {

    private const val MAX_BYTES = 20 * 1024 * 1024

    fun readFromUri(context: Context, uri: Uri, fallbackName: String): AudioClip {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) throw IllegalStateException("文件超过 20MB")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("无法读取所选文件")

        if (bytes.isEmpty()) throw IllegalStateException("所选文件为空")
        val mime = if (fallbackName.endsWith(".wav", true) || isWav(bytes)) "audio/wav" else "application/octet-stream"
        return AudioClip(bytes = bytes, mime = mime, durationMs = wavDurationMs(bytes))
    }

    private fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /**
     * 按 WAV 头的 data 块大小与 byteRate 计算时长；解析不了返回 0。
     * 小端解析，足够覆盖 PCM wav。
     */
    fun wavDurationMs(bytes: ByteArray): Long {
        if (!isWav(bytes)) return 0
        var pos = 12
        var byteRate = 0
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readIntLe(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (body + 12 <= bytes.size) byteRate = readIntLe(bytes, body + 8)
                "data" -> {
                    dataSize = if (size > 0 && body + size <= bytes.size) size else bytes.size - body
                    break
                }
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        if (byteRate <= 0 || dataSize <= 0) return 0
        return dataSize.toLong() * 1000 / byteRate
    }

    private fun readIntLe(b: ByteArray, offset: Int): Int {
        if (offset + 4 > b.size) return 0
        return (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
    }
}
