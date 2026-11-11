package com.yunhu.tv.net

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * 最小 multipart/form-data 解析：按 boundary 做字节级切分，不靠字符串查找拼接。
 *
 * 前提：请求体已由 [LanHttpServer] 流式落盘，这里顺序扫描临时文件，
 * 每个 part 直接写进自己的临时文件，不把整段音频读进内存。
 */
object Multipart {

    data class Part(
        val name: String,
        val fileName: String?,
        val contentType: String?,
        val file: File,
        val size: Long
    ) {
        fun readText(): String = file.readText(Charsets.UTF_8).trim()
    }

    /**
     * @param body 已落盘的请求体
     * @param contentType 形如 `multipart/form-data; boundary=----WebKitFormBoundaryxxx`
     * @param outDir part 临时文件目录
     * @return 解析出的 parts；不是 multipart 时返回 null
     */
    fun parse(body: File, contentType: String, outDir: File): List<Part>? {
        val boundary = boundaryOf(contentType) ?: return null
        val first = "--$boundary".toByteArray(Charsets.US_ASCII)
        val delim = "\r\n--$boundary".toByteArray(Charsets.US_ASCII)
        val parts = ArrayList<Part>()
        FileInputStream(body).use { raw ->
            val input = PushbackInputStream(BufferedInputStream(raw, 64 * 1024), 128 * 1024)
            if (!skipFirstBoundary(input, first)) return emptyList()
            while (true) {
                val headers = readHeaders(input) ?: break
                val part = createPart(headers, outDir) ?: break
                var size = 0L
                FileOutputStream(part.file).use { out ->
                    size = copyUntilDelimiter(input, out, delim)
                }
                parts.add(part.copy(size = size))
                // 分隔符之后：`--` 结束，或 `\r\n` 继续下一个 part
                val b1 = input.read()
                val b2 = input.read()
                if (b1 == '-'.code && b2 == '-'.code) break
                if (b1 == '\r'.code && b2 == '\n'.code) continue
                break
            }
        }
        return parts
    }

    fun boundaryOf(contentType: String): String? {
        if (!contentType.startsWith("multipart/form-data", ignoreCase = true)) return null
        contentType.split(';').forEach { seg ->
            val s = seg.trim()
            if (s.startsWith("boundary=", ignoreCase = true)) {
                return s.substringAfter('=').trim().trim('"')
            }
        }
        return null
    }

    private fun skipFirstBoundary(input: InputStream, first: ByteArray): Boolean {
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return false
            if (b == first[matched].toInt()) {
                matched++
                if (matched == first.size) {
                    // 首行 boundary 后面应是 CRLF
                    if (input.read() == '\r'.code) input.read()
                    return true
                }
            } else {
                matched = if (b == first[0].toInt()) 1 else 0
            }
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = HashMap<String, String>()
        var line = readLine(input) ?: return null
        while (line.isNotEmpty()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            line = readLine(input) ?: return null
        }
        return headers
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8 * 1024) return null
            sb.append(b.toChar())
        }
    }

    private fun createPart(headers: Map<String, String>, outDir: File): Part? {
        val disposition = headers["content-disposition"] ?: return null
        val name = attrOf(disposition, "name") ?: return null
        val fileName = attrOf(disposition, "filename")
        val file = File.createTempFile("part-", ".bin", outDir)
        return Part(name, fileName, headers["content-type"], file, 0)
    }

    private fun attrOf(header: String, key: String): String? {
        header.split(';').forEach { seg ->
            val s = seg.trim()
            if (s.startsWith("$key=", ignoreCase = true)) {
                return s.substringAfter('=').trim().trim('"')
            }
        }
        return null
    }

    /** 把内容写进 out，直到遇到分隔符（分隔符本身不写入，剩余字节回推） */
    private fun copyUntilDelimiter(
        input: PushbackInputStream,
        out: FileOutputStream,
        delim: ByteArray
    ): Long {
        val keep = delim.size - 1
        val buf = ByteArray(64 * 1024)
        var carry = ByteArray(0)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val data = if (carry.isEmpty()) {
                if (n == buf.size) buf.copyOf(n) else buf.copyOf(n)
            } else {
                carry + buf.copyOf(n)
            }
            val hit = indexOf(data, delim)
            if (hit >= 0) {
                out.write(data, 0, hit)
                written += hit
                val rest = data.size - hit - delim.size
                if (rest > 0) input.unread(data, hit + delim.size, rest)
                return written
            }
            val safe = data.size - keep
            if (safe > 0) {
                out.write(data, 0, safe)
                written += safe
                carry = data.copyOfRange(safe, data.size)
            } else {
                carry = data
            }
        }
        out.write(carry)
        written += carry.size
        return written
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        val last = hay.size - needle.size
        var i = 0
        while (i <= last) {
            var j = 0
            while (j < needle.size && hay[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}
