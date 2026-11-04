package com.yunhu.pad.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 七牛 Kodo 直传。
 *
 * 云湖的媒体消息（图片 / 视频 / 语音）都先把文件传到七牛对象存储，
 * 再把返回的 key（例如 `123.png`）填进 MsgContent 的 image_url / video_url / audio_url。
 *
 * 凭证由 `GET /v1/misc/qiniu-token*` 下发，格式为 `ak:sign:base64(policy)`，
 * policy 里的 `scope` 是 `bucket`（或 `bucket:prefix`）。
 * 上传 Host 通过 `GET https://api.qiniu.com/v4/query?ak=&bucket=` 获取，失败则回退默认上传域名。
 */
object Qiniu {

    private const val FALLBACK_UP = "https://upload.qiniup.com"

    private val hostCache = ConcurrentHashMap<String, String>()

    /** 上传文件并返回七牛 key。prefix 用于给对象名加目录前缀（如 `sticker/`） */
    suspend fun upload(file: File, token: String, prefix: String = ""): String =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) throw IOException("待上传文件为空")
            val host = resolveUpHost(token)
            val key = buildKey(prefix, file)
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
            Api.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("上传失败 HTTP ${resp.code}: ${text.take(200)}")
                }
                val json = runCatching { JSONObject(text) }.getOrNull()
                json?.optString("key")?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("上传响应异常: ${text.take(200)}")
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

    /** 解析凭证里的 ak / bucket，查询上传 Host；任何一步失败都回退到默认域名 */
    private suspend fun resolveUpHost(token: String): String = withContext(Dispatchers.IO) {
        val bucket = bucketOf(token)
        if (bucket == null) return@withContext FALLBACK_UP
        hostCache[bucket.second]?.let { return@withContext it }
        val (ak, bucketName) = bucket
        val host = runCatching {
            val url = "https://api.qiniu.com/v4/query?ak=$ak&bucket=$bucketName"
            val text = Api.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
            }
            val up = JSONObject(text).optJSONObject("data")?.optJSONObject("up")
            val domains = up?.optJSONArray("domains")
            if (domains != null && domains.length() > 0) {
                "https://" + domains.optString(0).removePrefix("https://").removePrefix("http://")
            } else null
        }.getOrNull() ?: FALLBACK_UP
        hostCache[bucketName] = host
        host
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
