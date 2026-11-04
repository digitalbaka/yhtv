package com.yunhu.pad.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.pad.R
import com.yunhu.pad.api.Api
import com.yunhu.pad.api.ChatType
import com.yunhu.pad.api.GroupMember
import com.yunhu.pad.databinding.ActivitySimpleListBinding
import com.yunhu.pad.util.Session
import kotlinx.coroutines.launch

/** 群成员列表：分页加载 + 管理员/群主的禁言、群标签操作 */
class GroupMembersActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        private const val PAGE_SIZE = 50
    }

    private lateinit var binding: ActivitySimpleListBinding
    private val members = mutableListOf<GroupMember>()

    private var groupId = ""
    private var myPermission = 0
    private var page = 1
    private var loading = false
    private var noMore = false
    private var tags: List<Api.GroupTag> = emptyList()

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
                startActivity(
                    Intent(this@GroupMembersActivity, UserProfileActivity::class.java)
                        .putExtra(UserProfileActivity.EXTRA_USER_ID, m.userId)
                )
            }
            h.itemView.setOnLongClickListener {
                showMemberMenu(m)
                true
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

        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        binding.tvSimpleTitle.text = "群成员"
        binding.rvSimple.layoutManager = LinearLayoutManager(this)
        binding.rvSimple.adapter = adapter
        binding.tvSimpleEmpty.text = "加载中…"
        binding.tvSimpleEmpty.setOnClickListener { reload() }
        binding.rvSimple.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= members.size - 3) loadPage()
            }
        })

        lifecycleScope.launch {
            try {
                myPermission = Api.groupInfo(groupId).permissionLevel
            } catch (_: Exception) {
                // 拿不到权限时伪装成普通成员，管理入口不显示，由服务端兜底鉴权
            }
            loadPage(reset = true)
        }
    }

    private fun reload() {
        page = 1
        noMore = false
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean = false) {
        if (loading || noMore) return
        loading = true
        if (reset) binding.tvSimpleEmpty.text = "加载中…"
        binding.tvSimpleEmpty.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            try {
                val (list, _) = Api.listMember(groupId, PAGE_SIZE, page)
                if (reset) members.clear()
                // 服务端分页边界可能重复返回末条，按 userId 去重
                list.forEach { item ->
                    if (members.none { it.userId == item.userId }) members.add(item)
                }
                adapter.notifyDataSetChanged()
                if (list.size < PAGE_SIZE) noMore = true else page++
                binding.tvSimpleEmpty.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
                if (members.isEmpty()) binding.tvSimpleEmpty.text = "暂无成员"
            } catch (e: Exception) {
                binding.tvSimpleEmpty.text = "加载失败: ${e.message}\n点按重试"
                binding.tvSimpleEmpty.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
            }
            loading = false
        }
    }

    // ---------- 成员管理（群主 / 管理员） ----------

    private fun showMemberMenu(m: GroupMember) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val isManager = myPermission >= 2
        val targetIsOwner = m.permissionLevel == 100
        val isSelf = m.userId == Session.userId
        if (isManager && !targetIsOwner && !isSelf) {
            if (m.isGag) actions.add("解除禁言" to { gag(m, 0) }) else actions.add("禁言" to { pickGagDuration(m) })
            actions.add("设置群标签" to { showRelateTags(m) })
        }
        actions.add("查看资料" to {
            startActivity(
                Intent(this, UserProfileActivity::class.java)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, m.userId)
                    .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, ChatType.USER)
                    .putExtra(UserProfileActivity.EXTRA_NAME, m.name)
                    .putExtra(UserProfileActivity.EXTRA_AVATAR, m.avatarUrl)
            )
        })
        if (!isManager) {
            // 普通成员没有管理权限，直接进资料页
            actions.removeAll { it.first == "查看资料" }
            startActivity(
                Intent(this, UserProfileActivity::class.java)
                    .putExtra(UserProfileActivity.EXTRA_USER_ID, m.userId)
                    .putExtra(UserProfileActivity.EXTRA_CHAT_TYPE, ChatType.USER)
                    .putExtra(UserProfileActivity.EXTRA_NAME, m.name)
                    .putExtra(UserProfileActivity.EXTRA_AVATAR, m.avatarUrl)
            )
            return
        }
        AlertDialog.Builder(this)
            .setTitle(m.name.ifEmpty { m.userId })
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun pickGagDuration(m: GroupMember) {
        val labels = arrayOf("禁言 10 分钟", "禁言 1 小时", "禁言 6 小时", "禁言 12 小时", "永久禁言")
        val values = intArrayOf(600, 3600, 21600, 43200, -1)
        AlertDialog.Builder(this)
            .setTitle("禁言 ${m.name.ifEmpty { m.userId }}")
            .setItems(labels) { _, which -> gag(m, values[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun gag(m: GroupMember, seconds: Int) {
        lifecycleScope.launch {
            try {
                Api.gagMember(groupId, m.userId, seconds)
                m.isGag = seconds != 0
                adapter.notifyDataSetChanged()
                toast(if (seconds == 0) "已解除禁言" else "已禁言")
            } catch (e: Exception) {
                toast("操作失败: ${e.message}")
            }
        }
    }

    /** 群成员当前标签没有查询接口，只能逐项关联 / 取消关联 */
    private fun showRelateTags(m: GroupMember) {
        lifecycleScope.launch {
            try {
                if (tags.isEmpty()) tags = Api.groupTagList(groupId, 100, 1)
                if (tags.isEmpty()) {
                    toast("还没有群标签，请先在群设置中创建")
                    return@launch
                }
                val labels = tags.map { "【${it.tag}】${it.desc}" }.toTypedArray()
                AlertDialog.Builder(this@GroupMembersActivity)
                    .setTitle("群标签 · ${m.name.ifEmpty { m.userId }}")
                    .setItems(labels) { _, which ->
                        val tag = tags[which]
                        AlertDialog.Builder(this@GroupMembersActivity)
                            .setTitle("【${tag.tag}】")
                            .setItems(arrayOf("关联该标签", "取消关联")) { _, action ->
                                lifecycleScope.launch {
                                    try {
                                        if (action == 0) {
                                            Api.relateGroupTag(m.userId, tag.id)
                                        } else {
                                            Api.cancelGroupTag(m.userId, tag.id)
                                        }
                                        toast(if (action == 0) "已关联" else "已取消关联")
                                    } catch (e: Exception) {
                                        toast("操作失败: ${e.message}")
                                    }
                                }
                            }
                            .show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                toast("获取群标签失败: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
