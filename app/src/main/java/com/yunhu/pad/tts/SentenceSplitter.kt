package com.yunhu.pad.tts

/**
 * 长按朗读用的分句规则，与电视端保持一致：
 * 先按 。！？； 和换行切句；单句过长时再按逗号类标点切；仍过长则按固定长度切块。
 * 只做纯文本切分，不依赖任何跨仓代码。
 */
object SentenceSplitter {

    /** 单段最大字数，超过就继续切，避免一次丢给引擎的文本过长 */
    private const val MAX_SEGMENT = 60

    fun split(raw: String): List<String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in text) {
            buffer.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n') {
                addChunk(out, buffer.toString())
                buffer.setLength(0)
            }
        }
        addChunk(out, buffer.toString())
        return out
    }

    /** 一级切分结果：先按逗号类标点再切一轮，最后按长度硬切 */
    private fun addChunk(out: MutableList<String>, chunk: String) {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.length <= MAX_SEGMENT) {
            out.add(trimmed)
            return
        }
        val buffer = StringBuilder()
        for (ch in trimmed) {
            buffer.append(ch)
            if (ch == '，' || ch == '、' || ch == '：') {
                addFixed(out, buffer.toString())
                buffer.setLength(0)
            }
        }
        addFixed(out, buffer.toString())
    }

    /** 按固定长度硬切，保证每段都不超过 MAX_SEGMENT */
    private fun addFixed(out: MutableList<String>, piece: String) {
        val trimmed = piece.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.length <= MAX_SEGMENT) {
            out.add(trimmed)
            return
        }
        var start = 0
        while (start < trimmed.length) {
            val end = minOf(start + MAX_SEGMENT, trimmed.length)
            out.add(trimmed.substring(start, end))
            start = end
        }
    }
}
