package com.yunhu.tv.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View

/** Markdown 轻量渲染：纯文本样式（不产生图片 bitmap，内存友好） */
object MarkdownLite {

    fun toPlain(src: String): String {
        var s = src
        s = s.replace(Regex("!\\[([^]]*)]\\([^)]*\\)")) { "[图片 ${it.groupValues[1]}]" }
        s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)")) { it.groupValues[1] }
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*")) { it.groupValues[1] }
        s = s.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { it.groupValues[1] }
        s = s.replace(Regex("~~([^~]+)~~")) { it.groupValues[1] }
        s = s.replace(Regex("`([^`]+)`")) { it.groupValues[1] }
        s = s.replace(Regex("(?m)^>\\s?"), "▎")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        return s.trim()
    }

    /** 提取 Markdown 中所有图片地址 ![alt](url) */
    fun extractImages(src: String): List<String> =
        Regex("!\\[[^]]*]\\(([^)]+)\\)").findAll(src).map { it.groupValues[1].trim() }.toList()

    /**
     * 转为带样式的 CharSequence（标题/加粗/斜体/删除线/行内代码/引用/列表/链接）。
     * 仅使用文本 span，不加载任何图片，内存占用低。
     */
    fun toSpanned(src: String, baseSizePx: Int, onLink: (String) -> Unit = {}): CharSequence {
        val sb = SpannableStringBuilder()
        src.lineSequence().forEach { raw ->
            var line = raw
            // 跳过图片行（图片另行用 ImageView 展示）
            if (line.matches(Regex("\\s*!\\[[^]]*]\\([^)]*\\)\\s*"))) {
                sb.append('\n')
                return@forEach
            }
            // 标题
            val heading = Regex("^(#{1,6})\\s*(.+)$").find(line)
            val start = sb.length
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2]
                sb.append(renderInline(text, onLink))
                val size = (baseSizePx * (1.6 - (level - 1) * 0.12)).toInt()
                sb.setSpan(AbsoluteSizeSpan(size, false), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val quote = Regex("^>\\s?(.*)$").find(line)
                if (quote != null) {
                    sb.append("▎ ")
                    sb.append(renderInline(quote.groupValues[1], onLink))
                    sb.setSpan(QuoteSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val list = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
                    if (list != null) {
                        sb.append("• ")
                        sb.append(renderInline(list.groupValues[1], onLink))
                    } else {
                        sb.append(renderInline(line, onLink))
                    }
                }
            }
            sb.append('\n')
        }
        return sb.trim()
    }

    /** 行内样式：加粗/斜体/删除线/代码/链接 */
    private fun renderInline(text: String, onLink: (String) -> Unit): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        val s = out.length
                        out.append(text, i + 2, end)
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else { out.append(text[i]); i++ }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > 0) {
                        val s = out.length
                        out.append(text, i + 2, end)
                        out.setSpan(StrikethroughSpan(), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else { out.append(text[i]); i++ }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && text[end - 1] != ' ' && text[i + 1] != ' ') {
                        val s = out.length
                        out.append(text, i + 1, end)
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else { out.append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        val s = out.length
                        out.append(text, i + 1, end)
                        out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else { out.append(text[i]); i++ }
                }
                text.startsWith("[", i) -> {
                    val close = text.indexOf("](", i)
                    if (close > 0) {
                        val urlEnd = text.indexOf(')', close + 2)
                        if (urlEnd > 0) {
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, urlEnd)
                            val s = out.length
                            out.append(label)
                            out.setSpan(object : android.text.style.ClickableSpan() {
                                override fun onClick(widget: View) { onLink(url) }
                                override fun updateDrawState(ds: android.text.TextPaint) {
                                    ds.color = 0xFF4FC3F7.toInt()
                                    ds.isUnderlineText = true
                                }
                            }, s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = urlEnd + 1
                        } else { out.append(text[i]); i++ }
                    } else { out.append(text[i]); i++ }
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out
    }
}
