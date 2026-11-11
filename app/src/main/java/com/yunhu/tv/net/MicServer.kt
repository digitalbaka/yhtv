package com.yunhu.tv.net

import android.media.MediaMetadataRetriever
import com.yunhu.tv.voice.AudioClip
import com.yunhu.tv.voice.VoiceOutcome
import com.yunhu.tv.voice.VoicePipeline
import com.yunhu.tv.voice.VoiceResultSink
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 「手机当麦克风」用的一次性页面。
 *
 * 手机扫码打开 /voice/<口令>，录音或选择音频文件后 multipart POST 到电视，
 * 音频只在手机与电视之间传输；两条入口（网页录音 / 系统录音机或已有文件）
 * 都落到同一个 [ingest] 里走 [VoicePipeline]，不另开第二条链路。
 */
object MicServer {

    const val PREFIX = "voice"

    /** 识别完成回调（后台线程） */
    fun create(
        token: String,
        tempDir: File,
        ttlMs: Long,
        onResult: (VoiceOutcome) -> Unit
    ): LanHttpServer = LanHttpServer(
        prefix = PREFIX,
        token = token,
        tempDir = tempDir,
        ttlMs = ttlMs,
        expiredHandler = { LanHttpServer.Response.html(expiredPage(), closeAfter = false) }
    ) { req ->
        when {
            req.method == "GET" && req.subPath.isEmpty() ->
                LanHttpServer.Response.html(page())

            // 链接失效 / 服务已重启：页面给「刷新」按钮
            req.method == "GET" ->
                LanHttpServer.Response.html(expiredPage(), closeAfter = false)

            req.method == "POST" && req.subPath == "audio" ->
                ingest(req, tempDir, onResult)

            else -> LanHttpServer.Response.plain(404, "not found", false)
        }
    }

    private fun ingest(
        req: LanHttpServer.Request,
        tempDir: File,
        onResult: (VoiceOutcome) -> Unit
    ): LanHttpServer.Response {
        var declaredMs = req.query["ms"]?.toLongOrNull() ?: 0L
        var audio: File? = null
        var mime = ""
        var ownBodyFile: File? = null
        val parts = ArrayList<Multipart.Part>()
        try {
            // 小请求体在内存里，multipart 也要先落盘再按 boundary 切分
            val bodyFile = req.bodyFile ?: run {
                if (req.body.isNotEmpty() && Multipart.boundaryOf(req.contentType) != null) {
                    File.createTempFile("req-", ".bin", tempDir).also {
                        it.writeBytes(req.body)
                        ownBodyFile = it
                    }
                } else null
            }
            val boundaryParts = bodyFile?.let { Multipart.parse(it, req.contentType, tempDir) }
            if (boundaryParts != null) {
                parts.addAll(boundaryParts)
                val filePart = boundaryParts.firstOrNull { it.name == "file" && it.size > 0 }
                    ?: return badRequest("没有收到音频", "请重新录音或选择音频文件")
                audio = filePart.file
                mime = filePart.contentType
                    ?: lookupMime(filePart.fileName) ?: "application/octet-stream"
            } else if (req.body.isNotEmpty()) {
                // 兼容直接 POST 裸音频（调试脚本用）
                val f = File.createTempFile("mic-", ".bin", tempDir)
                f.writeBytes(req.body)
                audio = f
                mime = req.contentType.substringBefore(';').trim()
                    .ifEmpty { "application/octet-stream" }
            }
            val f = audio ?: return badRequest("没有收到音频", "请重新录音或选择音频文件")
            if (declaredMs <= 0) {
                declaredMs = parts.firstOrNull { it.name == "ms" }?.readText()?.toLongOrNull() ?: 0L
            }

            val kind = normalizeKind(mime, f.name, parts.firstOrNull { it.file == f }?.fileName)
                ?: return unsupported()
            val size = f.length()
            if (size <= 0) return badRequest("音频是空的", "请重新录音")
            if (size > MAX_BYTES) {
                return LanHttpServer.Response.plain(413, "音频超过 50 MB 上限（当前 ${size / 1024 / 1024} MB）")
            }

            val durationMs = durationOf(f).takeIf { it > 0 } ?: declaredMs
            val clip = AudioClip(bytes = ByteArray(0), mime = kind, durationMs = durationMs, file = f)
            val outcome = runBlocking { VoicePipeline.transcribe(clip) }
            VoiceResultSink.dispatchVoice(outcome)
            onResult(outcome)
            // 保持服务开启，允许连续录几段；由有效期或 TV 端关闭
            return LanHttpServer.Response.html(resultPage(outcome), closeAfter = false)
        } finally {
            parts.forEach { runCatching { it.file.delete() } }
            if (req.body.isNotEmpty() && audio != null) {
                runCatching { audio.delete() }
            }
            ownBodyFile?.delete()
        }
    }

