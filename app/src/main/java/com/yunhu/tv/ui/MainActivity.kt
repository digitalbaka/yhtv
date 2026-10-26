package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.yunhu.tv.App
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatMsg
import com.yunhu.tv.api.WsClient
import com.yunhu.tv.databinding.ActivityMainBinding
import com.yunhu.tv.util.Session
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var contactAdapter: ContactAdapter
    private lateinit var baAdapter: BaAdapter

    private var conversationMd5 = ""
    private var contactsMd5 = ""
    private var contactsLoaded = false
    private var baLoaded = false
    private var baTyp = 1 // 1-关注 4-全部
    private var currentTab = 0

    private val wsListener = object : WsClient.Listener {
        // 收到推送时强制全量刷新会话（清空 md5），避免增量缓存异常导致列表卡在"暂无会话"
        override fun onPush(msg: ChatMsg) {
            conversationMd5 = ""
            refreshConversations()
        }
        override fun onStateChanged(connected: Boolean) {
            binding.tvWsState.text = if (connected) "已连接" else "重连中…"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupTabs()

        App.wsClient.addListener(wsListener)
        App.wsClient.start()

        binding.tabConversation.requestFocus()
        refreshConversations()
    }

    override fun onDestroy() {
        App.wsClient.removeListener(wsListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::conversationAdapter.isInitialized) refreshConversations()
    }

    private fun setupLists() {
        conversationAdapter = ConversationAdapter(
            onClick = { openChat(it.chatId, it.chatType, it.displayName) },
            onLongClick = { confirmDeleteConversation(it.chatId) }
        )
        binding.rvConversations.layoutManager = LinearLayoutManager(this)
        binding.rvConversations.adapter = conversationAdapter

        contactAdapter = ContactAdapter { item, chatType ->
            openChat(item.chatId, chatType, item.displayName)
        }
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = contactAdapter

        baAdapter = BaAdapter { ba ->
            startActivity(
                Intent(this, PostListActivity::class.java)
                    .putExtra(PostListActivity.EXTRA_BA_ID, ba.id)
                    .putExtra(PostListActivity.EXTRA_BA_NAME, ba.name)
            )
        }
        binding.rvBas.layoutManager = LinearLayoutManager(this)
        binding.rvBas.adapter = baAdapter
        binding.btnBaFollowed.setOnClickListener { switchBaTyp(1) }
        binding.btnBaAll.setOnClickListener { switchBaTyp(4) }
    }

    private fun setupTabs() {
        val tabs = listOf(binding.tabConversation, binding.tabContacts, binding.tabCommunity, binding.tabMe)
        tabs.forEachIndexed { index, tab ->
            FocusEffects.apply(tab, 1.06f)
            tab.setOnClickListener { switchTab(index) }
            tab.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) switchTab(index) }
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    private fun switchTab(index: Int) {
        currentTab = index
        binding.panelConversations.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelContacts.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelCommunity.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.panelMe.visibility = if (index == 3) View.VISIBLE else View.GONE
        when (index) {
            1 -> loadContactsOnce()
            2 -> loadBaOnce()
            3 -> loadMe()
        }
    }

    private fun loadBaOnce() {
        if (baLoaded) return
        baLoaded = true
        loadBas()
    }

    private fun switchBaTyp(typ: Int) {
        if (baTyp == typ) return
        baTyp = typ
        loadBas()
    }

    private fun loadBas() {
        lifecycleScope.launch {
            try {
                val list = Api.baList(baTyp)
                baAdapter.submit(list)
                binding.tvBaEmpty.visibility =
                    if (baAdapter.itemCount == 0) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "版块加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshConversations() {
        lifecycleScope.launch {
            try {
                val (list, md5) = Api.conversationList(conversationMd5)
                // 服务端在 md5 一致时返回空列表；只有拿到新数据或 md5 变化时才更新界面
                if (list.isNotEmpty() || md5 != conversationMd5) {
                    conversationMd5 = md5
                    conversationAdapter.submit(list)
                }
                binding.tvConversationEmpty.visibility =
                    if (conversationAdapter.itemCount == 0) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                // 失败时清缓存，下次全量拉取
                conversationMd5 = ""
            }
        }
    }

    private fun loadContactsOnce() {
        if (contactsLoaded) return
        contactsLoaded = true
        lifecycleScope.launch {
            try {
                val (sections, md5) = Api.addressBookList(contactsMd5)
                contactsMd5 = md5
                val data = sections.map { it.listName to (it.chatType to it.items) }
                contactAdapter.submit(data)
                binding.tvContactsEmpty.visibility =
                    if (contactAdapter.itemCount == 0) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                contactsLoaded = false
            }
        }
    }

    private fun loadMe() {
        binding.tvMyName.text = Session.nickname.ifEmpty { "用户 ${Session.userId}" }
        binding.tvMyId.text = "ID: ${Session.userId}"
        binding.ivMyAvatar.load(Session.avatarUrl) { placeholder(R.drawable.bg_card) }
        lifecycleScope.launch {
            try {
                val info = Api.getSelfInfo()
                Session.nickname = info.nickname
                Session.avatarUrl = info.avatarUrl
                binding.tvMyName.text = info.nickname
                binding.tvMyId.text = "ID: ${info.userId}"
                binding.tvMyCoin.text = "金币: ${info.coin}"
                binding.ivMyAvatar.load(info.avatarUrl) { placeholder(R.drawable.bg_card) }
            } catch (_: Exception) {
            }
        }
    }

    private fun openChat(chatId: String, chatType: Int, name: String) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                .putExtra(ChatActivity.EXTRA_CHAT_TYPE, chatType)
                .putExtra(ChatActivity.EXTRA_CHAT_NAME, name)
        )
    }

    private fun confirmDeleteConversation(chatId: String) {
        AlertDialog.Builder(this)
            .setTitle("删除该会话？")
            .setMessage("会话将从列表中移除")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    Api.removeConversation(chatId)
                    conversationMd5 = ""
                    refreshConversations()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录？")
            .setPositiveButton("退出") { _, _ ->
                lifecycleScope.launch {
                    Api.logout()
                    App.wsClient.stop()
                    Session.clear()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
