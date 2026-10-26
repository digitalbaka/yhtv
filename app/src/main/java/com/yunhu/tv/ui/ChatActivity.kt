package com.yunhu.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yunhu.tv.App
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatMsg
import com.yunhu.tv.api.ChatType
import com.yunhu.tv.api.ContentType
import com.yunhu.tv.api.MsgContent
import com.yunhu.tv.api.Sender
import com.yunhu.tv.api.StreamMsg
import com.yunhu.tv.api.WsClient
import com.yunhu.tv.databinding.ActivityChatBinding
import com.yunhu.tv.util.Session
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

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

        override fun onStream(s: StreamMsg) {
            if (s.chatId == chatId) messageAdapter.applyStream(s.msgId, s.content)
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
        loadInitial()
        lifecycleScope.launch { Api.dismissNotification(chatId) }
    }

    override fun onDestroy() {
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
        binding.btnSend.setOnClickListener { sendText() }
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            val send = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (send) { sendText(); true } else false
        }
        binding.btnSticker.setOnClickListener { toggleStickerPanel() }

        stickerAdapter = StickerAdapter { sendSticker(it) }
        binding.rvStickers.layoutManager = GridLayoutManager(this, 6)
        binding.rvStickers.adapter = stickerAdapter
    }

    private fun toggleStickerPanel() {
        val show = binding.rvStickers.visibility != android.view.View.VISIBLE
        binding.rvStickers.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        if (show) loadStickersOnce()
    }

    private fun loadStickersOnce() {
        if (stickersLoaded) return
        stickersLoaded = true
        lifecycleScope.launch {
            try {
                stickerAdapter.submit(Api.expressionList())
            } catch (_: Exception) {
                stickersLoaded = false
            }
        }
    }

    private fun sendText() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.setText("")
        lifecycleScope.launch {
            try {
                val content = MsgContent(text = text)
                val msgId = Api.sendMessage(chatId, chatType, ContentType.TEXT, content)
                appendLocal(msgId, ContentType.TEXT, content)
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
    private fun appendLocal(msgId: String, contentType: Int, content: MsgContent) {
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
            chatId = chatId,
            chatType = chatType
        )
        messageAdapter.append(m)
        scrollToBottom()
    }

    // ---------- 消息点击 / 长按 ----------

    private fun onMsgClick(m: ChatMsg) {
        val url = MessageAdapter.imageUrlOf(m)
        if (url.isNotEmpty()) openImage(url)
    }

    private fun onMsgLongClick(m: ChatMsg) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val imgUrl = MessageAdapter.imageUrlOf(m)
        if (imgUrl.isNotEmpty()) actions.add("查看图片" to { openImage(imgUrl) })
        if (m.content.text.isNotEmpty()) actions.add("复制文字" to { copyText(m.content.text) })
        if (MessageAdapter.isOwn(m) && !m.isRecalled && m.msgId.isNotEmpty()) {
            actions.add("撤回" to { recallMsg(m) })
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second.invoke() }
            .show()
    }

    private fun openImage(url: String) {
        startActivity(Intent(this, ImageViewActivity::class.java).putExtra(ImageViewActivity.EXTRA_URL, url))
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
