package com.yunhu.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yunhu.tv.App
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatMsg
import com.yunhu.tv.api.ChatType
import com.yunhu.tv.api.ContentType
import com.yunhu.tv.api.MsgContent
import com.yunhu.tv.api.Sender
import com.yunhu.tv.api.StreamMsg
import com.yunhu.tv.api.WsClient
import com.yunhu.tv.databinding.ActivityChatBinding
import com.yunhu.tv.tts.TtsSpeaker
import com.yunhu.tv.util.Session
import com.yunhu.tv.util.YunhuRouter
import com.yunhu.tv.voice.VoiceOutcome
import com.yunhu.tv.voice.VoicePrefs
import com.yunhu.tv.voice.VoiceResultSink
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity(), VoiceResultSink.Receiver {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TYPE = "chat_type"
        const val EXTRA_CHAT_NAME = "chat_name"
        private const val PAGE_SIZE = 30
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var stickerAdapter: StickerAdapter

    private var chatId = ""
    private var chatType = ChatType.USER
    private var chatName = ""

    private var loadingOlder = false
    private var hasMore = true
    private var stickersLoaded = false

    /** 输入框原始 hint，语音提示会临时改写它，恢复时要还原 */
    private var defaultHint: CharSequence = ""

    private val wsListener = object : WsClient.Listener {
        override fun onPush(msg: ChatMsg) {
            if (msg.chatId != chatId) return
            if (msg.recallTimestamp > 0) {
                messageAdapter.markRecalled(msg.msgId)
                return
            }
            if (messageAdapter.append(msg)) {
                scrollToBottom()
                lifecycleScope.launch { Api.dismissNotification(chatId) }
                maybeSpeak(msg)
            }
        }

        override fun onEdit(msg: ChatMsg) {
            if (msg.chatId == chatId) messageAdapter.applyEdit(msg)
        }

        override fun onStream(stream: StreamMsg) {
            if (stream.chatId == chatId) messageAdapter.applyStream(stream.msgId, stream.content)
        }

        override fun onStateChanged(connected: Boolean) {
            binding.tvState.text = if (connected) "已连接" else "重连中…"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        chatType = intent.getIntExtra(EXTRA_CHAT_TYPE, ChatType.USER)
        chatName = intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty()
        binding.tvTitle.text = chatName.ifEmpty { chatId }

        setupMessages()
        setupInput()

        App.wsClient.addListener(wsListener)
        VoiceResultSink.attach(this)
        loadInitial()
        lifecycleScope.launch { Api.dismissNotification(chatId) }
    }

    override fun onDestroy() {
        VoiceResultSink.detach(this)
        App.wsClient.removeListener(wsListener)
        // 离开聊天页停止朗读，不影响其它页面
        TtsSpeaker.stop()
        super.onDestroy()
    }

    /** 语音文本落到输入框，等用户自己按发送（不自动发送） */
    override fun onVoiceText(outcome: VoiceOutcome) {
        val text = outcome.text
        if (text.isBlank()) {
            // 已配置密钥但识别失败：不覆盖输入框里已有的内容，只把原因挂在输入框上方
            val reason = outcome.note.ifEmpty { "识别失败，请重试" }
            binding.etInput.hint = reason
            showVoiceHint(reason)
            toast(reason)
            return
        }
        binding.etInput.hint = defaultHint
        binding.etInput.setText(text)
        binding.etInput.setSelection(text.length)
        binding.etInput.requestFocus()
        if (outcome.placeholder) {
            // 输入框上方常驻一句「这是占位文本」，避免被当成识别结果直接发出去
            binding.etInput.hint = "未配置识别密钥，当前为占位文本"
            showVoiceHint("未配置识别密钥，当前为占位文本")
            toast("这是占位文本，不是识别结果：$text")
        } else {
            showVoiceHint(null)
            toast("语音文本已填入输入框（未发送）")
        }
    }

    /** visible=true 时在输入框上方常驻提示；传 null 隐藏 */
    private fun showVoiceHint(text: String?) {
        if (text.isNullOrEmpty()) {
            binding.tvVoiceHint.visibility = View.GONE
        } else {
            binding.tvVoiceHint.text = text
            binding.tvVoiceHint.visibility = View.VISIBLE
        }
    }

    /**
     * 朗读新消息：只往队列尾部追加，不抢焦点、不打断正在朗读的内容。
     * 范围由设置决定（全部 / 仅新消息 / 指定用户 / 机器人消息）。
     */
    private fun maybeSpeak(msg: ChatMsg) {
        val settings = VoicePrefs.ttsSettings
        if (!settings.enabled) return
        if (msg.contentType == ContentType.TIP) return
        val isMine = msg.direction == "right" ||
            (msg.sender.chatId.isNotEmpty() && msg.sender.chatId == Session.userId)
        if (!TtsSpeaker.shouldSpeak(
                settings,
                msg.sender.name,
                msg.sender.chatId,
                msg.sender.chatType,
                isMine
            )
        ) return
        val text = speechTextOf(msg)
        if (text.isNotBlank()) TtsSpeaker.speak(text)
    }

    private fun speechTextOf(msg: ChatMsg): String = when (msg.contentType) {
        ContentType.TEXT -> msg.content.text
        ContentType.MARKDOWN, ContentType.HTML ->
            msg.content.text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        ContentType.IMAGE -> "[图片]"
        ContentType.STICKER -> "[表情]"
        ContentType.VIDEO -> "[视频]"
        ContentType.FILE -> "[文件] ${msg.content.text}"
        ContentType.AUDIO -> "[语音] ${msg.content.audioTime} 秒"
        ContentType.CALL -> "[通话] ${msg.content.callStatusText}"
        else -> msg.content.text
    }

    override fun onResume() {
        super.onResume()
        // 调试面板 / 手机录音提交时聊天页可能还没打开，进来后把暂存的结果补上
        while (true) {
            val outcome = VoiceResultSink.takePending() ?: break
            onVoiceText(outcome)
        }
    }

    // ---------- 消息列表 ----------

    private fun setupMessages() {
        messageAdapter = MessageAdapter(
            onClick = { onMsgClick(it) },
            onLongClick = { onMsgLongClick(it) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messageAdapter
        // 滚动到顶部时加载更早的历史
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findFirstVisibleItemPosition() == 0) loadOlder()
            }
        })
    }

    private fun loadInitial() {
        lifecycleScope.launch {
            try {
                val list = Api.listMessages(chatId, chatType, PAGE_SIZE)
                if (list.size < PAGE_SIZE) hasMore = false
                messageAdapter.submitAll(list.reversed())
                scrollToBottom()
            } catch (e: Exception) {
                toast("消息加载失败: ${e.message}")
            }
        }
    }

    private fun loadOlder() {
        if (loadingOlder || !hasMore) return
        val firstId = messageAdapter.firstMsgId() ?: return
        loadingOlder = true
        lifecycleScope.launch {
            try {
                val list = Api.listMessages(chatId, chatType, PAGE_SIZE, firstId)
                if (list.size < PAGE_SIZE) hasMore = false
                messageAdapter.prependOlder(list.reversed())
            } catch (_: Exception) {
            }
            loadingOlder = false
        }
    }

    private fun scrollToBottom() {
        val n = messageAdapter.itemCount
        if (n > 0) binding.rvMessages.scrollToPosition(n - 1)
    }

    // ---------- 输入与发送 ----------

    private fun setupInput() {
        defaultHint = binding.etInput.hint ?: ""
        binding.btnQuoteCancel.setOnClickListener { clearQuote() }
        binding.btnSettings.setOnClickListener {
            if (chatType == ChatType.GROUP) {
                startActivity(Intent(this, GroupSettingsActivity::class.java)
                    .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, chatId))
            } else {
                openUserProfile(chatId, chatType, chatName, "")
            }
        }
        binding.btnSend.setOnClickListener { sendText() }
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            val send = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (send) { sendText(); true } else false
        }
        binding.btnSticker.setOnClickListener { toggleStickerPanel() }

        stickerAdapter = StickerAdapter(
            onEmoji = { insertEmoji(it) },
            onPick = { sendSticker(it) }
        )
        binding.rvStickers.layoutManager = GridLayoutManager(this, 6)
        binding.rvStickers.adapter = stickerAdapter

        // Tab 切换：遥控器 D-pad 或点击确定键都可切
        binding.tabEmoji.setOnClickListener { switchStickerTab(StickerAdapter.TAB_EMOJI) }
        binding.tabFav.setOnClickListener { switchStickerTab(StickerAdapter.TAB_FAV) }
    }

    /** Emoji 插入到输入框光标处 */
    private fun insertEmoji(ch: String) {
        val start = binding.etInput.selectionStart.coerceAtLeast(0)
        binding.etInput.text?.insert(start, ch)
        binding.etInput.requestFocus()
    }

    private fun toggleStickerPanel() {
        val show = binding.panelStickers.visibility != android.view.View.VISIBLE
        binding.panelStickers.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show) {
            loadStickersOnce()
            // 默认回到 Emoji Tab 并把焦点送进 RecyclerView
            switchStickerTab(StickerAdapter.TAB_EMOJI, moveFocus = true)
        }
    }

    /** 切换表情面板 Tab，同步按钮选中态和空占位 */
    private fun switchStickerTab(tab: Int, moveFocus: Boolean = false) {
        val empty = stickerAdapter.setTab(tab)
        binding.tabEmoji.isSelected = tab == StickerAdapter.TAB_EMOJI
        binding.tabFav.isSelected = tab == StickerAdapter.TAB_FAV
        binding.tabEmoji.setTextColor(
            ContextCompat.getColor(this, if (tab == StickerAdapter.TAB_EMOJI) R.color.accent else R.color.text_secondary)
        )
        binding.tabFav.setTextColor(
            ContextCompat.getColor(this, if (tab == StickerAdapter.TAB_FAV) R.color.accent else R.color.text_secondary)
        )
        refreshStickerEmpty(tab, empty)
        if (moveFocus) binding.rvStickers.requestFocus()
    }

    private fun refreshStickerEmpty(tab: Int, empty: Boolean) {
        if (!empty) {
            binding.tvStickerEmpty.visibility = android.view.View.GONE
            return
        }
        binding.tvStickerEmpty.visibility = android.view.View.VISIBLE
        binding.tvStickerEmpty.text =
            if (tab == StickerAdapter.TAB_FAV) "还没有收藏表情" else "(Emoji 面板为空)"
    }

    private fun loadStickersOnce() {
        if (stickersLoaded) return
        stickersLoaded = true
        lifecycleScope.launch {
            try {
                val list = Api.expressionList()
                stickerAdapter.submitFavorites(list)
                // 如果收藏 Tab 正处于激活态，刷新空占位
                if (stickerAdapter.currentTab() == StickerAdapter.TAB_FAV && binding.panelStickers.isShown) {
                    switchStickerTab(StickerAdapter.TAB_FAV)
                }
            } catch (_: Exception) {
                stickersLoaded = false
            }
        }
    }

    private fun sendText() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.setText("")
        submitText(text)
    }

    /**
     * 把一条文本走现有发送链路（引用、本地回显都复用）。
     * 语音输入等外部来源将来也走这里，便于保持行为一致。
     */
    private fun submitText(text: String) {
        if (text.isBlank()) return
        // 引用：带上被引用消息的 msgId，本地回显时附引用摘要
        val quote = quoteTarget
        val quoteId = quote?.msgId ?: ""
        if (quote != null) {
            clearQuote()
        }
        lifecycleScope.launch {
            try {
                val content = MsgContent(text = text)
                if (quote != null) content.quoteMsgText = quotePreview(quote)
                val msgId = Api.sendMessage(chatId, chatType, ContentType.TEXT, content, quoteId)
                appendLocal(msgId, ContentType.TEXT, content, quoteId)
            } catch (e: Exception) {
                toast("发送失败: ${e.message}")
            }
        }
    }

    private fun sendSticker(exp: Api.Expression) {
        lifecycleScope.launch {
            try {
                val content = MsgContent(image = exp.url, expressionId = exp.id.toString())
                val msgId = Api.sendMessage(chatId, chatType, ContentType.STICKER, content)
                appendLocal(msgId, ContentType.STICKER, content)
            } catch (e: Exception) {
                toast("发送失败: ${e.message}")
            }
        }
    }

    /** 发送成功后立即本地插一条（WS 推送到达时按 msgId 去重） */
    private fun appendLocal(msgId: String, contentType: Int, content: MsgContent, quoteMsgId: String = "") {
        val m = ChatMsg(
            msgId = msgId,
            sender = Sender(
                chatId = Session.userId,
                chatType = ChatType.USER,
                name = Session.nickname,
                avatarUrl = Session.avatarUrl
            ),
            direction = "right",
            contentType = contentType,
            content = content,
            sendTimestampMs = System.currentTimeMillis(),
            quoteMsgId = quoteMsgId,
            chatId = chatId,
            chatType = chatType
        )
        messageAdapter.append(m)
        scrollToBottom()
    }

    // ---------- 引用 ----------

    private var quoteTarget: ChatMsg? = null

    private fun setQuote(m: ChatMsg) {
        quoteTarget = m
        binding.tvQuoteText.text = "引用 ${m.sender.name.ifEmpty { "对方" }}: ${quotePreview(m)}"
        binding.llQuoteBar.visibility = android.view.View.VISIBLE
    }

    private fun clearQuote() {
        quoteTarget = null
        binding.llQuoteBar.visibility = android.view.View.GONE
    }

    private fun quotePreview(m: ChatMsg): String = when (m.contentType) {
        ContentType.TEXT, ContentType.HTML -> m.content.text
        ContentType.MARKDOWN -> com.yunhu.tv.util.MarkdownLite.toPlain(m.content.text)
        ContentType.IMAGE -> "[图片]"
        ContentType.STICKER -> "[表情]"
        ContentType.FILE -> "[文件] ${m.content.fileName}"
        ContentType.VIDEO -> "[视频]"
        ContentType.AUDIO -> "[语音]"
        else -> "[消息]"
    }.take(50)

    // ---------- 消息点击 / 长按 ----------

    private fun onMsgClick(m: ChatMsg) {
        val img = MessageAdapter.imageUrlOf(m)
        if (img.isNotEmpty()) { openImage(img); return }
        val media = MessageAdapter.playableUrlOf(m)
        if (media.isNotEmpty()) {
            val isAudio = m.contentType == ContentType.AUDIO ||
                (m.contentType == ContentType.FILE && MessageAdapter.isAudioFile(m.content.fileName))
            val title = when (m.contentType) {
                ContentType.AUDIO -> "语音消息"
                ContentType.FILE -> m.content.fileName
                else -> chatName
            }
            openVideo(media, title, isAudio)
        }
    }

    private fun onMsgLongClick(m: ChatMsg) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val imgUrl = MessageAdapter.imageUrlOf(m)
        if (imgUrl.isNotEmpty()) actions.add("查看图片" to { openImage(imgUrl) })
        if (m.content.text.isNotEmpty()) actions.add("复制文字" to { copyText(m.content.text) })
        // 查看对方信息（他人消息）：把 sender 的 chatType 一起带过去，
        // 否则机器人会被当成普通用户去查 /v1/user/get-user，详情页全空
        if (!MessageAdapter.isOwn(m) && m.sender.chatId.isNotEmpty()) {
            actions.add("查看对方信息" to {
                openUserProfile(m.sender.chatId, m.sender.chatType, m.sender.name, m.sender.avatarUrl)
            })
        }
        // 打开 yunhu:// 应用内链接
        val yunhuLink = YunhuRouter.extractYunhuLink(m.content.text)
        if (yunhuLink != null) {
            actions.add("打开链接" to {
                if (!YunhuRouter.open(this, yunhuLink)) toast("无法识别的链接")
            })
        }
        // 引用（未撤回的消息）
        if (!m.isRecalled) actions.add("引用" to { setQuote(m) })
        // 转发到其他会话
        if (!m.isRecalled) actions.add("转发" to { showForwardDialog(m) })
        // +1：原样复读该消息到当前会话
        if (!m.isRecalled) actions.add("+1" to { resendMsg(m) })
        // 屏蔽用户（仅他人消息）
        if (!MessageAdapter.isOwn(m) && m.sender.chatId.isNotEmpty()) {
            actions.add("屏蔽用户" to { confirmBlock(m) })
        }
        // 群聊中管理员可撤回他人消息；客户端无法获知权限时提供入口，由服务端鉴权，失败会提示
        val canRecall = !m.isRecalled && m.msgId.isNotEmpty() &&
            (MessageAdapter.isOwn(m) || chatType == ChatType.GROUP)
        if (canRecall) {
            actions.add("撤回" to { recallMsg(m) })
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second.invoke() }
            .show()
    }

    /** +1：把这条消息原样发送到当前会话 */
    private fun resendMsg(m: ChatMsg) {
        lifecycleScope.launch {
            try {
                val msgId = Api.sendMessage(chatId, chatType, m.contentType, m.content)
                appendLocal(msgId, m.contentType, m.content)
            } catch (e: Exception) {
                toast("发送失败: ${e.message}")
            }
        }
    }

    /** 转发：选择目标会话后原样发送 */
    private fun showForwardDialog(m: ChatMsg) {
        lifecycleScope.launch {
            try {
                val (list, _) = Api.conversationList("")
                if (list.isEmpty()) { toast("暂无可转发的会话"); return@launch }
                val names = list.map { it.displayName.ifEmpty { it.chatId } }.toTypedArray()
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("转发到")
                    .setItems(names) { _, which ->
                        val target = list[which]
                        lifecycleScope.launch {
                            try {
                                Api.sendMessage(target.chatId, target.chatType, m.contentType, m.content)
                                toast("已转发到 ${names[which]}")
                            } catch (e: Exception) {
                                toast("转发失败: ${e.message}")
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                toast("获取会话失败: ${e.message}")
            }
        }
    }

    /** 屏蔽该用户（社区黑名单，屏蔽其文章/评论） */
    private fun confirmBlock(m: ChatMsg) {
        val name = m.sender.name.ifEmpty { m.sender.chatId }
        AlertDialog.Builder(this)
            .setTitle("屏蔽用户")
            .setMessage("确定屏蔽 $name 吗？可在 社区-我的-屏蔽用户 中取消")
            .setPositiveButton("屏蔽") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.setBlackList(m.sender.chatId, 1)
                        toast("已屏蔽 $name")
                    } catch (e: Exception) {
                        toast("屏蔽失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openImage(url: String) {
        startActivity(Intent(this, ImageViewActivity::class.java).putExtra(ImageViewActivity.EXTRA_URL, url))
    }

    private fun openVideo(url: String, title: String = chatName, isAudio: Boolean = false) {
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_URL, url)
                .putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
                .putExtra(VideoPlayerActivity.EXTRA_IS_AUDIO, isAudio)
        )
    }

    private fun openUserProfile(userId: String, type: Int, name: String, avatar: String) {
        startActivity(
            Intent(this, UserProfileActivity::class.java)
                .putExtra(UserProfileActivity.EXTRA_USER_ID, userId)
                .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, type)
                .putExtra(UserProfileActivity.EXTRA_NAME, name)
                .putExtra(UserProfileActivity.EXTRA_AVATAR, avatar)
        )
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("msg", text))
        toast("已复制")
    }

    private fun recallMsg(m: ChatMsg) {
        lifecycleScope.launch {
            try {
                Api.recallMessage(chatId, chatType, m.msgId)
                messageAdapter.markRecalled(m.msgId)
            } catch (e: Exception) {
                toast("撤回失败: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
