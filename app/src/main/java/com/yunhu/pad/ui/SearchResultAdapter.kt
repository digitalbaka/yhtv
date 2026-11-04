package com.yunhu.pad.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yunhu.pad.R
import com.yunhu.pad.api.Api

/** 搜索结果列表（分组标题 + 条目），支持用户/群组/机器人与社区文章 */
class SearchResultAdapter(
    private val onClick: (Api.SearchResult) -> Unit,
    private val onPostClick: ((Api.Post) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val result: Api.SearchResult) : Row()
        data class PostItem(val post: Api.Post) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    /** 追加结果（社区搜索分页加载更多） */
    fun append(list: List<Row>) {
        if (list.isEmpty()) return
        val start = rows.size
        rows.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val header: TextView = view.findViewById(R.id.tv_header)
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.iv_avatar)
        val name: TextView = view.findViewById(R.id.tv_name)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> 0
        is Row.Item -> 1
        is Row.PostItem -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(inf.inflate(R.layout.item_contact_header, parent, false))
            else -> ItemVH(inf.inflate(R.layout.item_contact, parent, false))
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
            is Row.PostItem -> {
                val vh = h as ItemVH
                val p = row.post
                vh.name.text = buildString {
                    append("[文章] ")
                    append(p.title.ifEmpty { "(无标题)" })
                    if (p.senderNickname.isNotEmpty()) append(" · ").append(p.senderNickname)
                }
                vh.avatar.load(Api.fullImageUrl(p.senderAvatar)) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
                vh.itemView.setOnClickListener { onPostClick?.invoke(p) }
                FocusEffects.apply(vh.itemView)
            }
        }
    }
}
