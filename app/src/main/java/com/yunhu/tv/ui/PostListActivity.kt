package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yunhu.tv.api.Api
import com.yunhu.tv.databinding.ActivityPostListBinding
import kotlinx.coroutines.launch

/** 某分区的文章列表 */
class PostListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BA_ID = "ba_id"
        const val EXTRA_BA_NAME = "ba_name"
        private const val PAGE_SIZE = 20
    }

    private lateinit var binding: ActivityPostListBinding
    private lateinit var postAdapter: PostAdapter

    private var baId = 0L
    private var page = 1
    private var total = Int.MAX_VALUE
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        baId = intent.getLongExtra(EXTRA_BA_ID, 0)
        binding.tvTitle.text = intent.getStringExtra(EXTRA_BA_NAME).orEmpty().ifEmpty { "分区 $baId" }

        postAdapter = PostAdapter { post ->
            startActivity(
                Intent(this, PostDetailActivity::class.java)
                    .putExtra(PostDetailActivity.EXTRA_POST_ID, post.id)
            )
        }
        binding.rvPosts.layoutManager = LinearLayoutManager(this)
        binding.rvPosts.adapter = postAdapter
        // 滚动到底部自动加载下一页
        binding.rvPosts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= postAdapter.itemCount - 3) {
                    loadPage()
                }
            }
        })

        loadPage()
    }

    private fun loadPage() {
        if (loading || postAdapter.itemCount >= total) return
        loading = true
        lifecycleScope.launch {
            try {
                val (list, t) = Api.postList(baId, PAGE_SIZE, page)
                total = t
                if (page == 1) {
                    postAdapter.submit(list)
                } else {
                    postAdapter.append(list)
                }
                if (list.isNotEmpty()) page++
                binding.tvPostTotal.text = "共 $total 篇"
                binding.tvPostEmpty.visibility =
                    if (postAdapter.itemCount == 0) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@PostListActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            loading = false
        }
    }
}
