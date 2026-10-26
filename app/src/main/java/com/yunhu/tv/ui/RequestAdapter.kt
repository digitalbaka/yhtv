package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.ApplyRequest

/** 申请/邀请列表 */
class RequestAdapter(
    private val onClick: (ApplyRequest) -> Unit
) : RecyclerView.Adapter<RequestAdapter.VH>() {

    private val items = mutableListOf<ApplyRequest>()

    fun submit(list: List<ApplyRequest>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_req_avatar)
        val title: TextView = view.findViewById(R.id.tv_req_title)
        val desc: TextView = view.findViewById(R.id.tv_req_desc)
        val state: TextView = view.findViewById(R.id.tv_req_state)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val fromName = r.name.ifEmpty { r.botName }.ifEmpty { "未知" }
        h.title.text = fromName
        // 描述：申请类型 + 理由 + 时间
        val kind = when (r.targetType) {
            1 -> "申请添加您为好友"
            2 -> if (r.groupName.isNotEmpty()) "申请加入群聊「${r.groupName}」" else "申请加入群聊"
            3 -> "申请添加机器人"
            else -> "申请"
        }
        h.desc.text = buildString {
            append(kind)
            if (r.note.isNotEmpty()) append("：${r.note}")
            if (r.inviteAtStr.isNotEmpty()) append(" · ${r.inviteAtStr}")
        }
        // 状态：0-待处理，1-通过，2-拒绝，3-过期，4-已解散
        h.state.text = when (r.result) {
            0 -> "待处理"
            1 -> "已通过"
            2 -> "已拒绝"
            3 -> "已过期"
            4 -> "已解散"
            else -> ""
        }
        h.avatar.load(r.avatar.ifEmpty { r.groupAvatar }) {
            placeholder(R.drawable.bg_card)
            error(R.drawable.bg_card)
        }
        h.itemView.setOnClickListener { onClick(r) }
        FocusEffects.apply(h.itemView)
    }
}
