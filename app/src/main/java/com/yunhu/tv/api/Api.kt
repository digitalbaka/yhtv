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
            android.util.Log.d("CommentDebug", "comment-list raw: ${resp.toString()}")
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
        val avatarUrl: String
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
                        avatarUrl = o.optString("avatarUrl")
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

    /** 屏蔽用户列表 */
    suspend fun blackList(size: Int = 50, page: Int = 1): List<BlockedUser> = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/black-list", JSONObject().put("size", size).put("page", page))
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "获取屏蔽列表失败"))
        val arr = resp.optJSONObject("data")?.optJSONArray("list") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                BlockedUser(
                    id = o.optLong("id"),
                    userId = o.optString("user_id"),
                    nickname = o.optString("nickname"),
                    avatarUrl = o.optString("avatar_url")
                )
            }
        }
    }

    /** 屏蔽/取消屏蔽用户。isAdd: 1-屏蔽 0-取消 */
    suspend fun setBlackList(authorId: String, isAdd: Int) = withContext(Dispatchers.IO) {
        val resp = postJson(
            "$BASE/v1/community/set-black-list",
            JSONObject().put("isAdd", isAdd).put("authorId", authorId)
        )
        if (resp.optInt("code", -1) != 1) throw ApiException(resp.optInt("code"), resp.optString("msg", "操作失败"))
    }

    /** 收藏/取消收藏文章 */
    suspend fun postCollect(postId: Long) = withContext(Dispatchers.IO) {
        val resp = postJson("$BASE/v1/community/posts/post-collect", JSONObject().put("id", postId))
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
}
