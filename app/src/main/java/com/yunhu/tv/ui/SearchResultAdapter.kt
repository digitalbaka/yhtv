package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.api.Api

/** 搜索结果列表（分组标题 + 条目） */
class SearchResultAdapter(
    private val onClick: (Api.SearchResult) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val result: Api.SearchResult) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val header: TextView = view.findViewById(R.id.tv_header)
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_avatar)
        val name: TextView = view.findViewById(R.id.tv_name)
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

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
            is Row.Header -> (h as HeaderVH).header.text = row.title
            is Row.Item -> {
                val vh = h as ItemVH
                val r = row.result
                vh.name.text = r.nickname.ifEmpty { r.name }.ifEmpty { r.friendId }
                vh.avatar.load(Api.fullImageUrl(r.avatarUrl)) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
                vh.itemView.setOnClickListener { onClick(r) }
                FocusEffects.apply(vh.itemView)
            }
        }
    }
}
