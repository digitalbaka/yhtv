package com.yunhu.pad.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yunhu.pad.api.Api
import com.yunhu.pad.databinding.ActivityAddFriendBinding
import kotlinx.coroutines.launch

/**
 * 全局搜索：用户 / 群组 / 机器人（/v1/search/home-search）+ 社区文章（/v1/community/search）。
 * 入口收敛在「消息」页顶部与「社区」页，不再单独散落。
 */
class GlobalSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddFriendBinding
    private lateinit var adapter: SearchResultAdapter
    private var searching = false
    private var lastWord = ""
    private var articlePage = 1
    private var loadedArticles = 0
    private var articleNoMore = false
    private var loadingMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFriendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = "全局搜索"
        binding.tvSearchHint.text = "输入关键词搜索用户、群组、机器人与文章"
        binding.btnBack.setOnClickListener { finish() }

        adapter = SearchResultAdapter(
            onClick = { openProfile(it) },
            onPostClick = { post ->
                startActivity(
                    Intent(this, PostDetailActivity::class.java)
                        .putExtra(PostDetailActivity.EXTRA_POST_ID, post.id)
                )
            }
        )
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
        binding.rvResults.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) loadMoreArticles()
            }
        })

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        binding.tvSearchHint.setOnClickListener {
            if (lastWord.isNotEmpty()) doSearch()
        }
    }

    private fun doSearch() {
        if (searching) return
        val word = binding.etSearch.text.toString().trim()
        if (word.isEmpty()) {
            binding.tvSearchHint.text = "请输入关键词"
            return
        }
        lastWord = word
        searching = true
        binding.tvSearchHint.text = "搜索中…"
        binding.tvSearchHint.visibility = View.VISIBLE
        binding.btnSearch.isEnabled = false
        lifecycleScope.launch {
            val rows = mutableListOf<SearchResultAdapter.Row>()
            var failed: String? = null
            try {
                Api.homeSearch(word).forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        rows.add(SearchResultAdapter.Row.Header(title))
                        list.forEach { rows.add(SearchResultAdapter.Row.Item(it)) }
                    }
                }
            } catch (e: Exception) {
                failed = e.message
            }
            try {
                val (posts, _) = Api.communitySearch(word)
                articlePage = 1
                loadedArticles = posts.size
                articleNoMore = posts.isEmpty()
                if (posts.isNotEmpty()) {
                    rows.add(SearchResultAdapter.Row.Header("文章"))
                    posts.forEach { rows.add(SearchResultAdapter.Row.PostItem(it)) }
                }
            } catch (e: Exception) {
                failed = failed ?: e.message
            }
            adapter.submit(rows)
            binding.btnSearch.isEnabled = true
            searching = false
            when {
                rows.isEmpty() && failed != null -> {
                    binding.tvSearchHint.visibility = View.VISIBLE
                    binding.tvSearchHint.text = "搜索失败: $failed\n点按重试"
                }
                rows.isEmpty() -> {
                    binding.tvSearchHint.visibility = View.VISIBLE
                    binding.tvSearchHint.text = "未找到相关结果"
                }
                else -> binding.tvSearchHint.visibility = View.GONE
            }
        }
    }

    /** 文章结果分页加载更多（社区搜索支持 page/size） */
    private fun loadMoreArticles() {
        if (loadingMore || searching || lastWord.isEmpty() || articleNoMore) return
        loadingMore = true
        lifecycleScope.launch {
            try {
                val (posts, _) = Api.communitySearch(lastWord, page = articlePage + 1)
                articlePage++
                loadedArticles += posts.size
                adapter.append(posts.map { SearchResultAdapter.Row.PostItem(it) })
                if (posts.isEmpty()) articleNoMore = true
            } catch (e: Exception) {
                binding.tvSearchHint.visibility = View.VISIBLE
                binding.tvSearchHint.text = "加载更多失败: ${e.message}\n点按重试"
            }
            loadingMore = false
        }
    }

    private fun openProfile(r: Api.SearchResult) {
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
