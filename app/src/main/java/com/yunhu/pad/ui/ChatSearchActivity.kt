package com.yunhu.pad.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.pad.api.Api
import com.yunhu.pad.databinding.ActivityChatSearchBinding
import kotlinx.coroutines.launch

/**
 * 会话内聊天记录搜索（POST /v1/search/chat-search）。
 * 结果按时间向前翻页：每次把当前最旧一条的 time 作为下一页游标。
 */
class ChatSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TYPE = "chat_type"
    }

    private lateinit var binding: ActivityChatSearchBinding
    private var chatId = ""
    private var chatType = 0
    private val hits = mutableListOf<Api.ChatSearchHit>()
    private val pageSize = 30

    private var loading = false
    private var noMore = false
    private var lastWord = ""
    private var cursorTime = Long.MAX_VALUE

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(com.yunhu.pad.R.layout.item_search_msg, parent, false)
            return VH(v)
        }

        override fun getItemCount() = hits.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val hit = hits[position]
            val vh = h as VH
            vh.name.text = hit.name
            vh.content.text = if (hit.content.isBlank()) "(非文本内容)" else hit.content
            vh.time.text = hit.timeText.ifEmpty { formatTime(hit.time) }
            vh.avatar.load(Api.fullImageUrl(hit.avatarUrl)) {
                placeholder(com.yunhu.pad.R.drawable.bg_card)
                error(com.yunhu.pad.R.drawable.bg_card)
            }
            vh.itemView.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra("msgId", hit.id))
                startActivity(
                    Intent(this@ChatSearchActivity, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                        .putExtra(ChatActivity.EXTRA_CHAT_TYPE, chatType)
                        .putExtra(ChatActivity.EXTRA_TARGET_MSG_ID, hit.id)
                        .putExtra(ChatActivity.EXTRA_TARGET_TEXT, hit.content)
                        .putExtra(ChatActivity.EXTRA_TARGET_NAME, hit.name)
                        .putExtra(ChatActivity.EXTRA_TARGET_AVATAR, hit.avatarUrl)
                        .putExtra(ChatActivity.EXTRA_TARGET_TIME, hit.time)
                )
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(com.yunhu.pad.R.id.iv_avatar)
        val name: TextView = view.findViewById(com.yunhu.pad.R.id.tv_name)
        val content: TextView = view.findViewById(com.yunhu.pad.R.id.tv_content)
        val time: TextView = view.findViewById(com.yunhu.pad.R.id.tv_time)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        chatType = intent.getIntExtra(EXTRA_CHAT_TYPE, 0)

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener { newSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { newSearch(); true } else false
        }
        binding.tvHint.setOnClickListener { if (lastWord.isNotEmpty()) newSearch() }
        binding.rvResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= hits.size - 3) loadMore()
            }
        })
    }

    private fun newSearch() {
        val word = binding.etSearch.text.toString().trim()
        if (word.isEmpty()) {
            binding.tvHint.text = "请输入关键词"
            return
        }
        lastWord = word
        hits.clear()
        adapter.notifyDataSetChanged()
        cursorTime = Long.MAX_VALUE
        noMore = false
        loadMore()
    }

    private fun loadMore() {
        if (loading || noMore || lastWord.isEmpty()) return
        loading = true
        binding.pbLoading.visibility = View.VISIBLE
        if (hits.isEmpty()) binding.tvHint.text = "搜索中…"
        binding.tvHint.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            try {
                val list = Api.chatSearch(lastWord, chatId, chatType, size = pageSize, time = cursorTime)
                val fresh = list.filterNot { hit -> hits.any { it.id == hit.id } }
                if (fresh.isEmpty()) {
                    noMore = true
                    if (hits.isEmpty()) binding.tvHint.text = "未找到相关聊天记录"
                } else {
                    hits.addAll(fresh)
                    adapter.notifyDataSetChanged()
                    cursorTime = fresh.last().time
                    binding.tvHint.visibility = View.GONE
                }
            } catch (e: Exception) {
                binding.tvHint.visibility = View.VISIBLE
                binding.tvHint.text = "搜索失败: ${e.message}\n点按重试"
            }
            loading = false
            binding.pbLoading.visibility = View.GONE
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }
}
