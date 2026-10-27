package com.yunhu.tv.api

/** chat_type: 1-用户 2-群组 3-机器人 */
object ChatType {
    const val USER = 1
    const val GROUP = 2
    const val BOT = 3
}

/** content_type: 1-文本 2-图片 3-markdown 4-文件 5-表单 7-表情 8-html 9-tip 10-视频 11-语音 13-语音通话 14-a2ui */
object ContentType {
    const val TEXT = 1
    const val IMAGE = 2
    const val MARKDOWN = 3
    const val FILE = 4
    const val FORM = 5
    const val STICKER = 7
    const val HTML = 8
    const val TIP = 9
    const val VIDEO = 10
    const val AUDIO = 11
    const val CALL = 13
    const val A2UI = 14
}

data class MsgContent(
    var text: String = "",
    var buttons: String = "",
    var imageUrl: String = "",
    var fileName: String = "",
    var fileUrl: String = "",
    var mentionedIds: List<String> = emptyList(),
    var form: String = "",
    var quoteMsgText: String = "",
    var image: String = "",
    var postId: String = "",
    var postTitle: String = "",
    var postContent: String = "",
    var expressionId: String = "",
    var fileSize: Long = 0,
    var videoUrl: String = "",
    var videoTime: Int = 0,
    var audioUrl: String = "",
    var audioTime: Int = 0,
    var stickerItemId: Long = 0,
    var stickerPackId: Long = 0,
    var callStatusText: String = "",
    var width: Int = 0,
    var height: Int = 0,
    var tip: String = ""
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.string(1, text)
        w.string(2, buttons)
        w.string(3, imageUrl)
        w.string(4, fileName)
        w.string(5, fileUrl)
        mentionedIds.forEach { w.string(6, it) }
        w.string(7, form)
        w.string(8, quoteMsgText)
        w.string(9, image)
        w.string(10, postId)
        w.string(11, postTitle)
        w.string(12, postContent)
        w.string(15, expressionId)
        w.varint(18, fileSize)
        w.string(19, videoUrl)
        w.varint(20, videoTime.toLong())
        w.string(21, audioUrl)
        w.varint(22, audioTime.toLong())
        w.varint(25, stickerItemId)
        w.varint(26, stickerPackId)
        w.string(32, callStatusText)
        w.varint(33, width.toLong())
        w.varint(34, height.toLong())
        w.string(37, tip)
        return w.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): MsgContent {
            val c = MsgContent()
            val r = ProtoReader(data)
            val mentioned = mutableListOf<String>()
            while (r.hasMore()) {
                val tag = r.readTag()
                val field = tag ushr 3
                val wire = tag and 7
                when (field) {
                    1 -> c.text = r.readString()
                    2 -> c.buttons = r.readString()
                    3 -> c.imageUrl = r.readString()
                    4 -> c.fileName = r.readString()
                    5 -> c.fileUrl = r.readString()
                    6 -> mentioned.add(r.readString())
                    7 -> c.form = r.readString()
                    8 -> c.quoteMsgText = r.readString()
                    9 -> c.image = r.readString()
                    10 -> c.postId = r.readString()
                    11 -> c.postTitle = r.readString()
                    12 -> c.postContent = r.readString()
                    15 -> c.expressionId = r.readString()
                    18 -> c.fileSize = r.readVarint()
                    19 -> c.videoUrl = r.readString()
                    20 -> c.videoTime = r.readVarint().toInt()
                    21 -> c.audioUrl = r.readString()
                    22 -> c.audioTime = r.readVarint().toInt()
                    25 -> c.stickerItemId = r.readVarint()
                    26 -> c.stickerPackId = r.readVarint()
                    32 -> c.callStatusText = r.readString()
                    33 -> c.width = r.readVarint().toInt()
                    34 -> c.height = r.readVarint().toInt()
                    37 -> c.tip = r.readString()
                    else -> r.skip(wire)
                }
            }
            c.mentionedIds = mentioned
            return c
        }
    }
}

