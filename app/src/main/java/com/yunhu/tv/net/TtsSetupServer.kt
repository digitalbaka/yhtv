package com.yunhu.tv.net

import com.yunhu.tv.voice.VoicePrefs
import java.io.File
import java.net.URLDecoder

/**
 * 「扫码填写在线 TTS 配置」用的一次性页面。
 *
 * 手机扫电视上的二维码 → 打开 /tts/<口令> 表单页 → 提交后写入 [VoicePrefs]。
 * 表单只有 baseUrl、模型名、key 三项，不碰系统音色（tts_voice_name）；
 * key 由 SecureStore 加密后存入 tts_token，与 ASR 的密钥互不影响。
 */
object TtsSetupServer {

    const val PREFIX = "tts"

    /** 保存成功回调（后台线程） */
    fun create(
        token: String,
        tempDir: File,
        ttlMs: Long,
        onSaved: (VoicePrefs.TtsSettings) -> Unit
    ): LanHttpServer = LanHttpServer(
        prefix = PREFIX,
        token = token,
        tempDir = tempDir,
        ttlMs = ttlMs,
        expiredHandler = { LanHttpServer.Response.html(expiredPage(), closeAfter = false) }
    ) { req ->
        when {
            req.method == "GET" && req.subPath.isEmpty() ->
                LanHttpServer.Response.html(formPage())

            // 打开过期链接 / 服务已重启：给「刷新」按钮
            req.method == "GET" ->
                LanHttpServer.Response.html(expiredPage(), closeAfter = false)

            req.method == "POST" && (req.subPath.isEmpty() || req.subPath == "submit") -> {
                val form = parseForm(req.body)
                val baseUrl = form["baseUrl"].orEmpty().trim()
                val model = form["model"].orEmpty().trim()
                val keyInput = form["key"].orEmpty().trim()
                val old = VoicePrefs.ttsSettings
                // 留空表示沿用已保存的 key，其它字段仍然可以单独修改
                val key = keyInput.ifEmpty { old.token }

                val error = when {
                    baseUrl.isEmpty() -> "baseUrl 不能为空"
                    !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
                        "baseUrl 需以 http:// 或 https:// 开头"
                    baseUrl.length > 300 -> "baseUrl 过长"
                    model.isEmpty() -> "模型名不能为空"
                    model.length > 100 -> "模型名过长"
                    key.isEmpty() -> "key 不能为空"
                    keyInput.length > 4096 -> "key 过长"
                    else -> ""
                }
                if (error.isNotEmpty()) {
                    LanHttpServer.Response.html(resultPage("保存失败", error), closeAfter = false)
                } else {
                    var saved = old.copy(
                        provider = "custom",
                        baseUrl = baseUrl,
                        model = model,
                        token = key
                    )
                    // 三项齐全才切到在线引擎，否则保持用户原来的引擎选择
                    if (baseUrl.isNotEmpty() && model.isNotEmpty() && key.isNotEmpty()) {
                        saved = saved.copy(engine = "online")
                    }
                    VoicePrefs.ttsSettings = saved
                    onSaved(saved)
                    LanHttpServer.Response.html(
                        resultPage("已保存", "配置已写入电视，可以关闭此页面"),
                        closeAfter = true
                    )
                }
            }

            else -> LanHttpServer.Response.plain(404, "not found", true)
        }
    }

    private fun parseForm(body: ByteArray): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        return try {
            String(body, Charsets.UTF_8).split('&').mapNotNull { pair ->
                if (pair.isEmpty()) return@mapNotNull null
                val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                k to v
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun formPage(): String {
        val current = VoicePrefs.ttsSettings
        val keyHint = if (current.token.isNotEmpty()) "已配置，留空表示不修改" else "粘贴服务商控制台里的 API Key"
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>在线 TTS 配置</title>
<style>
${AsrSetupServer.baseCss()}
</style>
</head>
<body>
<h1>在线 TTS 配置</h1>
<p class="hint">仅在局域网内、链接有效期内可打开；保存后链接立即失效，服务自动关闭。key 加密保存在电视上，不会显示、不会写日志。</p>
<form method="post">
  <label>baseUrl（http:// 或 https:// 开头）</label>
  <input name="baseUrl" id="baseUrl" value="${AsrSetupServer.escapeHtml(current.baseUrl)}" placeholder="https://api.example.com/v1" autocapitalize="off" autocorrect="off" spellcheck="false">
  <label>模型名</label>
  <input name="model" id="model" value="${AsrSetupServer.escapeHtml(current.model)}" placeholder="FunAudioLLM/CosyVoice2-0.5B" autocapitalize="off" autocorrect="off" spellcheck="false">
  <label>API Key</label>
  <input name="key" id="key" type="password" placeholder="${AsrSetupServer.escapeHtml(keyHint)}" autocomplete="off">
  <button type="submit">保存到电视</button>
</form>
</body>
</html>
"""
    }

    private fun resultPage(title: String, message: String): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>${AsrSetupServer.baseCss()}</style>
</head>
<body>
<h1>${AsrSetupServer.escapeHtml(title)}</h1>
<p class="hint">${AsrSetupServer.escapeHtml(message)}</p>
</body>
</html>
"""

    private fun expiredPage(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>链接已失效</title>
<style>${AsrSetupServer.baseCss()}</style>
</head>
<body>
<h1>链接已失效</h1>
<p class="hint">这个链接是一次性的，已提交过或已超过有效期。<br>请在电视上重新打开「扫码填写」生成新二维码后再扫。</p>
<button onclick="location.reload()">刷新</button>
</body>
</html>
"""
}
