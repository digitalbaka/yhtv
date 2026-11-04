package com.yunhu.pad.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yunhu.pad.R
import com.yunhu.pad.api.Api
import com.yunhu.pad.databinding.ActivityPostCreateBinding
import com.yunhu.pad.util.Prefs
import kotlinx.coroutines.launch

/**
 * 社区发布 / 编辑文章：纯文本 / Markdown 两种类型，支持本地草稿（create-draft / cancel-draft）。
 * 发布或编辑成功后回传 RESULT_OK，列表页收到后刷新第一页。
 */
class PostCreateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BA_ID = "ba_id"
        const val EXTRA_BA_NAME = "ba_name"
        /** 编辑模式：非 0 表示编辑已有文章 */
        const val EXTRA_POST_ID = "post_id"
        const val EXTRA_POST_TITLE = "post_title"
        const val EXTRA_POST_CONTENT = "post_content"
        const val EXTRA_POST_TYPE = "post_type"
        /** 从草稿箱继续编辑 */
        const val EXTRA_DRAFT_ID = "draft_id"
        const val EXTRA_DRAFT_BA_ID = "draft_ba_id"

        /** 与接口文档一致：1-纯文本，2-Markdown */
        private const val TYPE_TEXT = 1
        private const val TYPE_MARKDOWN = 2
    }

    private lateinit var binding: ActivityPostCreateBinding

    private var baId = 0L
    private var contentType = TYPE_TEXT
    private var publishing = false
    private var postId = 0L
    private var draftId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        baId = intent.getLongExtra(EXTRA_BA_ID, 0)
        postId = intent.getLongExtra(EXTRA_POST_ID, 0)
        draftId = intent.getLongExtra(EXTRA_DRAFT_ID, 0)
        if (draftId > 0 && baId == 0L) baId = intent.getLongExtra(EXTRA_DRAFT_BA_ID, 0)

        val baName = intent.getStringExtra(EXTRA_BA_NAME).orEmpty()
        if (postId > 0) {
            binding.tvTitle.text = "编辑文章"
            binding.btnPublish.text = "保存"
        } else if (baName.isNotEmpty()) {
            binding.tvTitle.text = "发布到 $baName"
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTypeText.setOnClickListener { setType(TYPE_TEXT) }
        binding.btnTypeMarkdown.setOnClickListener { setType(TYPE_MARKDOWN) }
        binding.btnPublish.setOnClickListener { publish() }
        binding.btnSaveDraft.setOnClickListener { saveDraft() }
        setType(intent.getIntExtra(EXTRA_POST_TYPE, TYPE_TEXT))

        if (postId > 0) {
            binding.etPostTitle.setText(intent.getStringExtra(EXTRA_POST_TITLE).orEmpty())
            binding.etPostContent.setText(intent.getStringExtra(EXTRA_POST_CONTENT).orEmpty())
        } else if (draftId > 0) {
            loadDraft(draftId)
        }
    }

    private fun setType(type: Int) {
        contentType = type
        binding.btnTypeText.isSelected = type == TYPE_TEXT
        binding.btnTypeMarkdown.isSelected = type == TYPE_MARKDOWN
        val accent = ContextCompat.getColor(this, R.color.accent)
        val normal = ContextCompat.getColor(this, R.color.text_primary)
        binding.btnTypeText.setTextColor(if (type == TYPE_TEXT) accent else normal)
        binding.btnTypeMarkdown.setTextColor(if (type == TYPE_MARKDOWN) accent else normal)
        binding.etPostContent.hint =
            if (type == TYPE_MARKDOWN) "支持 Markdown：# 标题、**粗体**、- 列表、> 引用" else "正文内容"
    }

    private fun loadDraft(id: Long) {
        lifecycleScope.launch {
            try {
                val post = Api.getDraft(id, baId)
                if (post == null) {
                    toast("草稿不存在或已失效")
                    draftId = 0
                    Prefs.lastDraftId = 0
                    return@launch
                }
                binding.etPostTitle.setText(post.title)
                binding.etPostContent.setText(post.content)
                setType(if (post.contentType == TYPE_MARKDOWN) TYPE_MARKDOWN else TYPE_TEXT)
                if (post.baId > 0) baId = post.baId
                binding.tvTitle.text = "编辑草稿"
            } catch (e: Exception) {
                toast("读取草稿失败: ${e.message}")
            }
        }
    }

    private fun saveDraft() {
        if (publishing) return
        val title = binding.etPostTitle.text.toString().trim()
        val content = binding.etPostContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            toast("草稿内容为空")
            return
        }
        publishing = true
        binding.btnSaveDraft.isEnabled = false
        lifecycleScope.launch {
            try {
                draftId = Api.createDraft(baId, title, content, contentType, draftId)
                Prefs.lastDraftId = draftId
                Prefs.lastDraftBaId = baId
                toast("草稿已保存")
            } catch (e: Exception) {
                toast("保存草稿失败: ${e.message}")
            } finally {
                publishing = false
                binding.btnSaveDraft.isEnabled = true
            }
        }
    }

    private fun publish() {
        if (publishing) return
        val title = binding.etPostTitle.text.toString().trim()
        val content = binding.etPostContent.text.toString().trim()
        if (title.isEmpty()) {
            toast("请填写标题")
            return
        }
        if (content.isEmpty()) {
            toast("请填写正文")
            return
        }
        publishing = true
        binding.btnPublish.isEnabled = false
        binding.btnPublish.text = if (postId > 0) "保存中…" else "发布中…"
        lifecycleScope.launch {
            try {
                if (postId > 0) {
                    Api.editPost(postId, title, content, contentType)
                    toast("已保存")
                } else {
                    Api.createPost(baId, title, content, contentType)
                    toast("发布成功")
                }
                // 发布成功后清掉对应草稿，避免草稿箱里残留已发布内容
                if (draftId > 0) {
                    runCatching { Api.cancelDraft(draftId) }
                    if (Prefs.lastDraftId == draftId) Prefs.lastDraftId = 0
                    draftId = 0
                }
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                toast((if (postId > 0) "保存失败: " else "发布失败: ") + e.message)
            } finally {
                publishing = false
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = if (postId > 0) "保存" else "发布"
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
