package com.yunhu.tv.api

import com.yunhu.tv.util.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 云湖 HTTP API。
 * Protobuf 接口域名: https://chat-go.jwzhd.com
 * Web 接口域名:     https://chat-web-go.jwzhd.com
 */
object Api {

    const val BASE = "https://chat-go.jwzhd.com"
    const val WEB_BASE = "https://chat-web-go.jwzhd.com"
    const val WS_URL = "wss://chat-ws-go.jwzhd.com/ws"
    const val IMG_BASE = "https://chat-img.jwznb.com/"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val PROTO_MEDIA = "application/x-protobuf".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS) // WS 心跳自行管理
            .build()
    }

    class ApiException(val code: Int, message: String) : Exception(message)

    // ---------- 基础请求 ----------

    private fun postProto(url: String, body: ByteArray): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("token", Session.token)
            .post(body.toRequestBody(PROTO_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun postJson(url: String, json: JSONObject, withToken: Boolean = true): JSONObject {
        val b = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON_MEDIA))
        if (withToken) b.header("token", Session.token)
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            return JSONObject(text)
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("token", Session.token)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            return JSONObject(text)
        }
    }

    // ---------- 用户 ----------

    data class LoginResult(val token: String)

    /** 邮箱密码登录 */
    suspend fun emailLogin(email: String, password: String): String = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("deviceId", Session.deviceId)
            .put("platform", "android")
        val resp = postJson("$BASE/v1/user/email-login", json, withToken = false)
        val code = resp.optInt("code", -1)
        if (code != 1) throw ApiException(code, resp.optString("msg", "登录失败"))
        resp.getJSONObject("data").getString("token")
    }

    data class SelfInfo(
        val userId: String,
        val nickname: String,
        val avatarUrl: String,
        val coin: Double
    )

    /** 获取自身信息 */
    suspend fun getSelfInfo(): SelfInfo = withContext(Dispatchers.IO) {
        val resp = getJson("$WEB_BASE/v1/user/info")
        val code = resp.optInt("code", -1)
        if (code != 1) throw ApiException(code, resp.optString("msg", "获取用户信息失败"))
        val u = resp.getJSONObject("data").getJSONObject("user")
        SelfInfo(
            userId = u.optString("userId"),
            nickname = u.optString("nickname"),
            avatarUrl = u.optString("avatarUrl"),
            coin = u.optDouble("goldCoinAmount", 0.0)
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching {
            postJson("$BASE/v1/user/logout", JSONObject().put("device-id", Session.deviceId))
        }
    }

    // ---------- 会话 ----------

    /** 获取对话列表，返回 (列表, md5)。md5 与服务端一致时返回空列表。 */
    suspend fun conversationList(md5: String = ""): Pair<List<Conversation>, String> =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/conversation/list", Requests.conversationList(md5))
            Responses.conversationList(data)
        }

    /** 对话设为已读 */
    suspend fun dismissNotification(chatId: String) = withContext(Dispatchers.IO) {
        runCatching {
            postJson("$BASE/v1/conversation/dismiss-notification", JSONObject().put("chatId", chatId))
        }
    }

    /** 删除对话 */
    suspend fun removeConversation(chatId: String) = withContext(Dispatchers.IO) {
        runCatching {
            postJson("$BASE/v1/conversation/remove", JSONObject().put("chatId", chatId))
        }
    }

    // ---------- 消息 ----------

    /** 拉取历史消息（msgId 为空时取最新 count 条，否则取该消息之前的） */
    suspend fun listMessages(chatId: String, chatType: Int, count: Int, msgId: String = ""): List<ChatMsg> =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/msg/list-message", Requests.listMessage(chatId, chatType, count, msgId))
            Responses.listMessage(data)
        }

    /** 按序列拉取新消息 */
    suspend fun listMessagesBySeq(chatId: String, chatType: Int, msgStart: Long): List<ChatMsg> =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/msg/list-message-by-seq", Requests.listMessageBySeq(chatId, chatType, msgStart))
            Responses.listMessage(data)
        }

    /** 发送消息，返回生成的 msgId */
    suspend fun sendMessage(
        chatId: String,
        chatType: Int,
        contentType: Int,
        content: MsgContent,
        quoteMsgId: String = ""
    ): String = withContext(Dispatchers.IO) {
        val msgId = UUID.randomUUID().toString().replace("-", "")
        val data = postProto(
            "$BASE/v1/msg/send-message",
            Requests.sendMessage(msgId, chatId, chatType, contentType, content, quoteMsgId)
        )
        val st = Responses.status(data)
        if (st.code != 1) throw ApiException(st.code, if (st.msg.isNotEmpty()) st.msg else "发送失败")
        msgId
    }

    suspend fun recallMessage(chatId: String, chatType: Int, msgId: String) =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/msg/recall-msg", Requests.recallMessage(chatId, chatType, listOf(msgId)))
            val st = Responses.status(data)
            if (st.code != 1) throw ApiException(st.code, if (st.msg.isNotEmpty()) st.msg else "撤回失败")
        }

    // ---------- 通讯录 ----------

    suspend fun addressBookList(md5: String = ""): Pair<List<ContactSection>, String> =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/friend/address-book-list", Requests.addressBookList(md5))
            Responses.addressBookList(data)
        }

    // ---------- 表情 ----------

    data class Expression(val id: Long, val url: String)

    /** 个人表情收藏 */
    suspend fun expressionList(): List<Expression> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/expression/list", JSONObject())
        if (resp.optInt("code", -1) != 1) return@withContext emptyList<Expression>()
        val arr = resp.optJSONObject("data")?.optJSONArray("expression") ?: return@withContext emptyList<Expression>()
        val out = ArrayList<Expression>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Expression(o.optLong("id"), o.optString("url")))
        }
        out
    }

    /** 图片完整 URL（相对路径补全图床域名） */
    fun fullImageUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return IMG_BASE + url
    }

    // ---------- 社区 ----------

    data class Ba(
        val id: Long,
        val name: String,
        val avatar: String,
        val memberNum: Int,
        val postNum: Int
    )

    data class Post(
        val id: Long,
        val baId: Long,
        val senderId: String,
        val title: String,
        val content: String,
        val contentType: Int,
        val senderNickname: String,
        val senderAvatar: String,
        val createTimeText: String,
        val likeNum: Int,
        val commentNum: Int,
        val amountNum: Int
    )

    data class Comment(
        val id: Long,
        val senderId: String,
        val content: String,
        val senderNickname: String,
        val senderAvatar: String,
        val createTimeText: String,
        val likeNum: Int,
        val amountNum: Int
    )

    private fun JSONObject.toBa() = Ba(
        id = optLong("id"),
        name = optString("name"),
        avatar = optString("avatar"),
        memberNum = optInt("memberNum"),
        postNum = optInt("postNum")
    )

    private fun JSONObject.toPost() = Post(
        id = optLong("id"),
        baId = optLong("baId"),
        senderId = optString("senderId"),
        title = optString("title"),
        content = optString("content"),
        contentType = optInt("contentType"),
        senderNickname = optString("senderNickname"),
        senderAvatar = optString("senderAvatar"),
        createTimeText = optString("createTimeText"),
        likeNum = optInt("likeNum"),
        commentNum = optInt("commentNum"),
        amountNum = optInt("amountNum")
    )

    /** 分区列表。typ: 1-关注 2-热门 3-我的 4-全部 */
    suspend fun baList(typ: Int, size: Int = 50, page: Int = 1): List<Ba> = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/ba/following-ba-list",
            JSONObject().put("typ", typ).put("size", size).put("page", page)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取分区失败"))
        val arr = resp.optJSONObject("data")?.optJSONArray("ba") ?: return@withContext emptyList<Ba>()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toBa() }
    }

    /** 某分区文章列表，返回 (文章, 总数) */
    suspend fun postList(baId: Long, size: Int = 20, page: Int = 1): Pair<List<Post>, Int> =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/posts/post-list",
                JSONObject().put("typ", 1).put("baId", baId).put("size", size).put("page", page)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取文章失败"))
            val data = resp.optJSONObject("data")
            val arr = data?.optJSONArray("posts")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.toPost() }
            list to (data?.optInt("total") ?: list.size)
        }

    /** 文章详情，返回 (文章, 所属分区) */
    suspend fun postDetail(id: Long): Pair<Post, Ba> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/post-detail", JSONObject().put("id", id))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取文章详情失败"))
        val data = resp.getJSONObject("data")
        val post = data.getJSONObject("post").toPost()
        val ba = data.optJSONObject("ba")?.toBa() ?: Ba(0, "", "", 0, 0)
        post to ba
    }

    /** 评论列表，返回 (评论, 总数) */
    suspend fun commentList(postId: Long, size: Int = 50, page: Int = 1): Pair<List<Comment>, Int> =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/comment/comment-list",
                JSONObject().put("postId", postId).put("size", size).put("page", page)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取评论失败"))
            val data = resp.optJSONObject("data")
            val arr = data?.optJSONArray("comments")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr?.optJSONObject(i)?.let { o ->
                    Comment(
                        id = o.optLong("id"),
                        senderId = o.optString("senderId"),
                        content = o.optString("content"),
                        senderNickname = o.optString("senderNickname"),
                        senderAvatar = o.optString("senderAvatar"),
                        createTimeText = o.optString("createTimeText"),
                        likeNum = o.optInt("likeNum"),
                        amountNum = o.optInt("amountNum")
                    )
                }
            }
            list to (data?.optInt("total") ?: list.size)
        }

    /** 发表评论。commentId=0 表示直接评论文章 */
    suspend fun commentPost(postId: Long, content: String, commentId: Long = 0) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/comment/comment",
            JSONObject().put("postId", postId).put("commentId", commentId).put("content", content)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "评论失败"))
    }

    /** 投币（打赏文章）。recvId 为作者 ID */
    suspend fun postReward(postId: Long, recvId: String, amount: Double) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/posts/post-reward",
            JSONObject().put("postId", postId).put("recvId", recvId).put("amount", amount)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "投币失败"))
    }

    /** 点赞/取消点赞文章 */
    suspend fun postLike(postId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/post-like", JSONObject().put("id", postId))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "点赞失败"))
    }
}
