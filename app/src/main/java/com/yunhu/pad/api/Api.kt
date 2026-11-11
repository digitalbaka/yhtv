package com.yunhu.pad.api

import com.yunhu.pad.util.Session
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
    // 文档「音频路由 / 视频路由 / 文件路由」：不同类型走不同域名，见 /api/ 首页说明
    const val VIDEO_BASE = "https://chat-video1.jwznb.com/"
    const val AUDIO_BASE = "https://chat-audio1.jwznb.com/"
    const val FILE_BASE = "https://chat-file.jwznb.com/"

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

    /**
     * 媒体完整 URL：按消息类型补全对应路由。
     * 文档里 video_url / audio_url 允许存「key」形式（如 123.mp4），
     * 不同类型的 key 分别落在 视频 / 音频 / 文件 路由下；
     * 已经是 http(s) 直链的历史消息原样返回，不受影响。
     */
    fun fullMediaUrl(url: String, contentType: Int): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = when (contentType) {
            ContentType.VIDEO -> VIDEO_BASE
            ContentType.AUDIO -> AUDIO_BASE
            ContentType.FILE -> FILE_BASE
            else -> IMG_BASE
        }
        return base + url
    }

    // ---------- 媒体上传 ----------

    /**
     * 七牛上传凭证。不同媒体走不同接口：
     * 图片 /v1/misc/qiniu-token、语音 -audio、视频 -video、文件 2
     */
    suspend fun qiniuToken(kind: String): String = withContext(Dispatchers.IO) {
        val resp = getJson("$BASE/v1/misc/qiniu-token$kind")
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取上传凭证失败"))
        val token = resp.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isEmpty()) throw ApiException(-1, "上传凭证为空")
        token
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
        val amountNum: Int,
        val collectNum: Int = 0,
        /** 我是否已点赞：0 未赞 1 已赞 */
        val isLiked: Int = 0,
        /** 我是否已收藏：0 未收藏 1 已收藏 */
        val isCollected: Int = 0
    )

    data class Comment(
        val id: Long,
        val senderId: String,
        val content: String,
        val senderNickname: String,
        val senderAvatar: String,
        val createTimeText: String,
        val likeNum: Int,
        val amountNum: Int,
        val replies: List<Comment> = emptyList()
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
        amountNum = optInt("amountNum"),
        collectNum = optInt("collectNum"),
        isLiked = optInt("isLiked"),
        isCollected = optInt("isCollected")
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
                arr?.optJSONObject(i)?.toComment()
            }
            list to (data?.optInt("total") ?: list.size)
        }

    private fun JSONObject.toComment(): Comment {
        val replies = optJSONArray("replies")
        val replyList = (0 until (replies?.length() ?: 0)).mapNotNull {
            replies?.optJSONObject(it)?.toComment()
        }
        return Comment(
            id = optLong("id"),
            senderId = optString("senderId"),
            content = optString("content"),
            senderNickname = optString("senderNickname"),
            senderAvatar = optString("senderAvatar"),
            createTimeText = optString("createTimeText"),
            likeNum = optInt("likeNum"),
            amountNum = optInt("amountNum"),
            replies = replyList
        )
    }

    /**
     * 发布文章。contentType：1-纯文本，2-Markdown。
     * 文档里返回字段名有误（示例写成 audioUrl），这里做兼容：优先 postId，取不到也不影响发布结果。
     */
    suspend fun createPost(baId: Long, title: String, content: String, contentType: Int): Long =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/posts/create",
                JSONObject()
                    .put("baId", baId)
                    .put("groupId", "")
                    .put("title", title)
                    .put("content", content)
                    .put("contentType", contentType)
                    .put("draftId", 0)
            )
            if (resp.optInt("code", -1) != 1) {
                throw ApiException(resp.optInt("code"), resp.optString("msg", "发布失败"))
            }
            resp.optJSONObject("data")?.optLong("postId", 0) ?: 0
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

    /** 点赞/取消点赞文章（toggle） */
    suspend fun postLike(postId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/post-like", JSONObject().put("id", postId))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "点赞失败"))
    }

    /** 收藏/取消收藏文章（toggle） */
    suspend fun postCollect(postId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/post-collect", JSONObject().put("id", postId))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "收藏失败"))
    }

    // ---------- 用户资料 ----------

    /** 修改昵称（proto: EditNicknameRequest name=3） */
    suspend fun editNickname(name: String) = withContext(Dispatchers.IO) {
        val w = ProtoWriter()
        w.string(3, name)
        val data = postProto("$BASE/v1/user/edit-nickname", w.toByteArray())
        val st = Responses.status(data)
        if (st.code != 1) throw ApiException(st.code, if (st.msg.isNotEmpty()) st.msg else "修改昵称失败")
    }

    data class UserData(
        val introduction: String,
        val gender: Int,
        val birthday: Long,
        val province: String,
        val city: String
    )

    /** 获取个人资料 */
    suspend fun getUserData(): UserData = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/user/get-user-data", JSONObject())
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取资料失败"))
        val d = resp.optJSONObject("data")?.optJSONObject("data") ?: JSONObject()
        UserData(
            introduction = d.optString("introduction"),
            gender = d.optInt("gender", 3),
            birthday = d.optLong("birthday"),
            province = d.optString("province"),
            city = d.optString("city")
        )
    }

    /** 保存个人资料（简介/性别） */
    suspend fun saveUserData(introduction: String, gender: Int = 3) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/user/save-user-data",
            JSONObject().put("introduction", introduction).put("gender", gender)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "保存资料失败"))
    }

    // ---------- 搜索与申请 ----------

    data class SearchResult(
        val friendId: String,
        val friendType: Int,
        val nickname: String,
        val name: String,
        val avatarUrl: String,
        /** 机器人/群组是否可直接添加（无需审核） */
        val alwaysAgree: Boolean = false
    )

    /** 综合搜索（用户/群组/机器人），返回分类标题到结果的映射 */
    suspend fun homeSearch(word: String): List<Pair<String, List<SearchResult>>> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/search/home-search", JSONObject().put("word", word))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "搜索失败"))
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val sec = arr.optJSONObject(i) ?: return@mapNotNull null
            val items = sec.optJSONArray("list")
            val results = (0 until (items?.length() ?: 0)).mapNotNull { j ->
                items?.optJSONObject(j)?.let { o ->
                    SearchResult(
                        friendId = o.optString("friendId"),
                        friendType = o.optInt("friendType"),
                        nickname = o.optString("nickname"),
                        name = o.optString("name"),
                        avatarUrl = o.optString("avatarUrl"),
                        // 该字段可能是布尔值也可能是 0/1
                        alwaysAgree = o.optBoolean("alwaysAgree", false) || o.optInt("alwaysAgree", 0) != 0
                    )
                }
            }
            sec.optString("title") to results
        }
    }

    /** 申请添加好友/群聊/机器人，remark 为申请理由（可选） */
    suspend fun friendApply(chatId: String, chatType: Int, remark: String = "") = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/friend/apply",
            JSONObject().put("chatId", chatId).put("chatType", chatType).put("remark", remark)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "申请失败"))
    }

    /** 申请列表，返回 (列表, 待处理数) */
    suspend fun requestList(): Pair<List<ApplyRequest>, Int> = withContext(Dispatchers.IO) {
        val data = postProto("$BASE/v1/friend/request-list", ByteArray(0))
        Responses.requestList(data)
    }

    /** 处理申请。agree: 1-通过 2-拒绝 */
    suspend fun agreeApply(id: Int, agree: Int) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/friend/agree-apply", JSONObject().put("id", id).put("agree", agree))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "操作失败"))
    }

    /** 忽略申请 */
    suspend fun ignoreApply(id: Int) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/friend/ignore-apply", JSONObject().put("id", id).put("ignore", 1))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "操作失败"))
    }

    // ---------- 社区「我的」 ----------

    /** 我的文章 */
    suspend fun myPostList(size: Int = 20, page: Int = 1): Pair<List<Post>, Int> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/my-post-list", JSONObject().put("size", size).put("page", page))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取文章失败"))
        val data = resp.optJSONObject("data")
        val arr = data?.optJSONArray("posts")
        val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.toPost() }
        list to (data?.optInt("total") ?: list.size)
    }

    /** 我的收藏（post-list typ=2，全站范围 baId=0） */
    suspend fun myCollectList(size: Int = 20, page: Int = 1): Pair<List<Post>, Int> = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/posts/post-list",
            JSONObject().put("typ", 2).put("baId", 0).put("size", size).put("page", page)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取收藏失败"))
        val data = resp.optJSONObject("data")
        val arr = data?.optJSONArray("posts")
        val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.toPost() }
        list to (data?.optInt("total") ?: list.size)
    }

    data class RewardRecord(
        val id: Long,
        val senderId: String,
        val recvId: String,
        val amount: Double,
        val createTime: Long,
        val reason: String
    )

    /** 打赏记录（typ: post-文章, comment-评论） */
    suspend fun rewardRecord(typ: String = "post", size: Int = 20, page: Int = 1): List<RewardRecord> =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/reward-record",
                JSONObject().put("typ", typ).put("size", size).put("page", page)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取打赏记录失败"))
            val arr = resp.optJSONObject("data")?.optJSONArray("rewards") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    RewardRecord(
                        id = o.optLong("id"),
                        senderId = o.optString("senderId"),
                        recvId = o.optString("recvId"),
                        amount = o.optDouble("amount"),
                        createTime = o.optLong("createTime"),
                        reason = o.optString("reason")
                    )
                }
            }
        }

    data class BlockedUser(
        val id: Long,
        val userId: String,
        val nickname: String,
        val avatarUrl: String
    )

    /** 屏蔽用户列表（分页，同时返回服务端 total） */
    suspend fun blackListPage(size: Int = 50, page: Int = 1): Pair<List<BlockedUser>, Int> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/black-list", JSONObject().put("size", size).put("page", page))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取屏蔽列表失败"))
        val data = resp.optJSONObject("data")
        val total = data?.optInt("total", -1) ?: -1
        val arr = data?.optJSONArray("list") ?: return@withContext emptyList<BlockedUser>() to total
        val users = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                BlockedUser(
                    id = o.optLong("id"),
                    userId = o.optString("user_id"),
                    nickname = o.optString("nickname"),
                    avatarUrl = o.optString("avatar_url")
                )
            }
        }
        users to total
    }

    /** 屏蔽用户列表 */
    suspend fun blackList(size: Int = 50, page: Int = 1): List<BlockedUser> = blackListPage(size, page).first

    /** 屏蔽/取消屏蔽用户。isAdd: 1-屏蔽 0-取消 */
    suspend fun setBlackList(authorId: String, isAdd: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/set-black-list",
            JSONObject().put("isAdd", isAdd).put("authorId", authorId)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "操作失败"))
    }

    // ---------- 用户详情 / 群信息 ----------

    /** 获取用户详情 */
    suspend fun getUser(userId: String): GetUserData = withContext(Dispatchers.IO) {
        val data = postProto("$BASE/v1/user/get-user", Requests.getUser(userId))
        Responses.getUser(data)
    }

    /** 获取群资料 */
    suspend fun groupInfo(groupId: String): GroupInfo = withContext(Dispatchers.IO) {
        val data = postProto("$BASE/v1/group/info", Requests.groupInfo(groupId))
        Responses.groupInfo(data)
    }

    /** 群成员列表 */
    suspend fun listMember(groupId: String, size: Int = 50, page: Int = 1): Pair<List<GroupMember>, Int> =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/group/list-member", Requests.listMember(groupId, size, page))
            Responses.listMember(data)
        }

    /** 编辑群信息（需管理员/群主权限） */
    suspend fun editGroup(groupId: String, name: String = "", introduction: String = "") =
        withContext(Dispatchers.IO) {
            val data = postProto("$BASE/v1/group/edit-group", Requests.editGroup(groupId, name, introduction))
            val st = Responses.status(data)
            if (st.code != 1) throw ApiException(st.code, if (st.msg.isNotEmpty()) st.msg else "修改失败")
        }

    /** 修改我的群昵称 */
    suspend fun editMyGroupNickname(groupId: String, nickname: String) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/edit-my-group-nickname",
            JSONObject().put("groupId", groupId).put("nickname", nickname)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "修改失败"))
    }

    /** 设置会话免打扰（群聊/好友通用）。noNotify: 1-开启 0-关闭 */
    suspend fun setConversationNotify(chatId: String, noNotify: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/friend/no-notify",
            JSONObject().put("chatId", chatId).put("noNotify", noNotify)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置失败"))
    }

    /** 退出群聊：将自己从群成员中移除 */
    suspend fun quitGroup(groupId: String) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/remove-member",
            JSONObject().put("groupId", groupId).put("userId", Session.userId)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "退出失败"))
    }

    // ---------- 聊天记录搜索 ----------

    data class ChatSearchHit(
        val id: String,
        val sequence: Long,
        val name: String,
        val avatarUrl: String,
        val content: String,
        val type: Int,
        val time: Long,
        val timeText: String
    )

    /**
     * 会话内聊天记录搜索。文档：POST /v1/search/chat-search
     * type: all-全部，1-文本，3-markdown，4-文件，6-文章，7-表情，8-html
     * time：只返回该时间戳内的消息，用于「加载更多」按时间向前翻页
     */
    suspend fun chatSearch(
        word: String,
        chatId: String,
        chatType: Int,
        type: String = "all",
        size: Int = 30,
        time: Long = 9999999999999L
    ): List<ChatSearchHit> = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/search/chat-search",
            JSONObject()
                .put("word", word)
                .put("chatId", chatId)
                .put("chatType", chatType)
                .put("type", type)
                .put("size", size)
                .put("time", time)
                .put("direction", 1)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "搜索失败"))
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                ChatSearchHit(
                    id = o.optString("id"),
                    sequence = o.optLong("sequence"),
                    name = o.optString("name"),
                    avatarUrl = o.optString("avatarUrl"),
                    content = o.optString("content"),
                    type = o.optString("type").toIntOrNull() ?: 0,
                    time = o.optLong("time"),
                    timeText = o.optString("timeText")
                )
            }
        }
    }

    // ---------- 消息编辑 / 删除 ----------

    /** 编辑消息：与发送共用 SendMsgRequest，msg_id 换成要编辑的消息 */
    suspend fun editMessage(msgId: String, chatId: String, chatType: Int, contentType: Int, content: MsgContent) =
        withContext(Dispatchers.IO) {
            val data = postProto(
                "$BASE/v1/msg/edit-message",
                Requests.sendMessage(msgId, chatId, chatType, contentType, content)
            )
            val st = Responses.status(data)
            if (st.code != 1) throw ApiException(st.code, if (st.msg.isNotEmpty()) st.msg else "编辑失败")
        }

    /** 长按删除消息（非撤回） */
    suspend fun deleteMessage(chatId: String, msgId: String, chatType: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/msg/delete",
            JSONObject().put("chatId", chatId).put("msgId", msgId).put("chatType", chatType)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "删除失败"))
    }

    data class MsgEditRecord(
        val id: Long,
        val contentType: Int,
        /** 历史内容，服务端返回转义后的 JSON（如 {"text":"..."}） */
        val contentOld: String,
        val msgTime: Long
    )

    /** 消息编辑历史，返回 (记录, 总数) */
    suspend fun msgEditRecords(msgId: String, size: Int = 10, page: Int = 1): Pair<List<MsgEditRecord>, Int> =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/msg/list-message-edit-record",
                JSONObject().put("msgId", msgId).put("size", size).put("page", page)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取编辑记录失败"))
            val data = resp.optJSONObject("data")
            val arr = data?.optJSONArray("list") ?: return@withContext emptyList<MsgEditRecord>() to 0
            val list = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    MsgEditRecord(
                        id = o.optLong("id"),
                        contentType = o.optInt("contentType"),
                        contentOld = o.optString("contentOld"),
                        msgTime = o.optLong("msgTime")
                    )
                }
            }
            list to data.optInt("total")
        }

    // ---------- 首页置顶会话 ----------

    data class Sticky(
        val id: Long,
        val chatId: String,
        val chatType: Int,
        val chatName: String,
        val avatarUrl: String
    )

    suspend fun stickyList(): List<Sticky> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/sticky/list", JSONObject())
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取置顶失败"))
        val arr = resp.optJSONObject("data")?.optJSONArray("sticky") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                Sticky(
                    id = o.optLong("id"),
                    chatId = o.optString("chatId"),
                    chatType = o.optInt("chatType"),
                    chatName = o.optString("chatName"),
                    avatarUrl = o.optString("avatarUrl")
                )
            }
        }
    }

    suspend fun stickyAdd(chatId: String, chatType: Int) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/sticky/add", JSONObject().put("chatId", chatId).put("chatType", chatType))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "置顶失败"))
    }

    suspend fun stickyDelete(chatId: String, chatType: Int) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/sticky/delete", JSONObject().put("chatId", chatId).put("chatType", chatType))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "取消置顶失败"))
    }

    suspend fun stickyTopping(id: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/sticky/topping", JSONObject().put("id", id))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "操作失败"))
    }

    // ---------- 群管理（禁言 / 标签 / 进阶设置） ----------

    /** 禁言用户。seconds: 0-取消禁言，600/3600/21600/43200，-1-永久 */
    suspend fun gagMember(groupId: String, userId: String, seconds: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/gag-member",
            JSONObject().put("groupId", groupId).put("userId", userId).put("gag", seconds)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "禁言操作失败"))
    }

    /** 群消息类型限制，type 为允许的类别列表（逗号分隔，如 "1,2,3"） */
    suspend fun setMsgTypeLimit(groupId: String, type: String) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/msg-type-limit",
            JSONObject().put("groupId", groupId).put("type", type)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置失败"))
    }

    suspend fun editGroupKeyword(groupId: String, keyword: String) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/edit-group-keyword",
            JSONObject().put("groupId", groupId).put("keyword", keyword)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置群口令失败"))
    }

    /** 消息自动销毁：0-不删，90-90天，365-1年，730-2年 */
    suspend fun setAutoDeleteMessage(groupId: String, days: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/edit-auto-delete-message",
            JSONObject().put("groupId", groupId).put("autoDeleteMessage", days)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置失败"))
    }

    suspend fun setStopMemberUpload(groupId: String, stop: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/edit-stop-member-upload-group-file",
            JSONObject().put("groupId", groupId).put("stopMemberUploadGroupFile", stop)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置失败"))
    }

    /** 加入群推荐开关：hide 0-不隐藏（参与推荐），1-隐藏 */
    suspend fun setGroupSwitch(groupId: String, hide: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group/switch",
            JSONObject().put("groupId", groupId).put("hide", hide)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置失败"))
    }

    data class GroupTag(
        val id: Long,
        val groupId: String,
        val tag: String,
        val color: String,
        val desc: String,
        val sort: Int
    )

    suspend fun groupTagList(groupId: String, size: Int = 50, page: Int = 1, tag: String = ""): List<GroupTag> =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/group-tag/list",
                JSONObject().put("groupId", groupId).put("size", size).put("page", page).put("tag", tag)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取群标签失败"))
            val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    GroupTag(
                        id = o.optLong("id"),
                        groupId = o.optString("groupId"),
                        tag = o.optString("tag"),
                        color = o.optString("color"),
                        desc = o.optString("desc"),
                        sort = o.optInt("sort")
                    )
                }
            }
        }

    suspend fun createGroupTag(groupId: String, tag: String, color: String, desc: String = "", sort: Int = 0) =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/group-tag/create",
                JSONObject().put("groupId", groupId).put("tag", tag).put("color", color).put("desc", desc).put("sort", sort)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "创建标签失败"))
        }

    suspend fun editGroupTag(id: Long, groupId: String, tag: String, color: String, desc: String = "", sort: Int = 0) =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/group-tag/edit",
                JSONObject().put("id", id).put("groupId", groupId).put("tag", tag)
                    .put("color", color).put("desc", desc).put("sort", sort)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "编辑标签失败"))
        }

    suspend fun deleteGroupTag(id: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/group-tag/delete", JSONObject().put("id", id))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "删除标签失败"))
    }

    suspend fun relateGroupTag(userId: String, tagId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group-tag/relate",
            JSONObject().put("userId", userId).put("tagGroupId", tagId)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "设置标签失败"))
    }

    suspend fun cancelGroupTag(userId: String, tagId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/group-tag/relate-cancel",
            JSONObject().put("userId", userId).put("tagGroupId", tagId)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "取消标签失败"))
    }

    // ---------- 社区闭环：编辑 / 删除 / 草稿 / 搜索 / 举报 ----------

    suspend fun editPost(postId: Long, title: String, content: String, contentType: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/posts/edit",
            JSONObject().put("postId", postId).put("title", title)
                .put("content", content).put("contentType", contentType)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "编辑失败"))
    }

    suspend fun deletePost(postId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/delete", JSONObject().put("postId", postId))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "删除失败"))
    }

    /** 创建/更新草稿，draftId 非 0 时会先删除旧草稿；返回新草稿 ID */
    suspend fun createDraft(baId: Long, title: String, content: String, contentType: Int, draftId: Long = 0): Long =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/posts/create-draft",
                JSONObject().put("baId", baId).put("title", title).put("content", content)
                    .put("contentType", contentType).put("draftId", draftId)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "保存草稿失败"))
            resp.optJSONObject("data")?.optLong("id", 0) ?: 0
        }

    /** 草稿详情：返回 (标题, 正文, 内容类型, 分区 ID) */
    suspend fun getDraft(draftId: Long, baId: Long = 0): Api.Post? = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/posts/get-draft",
            JSONObject().put("draftId", draftId).put("baId", baId)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取草稿失败"))
        resp.optJSONObject("data")?.optJSONObject("posts")?.toPost()
    }

    suspend fun cancelDraft(draftId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/cancel-draft", JSONObject().put("draftId", draftId))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "删除草稿失败"))
    }

    /** 社区文章搜索 */
    suspend fun communitySearch(keyword: String, size: Int = 20, page: Int = 1): Pair<List<Post>, Int> =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/search",
                JSONObject().put("typ", 3).put("keyword", keyword).put("size", size).put("page", page)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "搜索失败"))
            val data = resp.optJSONObject("data")
            val arr = data?.optJSONArray("posts")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.toPost() }
            list to (data?.optInt("total") ?: list.size)
        }

    /** 举报文章 / 评论（community/report）。typ: 1-文章 2-评论 */
    suspend fun reportCommunity(typ: Int, id: Long, content: String, reason: String, url: String = "") =
        withContext(Dispatchers.IO) {
            val resp = postJson(
                "$BASE/v1/community/report",
                JSONObject().put("typ", typ).put("id", id).put("content", content)
                    .put("reason", reason).put("url", url)
            )
            if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "举报失败"))
        }

    /** 举报会话（report/create） */
    suspend fun reportChat(
        chatId: String, chatType: Int, chatName: String, content: String, reason: String, url: String = ""
    ) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/report/create",
            JSONObject().put("chatId", chatId).put("chatType", chatType).put("chatName", chatName)
                .put("content", content).put("reason", reason).put("url", url)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "举报失败"))
    }

    // ---------- 账号自服务 ----------

    /** 修改头像，url 必须是完整地址 */
    suspend fun editAvatar(url: String) = withContext(Dispatchers.IO) {
        val data = postProto("$BASE/v1/user/edit-avatar", ProtoWriter().string(2, url).toByteArray())
        val st = Responses.status(data)
        if (st.code != 1) throw ApiException(st.code, if (st.msg.isNotEmpty()) st.msg else "修改头像失败")
    }

    suspend fun bindEmail(email: String, captcha: String) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/user/bing-email", JSONObject().put("email", email).put("captcha", captcha))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "绑定邮箱失败"))
    }

    suspend fun changeEmail(email: String, captcha: String) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/user/change-email-check", JSONObject().put("email", email).put("captcha", captcha))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "更换邮箱失败"))
    }

    suspend fun forgetPassword(email: String, captcha: String, password: String) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/user/forget-password",
            JSONObject().put("email", email).put("captcha", captcha).put("password", password)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "修改密码失败"))
    }

    /**
     * 获取邮箱验证码。文档里 code/id 是人机验证参数，但没有说明校验码的获取方式，
     * 这里如实传空，服务端若要求人机验证会返回可读错误由界面提示。
     */
    suspend fun emailVerificationCode(email: String, typ: String = "") = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/verification/get-email-verification-code",
            JSONObject().put("email", email).put("typ", typ).put("code", "").put("id", "")
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "发送验证码失败"))
    }

    suspend fun deviceOffline(deviceId: String) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/user/device-offline", JSONObject().put("deviceId", deviceId))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "下线失败"))
    }
}
