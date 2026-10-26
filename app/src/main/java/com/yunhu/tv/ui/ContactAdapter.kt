package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.ContactItem

/** 通讯录：分组标题 + 联系人混排 */
class ContactAdapter(
    private val onClick: (ContactItem, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val item: ContactItem, val chatType: Int) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(sections: List<Pair<String, Pair<Int, List<ContactItem>>>>) {
        rows.clear()
        for ((title, pair) in sections) {
            val (chatType, items) = pair
            if (items.isEmpty()) continue
            rows.add(Row.Header(title))
            items.forEach { rows.add(Row.Item(it, chatType)) }
        }
        notifyDataSetChanged()
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_header)
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_avatar)
        val name: TextView = view.findViewById(R.id.tv_name)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(inf.inflate(R.layout.item_contact_header, parent, false))
        } else {
            ItemVH(inf.inflate(R.layout.item_contact, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (h as HeaderVH).title.text = row.title
            is Row.Item -> {
                val vh = h as ItemVH
                vh.name.text = row.item.displayName.ifEmpty { row.item.chatId }
                vh.avatar.load(row.item.avatarUrl) {
                    placeholder(R.drawable.bg_card)
                    error(R.drawable.bg_card)
                }
                vh.itemView.setOnClickListener { onClick(row.item, row.chatType) }
                FocusEffects.apply(vh.itemView)
            }
        }
    }
}
