package com.yunhu.tv.ui

import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        postId = intent.getLongExtra(EXTRA_POST_ID, 0)

        commentAdapter = CommentAdapter()
        binding.rvComments.layoutManager = LinearLayoutManager(this)
        binding.rvComments.adapter = commentAdapter

        binding.btnLike.setOnClickListener { doLike() }
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
                authorId = post.senderId
                binding.tvPostTitle.text = post.title.ifEmpty { "(无标题)" }
                binding.tvAuthor.text = post.senderNickname.ifEmpty { post.senderId }
                binding.tvPostTime.text = post.createTimeText
                binding.ivAuthorAvatar.load(post.senderAvatar) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
                // Markdown 文章转纯文本阅读
                binding.tvPostContent.text =
                    if (post.contentType == 2) MarkdownLite.toPlain(post.content) else post.content
                binding.tvPostStats.text =
                    "${ba.name} · 赞 ${post.likeNum} · 评论 ${post.commentNum} · 投币 ${post.amountNum}"
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

    private fun doLike() {
        lifecycleScope.launch {
            try {
                Api.postLike(postId)
                toast("操作成功")
                loadDetail()
            } catch (e: Exception) {
                toast("点赞失败: ${e.message}")
            }
        }
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
