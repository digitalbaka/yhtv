package com.yunhu.tv.util

/** 将 Markdown 文本转为电视上易读的纯文本（保留换行与基本结构） */
object MarkdownLite {

    fun toPlain(src: String): String {
        var s = src
        // 图片/链接: ![alt](url) -> [图片 alt] ; [text](url) -> text
        s = s.replace(Regex("!\\[([^]]*)]\\([^)]*\\)")) { "[图片 ${it.groupValues[1]}]" }
        s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)")) { it.groupValues[1] }
        // 标题/加粗/斜体/删除线/行内代码标记
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("\\*\\*([^*]+)\\*\\*")) { it.groupValues[1] }
        s = s.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")) { it.groupValues[1] }
        s = s.replace(Regex("~~([^~]+)~~")) { it.groupValues[1] }
        s = s.replace(Regex("`([^`]+)`")) { it.groupValues[1] }
        // 引用块
        s = s.replace(Regex("(?m)^>\\s?"), "▎")
        // 无序列表
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        return s.trim()
    }

    /** 提取 Markdown 中所有图片地址 ![alt](url) */
    fun extractImages(src: String): List<String> =
        Regex("!\\[[^]]*]\\(([^)]+)\\)").findAll(src).map { it.groupValues[1].trim() }.toList()
}
