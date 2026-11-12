package com.yunhu.tv.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极小号局域网 HTTP 服务，只为本机的一次性配置 / 录音页面服务。
 *
 * 安全约束：
 * - 只绑定本机网卡，且拒绝非局域网来源；
 * - 路径里带一次性口令，口令只能匹配一次（提交后立即关闭服务）；
 * - 5 分钟过期自动关闭；
 * - 不打印请求体、路径口令与配置内容，密钥只在内存里过一遍。
 */
class LanHttpServer(
    private val prefix: String,
    private val token: String,
    /** 请求体落盘目录（调用方传 context.cacheDir） */
    private val tempDir: File,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    /** 口令不匹配时（服务重启/链接过期）给浏览器 GET 的兜底页面 */
    private val expiredHandler: (() -> Response)? = null,
    private val handler: (Request) -> Response
) {

    data class Request(
        val method: String,
        /** 去掉 `/前缀/口令` 之后剩下的子路径，例如 ""、"/submit"、"audio" */
        val subPath: String,
        val query: Map<String, String>,
        val contentType: String,
        /** 小请求体（≤ 512KB）直接给出字节；大请求体走 bodyFile */
        val body: ByteArray,
        /** 大请求体落盘文件；handler 用完负责删除 */
        val bodyFile: File?,
        val bodySize: Long
    )

    data class Response(
        val status: Int = 200,
        val contentType: String = "text/html; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        /** true 表示这次响应后关闭服务（一次性口令用掉） */
        val closeAfter: Boolean = false
    ) {
        constructor(
            status: Int,
            contentType: String,
            text: String,
            closeAfter: Boolean = false
        ) : this(status, contentType, text.toByteArray(Charsets.UTF_8), closeAfter)

        companion object {
            fun html(text: String, closeAfter: Boolean = false) =
                Response(200, "text/html; charset=utf-8", text.toByteArray(Charsets.UTF_8), closeAfter)

            fun json(text: String, closeAfter: Boolean = false) =
                Response(200, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8), closeAfter)

            fun plain(status: Int, text: String, closeAfter: Boolean = false) =
                Response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8), closeAfter)
        }
    }

    interface Listener {
        /** 服务停止（提交成功 / 超时 / 手动取消 / 异常），只会回调一次；可能在后台线程 */
        fun onStopped(reason: String)
    }

    var port: Int = 0
        private set

    var isRunning: Boolean = false
        private set

    private var socket: ServerSocket? = null
    private var listener: Listener? = null
    private val stopped = AtomicBoolean(false)

    /**
     * @param bindHost 指定绑定的本机地址（如局域网 IPv4）；为空时沿用原来的 0.0.0.0。
     *                 指定地址绑不上时退回 0.0.0.0，仍由 [isLanAddress] 拦截非局域网来源。
     */
    fun start(
        preferredPort: Int,
        onStopped: Listener? = null,
        bindHost: String? = null
    ): Boolean {
        if (isRunning) return true
        listener = onStopped
        stopped.set(false)
        var server = bind(bindHost, preferredPort) ?: bind(bindHost, 0)
        if (server == null && bindHost != null) {
            Log.i(TAG, "bind $bindHost failed, fallback to 0.0.0.0")
            server = bind(null, preferredPort) ?: bind(null, 0)
        }
        val bound = server ?: return false
        socket = bound
        port = bound.localPort
        isRunning = true
        Log.i(TAG, "lan server started for /$prefix on port $port")
        Thread({ acceptLoop(bound) }, "lan-$prefix").apply { isDaemon = true }.start()
        return true
    }

    fun stop(reason: String) {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        if (stopped.compareAndSet(false, true)) {
            Log.i(TAG, "lan server stopped for /$prefix: $reason")
            listener?.onStopped(reason)
        }
    }

    private fun bind(host: String?, port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            soTimeout = ACCEPT_TIMEOUT_MS
            val address = if (host.isNullOrEmpty()) InetSocketAddress(port)
            else InetSocketAddress(InetAddress.getByName(host), port)
            bind(address, 8)
        }
    } catch (_: Exception) {
        null
    }

    private fun acceptLoop(server: ServerSocket) {
        // ttlMs <= 0 表示不过期（设置里选「永久有效」）
        val deadline = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else Long.MAX_VALUE
        try {
            while (isRunning) {
                if (System.currentTimeMillis() > deadline) {
                    stop("已超时")
                    return
                }
                val client = try {
                    server.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    if (isRunning) stop("服务异常")
                    return
                }
                client.use { c ->
                    c.soTimeout = READ_TIMEOUT_MS
                    val remote = c.inetAddress
                    if (!isLanAddress(remote)) {
                        Log.i(TAG, "rejected non-lan client: ${remote.hostAddress}")
                        writeResponse(c.getOutputStream(), Response.plain(403, "only LAN clients allowed"))
                        return@use
                    }
                    try {
                        handle(c.getInputStream(), c.getOutputStream())
                    } catch (e: Exception) {
                        Log.i(TAG, "request failed: ${e.javaClass.simpleName}")
                        runCatching {
                            writeResponse(c.getOutputStream(), Response.plain(400, "bad request"))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (isRunning) stop("服务异常")
        }
    }

    private fun handle(input: InputStream, output: OutputStream) {
        val buffered = BufferedInputStream(input)
        val requestLine = readLine(buffered) ?: return
        val segments = requestLine.split(' ')
        if (segments.size < 2) return
        val method = segments[0].uppercase()
        val target = segments[1]

        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(buffered) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) return
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] != prefix || !tokenEquals(parts[1])) {
            // 口令对不上：服务重启或链接过期后手机再扫旧码就会走到这里。
            // 浏览器 GET 时给出「链接已失效」，其它情况仍回 404 不暴露细节。
            val fallback = expiredHandler?.invoke()
            if (method == "GET" && fallback != null) {
                writeResponse(output, fallback)
            } else {
                writeResponse(output, Response.plain(404, "not found"))
            }
            return
        }
        val subPath = parts.drop(2).joinToString("/")

        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        // GET / HEAD 本来就没有请求体，浏览器打开页面时不会带 Content-Length
        val hasBody = method != "GET" && method != "HEAD" && method != "DELETE"
        if (hasBody && contentLength > MAX_BODY_BYTES) {
            writeResponse(output, Response.plain(413, "音频超过 50 MB 上限"))
            return
        }
        if (hasBody && contentLength < 0) {
            // 老 WebView 的 XHR 上传会带 Content-Length；chunked 不做支持
            writeResponse(output, Response.plain(411, "缺少 Content-Length"))
            return
        }
        if (hasBody && chunked) {
            writeResponse(output, Response.plain(411, "不支持 chunked 上传"))
            return
        }

        var body = ByteArray(0)
        var bodyFile: File? = null
        var bodySize = 0L
        val length = if (hasBody) contentLength else 0L
        try {
            if (length <= MEM_BODY_LIMIT) {
                body = ByteArray(length.toInt())
                var read = 0
                while (read < length) {
                    val n = buffered.read(body, read, length.toInt() - read)
                    if (n <= 0) break
                    read += n
                }
                bodySize = read.toLong()
                if (read < length) body = body.copyOf(read)
            } else {
                val f = File.createTempFile("req-", ".bin", tempDir)
                bodyFile = f
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var remain = length
                    while (remain > 0) {
                        val n = buffered.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remain -= n
                        bodySize += n
                    }
                }
            }

            val response = handler(
                Request(
                    method = method,
                    subPath = subPath,
                    query = query,
                    contentType = headers["content-type"] ?: "",
                    body = body,
                    bodyFile = bodyFile,
                    bodySize = bodySize
                )
            )
            writeResponse(output, response)
            if (response.closeAfter) stop("已完成")
        } finally {
            bodyFile?.delete()
        }
    }

    private fun writeResponse(output: OutputStream, response: Response) {
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun tokenEquals(candidate: String): Boolean = try {
        MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8)
        )
    } catch (_: Exception) {
        false
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > MAX_LINE_CHARS) return null
            sb.append(b.toChar())
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            try {
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
                k to v
            }
        }.toMap()
    }

    companion object {
        private const val TAG = "VoiceLan"
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        private const val ACCEPT_TIMEOUT_MS = 500
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_LINE_CHARS = 8 * 1024
        /** 接收上限 50 MB（手机端会先压一道，正常远低于此） */
        private const val MAX_BODY_BYTES = 50L * 1024 * 1024
        /** 超过该大小的请求体不再进内存，改为流式落盘 */
        private const val MEM_BODY_LIMIT = 512L * 1024

        /** 16 字节随机口令，URL 安全 */
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
        }

        /** 本机局域网 IPv4；优先 wlan 网卡 */
        fun localIpv4(): String? {
            val candidates = ArrayList<InetAddress>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                            candidates.add(addr)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return candidates.firstOrNull()?.hostAddress
        }

        fun isLanAddress(addr: InetAddress): Boolean =
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
    }
}