data class Sender(
    var chatId: String = "",
    var chatType: Int = 0,
    var name: String = "",
    var avatarUrl: String = "",
    var isVip: Boolean = false
) {
    companion object {
        fun decode(data: ByteArray): Sender {
            val s = Sender()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> s.chatId = r.readString()
                    2 -> s.chatType = r.readVarint().toInt()
                    3 -> s.name = r.readString()
                    4 -> s.avatarUrl = r.readString()
                    5 -> s.isVip = r.readVarint() != 0L
                    else -> r.skip(tag and 7)
                }
            }
            return s
        }
    }
}

data class CmdInfo(
    var cmdId: Long = 0,
    var name: String = "",
    var type: Int = 0
) {
    companion object {
        fun decode(data: ByteArray): CmdInfo {
            val c = CmdInfo()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> c.cmdId = r.readVarint()
                    2 -> c.name = r.readString()
                    4 -> c.type = r.readVarint().toInt()
                    else -> r.skip(tag and 7)
                }
            }
            return c
        }
    }
}

/** 一条聊天消息（对应 ListMsgResponse.MsgData，与 PushMessage 结构基本一致） */
data class ChatMsg(
    var msgId: String = "",
    var sender: Sender = Sender(),
    var direction: String = "",
    var contentType: Int = 0,
    var content: MsgContent = MsgContent(),
    var sendTimestampMs: Long = 0,
    var cmd: CmdInfo? = null,
    var recallTimestamp: Long = 0,
    var quoteMsgId: String = "",
    var msgSeq: Long = 0,
    var editTimestamp: Long = 0,
    // 仅 WS 推送有
    var recvId: String = "",
    var chatId: String = "",
    var chatType: Int = 0
) {
    val isRecalled: Boolean get() = recallTimestamp > 0

    companion object {
        /** 解析 ListMsgResponse.MsgData */
        fun decodeListItem(data: ByteArray): ChatMsg {
            val m = ChatMsg()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> m.msgId = r.readString()
                    2 -> m.sender = Sender.decode(r.readBytes())
                    3 -> m.direction = r.readString()
                    4 -> m.contentType = r.readVarint().toInt()
                    5 -> m.content = MsgContent.decode(r.readBytes())
                    6 -> m.sendTimestampMs = r.readVarint()
                    7 -> m.cmd = CmdInfo.decode(r.readBytes())
                    8 -> m.recallTimestamp = r.readVarint()
                    9 -> m.quoteMsgId = r.readString()
                    10 -> m.msgSeq = r.readVarint()
                    12 -> m.editTimestamp = r.readVarint()
                    else -> r.skip(tag and 7)
                }
            }
            return m
        }

        /** 解析 WS PushMessage */
        fun decodePush(data: ByteArray): ChatMsg {
            val m = ChatMsg()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> m.msgId = r.readString()
                    2 -> m.sender = Sender.decode(r.readBytes())
                    3 -> m.recvId = r.readString()
                    4 -> m.chatId = r.readString()
                    5 -> m.chatType = r.readVarint().toInt()
                    6 -> m.content = MsgContent.decode(r.readBytes())
                    7 -> m.contentType = r.readVarint().toInt()
                    8 -> m.sendTimestampMs = r.readVarint()
                    9 -> m.cmd = CmdInfo.decode(r.readBytes())
                    10 -> m.recallTimestamp = r.readVarint()
                    11 -> m.quoteMsgId = r.readString()
                    12 -> m.msgSeq = r.readVarint()
                    14 -> m.editTimestamp = r.readVarint()
                    else -> r.skip(tag and 7)
                }
            }
            return m
        }
    }
}

