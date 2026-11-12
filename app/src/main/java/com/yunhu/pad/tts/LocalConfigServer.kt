package com.yunhu.pad.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * 只监听局域网的极简配置页服务。
 * - 绑定到本机 Wi-Fi 局域网地址，不监听 0.0.0.0 / 回环；
 * - 链接里带一次性口令，5 分钟过期，保存成功后立即失效并关闭服务；
 * - 表单里的 key 用 password 输入框，不回显；服务端不打印任何请求内容。
 */
class LocalConfigServer(
    private val context: Context,
    private val onSaved: (model: String) -> Unit
) {

    companion object {
        // 口令有效期固定 5 分钟：Android 7 剪贴板对所有应用可读，暴露窗口越短越好
        private const val VALID_MILLIS = 5 * 60 * 1000L
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024
    }

    private val main = Handler(Looper.getMainLooper())
    private val token = randomToken()

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private var startedAt = 0L

    @Volatile
    private var closed = false

    @Volatile
    private var used = false

    var port = -1
        private set

    private var host = ""

    val url: String get() = if (port > 0) "http://$host:$port/$token" else ""

    /** 启动失败返回 false（通常是没连局域网或端口被占用） */
    fun start(): Boolean {
        val lan = findLanAddress() ?: return false
        return try {
            val socket = ServerSocket(0, 4, InetAddress.getByName(lan))
            serverSocket = socket
            host = lan
            port = socket.localPort
            startedAt = System.currentTimeMillis()
            thread = Thread({ acceptLoop(socket) }, "tts-config-server").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        closed = true
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun isExpired(): Boolean = remainingMillis() <= 0

    fun remainingMillis(): Long = (VALID_MILLIS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0)

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            try {
                client.soTimeout = 5000
                handle(client)
            } catch (e: Exception) {
                // 请求异常不作为服务失败处理，直接丢弃连接
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val input = client.inputStream
        val request = readRequest(input) ?: return
        val output = client.outputStream

        if (request.method == "GET") {
            when {
                request.path != "/$token" -> respond(output, "404 Not Found", page("链接无效", "请回到平板上的配置页重新获取链接。"))
                used -> respond(output, "410 Gone", page("口令已失效", "该链接已使用过，请回平板重新生成。"))
                isExpired() -> respond(output, "410 Gone", page("口令已过期", "请在平板上重新打开配置页获取新链接。"))
                else -> respond(output, "200 OK", formPage())
            }
            return
        }

        if (request.method != "POST") {
            respond(output, "405 Method Not Allowed", page("不支持的方法", ""))
            return
        }
        when {
            request.path != "/$token" || used -> {
                respond(output, "410 Gone", page("口令已失效", "请回平板重新生成链接。"))
                return
            }
            isExpired() -> {
                respond(output, "410 Gone", page("口令已过期", "请在平板上重新打开配置页。"))
                return
            }
        }

        val form = parseForm(request.body)
        val baseUrl = form["baseUrl"].orEmpty().trim()
        val model = form["model"].orEmpty().trim()
        val voice = form["voice"].orEmpty().trim()
        val key = form["key"].orEmpty().trim()

        val invalid = when {
            !baseUrl.startsWith("https://") -> "baseUrl 必须以 https:// 开头"
            baseUrl.length > 300 -> "baseUrl 过长"
            model.isEmpty() -> "模型名不能为空"
            model.length > 100 -> "模型名过长"
            voice.length > 200 -> "voice 过长"
            key.isEmpty() -> "key 不能为空"
            key.length > 4096 -> "key 过长"
            else -> null
        }
        if (invalid != null) {
            respond(output, "400 Bad Request", page("参数有误", invalid))
            return
        }

        TtsConfig.baseUrl = baseUrl
        TtsConfig.model = model
        TtsConfig.voice = voice
        val saved = SecretStore.save(context, key)
        if (!saved) {
            respond(
                output,
                "500 Internal Server Error",
                page("无法保存", "本机系统版本过低，无法安全加密保存 key（需 Android 6.0 及以上）。")
            )
            return
        }

        used = true
        respond(output, "200 OK", page("已保存", "配置已写入平板，可以关闭这个页面了。"))
        main.post {
            Toast.makeText(context, "在线 TTS 配置已保存", Toast.LENGTH_SHORT).show()
            onSaved(model)
        }
        // 给响应留出落地时间后关闭服务
        main.postDelayed({ stop() }, 500)
    }

    // ---------- 表单页 ----------

    private fun formPage(): String = """
        <!doctype html>
        <html lang="zh-CN"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>在线 TTS 配置</title>
        <style>
        body{margin:0;padding:24px;font:16px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f4f6fa;color:#1a1f26}
        .card{max-width:520px;margin:0 auto;background:#fff;border-radius:12px;padding:20px;box-shadow:0 2px 12px rgba(0,0,0,.06)}
        h1{font-size:20px;margin:0 0 4px}
        p.hint{font-size:13px;color:#5e6b78;margin:0 0 20px}
        label{display:block;font-size:14px;margin:14px 0 6px}
        input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border:1px solid #d6dde5;border-radius:8px;background:#fff;color:#1a1f26}
        button{margin-top:22px;width:100%;padding:14px;font-size:16px;border:0;border-radius:8px;background:#1565c0;color:#fff}
        .foot{margin-top:16px;font-size:12px;color:#6f7b88}
        </style></head><body>
        <div class="card">
        <h1>在线 TTS 引擎</h1>
        <p class="hint">此页面仅在局域网内、链接有效期内可打开；保存后链接立即失效，服务自动关闭。</p>
        <form method="post" action="/$token" autocomplete="off">
          <label for="baseUrl">baseUrl（必须 https:// 开头）</label>
          <input id="baseUrl" name="baseUrl" type="url" required placeholder="https://api.example.com/v1">
          <label for="model">模型名</label>
          <input id="model" name="model" type="text" required placeholder="FunAudioLLM/CosyVoice2-0.5B">
          <label for="voice">voice（可选，留空用服务商默认；硅基流动填 模型名:音色，如 FunAudioLLM/CosyVoice2-0.5B:alex）</label>
          <input id="voice" name="voice" type="text" placeholder="留空则由服务商决定默认音色">
          <label for="key">key（不回显）</label>
          <input id="key" name="key" type="password" required autocomplete="new-password">
          <button type="submit">保存到平板</button>
        </form>
        <div class="foot">key 会以 AndroidKeyStore 加密后保存在平板上，不会显示、不会写日志。</div>
        </div></body></html>
    """.trimIndent()

    private fun page(title: String, message: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>body{margin:0;padding:32px;font:16px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f4f6fa;color:#1a1f26}
        .card{max-width:480px;margin:0 auto;background:#fff;border-radius:12px;padding:24px;box-shadow:0 2px 12px rgba(0,0,0,.06)}
        h1{font-size:20px;margin:0 0 8px}p{color:#5e6b78;margin:0}</style></head>
        <body><div class="card"><h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p></div></body></html>
    """.trimIndent()

    // ---------- HTTP 解析 ----------

    private data class HttpRequest(val method: String, val path: String, val body: String)

    private fun readRequest(input: InputStream): HttpRequest? {
        val header = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            header.write(b)
            matched = when {
                b == 13 && (matched == 0 || matched == 2) -> matched + 1
                b == 10 && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (header.size() > MAX_HEADER_BYTES) return null
        }
        val lines = header.toString("ISO-8859-1").split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty().split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')

        val contentLength = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (contentLength > MAX_BODY_BYTES) return null

        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyBytes, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(method, path, String(bodyBytes, 0, read, Charsets.UTF_8))
    }

    private fun respond(out: OutputStream, status: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            name to value
        }.toMap()

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun findLanAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
