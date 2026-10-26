package com.yunhu.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.yunhu.tv.api.Api
import com.yunhu.tv.databinding.ActivityRequestsBinding
import kotlinx.coroutines.launch

/** 好友/入群申请列表 */
class RequestListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRequestsBinding
    private lateinit var adapter: RequestAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRequestsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RequestAdapter { req -> showHandleDialog(req) }
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val (list, _) = Api.requestList()
                adapter.submit(list)
                binding.tvRequestEmpty.visibility =
                    if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@RequestListActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showHandleDialog(req: com.yunhu.tv.api.ApplyRequest) {
        // 已处理的申请不提供操作
        if (req.result != 0) return
        val options = arrayOf("接受", "拒绝", "忽略")
        AlertDialog.Builder(this)
            .setTitle("处理申请")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> handle(req.requestId, agree = 1)
                    1 -> handle(req.requestId, agree = 2)
                    2 -> ignore(req.requestId)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handle(id: Int, agree: Int) {
        lifecycleScope.launch {
            try {
                Api.agreeApply(id, agree)
                Toast.makeText(this@RequestListActivity, if (agree == 1) "已接受" else "已拒绝", Toast.LENGTH_SHORT).show()
                load()
            } catch (e: Exception) {
                Toast.makeText(this@RequestListActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ignore(id: Int) {
        lifecycleScope.launch {
            try {
                Api.ignoreApply(id)
                Toast.makeText(this@RequestListActivity, "已忽略", Toast.LENGTH_SHORT).show()
                load()
            } catch (e: Exception) {
                Toast.makeText(this@RequestListActivity, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
