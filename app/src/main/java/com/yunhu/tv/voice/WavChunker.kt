package com.yunhu.tv.voice

import java.io.File
import java.io.RandomAccessFile

/**
 * 长音频切段：单次识别请求普遍有秒数 / 体积上限，超过就切成 ≤60 秒的段依次识别。
 *
 * 目前只对 PCM WAV 做真实切段（能精确计算样本、找静音点）；
 * webm/opus、m4a、amr、mp3 没有解码器可用，按原样整段发送，由服务端限制兜底。
 */
object WavChunker {

    /** 每段目标时长，留成常量方便调 */
    const val SEGMENT_MS = 60_000L

    /** 切点搜索窗口与帧长 */
    private const val SILENCE_WINDOW_MS = 2_000L
    private const val FRAME_MS = 20L

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {
        val blockAlign: Int get() = channels * bitsPerSample / 8
        val bytesPerMs: Double get() = sampleRate * blockAlign / 1000.0
    }

    /**
     * 返回切好的分段；不需要切（非 WAV / 短于一段 / 解析失败）时返回单元素列表，
     * 调用方负责删除临时文件。
     */
    fun split(clip: AudioClip, tempDir: File): List<AudioClip> {
        val file = clip.file ?: return listOf(clip)
        val info = parse(file) ?: return listOf(clip)
        val totalMs = info.dataSize * 1000 / (info.sampleRate * info.blockAlign).coerceAtLeast(1)
        if (totalMs <= SEGMENT_MS) return listOf(clip)

        val segmentBytes = align((info.bytesPerMs * SEGMENT_MS).toLong(), info.blockAlign)
        val windowBytes = align((info.bytesPerMs * SILENCE_WINDOW_MS).toLong(), info.blockAlign)
        val frameBytes = align((info.bytesPerMs * FRAME_MS).toLong(), info.blockAlign)
            .coerceAtLeast(info.blockAlign.toLong())

        val out = ArrayList<AudioClip>()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            while (offset < info.dataSize) {
                var end = (offset + segmentBytes).coerceAtMost(info.dataSize)
                if (end < info.dataSize) end = findSilenceCut(raf, info, offset, end, windowBytes, frameBytes)
                val size = end - offset
                if (size <= 0) break
                val bytes = ByteArray(size.toInt())
                raf.seek(info.dataOffset + offset)
                raf.readFully(bytes)
                val chunk = File.createTempFile("asr-seg-", ".wav", tempDir)
                chunk.writeBytes(pcmWav(info, bytes))
                out.add(
                    AudioClip(
                        bytes = ByteArray(0),
                        mime = "audio/wav",
                        durationMs = size * 1000 / (info.sampleRate * info.blockAlign).coerceAtLeast(1),
                        file = chunk
                    )
                )
                offset = end
            }
        }
        return if (out.isEmpty()) listOf(clip) else out
    }

    /** 在 [end-window, end] 里找能量最低的 20ms 帧当切点；找不到就用原切点 */
    private fun findSilenceCut(
        raf: RandomAccessFile,
        info: WavInfo,
        start: Long,
        end: Long,
        windowBytes: Long,
        frameBytes: Long
    ): Long {
        val from = (end - windowBytes).coerceAtLeast(start)
        val len = (end - from).coerceAtMost(512 * 1024).toInt()
        if (len <= frameBytes) return end
        val buf = ByteArray(len)
        raf.seek(info.dataOffset + from)
        raf.readFully(buf)
        var best = end
        var bestEnergy = Long.MAX_VALUE
        var i = 0
        while (i + frameBytes <= len) {
            var energy = 0L
            var j = 0
            while (j + 1 < frameBytes) {
                val s = ((buf[i + j].toInt() and 0xFF) or (buf[i + j + 1].toInt() shl 8)).toShort().toInt()
                energy += s.toLong() * s
                j += 2
            }
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = from + i
            }
            i += frameBytes.toInt()
        }
        return best.coerceIn(start + info.blockAlign, end)
    }

    private fun parse(file: File): WavInfo? {
        if (file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = 0L
            var pos = 12L
            val len = file.length()
            while (pos + 8 <= len) {
                raf.seek(pos)
                val ch = ByteArray(8)
                raf.readFully(ch)
                val id = String(ch, 0, 4, Charsets.US_ASCII)
                val size = (ch[4].toLong() and 0xFF) or
                    ((ch[5].toLong() and 0xFF) shl 8) or
                    ((ch[6].toLong() and 0xFF) shl 16) or
                    ((ch[7].toLong() and 0xFF) shl 24)
                if (id == "fmt ") {
                    val fmt = ByteArray(size.coerceAtMost(32).toInt())
                    raf.readFully(fmt)
                    if (fmt.size >= 16) {
                        channels = ((fmt[2].toInt() and 0xFF) or (fmt[3].toInt() shl 8))
                        sampleRate = (fmt[4].toInt() and 0xFF) or
                            ((fmt[5].toInt() and 0xFF) shl 8) or
                            ((fmt[6].toInt() and 0xFF) shl 16) or
                            ((fmt[7].toInt() and 0xFF) shl 24)
                        bits = (fmt[14].toInt() and 0xFF) or (fmt[15].toInt() shl 8)
                    }
                } else if (id == "data") {
                    dataOffset = pos + 8
                    dataSize = size.coerceAtMost(len - dataOffset)
                }
                pos += 8 + size + (size and 1)
                if (dataOffset >= 0 && sampleRate > 0) break
            }
            if (dataOffset < 0 || sampleRate <= 0 || channels <= 0 || bits <= 0) return null
            return WavInfo(sampleRate, channels, bits, dataOffset, dataSize)
        }
    }

    private fun pcmWav(info: WavInfo, data: ByteArray): ByteArray {
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) {
            val b = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(b, 0, header, off, b.size)
        }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, 36 + data.size)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, info.channels)
        putInt(24, info.sampleRate)
        putInt(28, info.sampleRate * info.blockAlign)
        putShort(32, info.blockAlign)
        putShort(34, info.bitsPerSample)
        putStr(36, "data")
        putInt(40, data.size)
        return header + data
    }

    private fun align(v: Long, block: Int): Long = if (block <= 0) v else v / block * block
}
