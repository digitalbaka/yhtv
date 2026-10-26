package com.yunhu.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yunhu.tv.R
import com.yunhu.tv.api.Api
import com.yunhu.tv.util.MarkdownLite

/** 社区文章列表 */
class PostAdapter(
    private val onClick: (Api.Post) -> Unit
) : RecyclerView.Adapter<PostAdapter.VH>() {

    private val items = mutableListOf<Api.Post>()

    fun submit(list: List<Api.Post>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 分页追加 */
    fun append(list: List<Api.Post>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_post_title)
        val preview: TextView = view.findViewById(R.id.tv_post_preview)
        val meta: TextView = view.findViewById(R.id.tv_post_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.title.text = p.title.ifEmpty { "(无标题)" }
        // Markdown 文章预览转纯文本
        h.preview.text = if (p.contentType == 2) MarkdownLite.toPlain(p.content) else p.content
        h.meta.text = "${p.senderNickname} · ${p.createTimeText} · 赞 ${p.likeNum} · 评 ${p.commentNum} · 币 ${p.amountNum}"
        h.itemView.setOnClickListener { onClick(p) }
        FocusEffects.apply(h.itemView)
    }
}