data class Conversation(
    var chatId: String = "",
    var chatType: Int = 0,
    var remark: String = "",
    var chatContent: String = "",
    var timestampMs: Long = 0,
    var unreadMsg: Int = 0,
    var at: Boolean = false,
    var avatarUrl: String = "",
    var doNotDisturb: Boolean = false,
    var sendTimestamp: Long = 0,
    var name: String = "",
    var certificationLevel: Int = 0
) {
    val displayName: String get() = if (remark.isNotEmpty()) remark else name

    companion object {
        fun decode(data: ByteArray): Conversation {
            val c = Conversation()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> c.chatId = r.readString()
                    2 -> c.chatType = r.readVarint().toInt()
                    3 -> c.remark = r.readString()
                    4 -> c.chatContent = r.readString()
                    5 -> c.timestampMs = r.readVarint()
                    6 -> c.unreadMsg = r.readVarint().toInt()
                    7 -> c.at = r.readVarint() != 0L
                    9 -> c.avatarUrl = r.readString()
                    11 -> c.doNotDisturb = r.readVarint() != 0L
                    12 -> c.sendTimestamp = r.readVarint()
                    15 -> c.name = r.readString()
                    16 -> c.certificationLevel = r.readVarint().toInt()
                    else -> r.skip(tag and 7)
                }
            }
            return c
        }
    }
}

data class ContactItem(
    var chatId: String = "",
    var remark: String = "",
    var avatarUrl: String = "",
    var permissionLevel: Int = 0,
    var name: String = ""
) {
    val displayName: String get() = if (remark.isNotEmpty()) remark else name

    companion object {
        fun decode(data: ByteArray): ContactItem {
            val c = ContactItem()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> c.chatId = r.readString()
                    2 -> c.remark = r.readString()
                    3 -> c.avatarUrl = r.readString()
                    4 -> c.permissionLevel = r.readVarint().toInt()
                    8 -> c.name = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            return c
        }
    }
}

data class ContactSection(
    var listName: String = "",
    var chatType: Int = 0,
    var items: List<ContactItem> = emptyList()
) {
    companion object {
        fun decode(data: ByteArray): ContactSection {
            val s = ContactSection()
            val r = ProtoReader(data)
            val items = mutableListOf<ContactItem>()
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> s.listName = r.readString()
                    2 -> items.add(ContactItem.decode(r.readBytes()))
                    3 -> s.chatType = r.readVarint().toInt()
                    else -> r.skip(tag and 7)
                }
            }
            s.items = items
            return s
        }
    }
}

/** 好友/入群申请（RequestListResponse.Request） */
data class ApplyRequest(
    var name: String = "",
    var avatar: String = "",
    var groupName: String = "",
    var groupAvatar: String = "",
    var targetType: Int = 0,
    var targetId: String = "",
    var result: Int = 0,
    var inviteAtStr: String = "",
    var requestId: Int = 0,
    var botName: String = "",
    var note: String = ""
) {
    companion object {
        fun decode(data: ByteArray): ApplyRequest {
            val a = ApplyRequest()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    3 -> a.name = r.readString()
                    4 -> a.avatar = r.readString()
                    5 -> a.groupName = r.readString()
                    6 -> a.groupAvatar = r.readString()
                    10 -> a.targetType = r.readVarint().toInt()
                    11 -> a.targetId = r.readString()
                    13 -> a.result = r.readVarint().toInt()
                    17 -> a.inviteAtStr = r.readString()
                    18 -> a.requestId = r.readVarint().toInt()
                    19 -> a.botName = r.readString()
                    23 -> a.note = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            return a
        }
    }
}

