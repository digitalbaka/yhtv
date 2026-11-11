package com.yunhu.pad.api

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 七牛 Kodo 直传。
 *
 * 云湖的媒体消息（图片 / 视频 / 语音）都先把文件传到七牛对象存储，
 * 再把返回的 key（例如 `123.png`）填进 MsgContent 的 image / video / audio 字段。
 *
 * 凭证由 `GET /v1/misc/qiniu-token*` 下发，格式为 `ak:sign:base64(policy)`，
 * policy 里的 `scope` 是 bucket。
 *
 * 上传 Host 不能写死：不同的 bucket 落在不同区域，写错会返回
 * `HTTP 400 {"error":"incorrect region, please use up-cn-east-2.qiniup.com..."}`。
 * 这里的做法与解包客户端一致：
 * 1. 先查 `https://api.qiniu.com/v4/query?ak=&bucket=`，取 `hosts[0].up.domains[0]`；
 * 2. 查询失败按媒体类型兜底（图片/语音/文件走华南 z2，视频走华东二区）；
 * 3. 万一仍返回区域错误，按服务端提示的域名再试一次。
 */
object Qiniu {

    // 区域查询失败时的兜底上传域名，取值来自解包客户端 yz4.c() 的硬编码分支
    private const val FALLBACK_UP_Z2 = "https://upload-z2.qiniup.com"
    private const val FALLBACK_UP_VIDEO = "https://upload-cn-east-2.qiniup.com"

    /** 区域错误响应里服务端会给出正确域名：please use xxx.qiniup.com */
    private val HOST_HINT = Regex("""please use ([A-Za-z0-9.\-]+)""")

    private val hostCache = ConcurrentHashMap<String, String>()

    private class HttpError(val code: Int, val body: String) : IOException("上传失败 HTTP $code")

    /**
     * 上传文件并返回七牛 key。
     * @param prefix 对象名目录前缀（如 `sticker/`）
     * @param video 是否视频；只影响区域查询失败时的兜底域名
     */
    suspend fun upload(file: File, token: String, prefix: String = "", video: Boolean = false): String =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) throw IOException("待上传文件为空")
            val bucketName = bucketOf(token)?.second
            val key = buildKey(prefix, file)
            var host = resolveUpHost(token, video)
            for (attempt in 0..1) {
                try {
                    val result = postFile(host, token, key, file)
                    if (bucketName != null) hostCache[bucketName] = host
                    return@withContext result
                } catch (e: HttpError) {
                    val hinted = if (e.body.contains("incorrect region")) {
                        HOST_HINT.find(e.body)?.groupValues?.get(1)
                    } else {
                        null
                    }
                    val retryHost = when {
                        hinted != null -> normalizeHost(hinted)
                        e.body.contains("no such domain") -> fallbackHost(video)
                        else -> null
                    }
                    // 只在首次失败时按提示换域名重试，避免死循环
                    if (attempt == 0 && retryHost != null && retryHost != host) {
                        host = retryHost
                        continue
                    }
                    throw IOException("上传失败 HTTP ${e.code}: ${e.body.take(200)}")
                }
            }
            throw IOException("上传失败")
        }

    /** POST multipart 上传；token / key / file 三个字段名与解包客户端一致 */
    private suspend fun postFile(host: String, token: String, key: String, file: File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .addFormDataPart("key", key)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val req = Request.Builder().url("$host/").post(body).build()
        val call = Api.client.newCall(req)
        // 退出聊天页/取消任务时中止在途上传，并让 finally 里的临时文件清理照常执行
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        return call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpError(resp.code, text)
            val uploadedKey = runCatching { JSONObject(text) }.getOrNull()
                ?.optString("key").orEmpty()
            if (uploadedKey.isEmpty()) throw IOException("上传响应异常: ${text.take(200)}")
            uploadedKey
        }
    }

    private fun buildKey(prefix: String, file: File): String {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val name = StringBuilder()
        if (prefix.isNotEmpty()) name.append(prefix.trimEnd('/')).append('/')
        name.append(System.currentTimeMillis())
        name.append(Integer.toHexString(java.util.Random().nextInt(0x100000)))
        if (ext.isNotEmpty()) name.append('.').append(ext)
        return name.toString()
    }

    /** 解析凭证里的 ak / bucket，查询上传 Host；任何一步失败都回退到该类型的兜底域名 */
    private suspend fun resolveUpHost(token: String, video: Boolean): String =
        withContext(Dispatchers.IO) {
            val bucket = bucketOf(token) ?: return@withContext fallbackHost(video)
            hostCache[bucket.second]?.let { return@withContext it }
            val host = runCatching { queryUpHost(bucket.first, bucket.second) }.getOrNull()
                ?: fallbackHost(video)
            hostCache[bucket.second] = host
            host
        }

    /**
     * 查询 bucket 所在区域的上传域名。
     * 解包客户端解析路径为 `hosts[0].up.domains[0]`（xw 协程），
     * 这里同时兼容 `data.up.domains[0]` 的返回形态。
     */
    private suspend fun queryUpHost(ak: String, bucket: String): String? = withContext(Dispatchers.IO) {
        val url = "https://api.qiniu.com/v4/query?ak=$ak&bucket=$bucket"
        val text = Api.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string().orEmpty()
        }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val domains = json.optJSONArray("hosts")
            ?.optJSONObject(0)?.optJSONObject("up")?.optJSONArray("domains")
            ?: json.optJSONObject("data")?.optJSONObject("up")?.optJSONArray("domains")
            ?: return@withContext null
        domains.optString(0).takeIf { it.isNotEmpty() }?.let(::normalizeHost)
    }

    private fun fallbackHost(video: Boolean): String =
        if (video) FALLBACK_UP_VIDEO else FALLBACK_UP_Z2

    private fun normalizeHost(raw: String): String {
        val h = raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        return "https://$h"
    }

    private fun bucketOf(token: String): Pair<String, String>? {
        val parts = token.split(":")
        if (parts.size < 3) return null
        val ak = parts.first()
        val policy = runCatching {
            String(Base64.decode(parts.last(), Base64.DEFAULT))
        }.getOrNull() ?: return null
        val scope = runCatching { JSONObject(policy).optString("scope") }.getOrNull().orEmpty()
        val bucket = scope.substringBefore(":")
        if (ak.isEmpty() || bucket.isEmpty()) return null
        return ak to bucket
    }
}
