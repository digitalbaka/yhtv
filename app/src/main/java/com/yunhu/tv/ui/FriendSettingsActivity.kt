package com.yunhu.tv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatType
import com.yunhu.tv.databinding.ActivityFriendSettingsBinding
import kotlinx.coroutines.launch

/**
 * 好友会话设置页（第五步）。
 *
 * - 消息免打扰：初值取 /v1/conversation/list 的 Conversation.doNotDisturb（proto 字段 11），
 *   写入 /v1/friend/no-notify {chatId, noNotify}。
 * - 加入黑名单：初值取 /v1/community/black-list 分页比对 userId，
 *   写入 /v1/community/set-black-list {isAdd, authorId}。仅作用于社区发言。
 * - 删除好友：工程内无接口，底部只放只读灰条。
 *
 * 两项的初值只要拿不到（翻完没有 / 请求失败）就置灰并提示，不当作默认关闭。
 */
class FriendSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TYPE = "chat_type"
        const val EXTRA_CHAT_NAME = "chat_name"

        /** 黑名单分页大小；最多翻多少页，防止异常情况下无限翻 */
        private const val BLOCK_PAGE_SIZE = 50
        private const val BLOCK_MAX_PAGES = 20
    }

    private lateinit var binding: ActivityFriendSettingsBinding
    private var chatId = ""
    private var chatType = ChatType.USER
    private var chatName = ""
    private var displayName = ""
    private var avatarUrl = ""

    /** 黑名单只按真实 userId 匹配；取不到就不显示这一项 */
    private var userId = ""

    /** 初值是否已经拿到；没拿到时开关不可操作 */
    private var quietReady = false
    private var blockReady = false

    private val hints = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        chatType = intent.getIntExtra(EXTRA_CHAT_TYPE, ChatType.USER)
        chatName = intent.getStringExtra(EXTRA_CHAT_NAME).orEmpty()
        if (chatId.isEmpty()) {
            finish()
            return
        }
        displayName = chatName
        binding.tvTitle.text = displayName.ifEmpty { chatId }
        binding.tvName.text = displayName.ifEmpty { chatId }
        binding.tvId.text = "ID: $chatId"
        // 底部灰条只在好友关系成立后显示
        binding.tvNoDelete.visibility = View.GONE

        listOf(binding.btnBack, binding.btnCopyId, binding.btnProfile, binding.rowQuiet, binding.rowBlock)
            .forEach { FocusEffects.apply(it) }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCopyId.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("user_id", chatId))
            Toast.makeText(this, "已复制 ID", Toast.LENGTH_SHORT).show()
        }
        binding.btnProfile.setOnClickListener {
            startActivity(
                Intent(this, UserProfileActivity::class.java)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, chatId)
                    .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, chatType)
                    .putExtra(UserProfileActivity.EXTRA_NAME, displayName)
                    .putExtra(UserProfileActivity.EXTRA_AVATAR, avatarUrl)
            )
        }

        binding.rowQuiet.setOnClickListener {
            if (!quietReady) return@setOnClickListener
            val target = !binding.swQuiet.isChecked
            renderQuiet(target)
            lifecycleScope.launch {
                try {
                    Api.setConversationNotify(chatId, if (target) 1 else 0)
                } catch (e: Exception) {
                    renderQuiet(!target)
                    toast("设置失败：${e.message}")
                }
            }
        }
        binding.rowBlock.setOnClickListener {
            if (!blockReady) return@setOnClickListener
            val target = !binding.swBlock.isChecked
            renderBlock(target)
            lifecycleScope.launch {
                try {
                    Api.setBlackList(chatId, if (target) 1 else 0)
                } catch (e: Exception) {
                    renderBlock(!target)
                    toast("设置失败：${e.message}")
                }
            }
        }

        binding.rowQuiet.requestFocus()
        loadQuiet()
        loadProfileAndBlock()
    }

    /**
     * 资料卡 + 黑名单初值。
     *
     * 黑名单入口的显示条件：
     * 1. 会话类型不是普通用户（机器人 / 群聊）→ 判定成功，整项隐藏；
     * 2. 通讯录里没有该 chatId（陌生人）→ 判定成功，整项隐藏；
     * 3. 通讯录请求失败（网络错 / 超时）→ 判定不出来，置灰不可用并提示「无法校验好友关系，暂不可用」；
     * 4. /v1/user/get-user 拿不到非空 userId → 隐藏（匹配黑名单只用 userId，没有回退 chatId 的分支）。
     *
     * 底部「暂不支持删除好友」灰条同样只在好友关系成立时显示。
     */
    private fun loadProfileAndBlock() {
        lifecycleScope.launch {
            val user = runCatching { Api.getUser(chatId) }.getOrNull()
            if (user != null) {
                displayName = user.name.ifEmpty { chatName }
                avatarUrl = user.avatarUrl
                userId = user.id
                binding.tvTitle.text = displayName.ifEmpty { chatId }
                binding.tvName.text = displayName.ifEmpty { chatId }
                binding.ivAvatar.load(Api.fullImageUrl(user.avatarUrl)) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
            }
            loadBlock()
        }
    }

    private suspend fun loadBlock() {
        if (chatType != ChatType.USER) {
            hideBlock()
            return
        }
        val friendIds = runCatching {
            Api.addressBookList("").first.flatMap { it.items }.map { it.chatId }.toSet()
        }.getOrNull()
        if (friendIds == null) {
            // 校验请求本身失败：不知道是不是好友，置灰并提示，不当作陌生人隐藏
            renderBlockUnknown("无法校验好友关系，暂不可用")
            return
        }
        if (chatId !in friendIds) {
            hideBlock()
            return
        }
        binding.tvNoDelete.visibility = View.VISIBLE
        if (userId.isEmpty()) {
            hideBlock()
            return
        }
        try {
            val blocked = findBlocked(userId)
            if (blocked == null) {
                renderBlockUnknown("无法获取当前设置（黑名单页数过多）")
            } else {
                blockReady = true
                renderBlock(blocked)
            }
        } catch (e: Exception) {
            renderBlockUnknown("无法获取当前设置：${e.message}")
        }
    }

    private fun hideBlock() {
        blockReady = false
        binding.rowBlock.visibility = View.GONE
        binding.tvNoDelete.visibility = View.GONE
    }

    /**
     * 免打扰初值。
     *
     * /v1/conversation/list 的请求体只有 md5（proto 字段 2），没有分页参数：
     * 传空 md5 即返回全量会话，md5 命中时才返回空列表。因此这里一次全量取回后按 chatId 找，
     * 找不到就是「该会话不在列表里」。
     */
    private fun loadQuiet() {
        lifecycleScope.launch {
            try {
                val (list, _) = Api.conversationList("")
                val c = list.firstOrNull { it.chatId == chatId }
                if (c == null) {
                    renderQuietUnknown("无法获取当前设置（会话列表里没有该会话）")
                } else {
                    quietReady = true
                    renderQuiet(c.doNotDisturb)
                }
            } catch (e: Exception) {
                renderQuietUnknown("无法获取当前设置：${e.message}")
            }
        }
    }

    private suspend fun findBlocked(uid: String): Boolean? {
        var page = 1
        var loaded = 0
        while (page <= BLOCK_MAX_PAGES) {
            val result = Api.blackList(BLOCK_PAGE_SIZE, page)
            val list = result.list
            if (list.isEmpty()) return false
            if (list.any { it.userId == uid }) return true
            loaded += list.size
            if (loaded >= result.total) return false
            page++
        }
        return null
    }

    private fun renderQuiet(checked: Boolean) {
        binding.swQuiet.isChecked = checked
        binding.rowQuiet.isFocusable = true
        binding.rowQuiet.alpha = 1f
        binding.swQuiet.isEnabled = true
    }

    private fun renderBlock(checked: Boolean) {
        binding.swBlock.isChecked = checked
        binding.rowBlock.isFocusable = true
        binding.rowBlock.alpha = 1f
        binding.swBlock.isEnabled = true
    }

    private fun renderQuietUnknown(reason: String) {
        quietReady = false
        binding.rowQuiet.isFocusable = false
        binding.rowQuiet.alpha = 0.45f
        binding.swQuiet.isEnabled = false
        addHint("消息免打扰 $reason")
    }

    private fun renderBlockUnknown(reason: String) {
        blockReady = false
        binding.rowBlock.isFocusable = false
        binding.rowBlock.alpha = 0.45f
        binding.swBlock.isEnabled = false
        addHint("加入黑名单 $reason")
    }

    private fun addHint(text: String) {
        if (!hints.contains(text)) hints.add(text)
        binding.tvHint.text = hints.joinToString("\n")
        binding.tvHint.visibility = View.VISIBLE
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