/** 群资料 */
data class GroupInfo(
    var groupId: String = "",
    var name: String = "",
    var avatarUrl: String = "",
    var introduction: String = "",
    var headcount: Long = 0,
    var createBy: String = "",
    var directJoin: Boolean = false,
    var permissionLevel: Int = 0,
    var historyMsg: Boolean = false,
    var categoryName: String = "",
    var isPrivate: Boolean = false,
    var doNotDisturb: Boolean = false,
    var top: Boolean = false,
    var admins: List<String> = emptyList(),
    var owner: String = "",
    var myGroupNickname: String = "",
    var groupCode: String = ""
) {
    val isAdmin: Boolean get() = permissionLevel == 100 || admins.contains(createBy) // 简化：权限>=2 或在管理员列表
    companion object {
        fun decode(data: ByteArray): GroupInfo {
            val g = GroupInfo()
            val r = ProtoReader(data)
            val admins = mutableListOf<String>()
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> g.groupId = r.readString()
                    2 -> g.name = r.readString()
                    3 -> g.avatarUrl = r.readString()
                    5 -> g.introduction = r.readString()
                    6 -> g.headcount = r.readVarint()
                    7 -> g.createBy = r.readString()
                    8 -> g.directJoin = r.readVarint() != 0L
                    9 -> g.permissionLevel = r.readVarint().toInt()
                    10 -> g.historyMsg = r.readVarint() != 0L
                    11 -> g.categoryName = r.readString()
                    13 -> g.isPrivate = r.readVarint() != 0L
                    14 -> g.doNotDisturb = r.readVarint() != 0L
                    19 -> g.top = r.readVarint() != 0L
                    20 -> admins.add(r.readString())
                    23 -> g.owner = r.readString()
                    28 -> g.myGroupNickname = r.readString()
                    29 -> g.groupCode = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            g.admins = admins
            return g
        }
    }
}

/** 群成员 */
data class GroupMember(
    var userId: String = "",
    var name: String = "",
    var avatarUrl: String = "",
    var isVip: Boolean = false,
    var permissionLevel: Int = 0,
    var isGag: Boolean = false
) {
    companion object {
        fun decode(data: ByteArray): GroupMember {
            val m = GroupMember()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> {} // group_id
                    2 -> {
                        val sub = ProtoReader(r.readBytes())
                        while (sub.hasMore()) {
                            val t2 = sub.readTag()
                            when (t2 ushr 3) {
                                1 -> m.userId = sub.readString()
                                2 -> m.name = sub.readString()
                                4 -> m.avatarUrl = sub.readString()
                                6 -> m.isVip = sub.readVarint() != 0L
                                else -> sub.skip(t2 and 7)
                            }
                        }
                    }
                    3 -> m.permissionLevel = r.readVarint().toInt()
                    5 -> m.isGag = r.readVarint() != 0L
                    else -> r.skip(tag and 7)
                }
            }
            return m
        }
    }
}

/** 用户资料（ProfileInfo） */
data class ProfileInfo(
    var introduction: String = "",
    var gender: Int = 3,
    var birthday: Long = 0,
    var city: String = "",
    var district: String = "",
    var address: String = ""
)

/** 用户详情（GetUserResponse.Data 核心字段） */
data class GetUserData(
    var id: String = "",
    var name: String = "",
    var avatarUrl: String = "",
    var registerTime: String = "",
    var onlineDay: Int = 0,
    var continuousOnlineDay: Int = 0,
    var isVip: Boolean = false,
    var isBlocked: Boolean = false,
    var ipGeo: String = "",
    var profile: ProfileInfo = ProfileInfo()
) {
    companion object {
        fun decode(data: ByteArray): GetUserData {
            val u = GetUserData()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> u.id = r.readString()
                    2 -> u.name = r.readString()
                    4 -> u.avatarUrl = r.readString()
                    7 -> u.registerTime = r.readString()
                    11 -> u.onlineDay = r.readVarint().toInt()
                    12 -> u.continuousOnlineDay = r.readVarint().toInt()
                    13 -> u.isVip = r.readVarint() != 0L
                    16 -> u.isBlocked = r.readVarint() != 0L
                    19 -> u.profile = decodeProfile(r.readBytes())
                    20 -> u.ipGeo = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            return u
        }

        private fun decodeProfile(data: ByteArray): ProfileInfo {
            val p = ProfileInfo()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    2 -> p.introduction = r.readString()
                    3 -> p.gender = r.readVarint().toInt()
                    4 -> p.birthday = r.readVarint()
                    5 -> p.city = r.readString()
                    6 -> p.district = r.readString()
                    7 -> p.address = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            return p
        }
    }
}

