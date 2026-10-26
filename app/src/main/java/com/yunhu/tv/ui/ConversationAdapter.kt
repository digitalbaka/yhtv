package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Conversation
import com.yunhu.tv.util.TimeFmt

class ConversationAdapter(
    private val onClick: (Conversation) -> Unit,
    private val onLongClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    private val items = mutableListOf<Conversation>()

    fun submit(list: List<Conversation>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_avatar)
        val name: TextView = view.findViewById(R.id.tv_name)
        val lastMsg: TextView = view.findViewById(R.id.tv_last_msg)
        val time: TextView = view.findViewById(R.id.tv_time)
        val unread: TextView = view.findViewById(R.id.tv_unread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.displayName.ifEmpty { c.chatId }
        h.lastMsg.text = if (c.at) "[有人@我] ${c.chatContent}" else c.chatContent
        h.time.text = TimeFmt.format(if (c.sendTimestamp > 0) c.sendTimestamp * 1000 else c.timestampMs)
        if (c.unreadMsg > 0) {
            h.unread.visibility = View.VISIBLE
            h.unread.text = if (c.unreadMsg > 99) "99+" else c.unreadMsg.toString()
        } else {
            h.unread.visibility = View.GONE
        }
        h.avatar.load(c.avatarUrl) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
        FocusEffects.apply(h.itemView)
    }
}
