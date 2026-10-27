package com.yunhu.tv.ui

import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.databinding.ActivityPostDetailBinding
import com.yunhu.tv.util.MarkdownLite
import kotlinx.coroutines.launch

/** 社区文章详情：正文 + 点赞/投币 + 评论列表 + 发评论 */
class PostDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_POST_ID = "post_id"
    }

    private lateinit var binding: ActivityPostDetailBinding
    private lateinit var commentAdapter: CommentAdapter

    private var postId = 0L
    private var authorId = ""
    /** 详情接口返回的最新 Post（带 isLiked/isCollected 状态） */
    private var currentPost: Api.Post? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 支持 Markdown 中的可点击链接
        binding.tvPostContent.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        postId = intent.getLongExtra(EXTRA_POST_ID, 0)

        commentAdapter = CommentAdapter()
        binding.rvComments.layoutManager = LinearLayoutManager(this)
        binding.rvComments.adapter = commentAdapter

        binding.btnLike.setOnClickListener { doLike() }
        binding.btnCollect.setOnClickListener { doCollect() }
        binding.btnReward.setOnClickListener { showRewardDialog() }
        binding.btnSendComment.setOnClickListener { sendComment() }
        binding.etComment.setOnEditorActionListener { _, actionId, event ->
            val send = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (send) { sendComment(); true } else false
        }

        loadDetail()
        loadComments()
    }

    private fun loadDetail() {
        lifecycleScope.launch {
            try {
                val (post, ba) = Api.postDetail(postId)
                currentPost = post
                authorId = post.senderId
                binding.tvPostTitle.text = post.title.ifEmpty { "(无标题)" }
                binding.tvAuthor.text = post.senderNickname.ifEmpty { post.senderId }
                binding.tvPostTime.text = post.createTimeText
                binding.ivAuthorAvatar.load(post.senderAvatar) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
                // Markdown 文章：用轻量 span 渲染（仅文本样式，不产生图片 bitmap），图片单独渲染
                if (post.contentType == 2) {
                    val baseSize = (17 * resources.displayMetrics.density).toInt()
                    binding.tvPostContent.text = MarkdownLite.toSpanned(post.content, baseSize) { url ->
                        if (!com.yunhu.tv.util.YunhuRouter.open(this@PostDetailActivity, url)) {
                            // 非 yunhu:// 链接尝试用系统浏览器打开
                            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        }
                    }
                    showImages(post.content)
                } else {
                    binding.tvPostContent.text = post.content
                }
                // 统计：分区 · 赞 · 评论 · 收藏 · 投币
                binding.tvPostStats.text =
                    "${ba.name} · 赞 ${post.likeNum} · 评论 ${post.commentNum} · 收藏 ${post.collectNum} · 投币 ${post.amountNum}"
                // 按钮状态：已点赞=「取消赞」，已收藏=「已收藏」
                binding.btnLike.text = if (post.isLiked == 1) "已赞" else "点赞"
                binding.btnCollect.text = if (post.isCollected == 1) "已收藏" else "收藏"
            } catch (e: Exception) {
                toast("文章加载失败: ${e.message}")
            }
        }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            try {
                val (list, total) = Api.commentList(postId)
                commentAdapter.submit(list)
                binding.tvCommentHeader.text = "评论 ($total)"
            } catch (_: Exception) {
            }
        }
    }

    /** 把 Markdown 正文里的图片逐张加载到正文下方 */
    private fun showImages(markdown: String) {
        val urls = MarkdownLite.extractImages(markdown)
        if (urls.isEmpty()) return
        binding.llPostImages.removeAllViews()
        urls.forEach { url ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            iv.load(Api.fullImageUrl(url)) {
                // 按屏宽采样解码，避免超大原图占用内存（配合全局 RGB565）
                size(resources.displayMetrics.widthPixels, resources.displayMetrics.widthPixels)
                placeholder(R.drawable.bg_card)
                error(R.drawable.bg_card)
            }
            binding.llPostImages.addView(iv)
        }
    }

    private fun doLike() {
        lifecycleScope.launch {
            try {
                Api.postLike(postId)
                val cur = currentPost
                if (cur != null) {
                    val newLiked = if (cur.isLiked == 1) 0 else 1
                    currentPost = cur.copy(
                        isLiked = newLiked,
                        likeNum = cur.likeNum + if (newLiked == 1) 1 else -1
                    )
                    binding.btnLike.text = if (newLiked == 1) "已赞" else "点赞"
                    refreshStats()
                } else {
                    loadDetail()
                }
            } catch (e: Exception) {
                toast("点赞失败: ${e.message}")
            }
        }
    }

    private fun doCollect() {
        lifecycleScope.launch {
            try {
                Api.postCollect(postId)
                val cur = currentPost
                if (cur != null) {
                    val newCollected = if (cur.isCollected == 1) 0 else 1
                    currentPost = cur.copy(
                        isCollected = newCollected,
                        collectNum = cur.collectNum + if (newCollected == 1) 1 else -1
                    )
                    binding.btnCollect.text = if (newCollected == 1) "已收藏" else "收藏"
                    refreshStats()
                    toast(if (newCollected == 1) "已收藏" else "已取消收藏")
                } else {
                    loadDetail()
                }
            } catch (e: Exception) {
                toast("收藏失败: ${e.message}")
            }
        }
    }

    /** 根据 currentPost 刷新统计行（赞/收藏 切换后本地立即更新） */
    private fun refreshStats() {
        val cur = currentPost ?: return
        val stats = binding.tvPostStats.text?.toString() ?: ""
        // 用正则替换赞和收藏的数字，避免重新 fetch 分区名
        var s = stats.replace(Regex("赞 \\d+"), "赞 ${cur.likeNum}")
        s = s.replace(Regex("收藏 \\d+"), "收藏 ${cur.collectNum}")
        binding.tvPostStats.text = s
    }

    private fun showRewardDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "输入金币数量"
        }
        AlertDialog.Builder(this)
            .setTitle("投币")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    toast("请输入有效数量")
                } else {
                    doReward(amount)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doReward(amount: Double) {
        if (authorId.isEmpty()) {
            toast("文章信息未加载完成")
            return
        }
        lifecycleScope.launch {
            try {
                Api.postReward(postId, authorId, amount)
                toast("投币成功")
                loadDetail()
            } catch (e: Exception) {
                toast("投币失败: ${e.message}")
            }
        }
    }

    private fun sendComment() {
        val text = binding.etComment.text.toString().trim()
        if (text.isEmpty()) return
        binding.etComment.setText("")
        lifecycleScope.launch {
            try {
                Api.commentPost(postId, text)
                toast("评论成功")
                loadComments()
            } catch (e: Exception) {
                toast("评论失败: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