data class StatusInfo(
    var requestId: Long = 0,
    var code: Int = 0,
    var msg: String = ""
) {
    companion object {
        fun decode(data: ByteArray): StatusInfo {
            val s = StatusInfo()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> s.requestId = r.readVarint()
                    2 -> s.code = r.readVarint().toInt()
                    3 -> s.msg = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            return s
        }
    }
}

data class WsEnvelope(
    var seq: String = "",
    var cmd: String = "",
    var typeUrl: String = "",
    var payload: ByteArray = ByteArray(0)
) {
    companion object {
        fun decode(data: ByteArray): WsEnvelope {
            val e = WsEnvelope()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> {
                        // WsInfo
                        val ir = ProtoReader(r.readBytes())
                        while (ir.hasMore()) {
                            val itag = ir.readTag()
                            when (itag ushr 3) {
                                1 -> e.seq = ir.readString()
                                2 -> e.cmd = ir.readString()
                                else -> ir.skip(itag and 7)
                            }
                        }
                    }
                    2 -> {
                        // google.protobuf.Any
                        val ar = ProtoReader(r.readBytes())
                        while (ar.hasMore()) {
                            val atag = ar.readTag()
                            when (atag ushr 3) {
                                1 -> e.typeUrl = ar.readString()
                                2 -> e.payload = ar.readBytes()
                                else -> ar.skip(atag and 7)
                            }
                        }
                    }
                    else -> r.skip(tag and 7)
                }
            }
            return e
        }
    }
}

data class StreamMsg(
    var msgId: String = "",
    var recvId: String = "",
    var chatId: String = "",
    var content: String = ""
) {
    companion object {
        fun decode(data: ByteArray): StreamMsg {
            val s = StreamMsg()
            val r = ProtoReader(data)
            while (r.hasMore()) {
                val tag = r.readTag()
                when (tag ushr 3) {
                    1 -> s.msgId = r.readString()
                    2 -> s.recvId = r.readString()
                    3 -> s.chatId = r.readString()
                    4 -> s.content = r.readString()
                    else -> r.skip(tag and 7)
                }
            }
            return s
        }
    }
}

/** 请求编码集合 */
object Requests {

    fun conversationList(md5: String): ByteArray =
        ProtoWriter().string(2, md5).toByteArray()

    fun addressBookList(md5: String): ByteArray =
        ProtoWriter().string(2, md5).toByteArray()

    fun listMessage(chatId: String, chatType: Int, count: Int, msgId: String = ""): ByteArray =
        ProtoWriter()
            .varint(2, count.toLong())
            .string(3, msgId)
            .varint(4, chatType.toLong())
            .string(5, chatId)
            .toByteArray()

    fun listMessageBySeq(chatId: String, chatType: Int, msgStart: Long): ByteArray =
        ProtoWriter()
            .varint(3, msgStart)
            .varint(4, chatType.toLong())
            .string(5, chatId)
            .toByteArray()

    fun sendMessage(
        msgId: String,
        chatId: String,
        chatType: Int,
        contentType: Int,
        content: MsgContent,
        quoteMsgId: String = ""
    ): ByteArray =
        ProtoWriter()
            .string(2, msgId)
            .string(3, chatId)
            .varint(4, chatType.toLong())
            .msg(5, content.encode())
            .varint(6, contentType.toLong())
            .string(8, quoteMsgId)
            .toByteArray()

    fun recallMessage(chatId: String, chatType: Int, msgIds: List<String>): ByteArray {
        val w = ProtoWriter()
        msgIds.forEach { w.string(2, it) }
        w.string(3, chatId)
        w.varint(4, chatType.toLong())
        return w.toByteArray()
    }