    /** 400：请求本身有问题（没有音频 / 空音频） */
    private fun badRequest(title: String, message: String) =
        LanHttpServer.Response(400, "text/html; charset=utf-8", resultPageRaw(title, message, ok = false), false)

    /** 415：格式不支持，返回真正的 415 状态码，手机端才能把错误翻成人话 */
    private fun unsupported() = LanHttpServer.Response(
        415,
        "text/html; charset=utf-8",
        resultPageRaw("不支持的音频格式", "支持 wav / m4a / amr / mp3 / webm / ogg / aac / 3gp", ok = false),
        false
    )

    private fun resultPage(outcome: VoiceOutcome): String {
        // 识别失败时 text 为空、note 是原因，标题不能还写「已识别」
        val failed = outcome.text.isBlank()
        val title = when {
            failed -> "识别失败"
            outcome.placeholder -> "已收到语音（占位文本）"
            else -> "已识别"
        }
        val msg = buildString {
            append(outcome.text)
            if (outcome.note.isNotEmpty()) {
                append("<br><span class=\"hint\">").append(AsrSetupServer.escapeHtml(outcome.note)).append("</span>")
            }
        }
        return resultPageRaw(title, msg, ok = !failed)
    }

    private fun resultPageRaw(title: String, messageHtml: String, ok: Boolean): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${AsrSetupServer.escapeHtml(title)}</title>
<style>${AsrSetupServer.baseCss()}</style>
</head>
<body>
<h1>${AsrSetupServer.escapeHtml(title)}</h1>
<p class="hint">$messageHtml</p>
${if (ok) "<p class=\"hint\">已交给电视上的聊天输入框，可以关闭此页面。</p>" else ""}
</body>
</html>
"""

    /** 链接过期 / 服务重启后的页面 */
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
<p class="hint">这个链接是一次性的，已提交过或已超过有效期。<br>请在电视上点「手机扫码录音」重新生成二维码后再扫。</p>
<button onclick="location.reload()">刷新</button>
</body>
</html>
"""

