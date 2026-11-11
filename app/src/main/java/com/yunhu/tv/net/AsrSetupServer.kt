package com.yunhu.tv.net

import com.yunhu.tv.voice.AsrProviders
import com.yunhu.tv.voice.VoicePrefs
import java.net.URLDecoder

/**
 * 「扫码填写语音识别配置」用的一次性页面。
 *
 * 手机扫电视上的二维码 → 打开 /asr/<口令> 表单页 → 提交后写入 [VoicePrefs]。
 * 表单只包含服务商、接口地址、模型、token 四个字段，不能改其它设置。
 */
object AsrSetupServer {

    const val PREFIX = "asr"

    /** 保存成功回调（后台线程） */
    fun create(
        token: String,
        tempDir: java.io.File,
        ttlMs: Long,
        onSaved: (VoicePrefs.AsrConfig) -> Unit
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
                val tokenInput = form["token"].orEmpty().trim()
                val provider = form["provider"].orEmpty().trim()

                val error = when {
                    !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") ->
                        "接口地址需以 http:// 或 https:// 开头"
                    baseUrl.length > 300 -> "接口地址过长"
                    model.isEmpty() -> "模型名不能为空"
                    model.length > 100 -> "模型名过长"
                    tokenInput.length > 500 -> "密钥过长"
                    provider !in AsrProviders.ALL.map { it.id } && provider != AsrProviders.CUSTOM ->
                        "服务商取值不合法"
                    else -> ""
                }
                if (error.isNotEmpty()) {
                    LanHttpServer.Response.html(resultPage("保存失败", error), closeAfter = false)
                } else {
                    val old = VoicePrefs.asr
                    val token = tokenInput.ifEmpty { old.token }
                    val saved = VoicePrefs.AsrConfig(
                        provider = if (provider.isEmpty()) AsrProviders.CUSTOM else provider,
                        baseUrl = baseUrl,
                        model = model,
                        token = token
                    )
                    VoicePrefs.asr = saved
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
        val current = VoicePrefs.asr
        val provider = current.provider.ifEmpty { AsrProviders.SILICONFLOW.id }
        val baseUrl = current.baseUrl.ifEmpty { AsrProviders.SILICONFLOW.baseUrl }
        val model = current.model.ifEmpty { AsrProviders.SILICONFLOW.defaultModel }
        val tokenHint = if (current.token.isNotEmpty()) "已填写过，留空表示不修改" else "粘贴服务商控制台里的 API Key"
        val options = AsrProviders.ALL.joinToString("") { p ->
            val selected = if (p.id == provider) " selected" else ""
            "<option value=\"${p.id}\"$selected>${escapeHtml(p.label)}</option>"
        } + "<option value=\"${AsrProviders.CUSTOM}\"" +
            (if (provider == AsrProviders.CUSTOM) " selected" else "") + ">自定义</option>"
        val presets = AsrProviders.ALL.joinToString(",") { p ->
            "\"${p.id}\":{\"base\":\"${p.baseUrl}\",\"model\":\"${p.defaultModel}\"}"
        }
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>语音识别配置</title>
<style>
${baseCss()}
</style>
</head>
<body>
<h1>语音识别配置</h1>
<p class="hint">仅在手机与电视之间传输，保存后此页面失效。</p>
<form method="post">
  <label>服务商</label>
  <select name="provider" id="provider" onchange="fillPreset()">$options</select>
  <label>接口地址（baseUrl）</label>
  <input name="baseUrl" id="baseUrl" value="${escapeHtml(baseUrl)}" autocapitalize="off" autocorrect="off" spellcheck="false">
  <label>模型名</label>
  <input name="model" id="model" value="${escapeHtml(model)}" autocapitalize="off" autocorrect="off" spellcheck="false">
  <label>API Key</label>
  <input name="token" id="token" type="password" placeholder="${escapeHtml(tokenHint)}" autocomplete="off">
  <button type="submit">保存到电视</button>
</form>
<script>
var presets = {$presets};
function fillPreset() {
  var p = presets[document.getElementById('provider').value];
  if (!p) return;
  document.getElementById('baseUrl').value = p.base;
  document.getElementById('model').value = p.model;
}
</script>
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
<style>${baseCss()}</style>
</head>
<body>
<h1>${escapeHtml(title)}</h1>
<p class="hint">${escapeHtml(message)}</p>
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
<style>${baseCss()}</style>
</head>
<body>
<h1>链接已失效</h1>
<p class="hint">这个链接是一次性的，已提交过或已超过有效期。<br>请在电视上重新打开「手机扫码填写」生成新二维码后再扫。</p>
<button onclick="location.reload()">刷新</button>
</body>
</html>
"""

    internal fun baseCss(): String = """
* { box-sizing: border-box; }
body { margin: 0; padding: 20px; background: #121418; color: #f2f4f7;
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; }
h1 { font-size: 20px; margin: 8px 0 12px; }
.hint { color: #9aa3ad; font-size: 14px; line-height: 1.6; }
label { display: block; margin: 18px 0 6px; color: #c8d0d8; font-size: 14px; }
input, select { width: 100%; padding: 12px; font-size: 16px; color: #f2f4f7;
  background: #1c2026; border: 1px solid #2a3038; border-radius: 8px; }
button { width: 100%; margin-top: 26px; padding: 14px; font-size: 17px; color: #fff;
  background: #1e88b8; border: 0; border-radius: 8px; }
button.rec { background: #c62828; }
button.rec2 { margin-top: 12px; background: #2a3038; }
button:disabled { opacity: .5; }
#timer { font-size: 40px; text-align: center; margin: 24px 0; font-variant-numeric: tabular-nums; }
"""

    internal fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
