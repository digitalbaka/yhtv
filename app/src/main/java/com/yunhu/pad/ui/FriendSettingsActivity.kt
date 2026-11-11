package com.yunhu.pad.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.yunhu.pad.R
import com.yunhu.pad.api.Api
import com.yunhu.pad.api.ChatType
import com.yunhu.pad.databinding.ActivityFriendSettingsBinding
import kotlinx.coroutines.launch

/**
 * 好友设置页。
 * 消息免打扰初值取 /v1/conversation/list 中该会话的 doNotDisturb；
 * 黑名单初值取 /v1/community/black-list 分页结果；拿不到初值时置灰并提示，绝不按默认值展示。
 */
class FriendSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TYPE = "chat_type"
        const val EXTRA_NAME = "name"

        private const val PAGE_SIZE = 50
        private const val MAX_PAGE = 20
        private const val NOTIFY_DESC = "开启后仅提醒@我的消息"
        private const val BLOCK_DESC = "仅屏蔽该用户在社区的发言，不影响聊天"
    }

    private lateinit var binding: ActivityFriendSettingsBinding
    private var chatId = ""
    private var chatType = ChatType.USER

    /** 仅由 getUser 返回的 id 赋值；为空时黑名单入口整项不展示，不回退成 chatId */
    private var userId = ""
    private var chatName = ""

    /** 程序回填开关状态时会触发监听，用标记避免误发写请求 */
    private var applyingNotify = false
    private var applyingBlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        chatType = intent.getIntExtra(EXTRA_CHAT_TYPE, ChatType.USER)
        chatName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (chatId.isEmpty()) { finish(); return }

        binding.tvTitle.text = chatName
        binding.tvName.text = chatName.ifEmpty { chatId }
        binding.tvUserId.text = chatId

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopyId.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("id", binding.tvUserId.text))
            toast("ID 已复制")
        }
        binding.btnProfile.setOnClickListener {
            startActivity(
                Intent(this, UserProfileActivity::class.java)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, userId.ifEmpty { chatId })
                    .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, chatType)
                    .putExtra(UserProfileActivity.EXTRA_NAME, binding.tvName.text.toString())
            )
        }
        binding.btnSearch.setOnClickListener {
            startActivity(
                Intent(this, ChatSearchActivity::class.java)
                    .putExtra(ChatSearchActivity.EXTRA_CHAT_ID, chatId)
                    .putExtra(ChatSearchActivity.EXTRA_CHAT_TYPE, chatType)
            )
        }

        // 拿到服务端初值前不允许操作，避免把「未知」当成「关闭」写回服务端
        binding.swNotify.isEnabled = false
        binding.swBlock.isEnabled = false

        binding.swNotify.setOnCheckedChangeListener { _, checked ->
            if (!applyingNotify) setNotify(checked)
        }
        binding.swBlock.setOnCheckedChangeListener { _, checked ->
            if (!applyingBlock) setBlock(checked)
        }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            loadProfile()
            loadNotifyState()
            loadBlockEntry()
        }
    }

    private suspend fun loadProfile() {
        if (chatType != ChatType.USER) return
        try {
            val u = Api.getUser(chatId)
            if (u.id.isNotEmpty()) userId = u.id
            val display = u.name.ifEmpty { chatName.ifEmpty { chatId } }
            binding.tvTitle.text = display
            binding.tvName.text = display
            binding.tvUserId.text = u.id.ifEmpty { chatId }
            binding.ivAvatar.load(Api.fullImageUrl(u.avatarUrl)) {
                placeholder(R.drawable.bg_card)
                error(R.drawable.bg_card)
            }
        } catch (e: Exception) {
            // 资料加载失败只影响展示，不回退成任何默认设置值
        }
    }

    // ---------- 消息免打扰 ----------

    /**
     * 会话列表接口在 Api.conversationList 中只携带 md5、没有 page/size 参数，
     * 因此这里取一次全量列表按 chatId 匹配；找不到或请求失败都按「拿不到初值」处理。
     */
    private suspend fun loadNotifyState() {
        try {
            val conversation = Api.conversationList("").first.firstOrNull { it.chatId == chatId }
            if (conversation == null) {
                markNotifyUnavailable()
                return
            }
            applyingNotify = true
            binding.swNotify.isChecked = conversation.doNotDisturb
            applyingNotify = false
            binding.swNotify.isEnabled = true
            binding.tvNotifyDesc.text = NOTIFY_DESC
        } catch (e: Exception) {
            markNotifyUnavailable()
        }
    }

    private fun markNotifyUnavailable() {
        binding.swNotify.isEnabled = false
        binding.tvNotifyDesc.text = "未能获取当前免打扰设置，暂不可修改"
    }

    private fun setNotify(enabled: Boolean) {
        binding.swNotify.isEnabled = false
        lifecycleScope.launch {
            try {
                Api.setConversationNotify(chatId, if (enabled) 1 else 0)
                binding.tvNotifyDesc.text = NOTIFY_DESC
                toast(if (enabled) "已开启消息免打扰" else "已关闭消息免打扰")
            } catch (e: Exception) {
                applyingNotify = true
                binding.swNotify.isChecked = !enabled
                applyingNotify = false
                toast("设置失败：${e.message}")
            } finally {
                binding.swNotify.isEnabled = true
            }
        }
    }

    // ---------- 社区黑名单 ----------

    private enum class FriendState { FRIEND, NOT_FRIEND, UNKNOWN }

    /**
     * 机器人、群聊、确认不在通讯录的陌生人：整项不展示，也不发黑名单请求；
     * 好友关系校验失败（网络/超时等）或 userId 取不到：保留入口但置灰提示，不臆断为未拉黑。
     * 底部「暂不支持删除好友」只在好友关系成立时展示。
     */
    private suspend fun loadBlockEntry() {
        val state = when {
            chatType != ChatType.USER -> FriendState.NOT_FRIEND
            userId.isEmpty() -> FriendState.UNKNOWN
            else -> checkFriend()
        }
        binding.tvDeleteHint.visibility = if (state == FriendState.FRIEND) View.VISIBLE else View.GONE
        when (state) {
            FriendState.FRIEND -> {
                binding.rowBlock.visibility = View.VISIBLE
                loadBlockState()
            }
            FriendState.NOT_FRIEND -> binding.rowBlock.visibility = View.GONE
            FriendState.UNKNOWN -> {
                binding.rowBlock.visibility = View.VISIBLE
                markBlockUnavailable("无法校验好友关系，暂不可用")
            }
        }
    }

    private suspend fun checkFriend(): FriendState = try {
        val isFriend = Api.addressBookList("").first.any { section ->
            section.items.any { it.chatId == userId }
        }
        if (isFriend) FriendState.FRIEND else FriendState.NOT_FRIEND
    } catch (e: Exception) {
        FriendState.UNKNOWN
    }

    private suspend fun loadBlockState() {
        try {
            var page = 1
            var scanned = 0
            var blocked: Boolean? = null
            while (page <= MAX_PAGE) {
                val (list, total) = Api.blackListPage(PAGE_SIZE, page)
                if (list.isEmpty()) { blocked = false; break }
                if (list.any { it.userId == userId }) { blocked = true; break }
                scanned += list.size
                if (total >= 0 && scanned >= total) { blocked = false; break }
                page++
            }
            if (blocked == null) {
                markBlockUnavailable()
                return
            }
            applyingBlock = true
            binding.swBlock.isChecked = blocked
            applyingBlock = false
            binding.swBlock.isEnabled = true
            binding.tvBlockDesc.text = BLOCK_DESC
        } catch (e: Exception) {
            markBlockUnavailable()
        }
    }

    private fun markBlockUnavailable(desc: String = "未能获取当前黑名单状态，暂不可修改") {
        binding.swBlock.isEnabled = false
        binding.tvBlockDesc.text = desc
    }

    private fun setBlock(checked: Boolean) {
        binding.swBlock.isEnabled = false
        lifecycleScope.launch {
            try {
                Api.setBlackList(userId, if (checked) 1 else 0)
                binding.tvBlockDesc.text = BLOCK_DESC
                toast(if (checked) "已加入黑名单（仅社区发言生效）" else "已移出黑名单")
            } catch (e: Exception) {
                applyingBlock = true
                binding.swBlock.isChecked = !checked
                applyingBlock = false
                toast("设置失败：${e.message}")
            } finally {
                binding.swBlock.isEnabled = true
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
