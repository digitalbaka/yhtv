package com.yunhu.pad.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yunhu.pad.App
import com.yunhu.pad.R
import com.yunhu.pad.api.Api
import com.yunhu.pad.api.ChatMsg
import com.yunhu.pad.api.ChatType
import com.yunhu.pad.api.ContentType
import com.yunhu.pad.api.MsgContent
import com.yunhu.pad.api.Qiniu
import com.yunhu.pad.api.Sender
import com.yunhu.pad.api.StreamMsg
import com.yunhu.pad.api.WsClient
import com.yunhu.pad.databinding.ActivityChatBinding
import com.yunhu.pad.util.MediaPrep
import com.yunhu.pad.util.Prefs
import com.yunhu.pad.util.Session
import com.yunhu.pad.util.YunhuRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TYPE = "chat_type"
        const val EXTRA_CHAT_NAME = "chat_name"
        // 从聊天记录搜索跳转时携带的目标消息
        const val EXTRA_TARGET_MSG_ID = "target_msg_id"
        const val EXTRA_TARGET_TEXT = "target_text"
        const val EXTRA_TARGET_NAME = "target_name"
        const val EXTRA_TARGET_AVATAR = "target_avatar"
        const val EXTRA_TARGET_TIME = "target_time"
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

    // ---------- 媒体发送 ----------

    private var uploading = false
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartMs = 0L
    private var recordTimer: CountDownTimer? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendImage(it) }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendVideo(it) }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("未授予麦克风权限，无法发送语音")
    }

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
        applyChatBackground()
        App.wsClient.addListener(wsListener)
        loadInitial()
        lifecycleScope.launch { Api.dismissNotification(chatId) }
    }

    override fun onDestroy() {
        stopRecorder()
        App.wsClient.removeListener(wsListener)
        super.onDestroy()
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
        val targetId = intent.getStringExtra(EXTRA_TARGET_MSG_ID).orEmpty()
        if (targetId.isNotEmpty()) {
            loadAroundTarget(targetId)
            return
        }
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

    /**
     * 从聊天记录搜索跳转：以目标消息为锚点拉取它之前的历史，
     * 再把目标消息本身（来自搜索结果里服务端返回的数据）补在末尾并滚动到该位置。
     */
    private fun loadAroundTarget(targetId: String) {
        lifecycleScope.launch {
            try {
                val list = Api.listMessages(chatId, chatType, PAGE_SIZE, targetId)
                if (list.size < PAGE_SIZE) hasMore = false
                messageAdapter.submitAll(list.reversed())
                val target = ChatMsg(
                    msgId = targetId,
                    sender = Sender(
                        chatId = "",
                        chatType = ChatType.USER,
                        name = intent.getStringExtra(EXTRA_TARGET_NAME).orEmpty(),
                        avatarUrl = intent.getStringExtra(EXTRA_TARGET_AVATAR).orEmpty()
                    ),
                    contentType = ContentType.TEXT,
                    content = MsgContent(text = intent.getStringExtra(EXTRA_TARGET_TEXT).orEmpty()),
                    sendTimestampMs = intent.getLongExtra(EXTRA_TARGET_TIME, 0L),
                    chatId = chatId,
                    chatType = chatType
                )
                messageAdapter.append(target)
                scrollToBottom()
                toast("已跳转到搜索结果")
            } catch (e: Exception) {
                toast("定位消息失败: ${e.message}")
                // 失败时退回普通最新消息，避免空白页
                intent.removeExtra(EXTRA_TARGET_MSG_ID)
                loadInitial()
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
        binding.btnQuoteCancel.setOnClickListener { clearQuote() }
        binding.btnChatSearch.setOnClickListener {
            startActivity(
                Intent(this, ChatSearchActivity::class.java)
                    .putExtra(ChatSearchActivity.EXTRA_CHAT_ID, chatId)
                    .putExtra(ChatSearchActivity.EXTRA_CHAT_TYPE, chatType)
            )
        }
        binding.btnSettings.setOnClickListener {
            if (chatType == ChatType.GROUP) {
                startActivity(Intent(this, GroupSettingsActivity::class.java)
                    .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, chatId))
            } else {
                openUserProfile(chatId, chatType, chatName, "")
            }
        }
        binding.btnSend.setOnClickListener { sendText() }
        applyEnterSendMode()
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (!enter) return@setOnEditorActionListener false
            if (Prefs.enterToSend) {
                sendText()
                true
            } else {
                // 关闭「回车发送」：回车作为换行，交给输入框自己处理
                false
            }
        }
        binding.btnSticker.setOnClickListener { toggleStickerPanel() }
        binding.btnAttachImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnAttachVideo.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnRecord.setOnClickListener {
            if (recorder != null) stopRecordingAndSend() else ensureRecordPermission()
        }
        binding.btnRecordSend.setOnClickListener { stopRecordingAndSend() }
        binding.btnRecordCancel.setOnClickListener { cancelRecording() }

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

    /** 按「回车发送」开关调整输入框：开启时单行+回车发送，关闭时多行+回车换行 */
    private fun applyEnterSendMode() {
        val enterSend = Prefs.enterToSend
        binding.etInput.isSingleLine = enterSend
        binding.etInput.maxLines = if (enterSend) 1 else 3
        binding.etInput.inputType = if (enterSend) {
            android.text.InputType.TYPE_CLASS_TEXT
        } else {
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        binding.etInput.imeOptions = if (enterSend) {
            EditorInfo.IME_ACTION_SEND
        } else {
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        binding.etInput.hint = if (enterSend) "输入消息" else "输入消息（回车换行）"
    }

    /** 自定义聊天背景：铺满 + 可读性蒙层；未设置则隐藏，露出主题底色 */
    private fun applyChatBackground() {
        val path = Prefs.chatBackgroundPath
        val file = if (path.isEmpty()) null else File(path)
        if (file != null && file.exists()) {
            binding.ivChatBg.setImageBitmap(decodeBg(file))
            binding.ivChatBg.visibility = View.VISIBLE
            binding.vChatScrim.visibility = View.VISIBLE
        } else {
            binding.ivChatBg.setImageDrawable(null)
            binding.ivChatBg.visibility = View.GONE
            binding.vChatScrim.visibility = View.GONE
        }
    }

    /** 采样解码，避免大图直接进内存（1GB 运存设备优先） */
    private fun decodeBg(file: File): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
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

    // ---------- 图片 / 视频 / 语音 ----------

    /** 串行上传：同一时刻只允许一个文件在传，避免低内存设备并发上传 OOM */
    private fun queueUpload(hint: String, block: suspend () -> Unit) {
        if (uploading) {
            toast("还有文件正在上传，请稍候")
            return
        }
        uploading = true
        toast(hint)
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                toast("发送失败: ${e.message}")
            } finally {
                uploading = false
            }
        }
    }

    private fun sendImage(uri: Uri) {
        queueUpload("图片上传中…") {
            val dir = File(cacheDir, "outgoing")
            val src = withContext(Dispatchers.IO) {
                MediaPrep.copyToDir(this@ChatActivity, uri, dir, "image.jpg")
            }
            try {
                val img = withContext(Dispatchers.IO) { MediaPrep.compressImage(src, dir) }
                try {
                    val key = Qiniu.upload(img.file, Api.qiniuToken(""))
                    val content = MsgContent(imageUrl = key, width = img.width, height = img.height)
                    val msgId = Api.sendMessage(chatId, chatType, ContentType.IMAGE, content)
                    appendLocal(msgId, ContentType.IMAGE, content)
                } finally {
                    img.file.delete()
                }
            } finally {
                src.delete()
            }
        }
    }

    private fun sendVideo(uri: Uri) {
        queueUpload("视频上传中…") {
            val dir = File(cacheDir, "outgoing")
            val src = withContext(Dispatchers.IO) {
                MediaPrep.copyToDir(this@ChatActivity, uri, dir, "video.mp4")
            }
            try {
                // 服务端普通用户视频上限 40MB，超限直接拦下，避免传完才失败
                if (src.length() > 40L * 1024 * 1024) throw IOException("视频超过 40MB，无法发送")
                val meta = withContext(Dispatchers.IO) { MediaPrep.videoMeta(src) }
                val key = Qiniu.upload(src, Api.qiniuToken("-video"))
                val content = MsgContent(
                    videoUrl = key,
                    videoTime = meta.durationSec,
                    fileName = src.name,
                    width = meta.width,
                    height = meta.height
                )
                val msgId = Api.sendMessage(chatId, chatType, ContentType.VIDEO, content)
                appendLocal(msgId, ContentType.VIDEO, content)
            } finally {
                src.delete()
            }
        }
    }

    private fun ensureRecordPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (recorder != null) return
        val dir = File(cacheDir, "outgoing").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        try {
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = file
            recordStartMs = SystemClock.elapsedRealtime()
            binding.llRecording.visibility = View.VISIBLE
            binding.btnRecord.text = "停止"
            binding.tvRecording.text = "录音中… 00:00"
            // 60 秒上限，到时自动发送
            recordTimer = object : CountDownTimer(60_000, 1000) {
                override fun onTick(msUntilFinished: Long) {
                    val sec = 60 - (msUntilFinished / 1000).toInt()
                    binding.tvRecording.text = "录音中… %02d:%02d".format(sec / 60, sec % 60)
                }

                override fun onFinish() {
                    stopRecordingAndSend()
                }
            }.start()
        } catch (e: Exception) {
            toast("无法开始录音: ${e.message}")
            stopRecorder()
            file.delete()
        }
    }

    private fun stopRecordingAndSend() {
        val file = recordFile
        val seconds = ((SystemClock.elapsedRealtime() - recordStartMs) / 1000).toInt()
        stopRecorder()
        if (file == null || !file.exists() || file.length() < 1024) {
            file?.delete()
            toast("录音时间太短")
            return
        }
        queueUpload("语音上传中…") {
            try {
                val key = Qiniu.upload(file, Api.qiniuToken("-audio"))
                val content = MsgContent(audioUrl = key, audioTime = seconds.coerceIn(1, 60))
                val msgId = Api.sendMessage(chatId, chatType, ContentType.AUDIO, content)
                appendLocal(msgId, ContentType.AUDIO, content)
            } finally {
                file.delete()
            }
        }
    }

    private fun cancelRecording() {
        val file = recordFile
        stopRecorder()
        file?.delete()
    }

    private fun stopRecorder() {
        recordTimer?.cancel()
        recordTimer = null
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        recordFile = null
        if (this::binding.isInitialized) {
            binding.llRecording.visibility = View.GONE
            binding.btnRecord.text = "语音"
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
        ContentType.MARKDOWN -> com.yunhu.pad.util.MarkdownLite.toPlain(m.content.text)
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
        // 编辑：仅自己发送、未撤回的纯文本消息（其他类型编辑会改变消息语义，服务端也会拒绝）
        if (!m.isRecalled && m.msgId.isNotEmpty() && MessageAdapter.isOwn(m) && m.contentType == ContentType.TEXT) {
            actions.add("编辑" to { showEditMessage(m) })
        }
        if (!m.isRecalled && m.msgId.isNotEmpty()) {
            actions.add("编辑记录" to { showEditRecords(m) })
        }
        // 删除：仅自己发送的消息
        if (MessageAdapter.isOwn(m) && m.msgId.isNotEmpty()) {
            actions.add("删除" to { confirmDeleteMessage(m) })
        }
        actions.add("举报会话" to { showReportChat(m) })
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

    // ---------- 编辑 / 删除 / 举报 ----------

    private fun showEditMessage(m: ChatMsg) {
        val input = android.widget.EditText(this).apply {
            setText(m.content.text)
            setSelection(text.length)
            minLines = 2
            maxLines = 6
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("编辑消息")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("内容不能为空"); return@setPositiveButton }
                if (text == m.content.text) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        Api.editMessage(m.msgId, chatId, chatType, ContentType.TEXT, MsgContent(text = text))
                        val updated = m.copy(
                            content = MsgContent(text = text),
                            editTimestamp = System.currentTimeMillis()
                        )
                        messageAdapter.applyEdit(updated)
                        toast("已保存")
                    } catch (e: Exception) {
                        toast("编辑失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditRecords(m: ChatMsg) {
        lifecycleScope.launch {
            try {
                val (records, _) = Api.msgEditRecords(m.msgId)
                if (records.isEmpty()) { toast("暂无编辑记录"); return@launch }
                val text = records.joinToString("\n\n") { rec ->
                    val body = extractOldText(rec.contentOld)
                    "${formatRecordTime(rec.msgTime)}\n$body"
                }
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("编辑记录（共 ${records.size} 条）")
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                toast("获取编辑记录失败: ${e.message}")
            }
        }
    }

    private fun extractOldText(raw: String): String {
        if (raw.isEmpty()) return "(空)"
        return runCatching {
            val o = org.json.JSONObject(raw)
            o.optString("text").ifEmpty {
                o.keys().asSequence().joinToString(" ") { k -> "$k=${o.optString(k)}" }
            }
        }.getOrElse { raw }
    }

    private fun formatRecordTime(ms: Long): String =
        if (ms <= 0) "" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))

    private fun confirmDeleteMessage(m: ChatMsg) {
        AlertDialog.Builder(this)
            .setTitle("删除消息")
            .setMessage("删除后本会话内将不再显示这条消息，确定删除？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.deleteMessage(chatId, m.msgId, chatType)
                        messageAdapter.remove(m.msgId)
                        toast("已删除")
                    } catch (e: Exception) {
                        toast("删除失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showReportChat(m: ChatMsg) {
        val reasons = arrayOf(
            "色情低俗", "时政不时消息", "垃圾广告", "青少年不易", "辱骂攻击",
            "侵犯权益", "违法犯罪", "开盒网暴", "其他"
        )
        AlertDialog.Builder(this)
            .setTitle("举报会话")
            .setItems(reasons) { _, which ->
                lifecycleScope.launch {
                    try {
                        val content = m.content.text.ifEmpty { "[非文本消息]" }
                        Api.reportChat(chatId, chatType, chatName, content, reasons[which])
                        toast("举报已提交")
                    } catch (e: Exception) {
                        toast("举报失败: ${e.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