    private fun page(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>手机当麦克风</title>
<style>${AsrSetupServer.baseCss()}</style>
</head>
<body>
<h1>手机当麦克风</h1>
<p class="hint" id="tip">录一段话或选择音频文件，音频只在手机与电视之间传输，不经过第三方。</p>
<div id="timer">00:00</div>
<p class="hint" id="meta"></p>
<button id="rec" class="rec" style="display:none">开始录音</button>
<button id="pick" class="rec">上传音频</button>
<input type="file" id="file" accept="audio/*" style="display:none">
<p class="hint" id="status"></p>
<script>
(function () {
  var btn = document.getElementById('rec');
  var pickBtn = document.getElementById('pick');
  var statusEl = document.getElementById('status');
  var metaEl = document.getElementById('meta');
  var fileInput = document.getElementById('file');
  var recording = false, rec = null, chunks = [], startedAt = 0, tick = null, bytesSoFar = 0;
  var MAX = 50 * 1024 * 1024;
  // 老 WebView（Android 5.0）只有 navigator.getUserMedia / webkitGetUserMedia
  var legacyGet = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia;
  var modernGet = navigator.mediaDevices && navigator.mediaDevices.getUserMedia;
  var canRecord = !!(window.MediaRecorder && (legacyGet || modernGet));

  if (canRecord) {
    btn.style.display = 'block';
    btn.className = 'rec';
    pickBtn.className = 'rec2';
  } else {
    // 非安全上下文（http:// 局域网）时浏览器不给麦克风权限：隐藏录音，上传放主位
    btn.style.display = 'none';
    document.getElementById('tip').textContent =
      '当前浏览器不支持网页录音，请用上传音频。（通过局域网 HTTP 打开时浏览器不开放麦克风；点「上传音频」可选系统录音机刚录的文件或手机里已有的音频。）';
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function showTime(ms) {
    var s = Math.floor(ms / 1000);
    document.getElementById('timer').textContent =
      pad(Math.floor(s / 60)) + ':' + pad(s % 60);
  }
  function humanSize(b) {
    if (b < 1024) return b + ' B';
    if (b < 1024 * 1024) return (b / 1024).toFixed(0) + ' KB';
    return (b / 1024 / 1024).toFixed(1) + ' MB';
  }
  function errText(code) {
    if (code === 'NotSupportedError') return '这台手机的浏览器不支持网页录音（NotSupportedError），请用「上传音频」。';
    if (code === 'NotAllowedError' || code === 'SecurityError') return '浏览器拒绝了麦克风权限（NotAllowedError），请在地址栏权限里允许后重试，或直接「上传音频」。';
    if (code === 'NotFoundError') return '没有找到麦克风（NotFoundError），请改用「上传音频」。';
    return '无法录音：' + code;
  }

  btn.onclick = function () {
    if (recording) { stopRec(); } else { startRec(); }
  };
  pickBtn.onclick = function () { fileInput.click(); };

  function startRec() {
    var constraints = {
      audio: { channelCount: 1, sampleRate: 16000, echoCancellation: true, noiseSuppression: true }
    };
    var onStream = function (stream) {
      try {
        var mime = '';
        if (window.MediaRecorder && MediaRecorder.isTypeSupported) {
          // 优先单声道 16 kHz 的 m4a/aac，体积比 wav 小一个数量级
          if (MediaRecorder.isTypeSupported('audio/mp4')) mime = 'audio/mp4';
          else if (MediaRecorder.isTypeSupported('audio/aac')) mime = 'audio/aac';
          else if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) mime = 'audio/webm;codecs=opus';
        }
        var opts = { audioBitsPerSecond: 24000 };
        if (mime) opts.mimeType = mime;
        rec = mime ? new MediaRecorder(stream, opts) : new MediaRecorder(stream);
        chunks = [];
        bytesSoFar = 0;
        rec.ondataavailable = function (e) {
          if (e.data && e.data.size) { chunks.push(e.data); bytesSoFar += e.data.size; }
        };
        rec.onstop = function () {
          clearInterval(tick);
          var ms = Date.now() - startedAt;
          var tracks = stream.getTracks ? stream.getTracks() : [];
          for (var i = 0; i < tracks.length; i++) tracks[i].stop();
          var blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' });
          var bt = (rec.mimeType || blob.type || '').toLowerCase();
          if (bt.indexOf('wav') >= 0) {
            statusEl.textContent = '该机型录音体积较大（wav），建议缩短时长';
          }
          upload(blob, ms);
        };
        rec.start();
        recording = true;
        startedAt = Date.now();
        btn.textContent = '停止并发送';
        statusEl.textContent = '录音中，不设时长上限，录完点「停止并发送」';
        tick = setInterval(function () {
          showTime(Date.now() - startedAt);
          metaEl.textContent = '已录 ' + bytesSoFar + ' 字节';
        }, 200);
      } catch (e) {
        statusEl.textContent = errText(e && e.name ? e.name : e);
      }
    };
    var onErr = function (e) {
      statusEl.textContent = errText(e && e.name ? e.name : e);
    };
    try {
      if (legacyGet) {
        legacyGet.call(navigator, constraints, onStream, onErr);
      } else {
        modernGet.call(navigator.mediaDevices, constraints).then(onStream, onErr);
      }
    } catch (e) {
      statusEl.textContent = errText(e && e.name ? e.name : e);
    }
  }

  function stopRec() {
    if (!rec) return;
    recording = false;
    btn.disabled = true;
    statusEl.textContent = '发送中…';
    try { rec.stop(); } catch (e) { statusEl.textContent = '结束录音失败：' + e; }
  }

  function upload(blob, ms) {
    if (blob.size > MAX) {
      statusEl.textContent = '超过 50 MB，请换更短的音频（当前 ' + humanSize(blob.size) + '）';
      btn.disabled = false;
      return;
    }
    metaEl.textContent = '文件 ' + humanSize(blob.size) + ' · 时长 ' + Math.round(ms / 1000) + ' 秒';
    statusEl.textContent = '发送中…';
    var fd = new FormData();
    var name = 'voice.' + extOf(blob.type);
    fd.append('file', blob, name);
    fd.append('ms', String(ms));
    var xhr = new XMLHttpRequest();
    xhr.open('POST', location.pathname + '/audio', true);
    xhr.onload = function () {
      if (xhr.status === 200) {
        statusEl.textContent = '已发送，请回到电视查看输入框（可继续录下一段）';
        btn.textContent = '继续录音';
        btn.disabled = false;
        recording = false;
        return;
      }
      statusEl.textContent = httpText(xhr.status, xhr.responseText);
      btn.disabled = false;
    };
    xhr.onerror = function () {
      statusEl.textContent = '上传失败：连不上电视，请确认手机和电视在同一个 Wi-Fi，或链接已失效。';
      btn.disabled = false;
    };
    xhr.ontimeout = function () {
      statusEl.textContent = '上传超时，请重试。';
      btn.disabled = false;
    };
    xhr.timeout = 120000;
    xhr.send(fd);
  }

  function extOf(type) {
    type = (type || '').toLowerCase();
    if (type.indexOf('webm') >= 0) return 'webm';
    if (type.indexOf('mp4') >= 0 || type.indexOf('m4a') >= 0 || type.indexOf('aac') >= 0) return 'm4a';
    if (type.indexOf('ogg') >= 0) return 'ogg';
    if (type.indexOf('wav') >= 0) return 'wav';
    if (type.indexOf('amr') >= 0) return 'amr';
    if (type.indexOf('mpeg') >= 0 || type.indexOf('mp3') >= 0) return 'mp3';
    return 'bin';
  }

  function httpText(code, body) {
    if (code === 413) return '音频太大，电视端拒收（HTTP 413）。请换更短的音频。';
    if (code === 415) return '电视端不支持这个音频格式（HTTP 415）。请换 wav / m4a / amr / mp3。';
    if (code === 401 || code === 403) return '链接已失效（HTTP ' + code + '），请在电视上重新生成二维码。';
    if (code === 411) return '上传缺少长度信息（HTTP 411），请换一个浏览器重试。';
    return '发送失败：HTTP ' + code + (body ? '，' + body.substring(0, 120) : '');
  }

  fileInput.onchange = function () {
    var f = fileInput.files && fileInput.files[0];
    if (!f) return;
    if (f.size > MAX) {
      statusEl.textContent = '超过 50 MB，请换更短的音频（当前 ' + humanSize(f.size) + '）';
      fileInput.value = '';
      return;
    }
    statusEl.textContent = '准备发送…';
    metaEl.textContent = '文件 ' + humanSize(f.size) + (f.name ? ' · ' + f.name : '');
    var ms = 0;
    var got = false;
    var send = function () {
      if (got) return;
      got = true;
      upload(f, ms);
    };
    try {
      var a = new Audio();
      var url = (window.URL || window.webkitURL).createObjectURL(f);
      a.preload = 'metadata';
      a.onloadedmetadata = function () {
        ms = Math.round(a.duration * 1000) || 0;
        (window.URL || window.webkitURL).revokeObjectURL(url);
        send();
      };
      a.onerror = function () { send(); };
      a.src = url;
      setTimeout(send, 2500);
    } catch (e) {
      send();
    }
  };
})();
</script>
</body>
</html>
"""

    private fun normalizeKind(mime: String, tempName: String, originalName: String?): String? {
        val m = mime.lowercase()
        val n = (originalName ?: tempName).lowercase()
        if (m.startsWith("audio/") || m == "application/octet-stream") {
            if (n.endsWith(".wav") || m.contains("wav")) return "audio/wav"
            if (n.endsWith(".m4a") || m.contains("mp4") || m.contains("m4a")) return "audio/mp4"
            if (n.endsWith(".amr") || m.contains("amr")) return "audio/amr"
            if (n.endsWith(".mp3") || m.contains("mpeg")) return "audio/mpeg"
            if (n.endsWith(".ogg") || m.contains("ogg")) return "audio/ogg"
            if (n.endsWith(".3gp") || m.contains("3gpp")) return "audio/3gpp"
            if (n.endsWith(".webm") || m.contains("webm")) return "audio/webm"
            if (n.endsWith(".aac") || m.contains("aac")) return "audio/aac"
            if (m.startsWith("audio/")) return m
        }
        return null
    }

    /** 用系统元数据解析真实时长（wav/m4a/amr/mp3 都覆盖） */
    private fun durationOf(file: File): Long = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    } catch (_: Exception) {
        0L
    }

    private fun lookupMime(name: String?): String? {
        val n = name?.lowercase() ?: return null
        return when {
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".m4a") -> "audio/mp4"
            n.endsWith(".amr") -> "audio/amr"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".ogg") -> "audio/ogg"
            n.endsWith(".aac") -> "audio/aac"
            n.endsWith(".3gp") -> "audio/3gpp"
            n.endsWith(".webm") -> "audio/webm"
            else -> null
        }
    }

    private const val MAX_BYTES = 50L * 1024 * 1024
}
