package com.yunhu.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.api.GroupMember
import com.yunhu.tv.databinding.ActivitySimpleListBinding
import kotlinx.coroutines.launch

/** 群成员列表 */
class GroupMembersActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }

    private lateinit var binding: ActivitySimpleListBinding
    private val members = mutableListOf<GroupMember>()

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
            return VH(v)
        }

        override fun getItemCount() = members.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = members[position]
            h.name.text = buildString {
                append(m.name.ifEmpty { m.userId })
                when (m.permissionLevel) {
                    100 -> append("（群主）")
                    2 -> append("（管理员）")
                }
                if (m.isGag) append(" [禁言中]")
            }
            h.avatar.load(m.avatarUrl) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
            h.itemView.setOnClickListener {
                startActivity(Intent(this@GroupMembersActivity, UserProfileActivity::class.java)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, m.userId))
            }
            FocusEffects.apply(h.itemView)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_avatar)
        val name: TextView = view.findViewById(R.id.tv_name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        binding.tvSimpleTitle.text = "群成员"
        binding.rvSimple.layoutManager = LinearLayoutManager(this)
        binding.rvSimple.adapter = adapter

        lifecycleScope.launch {
            try {
                val (list, _) = Api.listMember(groupId, 200, 1)
                members.clear()
                members.addAll(list)
                adapter.notifyDataSetChanged()
                binding.tvSimpleEmpty.text = "暂无成员"
                binding.tvSimpleEmpty.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.tvSimpleEmpty.text = "加载失败: ${e.message}"
                binding.tvSimpleEmpty.visibility = View.VISIBLE
            }
        }
    }
}
