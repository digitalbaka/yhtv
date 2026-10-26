package com.yunhu.tv.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yunhu.tv.api.Api
import com.yunhu.tv.databinding.ActivityAddFriendBinding
import kotlinx.coroutines.launch

/** 添加好友 / 群聊 / 机器人：按 ID 综合搜索，带申请理由 */
class AddFriendActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddFriendBinding
    private lateinit var adapter: SearchResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddFriendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SearchResultAdapter { showApplyDialog(it) }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
    }

    private fun doSearch() {
        val word = binding.etSearch.text.toString().trim()
        if (word.isEmpty()) return
        binding.tvSearchHint.text = "搜索中…"
        binding.tvSearchHint.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val sections = Api.homeSearch(word)
                val flat = mutableListOf<SearchResultAdapter.Row>()
                sections.forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        flat.add(SearchResultAdapter.Row.Header(title))
                        list.forEach { flat.add(SearchResultAdapter.Row.Item(it)) }
                    }
                }
                adapter.submit(flat)
                binding.tvSearchHint.visibility = if (flat.isEmpty()) View.VISIBLE else View.GONE
                if (flat.isEmpty()) binding.tvSearchHint.text = "未找到相关结果"
            } catch (e: Exception) {
                binding.tvSearchHint.text = "搜索失败: ${e.message}"
            }
        }
    }

    /** 添加确认框，可填申请理由 */
    private fun showApplyDialog(r: Api.SearchResult) {
        val typeName = when (r.friendType) {
            1 -> "好友"
            2 -> "群聊"
            3 -> "机器人"
            else -> "对象"
        }
        val displayName = r.nickname.ifEmpty { r.name }.ifEmpty { r.friendId }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "申请备注（可选）"
        }
        AlertDialog.Builder(this)
            .setTitle("添加$typeName")
            .setMessage("确定要添加 $displayName 吗？")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                doApply(r, input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doApply(r: Api.SearchResult, remark: String) {
        lifecycleScope.launch {
            try {
                Api.friendApply(r.friendId, r.friendType, remark)
                Toast.makeText(this@AddFriendActivity, "申请已发送", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AddFriendActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