    /** 获取用户详情 GetUserRequest id=2 */
    fun getUser(userId: String): ByteArray {
        val w = ProtoWriter()
        w.string(2, userId)
        return w.toByteArray()
    }

    /** 群信息 GroupInfoRequest group_id=2 */
    fun groupInfo(groupId: String): ByteArray {
        val w = ProtoWriter()
        w.string(2, groupId)
        return w.toByteArray()
    }

    /** 群成员列表 ListMemberRequest: data=2{size=1,page=2}, group_id=3, keywords=4 */
    fun listMember(groupId: String, size: Int = 50, page: Int = 1): ByteArray {
        val w = ProtoWriter()
        val inner = ProtoWriter()
        inner.varint(1, size.toLong())
        inner.varint(2, page.toLong())
        w.bytes(2, inner.toByteArray())
        w.string(3, groupId)
        return w.toByteArray()
    }

    /** 编辑群信息 EditGroupRequest */
    fun editGroup(groupId: String, name: String = "", introduction: String = ""): ByteArray {
        val w = ProtoWriter()
        w.string(2, groupId)
        if (name.isNotEmpty()) w.string(3, name)
        if (introduction.isNotEmpty()) w.string(4, introduction)
        return w.toByteArray()
    }
}

/** 响应解码集合 */
object Responses {

    fun status(data: ByteArray): StatusInfo {
        val r = ProtoReader(data)
        var st = StatusInfo()
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                1 -> st = StatusInfo.decode(r.readBytes())
                else -> r.skip(tag and 7)
            }
        }
        return st
    }

    fun conversationList(data: ByteArray): Pair<List<Conversation>, String> {
        val r = ProtoReader(data)
        val list = mutableListOf<Conversation>()
        var md5 = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> list.add(Conversation.decode(r.readBytes()))
                4 -> md5 = r.readString()
                else -> r.skip(tag and 7)
            }
        }
        return list to md5
    }

    fun listMessage(data: ByteArray): List<ChatMsg> {
        val r = ProtoReader(data)
        val list = mutableListOf<ChatMsg>()
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> list.add(ChatMsg.decodeListItem(r.readBytes()))
                else -> r.skip(tag and 7)
            }
        }
        return list
    }

    fun addressBookList(data: ByteArray): Pair<List<ContactSection>, String> {
        val r = ProtoReader(data)
        val list = mutableListOf<ContactSection>()
        var md5 = ""
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> list.add(ContactSection.decode(r.readBytes()))
                3 -> md5 = r.readString()
                else -> r.skip(tag and 7)
            }
        }
        return list to md5
    }

    /** RequestListResponse: requests=2 repeated, total=3, pending=4 */
    fun requestList(data: ByteArray): Pair<List<ApplyRequest>, Int> {
        val r = ProtoReader(data)
        val list = mutableListOf<ApplyRequest>()
        var pending = 0
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> list.add(ApplyRequest.decode(r.readBytes()))
                4 -> pending = r.readVarint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        return list to pending
    }

    /** GetUserResponse: status=1, data=2 */
    fun getUser(data: ByteArray): GetUserData {
        val r = ProtoReader(data)
        var u = GetUserData()
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> u = GetUserData.decode(r.readBytes())
                else -> r.skip(tag and 7)
            }
        }
        return u
    }

    /** GroupInfoResponse: status=1, data=2 */
    fun groupInfo(data: ByteArray): GroupInfo {
        val r = ProtoReader(data)
        var g = GroupInfo()
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> g = GroupInfo.decode(r.readBytes())
                else -> r.skip(tag and 7)
            }
        }
        return g
    }

    /** ListMemberResponse: status=1, user=2 repeated, total=3 */
    fun listMember(data: ByteArray): Pair<List<GroupMember>, Int> {
        val r = ProtoReader(data)
        val list = mutableListOf<GroupMember>()
        var total = 0
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                2 -> list.add(GroupMember.decode(r.readBytes()))
                3 -> total = r.readVarint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        return list to total
    }
}
