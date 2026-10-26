package com.yunhu.tv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.databinding.ActivitySimpleListBinding
import com.yunhu.tv.util.TimeFmt
import kotlinx.coroutines.launch

/**
 * 社区简单列表页：打赏记录 / 屏蔽用户。
 * 屏蔽用户条目点击可取消屏蔽。
 */
class CommunitySimpleListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_REWARD = "reward"
        const val MODE_BLOCK = "block"
    }

    private data class Row(
        val title: String,
        val desc: String,
        val avatar: String,
        val userId: String = ""
    )

    private lateinit var binding: ActivitySimpleListBinding
    private val rows = mutableListOf<Row>()
    private var mode = MODE_REWARD

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.title.text = r.title
            h.desc.text = r.desc
            if (r.avatar.isEmpty()) {
                h.avatar.visibility = View.GONE
            } else {
                h.avatar.visibility = View.VISIBLE
                h.avatar.load(r.avatar) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
            }
            h.state.text = if (mode == MODE_BLOCK) "点按取消屏蔽" else ""
            h.itemView.setOnClickListener {
                if (mode == MODE_BLOCK) confirmUnblock(r) else Unit
            }
            FocusEffects.apply(h.itemView)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_req_avatar)
        val title: TextView = view.findViewById(R.id.tv_req_title)
        val desc: TextView = view.findViewById(R.id.tv_req_desc)
        val state: TextView = view.findViewById(R.id.tv_req_state)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_REWARD
        binding.tvSimpleTitle.text = if (mode == MODE_REWARD) "打赏记录" else "屏蔽用户"
        binding.rvSimple.layoutManager = LinearLayoutManager(this)
        binding.rvSimple.adapter = adapter
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                rows.clear()
                if (mode == MODE_REWARD) {
                    Api.rewardRecord().forEach { r ->
                        rows.add(
                            Row(
                                title = "${r.amount} 金币",
                                desc = "${r.reason} · ${TimeFmt.format(r.createTime * 1000)}",
                                avatar = ""
                            )
                        )
                    }
                } else {
                    Api.blackList().forEach { u ->
                        rows.add(Row(title = u.nickname.ifEmpty { u.userId }, desc = "ID: ${u.userId}", avatar = u.avatarUrl, userId = u.userId))
                    }
                }
                adapter.notifyDataSetChanged()
                binding.tvSimpleEmpty.text = if (mode == MODE_REWARD) "暂无打赏记录" else "暂无屏蔽用户"
                binding.tvSimpleEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@CommunitySimpleListActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmUnblock(r: Row) {
        AlertDialog.Builder(this)
            .setTitle("取消屏蔽")
            .setMessage("确定取消屏蔽 ${r.title} 吗？")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Api.setBlackList(r.userId, 0)
                        Toast.makeText(this@CommunitySimpleListActivity, "已取消屏蔽", Toast.LENGTH_SHORT).show()
                        load()
                    } catch (e: Exception) {
                        Toast.makeText(this@CommunitySimpleListActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
