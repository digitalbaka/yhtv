package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.ChatType
import com.yunhu.tv.databinding.ActivityAddFriendBinding
import kotlinx.coroutines.launch

/** 添加好友 / 群聊 / 机器人：按 ID 或名称搜索 */
class AddFriendActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TYPE = "type"
    }

    private lateinit var binding: ActivityAddFriendBinding
    private lateinit var adapter: SearchResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFriendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SearchResultAdapter { openDetail(it) }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        val presetId = intent.getStringExtra(EXTRA_ID)
        if (!presetId.isNullOrEmpty()) {
            binding.etSearch.setText(presetId)
            binding.etSearch.post { doSearch() }
        }
    }

    private fun doSearch() {
        val word = binding.etSearch.text.toString().trim()
        if (word.isEmpty()) return
        binding.tvSearchHint.text = "搜索中…"
        binding.tvSearchHint.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val flat = mutableListOf<SearchResultAdapter.Row>()
                // 纯数字 ID 搜索：9位=群组(groupInfo)，8位=机器人(home-search)，7位=用户(getUser)
                if (word.all { it.isDigit() }) {
                    when (word.length) {
                        9 -> searchGroup(word, flat)
                        8 -> homeSearch(word, flat)
                        7 -> searchUser(word, ChatType.USER, flat)
                        else -> homeSearch(word, flat)
                    }
                } else {
                    homeSearch(word, flat)
                }
                adapter.submit(flat)
                binding.tvSearchHint.visibility = if (flat.isEmpty()) View.VISIBLE else View.GONE
                if (flat.isEmpty()) binding.tvSearchHint.text = "未找到相关结果"
            } catch (e: Exception) {
                binding.tvSearchHint.text = "搜索失败: ${e.message}"
            }
        }
    }

    /** 通过 groupInfo 查找群组 */
    private suspend fun searchGroup(id: String, flat: MutableList<SearchResultAdapter.Row>) {
        try {
            val g = Api.groupInfo(id)
            if (g.groupId.isNotEmpty()) {
                flat.add(SearchResultAdapter.Row.Header("群组"))
                flat.add(SearchResultAdapter.Row.Item(Api.SearchResult(
                    friendId = g.groupId,
                    friendType = ChatType.GROUP,
                    nickname = g.name,
                    name = g.name,
                    avatarUrl = g.avatarUrl
                )))
            }
        } catch (_: Exception) { /* 群组不存在则走空结果 */ }
    }

    /** 通过 getUser 查找用户或机器人 */
    private suspend fun searchUser(id: String, type: Int, flat: MutableList<SearchResultAdapter.Row>) {
        try {
            val u = Api.getUser(id)
            if (u.id.isNotEmpty()) {
                val title = if (type == ChatType.BOT) "机器人" else "用户"
                flat.add(SearchResultAdapter.Row.Header(title))
                flat.add(SearchResultAdapter.Row.Item(Api.SearchResult(
                    friendId = u.id,
                    friendType = type,
                    nickname = u.name,
                    name = u.name,
                    avatarUrl = u.avatarUrl
                )))
            }
        } catch (_: Exception) { /* 不存在则走空结果 */ }
    }

    /** 名称综合搜索 */
    private suspend fun homeSearch(word: String, flat: MutableList<SearchResultAdapter.Row>) {
        val sections = Api.homeSearch(word)
        sections.forEach { (title, list) ->
            if (list.isNotEmpty()) {
                flat.add(SearchResultAdapter.Row.Header(title))
                list.forEach { flat.add(SearchResultAdapter.Row.Item(it)) }
            }
        }
    }

    /** 点击结果跳转到详情页 */
    private fun openDetail(r: Api.SearchResult) {
        startActivity(
            Intent(this, UserProfileActivity::class.java)
                .putExtra(UserProfileActivity.EXTRA_USER_ID, r.friendId)
                .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, r.friendType)
                .putExtra(UserProfileActivity.EXTRA_NAME, r.nickname.ifEmpty { r.name })
                .putExtra(UserProfileActivity.EXTRA_AVATAR, r.avatarUrl)
                .putExtra(UserProfileActivity.EXTRA_ALWAYS_AGREE, r.alwaysAgree)
        )
    }
}
